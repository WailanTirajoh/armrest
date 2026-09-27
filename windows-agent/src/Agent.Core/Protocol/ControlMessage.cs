using System.Text.Json;
using Armrest.Agent.Volume;

namespace Armrest.Agent.Protocol;

/// <summary>Pesan kontrol JSON (protocol/PROTOCOL.md, bagian "Pesan kontrol").</summary>
public abstract record ControlMessage
{
    public const string ModePair = "pair";
    public const string ModeAuth = "auth";

    public sealed record Hello(int Version, string DeviceId, string Mode) : ControlMessage;

    public sealed record PairRequest(string Token, string DeviceName, string PublicKey) : ControlMessage;

    /// <summary>Ok: <c>HostId</c> dan <c>HostName</c> terisi. Gagal: <c>Error</c> berisi kode.</summary>
    public sealed record PairResult(bool Ok, string? HostId, string? HostName, string? Error) : ControlMessage;

    public sealed record Challenge(string Nonce) : ControlMessage;

    public sealed record Auth(string Sig) : ControlMessage;

    /// <summary>Hanya saat ok: fitur opsional agent dan platformnya.</summary>
    public sealed record AuthResult(bool Ok, string? Error, IReadOnlyList<string> Features, string? Platform) : ControlMessage
    {
        public AuthResult(bool ok, string? error) : this(ok, error, [], null)
        {
        }

        public bool Equals(AuthResult? other) =>
            other is not null && Ok == other.Ok && Error == other.Error && Platform == other.Platform && Features.SequenceEqual(other.Features);

        public override int GetHashCode() => HashCode.Combine(Ok, Error, Platform, Features.Count);
    }

    /// <summary><c>FocusUpdates</c>: HP ingin menerima pesan <c>focus</c>. <c>VolumeUpdates</c>: pesan <c>volume_status</c>.</summary>
    public sealed record Settings(double Sensitivity, double ScrollSpeed, bool FocusUpdates, bool VolumeUpdates = false) : ControlMessage;

    /// <summary>Apakah kolom teks sedang fokus di komputer.</summary>
    public sealed record Focus(bool Text) : ControlMessage;

    /// <summary>HP mulai (request) atau berhenti (null) melihat layar. Permintaan ulang saat aktif = minta keyframe.</summary>
    public sealed record Screen(ScreenRequest? Request) : ControlMessage;

    public sealed record ScreenAck(uint Seq) : ControlMessage;

    public sealed record ScreenStatusMessage(ScreenStatus Status) : ControlMessage;

    /// <summary>Posisi kursor di video layar, 0–1 dari kiri atas. Hanya untuk HP yang meminta <c>cursor</c>.</summary>
    public sealed record ScreenCursor(double X, double Y) : ControlMessage;

    /// <summary>Perintah volume dari HP.</summary>
    public sealed record VolumeMessage(VolumeCommand Command) : ControlMessage;

    /// <summary>Volume output komputer, untuk HP yang meminta <c>volumeUpdates</c>.</summary>
    public sealed record VolumeStatus(VolumeState State) : ControlMessage;

    public sealed record Ping(long Ts) : ControlMessage;

    public sealed record Pong(long Ts) : ControlMessage;

    public sealed record ErrorMessage(string Code) : ControlMessage;

    public byte[] Encode()
    {
        using var stream = new MemoryStream();
        using (var w = new Utf8JsonWriter(stream))
        {
            w.WriteStartObject();
            switch (this)
            {
                case Hello m:
                    w.WriteString("t", "hello");
                    w.WriteNumber("v", m.Version);
                    w.WriteString("deviceId", m.DeviceId);
                    w.WriteString("mode", m.Mode);
                    break;
                case PairRequest m:
                    w.WriteString("t", "pair_request");
                    w.WriteString("token", m.Token);
                    w.WriteString("deviceName", m.DeviceName);
                    w.WriteString("publicKey", m.PublicKey);
                    break;
                case PairResult m:
                    w.WriteString("t", "pair_result");
                    w.WriteBoolean("ok", m.Ok);
                    if (m.Ok)
                    {
                        w.WriteString("hostId", m.HostId);
                        w.WriteString("hostName", m.HostName);
                    }
                    else
                    {
                        w.WriteString("error", m.Error);
                    }
                    break;
                case Challenge m:
                    w.WriteString("t", "challenge");
                    w.WriteString("nonce", m.Nonce);
                    break;
                case Auth m:
                    w.WriteString("t", "auth");
                    w.WriteString("sig", m.Sig);
                    break;
                case AuthResult m:
                    w.WriteString("t", "auth_result");
                    w.WriteBoolean("ok", m.Ok);
                    if (m.Error is not null) w.WriteString("error", m.Error);
                    if (m.Features.Count > 0)
                    {
                        w.WriteStartArray("features");
                        foreach (var feature in m.Features) w.WriteStringValue(feature);
                        w.WriteEndArray();
                    }
                    if (m.Platform is not null) w.WriteString("platform", m.Platform);
                    break;
                case Settings m:
                    w.WriteString("t", "settings");
                    w.WriteNumber("sensitivity", m.Sensitivity);
                    w.WriteNumber("scrollSpeed", m.ScrollSpeed);
                    w.WriteBoolean("focusUpdates", m.FocusUpdates);
                    w.WriteBoolean("volumeUpdates", m.VolumeUpdates);
                    break;
                case Focus m:
                    w.WriteString("t", "focus");
                    w.WriteBoolean("text", m.Text);
                    break;
                case Screen m:
                    w.WriteString("t", "screen");
                    w.WriteBoolean("on", m.Request is not null);
                    if (m.Request is { } request)
                    {
                        w.WriteNumber("maxWidth", request.MaxWidth);
                        w.WriteNumber("maxHeight", request.MaxHeight);
                        if (request.Cursor) w.WriteBoolean("cursor", true);
                    }
                    break;
                case ScreenAck m:
                    w.WriteString("t", "screen_ack");
                    w.WriteNumber("seq", m.Seq);
                    break;
                case ScreenStatusMessage m:
                    w.WriteString("t", "screen_status");
                    w.WriteString("state", m.Status.WireName());
                    break;
                case ScreenCursor m:
                    w.WriteString("t", "screen_cursor");
                    w.WriteNumber("x", Math.Round(m.X, 4));
                    w.WriteNumber("y", Math.Round(m.Y, 4));
                    break;
                case VolumeMessage m:
                    w.WriteString("t", "volume");
                    switch (m.Command)
                    {
                        case VolumeCommand.Step step:
                            w.WriteNumber("step", step.Count);
                            break;
                        case VolumeCommand.Level level:
                            w.WriteNumber("level", Math.Round(level.Value, 4));
                            break;
                        case VolumeCommand.Muted muted:
                            w.WriteBoolean("muted", muted.Value);
                            break;
                    }
                    break;
                case VolumeStatus m:
                    w.WriteString("t", "volume_status");
                    if (m.State.Level is { } stateLevel) w.WriteNumber("level", Math.Round(stateLevel, 4));
                    w.WriteBoolean("muted", m.State.Muted);
                    break;
                case Ping m:
                    w.WriteString("t", "ping");
                    w.WriteNumber("ts", m.Ts);
                    break;
                case Pong m:
                    w.WriteString("t", "pong");
                    w.WriteNumber("ts", m.Ts);
                    break;
                case ErrorMessage m:
                    w.WriteString("t", "error");
                    w.WriteString("error", m.Code);
                    break;
            }
            w.WriteEndObject();
        }
        return stream.ToArray();
    }

    public static ControlMessage? Decode(ReadOnlySpan<byte> data)
    {
        try
        {
            var reader = new Utf8JsonReader(data);
            using var document = JsonDocument.ParseValue(ref reader);
            var o = document.RootElement;
            if (o.ValueKind != JsonValueKind.Object) return null;
            string? Str(string name) => o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;
            bool? Bool(string name) => o.TryGetProperty(name, out var v) && v.ValueKind is JsonValueKind.True or JsonValueKind.False ? v.GetBoolean() : null;
            JsonElement? Num(string name) => o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? v : null;

            switch (Str("t"))
            {
                case "hello":
                    if (Num("v") is not { } v || !v.TryGetInt32(out var version) || Str("deviceId") is not { } deviceId) return null;
                    return Str("mode") is ModePair or ModeAuth ? new Hello(version, deviceId, Str("mode")!) : null;
                case "pair_request":
                    return Str("token") is { } token && Str("deviceName") is { } name && Str("publicKey") is { } key
                        ? new PairRequest(token, name, key)
                        : null;
                case "pair_result":
                    if (Bool("ok") is not { } ok) return null;
                    if (!ok) return new PairResult(false, null, null, Str("error") ?? "unknown");
                    return Str("hostId") is { } hostId && Str("hostName") is { } hostName ? new PairResult(true, hostId, hostName, null) : null;
                case "challenge":
                    return Str("nonce") is { } nonce ? new Challenge(nonce) : null;
                case "auth":
                    return Str("sig") is { } sig ? new Auth(sig) : null;
                case "auth_result":
                    if (Bool("ok") is not { } authOk) return null;
                    var features = o.TryGetProperty("features", out var list) && list.ValueKind == JsonValueKind.Array
                        ? list.EnumerateArray().Where(e => e.ValueKind == JsonValueKind.String).Select(e => e.GetString()!).ToList()
                        : [];
                    return new AuthResult(authOk, Str("error"), features, Str("platform"));
                case "settings":
                    if (Num("sensitivity") is not { } sensitivity || Num("scrollSpeed") is not { } scrollSpeed) return null;
                    // HP v0.3 belum mengirim focusUpdates, dan HP sebelum v0.7 belum mengirim volumeUpdates.
                    return new Settings(sensitivity.GetDouble(), scrollSpeed.GetDouble(), Bool("focusUpdates") ?? false, Bool("volumeUpdates") ?? false);
                case "focus":
                    return Bool("text") is { } text ? new Focus(text) : null;
                case "screen":
                    if (Bool("on") is not { } on) return null;
                    if (!on) return new Screen(null);
                    return Num("maxWidth") is { } width && width.TryGetInt32(out var w) && Num("maxHeight") is { } height && height.TryGetInt32(out var h)
                        ? new Screen(new ScreenRequest(w, h, Bool("cursor") ?? false))
                        : null;
                case "screen_ack":
                    return Num("seq") is { } seq && seq.TryGetUInt32(out var s) ? new ScreenAck(s) : null;
                case "screen_status":
                    return ScreenStatusExtensions.FromWireName(Str("state")) is { } status ? new ScreenStatusMessage(status) : null;
                case "screen_cursor":
                    return Num("x") is { } x && Num("y") is { } y ? new ScreenCursor(x.GetDouble(), y.GetDouble()) : null;
                case "volume":
                    // Tepat satu field. Langkah harus bilangan bulat.
                    if (Num("step") is { } stepValue)
                    {
                        var step = stepValue.GetDouble();
                        return step == Math.Round(step)
                            ? new VolumeMessage(new VolumeCommand.Step((int)Math.Clamp(step, -VolumeMath.Steps, VolumeMath.Steps)))
                            : null;
                    }
                    if (Num("level") is { } levelValue) return new VolumeMessage(new VolumeCommand.Level(Math.Clamp(levelValue.GetDouble(), 0, 1)));
                    return Bool("muted") is { } muted ? new VolumeMessage(new VolumeCommand.Muted(muted)) : null;
                case "volume_status":
                    return Bool("muted") is { } statusMuted ? new VolumeStatus(new VolumeState(Num("level")?.GetDouble(), statusMuted)) : null;
                case "ping":
                    return Num("ts") is { } ping && ping.TryGetInt64(out var pingTs) ? new Ping(pingTs) : null;
                case "pong":
                    return Num("ts") is { } pong && pong.TryGetInt64(out var pongTs) ? new Pong(pongTs) : null;
                case "error":
                    return new ErrorMessage(Str("error") ?? "unknown");
                default:
                    return null;
            }
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
