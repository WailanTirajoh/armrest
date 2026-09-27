using System.Collections;
using System.Text;
using CursorController.Agent.Hosting;
using CursorController.Agent.Protocol;
using CursorController.Agent.Session;
using CursorController.Agent.Volume;

namespace CursorController.Agent.Tests;

internal sealed class FakeEnvironment : ISessionEnvironment
{
    public string HostId => "0b7c2f6e-3d4a-4c1b-9e8f-5a6b7c8d9e0f";
    public string HostName => "PC Kantor";
    public PairingTokens Tokens { get; } = new();
    public Dictionary<string, TrustedDevice> Devices { get; } = [];
    public byte[] Nonce { get; } = Enumerable.Repeat((byte)7, 32).ToArray();
    public IReadOnlyList<string> Features { get; set; } = [];
    public string? Platform { get; set; }

    public PairingTokenCheck CheckPairingToken(string token) => Tokens.Check(token);

    public TrustedDevice? TrustedDevice(string id) => Devices.GetValueOrDefault(id);

    public byte[] MakeNonce() => Nonce;
}

public class SessionTests
{
    private const string DeviceId = "5f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0";

    private static byte[] Text(ControlMessage message) => message.Encode();

    private static List<ControlMessage> Sent(IReadOnlyList<SessionAction> actions) =>
        actions.OfType<SessionAction.Send>().Select(s => s.Message).ToList();

    private static string Sign(PhoneKey key, FakeEnvironment env) => key.Sign(AuthCrypto.Payload(env.Nonce, env.HostId, DeviceId));

    [Fact]
    public void FullPairingFlowEndsAuthenticated()
    {
        var env = new FakeEnvironment();
        var machine = new SessionMachine(env);
        using var key = new PhoneKey();
        var token = env.Tokens.Issue();

        Assert.Empty(machine.HandleText(Text(new ControlMessage.Hello(1, DeviceId, "pair"))));
        var request = machine.HandleText(Text(new ControlMessage.PairRequest(token.Value, "Pixel 8", key.PublicKeyBase64)));
        var pending = Assert.IsType<SessionAction.RequestApproval>(Assert.Single(request)).Device;
        Assert.Equal("Pixel 8", pending.Name);

        var approved = machine.ApprovalDecided(true);
        var device = Assert.IsType<SessionAction.TrustDevice>(approved[0]).Device;
        env.Devices[device.Id] = device;
        Assert.Equal(
            new ControlMessage[] { new ControlMessage.PairResult(true, env.HostId, env.HostName, null), new ControlMessage.Challenge(Convert.ToBase64String(env.Nonce)) },
            Sent(approved));

        var auth = machine.HandleText(Text(new ControlMessage.Auth(Sign(key, env))));
        Assert.Equal(new ControlMessage[] { new ControlMessage.AuthResult(true, null) }, Sent(auth));
        Assert.True(machine.IsAuthenticated);
        var move = new InputMessage.Move(5, -5);
        Assert.Equal(new SessionAction[] { new SessionAction.Input(move) }, machine.HandleBinary(move.Encode()));
    }

    [Fact]
    public void InputBeforeAuthenticationIsDropped()
    {
        var machine = new SessionMachine(new FakeEnvironment());
        machine.HandleText(Text(new ControlMessage.Hello(1, DeviceId, "pair")));
        Assert.Empty(machine.HandleBinary(new InputMessage.Click(MouseButton.Left, 1).Encode()));
    }

    [Fact]
    public void ReusedTokenIsRejected()
    {
        var env = new FakeEnvironment();
        var token = env.Tokens.Issue();
        Assert.Equal(PairingTokenCheck.Valid, env.Tokens.Check(token.Value));
        var machine = new SessionMachine(env);
        machine.HandleText(Text(new ControlMessage.Hello(1, DeviceId, "pair")));
        var actions = machine.HandleText(Text(new ControlMessage.PairRequest(token.Value, "Pixel", "AAAA")));
        Assert.Equal(new ControlMessage[] { new ControlMessage.PairResult(false, null, null, "token_invalid") }, Sent(actions));
        Assert.Equal(new SessionAction.Close("token_invalid"), actions[^1]);
    }

    [Fact]
    public void DeniedPairingClosesWithoutTrustingDevice()
    {
        var env = new FakeEnvironment();
        var machine = new SessionMachine(env);
        using var key = new PhoneKey();
        var token = env.Tokens.Issue();
        machine.HandleText(Text(new ControlMessage.Hello(1, DeviceId, "pair")));
        machine.HandleText(Text(new ControlMessage.PairRequest(token.Value, "Pixel", key.PublicKeyBase64)));
        Assert.Equal(
            new SessionAction[] { new SessionAction.Send(new ControlMessage.PairResult(false, null, null, "denied")), new SessionAction.Close("denied") },
            machine.ApprovalDecided(false));
    }

    [Fact]
    public void BadSignatureAndUnknownDeviceAreRejected()
    {
        var env = new FakeEnvironment();
        using var key = new PhoneKey();
        using var other = new PhoneKey();
        env.Devices[DeviceId] = new TrustedDevice(DeviceId, "Pixel", key.PublicKeyDer, DateTimeOffset.UtcNow);
        var machine = new SessionMachine(env);
        machine.HandleText(Text(new ControlMessage.Hello(1, DeviceId, "auth")));
        Assert.Equal(new ControlMessage[] { new ControlMessage.AuthResult(false, "bad_sig") }, Sent(machine.HandleText(Text(new ControlMessage.Auth(Sign(other, env))))));
        Assert.False(machine.IsAuthenticated);

        var stranger = new SessionMachine(env);
        Assert.Equal(
            new ControlMessage[] { new ControlMessage.AuthResult(false, "unknown_device") },
            Sent(stranger.HandleText(Text(new ControlMessage.Hello(1, "asing", "auth")))));
    }

    [Fact]
    public void UnsupportedVersionAndGarbageAreRejected()
    {
        var machine = new SessionMachine(new FakeEnvironment());
        Assert.Equal(
            new SessionAction[] { new SessionAction.Send(new ControlMessage.ErrorMessage("unsupported_version")), new SessionAction.Close("unsupported_version") },
            machine.HandleText(Text(new ControlMessage.Hello(2, DeviceId, "pair"))));
        var other = new SessionMachine(new FakeEnvironment());
        Assert.Equal(new SessionAction.Close("bad_message"), other.HandleText(Encoding.UTF8.GetBytes("{}"))[^1]);
    }

    [Fact]
    public void AuthenticatedSessionAnswersPingClampsSettingsAndForwardsScreen()
    {
        var env = new FakeEnvironment { Features = ["focus", "screen"], Platform = AgentPlatform.Windows };
        using var key = new PhoneKey();
        env.Devices[DeviceId] = new TrustedDevice(DeviceId, "Pixel", key.PublicKeyDer, DateTimeOffset.UtcNow);

        var early = new SessionMachine(env);
        early.HandleText(Text(new ControlMessage.Hello(1, DeviceId, "auth")));
        Assert.Equal(new SessionAction.Close("bad_message"), early.HandleText(Text(new ControlMessage.Screen(new ScreenRequest(1920, 1080))))[^1]);

        var machine = new SessionMachine(env);
        Assert.Equal(new SessionAction[] { new SessionAction.Send(new ControlMessage.Pong(9)) }, machine.HandleText(Text(new ControlMessage.Ping(9))));
        machine.HandleText(Text(new ControlMessage.Hello(1, DeviceId, "auth")));
        var auth = machine.HandleText(Text(new ControlMessage.Auth(Sign(key, env))));
        Assert.Equal(new ControlMessage[] { new ControlMessage.AuthResult(true, null, ["focus", "screen"], "windows") }, Sent(auth));
        Assert.Equal(
            new SessionAction[] { new SessionAction.Settings(5, 0.3, true, true) },
            machine.HandleText(Text(new ControlMessage.Settings(99, 0, true, true))));
        Assert.Equal(
            new SessionAction[] { new SessionAction.Volume(new VolumeCommand.Step(-1)) },
            machine.HandleText(Text(new ControlMessage.VolumeMessage(new VolumeCommand.Step(-1)))));
        var request = new ScreenRequest(1920, 1080);
        Assert.Equal(new SessionAction[] { new SessionAction.Screen(request) }, machine.HandleText(Text(new ControlMessage.Screen(request))));
        Assert.Equal(new SessionAction[] { new SessionAction.ScreenAck(7) }, machine.HandleText(Text(new ControlMessage.ScreenAck(7))));
    }

    [Fact]
    public void TokensAreSingleUseExpireAndLockAfterFiveFailures()
    {
        var now = DateTimeOffset.FromUnixTimeSeconds(1_790_000_000);
        var tokens = new PairingTokens(() => now);
        var token = tokens.Issue();
        Assert.Equal(120, tokens.SecondsRemaining());
        Assert.Equal(PairingTokenCheck.Valid, tokens.Check(token.Value));
        Assert.Equal(PairingTokenCheck.Invalid, tokens.Check(token.Value));

        token = tokens.Issue();
        now += TimeSpan.FromSeconds(120);
        Assert.Equal(PairingTokenCheck.Expired, tokens.Check(token.Value));

        token = tokens.Issue();
        for (var i = 0; i < 4; i++) Assert.Equal(PairingTokenCheck.Invalid, tokens.Check("salah"));
        Assert.Equal(PairingTokenCheck.TooManyAttempts, tokens.Check("salah"));
        Assert.Equal(PairingTokenCheck.Invalid, tokens.Check(token.Value));
    }

    [Fact]
    public void PairingUriEncodesEveryFieldLikeTheMac()
    {
        var uri = new PairingUri("h-1", "Mac Wailan+Kantor", "192.168.1.20", 47810, "a_b-c", "f_p");
        Assert.Equal("cursorctl://pair?h=h-1&n=Mac%20Wailan%2BKantor&a=192.168.1.20%3A47810&t=a_b-c&fp=f_p", uri.ToString());
    }

    [Fact]
    public void TrustedDeviceStorePersists()
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString(), "devices.json");
        var store = new TrustedDeviceStore(path);
        store.Upsert(new TrustedDevice("d1", "Pixel 8", [1, 2, 3], DateTimeOffset.FromUnixTimeSeconds(0)));
        store.Touch("d1", DateTimeOffset.FromUnixTimeSeconds(60));

        var reloaded = new TrustedDeviceStore(path);
        Assert.Equal(DateTimeOffset.FromUnixTimeSeconds(60), reloaded.Device("d1")?.LastSeen);
        Assert.Equal([1, 2, 3], reloaded.Device("d1")!.PublicKey);
        reloaded.Remove("d1");
        Assert.Empty(new TrustedDeviceStore(path).Devices);
    }

    [Fact]
    public void HeadlessProfileNeverUsesNormalAppData()
    {
        Assert.Equal("", Profile.FromEnvironment(new Hashtable()).Suffix);
        var headless = Profile.FromEnvironment(new Hashtable { ["CURSORCTL_E2E_PAIRING_FILE"] = "qr.txt" });
        Assert.True(headless.Headless);
        Assert.Equal("-e2e", headless.Suffix);
        Assert.Equal("-e2e-core", Profile.FromEnvironment(new Hashtable { ["CURSORCTL_PROFILE"] = "e2e-core", ["CURSORCTL_E2E_PAIRING_FILE"] = "qr.txt" }).Suffix);
    }
}
