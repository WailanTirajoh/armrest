using System.Globalization;
using CursorController.Agent.Protocol;
using CursorController.Agent.Volume;

namespace CursorController.Agent.Session;

/// <summary>Yang dibutuhkan sesi dari luar. Diimplementasikan oleh server (dan fake di test).</summary>
public interface ISessionEnvironment
{
    string HostId { get; }
    string HostName { get; }
    PairingTokenCheck CheckPairingToken(string token);
    TrustedDevice? TrustedDevice(string id);
    byte[] MakeNonce();
    /// <summary>Fitur opsional yang diumumkan ke HP di <c>auth_result</c>.</summary>
    IReadOnlyList<string> Features { get; }
    /// <summary>Platform yang diumumkan di <c>auth_result</c>.</summary>
    string? Platform { get; }
}

public sealed record PendingDevice(string Id, string Name, byte[] PublicKey);

public abstract record SessionAction
{
    public sealed record Send(ControlMessage Message) : SessionAction;

    /// <summary>UI menampilkan dialog "Izinkan?", lalu memanggil <see cref="SessionMachine.ApprovalDecided"/>.</summary>
    public sealed record RequestApproval(PendingDevice Device) : SessionAction;

    public sealed record TrustDevice(TrustedDevice Device) : SessionAction;

    public sealed record Authenticated(TrustedDevice Device) : SessionAction;

    public sealed record Input(InputMessage Message) : SessionAction;

    public sealed record Settings(double Sensitivity, double ScrollSpeed, bool FocusUpdates, bool VolumeUpdates = false) : SessionAction;

    public sealed record Screen(ScreenRequest? Request) : SessionAction;

    public sealed record Volume(VolumeCommand Command) : SessionAction;

    public sealed record ScreenAck(uint Seq) : SessionAction;

    public sealed record Close(string Reason) : SessionAction;
}

/// <summary>
/// State machine per koneksi: awaitingHello → pairing / challenge → authenticated → closed.
/// Tidak menyentuh jaringan; pemanggil menjalankan aksi yang dikembalikan.
/// </summary>
public sealed class SessionMachine(ISessionEnvironment environment, Func<DateTimeOffset>? now = null)
{
    private abstract record State
    {
        public sealed record AwaitingHello : State;
        public sealed record AwaitingPairRequest(string DeviceId) : State;
        public sealed record AwaitingApproval(PendingDevice Pending) : State;
        public sealed record AwaitingAuth(string DeviceId, byte[] Nonce) : State;
        public sealed record Authenticated(TrustedDevice Device) : State;
        public sealed record Closed : State;
    }

    private readonly Func<DateTimeOffset> now = now ?? (() => DateTimeOffset.UtcNow);
    private State state = new State.AwaitingHello();

    public bool IsAuthenticated => state is State.Authenticated;

    public IReadOnlyList<SessionAction> HandleText(ReadOnlySpan<byte> data)
    {
        if (state is State.Closed) return [];
        if (ControlMessage.Decode(data) is not { } message) return Fail("bad_message");

        switch (state, message)
        {
            case (_, ControlMessage.Ping ping):
                return [new SessionAction.Send(new ControlMessage.Pong(ping.Ts))];
            case (_, ControlMessage.Pong):
                return [];

            case (State.AwaitingHello, ControlMessage.Hello hello):
                if (hello.Version != AgentConstants.ProtocolVersion) return Fail("unsupported_version");
                if (hello.Mode == ControlMessage.ModePair)
                {
                    state = new State.AwaitingPairRequest(hello.DeviceId);
                    return [];
                }
                if (environment.TrustedDevice(hello.DeviceId) is null)
                {
                    return Close(new ControlMessage.AuthResult(false, "unknown_device"), "unknown_device");
                }
                return SendChallenge(hello.DeviceId);

            case (State.AwaitingPairRequest pairing, ControlMessage.PairRequest request):
                if (environment.CheckPairingToken(request.Token).ErrorCode() is { } code)
                {
                    return Close(new ControlMessage.PairResult(false, null, null, code), code);
                }
                if (FromBase64(request.PublicKey) is not { } key || !AuthCrypto.IsValidPublicKey(key)) return Fail("bad_message");
                var name = Truncate(request.DeviceName.Trim(), 64);
                var pending = new PendingDevice(pairing.DeviceId, name.Length == 0 ? "Perangkat Android" : name, key);
                state = new State.AwaitingApproval(pending);
                return [new SessionAction.RequestApproval(pending)];

            case (State.AwaitingAuth auth, ControlMessage.Auth reply):
                if (environment.TrustedDevice(auth.DeviceId) is not { } device)
                {
                    return Close(new ControlMessage.AuthResult(false, "unknown_device"), "unknown_device");
                }
                var payload = AuthCrypto.Payload(auth.Nonce, environment.HostId, auth.DeviceId);
                if (FromBase64(reply.Sig) is not { } signature || !AuthCrypto.Verify(signature, payload, device.PublicKey))
                {
                    return Close(new ControlMessage.AuthResult(false, "bad_sig"), "bad_sig");
                }
                state = new State.Authenticated(device);
                var result = new ControlMessage.AuthResult(true, null, environment.Features, environment.Platform);
                return [new SessionAction.Send(result), new SessionAction.Authenticated(device)];

            case (State.Authenticated, ControlMessage.Settings settings):
                return [new SessionAction.Settings(
                    Math.Clamp(settings.Sensitivity, 0.3, 5), Math.Clamp(settings.ScrollSpeed, 0.3, 8), settings.FocusUpdates, settings.VolumeUpdates)];

            case (State.Authenticated, ControlMessage.VolumeMessage volume):
                return [new SessionAction.Volume(volume.Command)];

            case (State.Authenticated, ControlMessage.Screen screen):
                return [new SessionAction.Screen(screen.Request)];

            case (State.Authenticated, ControlMessage.ScreenAck ack):
                return [new SessionAction.ScreenAck(ack.Seq)];

            default:
                return Fail("bad_message");
        }
    }

    /// <summary>Event input sebelum autentikasi selesai dibuang.</summary>
    public IReadOnlyList<SessionAction> HandleBinary(ReadOnlySpan<byte> data) =>
        IsAuthenticated && InputMessage.Decode(data) is { } message ? [new SessionAction.Input(message)] : [];

    public IReadOnlyList<SessionAction> ApprovalDecided(bool approved)
    {
        if (state is not State.AwaitingApproval { Pending: var pending }) return [];
        if (!approved) return Close(new ControlMessage.PairResult(false, null, null, "denied"), "denied");
        var device = new TrustedDevice(pending.Id, pending.Name, pending.PublicKey, now());
        return
        [
            new SessionAction.TrustDevice(device),
            new SessionAction.Send(new ControlMessage.PairResult(true, environment.HostId, environment.HostName, null)),
            .. SendChallenge(pending.Id),
        ];
    }

    /// <summary>Dipanggil saat koneksi putus atau ditutup dari sisi agent.</summary>
    public void MarkClosed() => state = new State.Closed();

    private IReadOnlyList<SessionAction> SendChallenge(string deviceId)
    {
        var nonce = environment.MakeNonce();
        state = new State.AwaitingAuth(deviceId, nonce);
        return [new SessionAction.Send(new ControlMessage.Challenge(Convert.ToBase64String(nonce)))];
    }

    private IReadOnlyList<SessionAction> Fail(string code) => Close(new ControlMessage.ErrorMessage(code), code);

    private IReadOnlyList<SessionAction> Close(ControlMessage message, string reason)
    {
        state = new State.Closed();
        return [new SessionAction.Send(message), new SessionAction.Close(reason)];
    }

    private static byte[]? FromBase64(string text)
    {
        try
        {
            return Convert.FromBase64String(text);
        }
        catch (FormatException)
        {
            return null;
        }
    }

    private static string Truncate(string text, int graphemes)
    {
        var info = new StringInfo(text);
        return info.LengthInTextElements <= graphemes ? text : info.SubstringByTextElements(0, graphemes);
    }
}
