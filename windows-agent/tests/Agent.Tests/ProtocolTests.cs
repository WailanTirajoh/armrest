using System.Text;
using Armrest.Agent.Protocol;
using Armrest.Agent.Volume;

namespace Armrest.Agent.Tests;

public class ProtocolTests
{
    [Fact]
    public void InputVectorsRoundTrip()
    {
        var cases = Vectors.Load("input.json").GetProperty("cases").EnumerateArray().ToList();
        Assert.True(cases.Count >= 12);
        foreach (var c in cases)
        {
            var hex = c.GetProperty("hex").GetString()!;
            InputMessage expected = c.GetProperty("message").GetString() switch
            {
                "move" => new InputMessage.Move((short)c.GetProperty("dx").GetInt32(), (short)c.GetProperty("dy").GetInt32()),
                "scroll" => new InputMessage.Scroll((short)c.GetProperty("dx").GetInt32(), (short)c.GetProperty("dy").GetInt32()),
                "button" => new InputMessage.Button((MouseButton)c.GetProperty("button").GetInt32(), c.GetProperty("down").GetBoolean()),
                "click" => new InputMessage.Click((MouseButton)c.GetProperty("button").GetInt32(), (byte)c.GetProperty("count").GetInt32()),
                "text" => new InputMessage.Text(c.GetProperty("text").GetString()!),
                "key" => new InputMessage.Key((KeyCode)c.GetProperty("key").GetInt32(), (KeyModifiers)c.GetProperty("modifiers").GetInt32()),
                var other => throw new InvalidOperationException($"tipe tidak dikenal: {other}"),
            };
            Assert.Equal(expected, InputMessage.Decode(Vectors.Hex(hex)));
            Assert.Equal(hex, Vectors.Hex(expected.Encode()));
        }
    }

    [Fact]
    public void InvalidInputFramesAreRejected()
    {
        foreach (var hex in Vectors.Load("input.json").GetProperty("invalid_hex").EnumerateArray())
        {
            Assert.Null(InputMessage.Decode(Vectors.Hex(hex.GetString()!)));
        }
    }

    [Fact]
    public void AuthVectorsVerifyLikeOpenSsl()
    {
        var root = Vectors.Load("auth.json");
        foreach (var c in root.GetProperty("cases").EnumerateArray())
        {
            var payload = AuthCrypto.Payload(
                Vectors.Hex(c.GetProperty("nonce_hex").GetString()!), c.GetProperty("hostId").GetString()!, c.GetProperty("deviceId").GetString()!);
            Assert.Equal(c.GetProperty("payload_hex").GetString(), Vectors.Hex(payload));
            var valid = AuthCrypto.Verify(
                Convert.FromBase64String(c.GetProperty("signature_b64").GetString()!),
                payload,
                Convert.FromBase64String(c.GetProperty("publicKey_b64").GetString()!));
            Assert.Equal(c.GetProperty("valid").GetBoolean(), valid);
        }
        var fingerprint = root.GetProperty("fingerprint");
        Assert.Equal(
            fingerprint.GetProperty("sha256_b64url").GetString(),
            AuthCrypto.Fingerprint(Vectors.Hex(fingerprint.GetProperty("input_hex").GetString()!)));
    }

    [Fact]
    public void ScreenVectorsRoundTrip()
    {
        var root = Vectors.Load("screen.json");
        foreach (var c in root.GetProperty("cases").EnumerateArray())
        {
            var hex = c.GetProperty("hex").GetString()!;
            ScreenPacket expected = c.GetProperty("packet").GetString() == "config"
                ? new ScreenPacket.Config(c.GetProperty("width").GetInt32(), c.GetProperty("height").GetInt32(), Vectors.Hex(c.GetProperty("parameterSets_hex").GetString()!))
                : new ScreenPacket.Frame(c.GetProperty("seq").GetUInt32(), c.GetProperty("keyframe").GetBoolean(), Vectors.Hex(c.GetProperty("data_hex").GetString()!));
            Assert.Equal(expected, ScreenPacket.Decode(Vectors.Hex(hex)));
            Assert.Equal(hex, Vectors.Hex(expected.Encode()));
        }
        foreach (var hex in root.GetProperty("invalid_hex").EnumerateArray())
        {
            Assert.Null(ScreenPacket.Decode(Vectors.Hex(hex.GetString()!)));
        }
    }

    [Fact]
    public void LengthPrefixedNalUnitsBecomeAnnexB()
    {
        var vector = Vectors.Load("screen.json").GetProperty("annexb");
        var annexB = Vectors.Hex(vector.GetProperty("annexb_hex").GetString()!);
        Assert.Equal(annexB, AnnexB.FromLengthPrefixed(Vectors.Hex(vector.GetProperty("lengthPrefixed_hex").GetString()!)));
        var units = vector.GetProperty("units_hex").EnumerateArray().Select(u => u.GetString()!).ToList();
        Assert.Equal(annexB, AnnexB.Join(units.Select(Vectors.Hex)));
        Assert.Equal(units, AnnexB.Split(annexB).Select(Vectors.Hex));
        Assert.Equal(units, AnnexB.Split(Vectors.Hex(vector.GetProperty("mixedStartCodes_hex").GetString()!)).Select(Vectors.Hex));
        Assert.Null(AnnexB.FromLengthPrefixed(new byte[] { 0, 0, 0, 9, 0x65, 0x88 }));
    }

    [Fact]
    public void ControlMessagesRoundTrip()
    {
        ControlMessage[] messages =
        [
            new ControlMessage.Hello(1, "d", ControlMessage.ModePair),
            new ControlMessage.Hello(1, "d", ControlMessage.ModeAuth),
            new ControlMessage.PairRequest("tok", "Pixel 8", "AAA="),
            new ControlMessage.PairResult(true, "h", "PC Kantor", null),
            new ControlMessage.PairResult(false, null, null, "denied"),
            new ControlMessage.Challenge("bm9uY2U="),
            new ControlMessage.Auth("c2ln"),
            new ControlMessage.AuthResult(true, null),
            new ControlMessage.AuthResult(false, "bad_sig"),
            new ControlMessage.AuthResult(true, null, ["focus", "screen"], AgentPlatform.Windows),
            new ControlMessage.AuthResult(true, null, ["power"], AgentPlatform.Windows, "a4:83:e7:12:34:56"),
            new ControlMessage.Settings(1.5, 2, true),
            new ControlMessage.Settings(1.5, 2, true, true),
            new ControlMessage.Focus(true),
            new ControlMessage.Screen(new ScreenRequest(2712, 1220)),
            new ControlMessage.Screen(new ScreenRequest(2712, 1220, cursor: true)),
            new ControlMessage.Screen(null),
            new ControlMessage.ScreenAck(4_294_967_295),
            new ControlMessage.ScreenStatusMessage(ScreenStatus.Denied),
            new ControlMessage.ScreenCursor(0.4213, 1),
            new ControlMessage.VolumeMessage(new VolumeCommand.Step(1)),
            new ControlMessage.VolumeMessage(new VolumeCommand.Step(-3)),
            new ControlMessage.VolumeMessage(new VolumeCommand.Level(0.25)),
            new ControlMessage.VolumeMessage(new VolumeCommand.Muted(true)),
            new ControlMessage.VolumeStatus(new VolumeState(0.5625, false)),
            new ControlMessage.VolumeStatus(new VolumeState(null, true)),
            new ControlMessage.Power(PowerAction.Sleep),
            new ControlMessage.Power(PowerAction.Restart),
            new ControlMessage.Power(PowerAction.Shutdown),
            new ControlMessage.Ping(1_790_000_000_000),
            new ControlMessage.Pong(42),
            new ControlMessage.ErrorMessage("bad_message"),
        ];
        foreach (var message in messages)
        {
            Assert.Equal(message, ControlMessage.Decode(message.Encode()));
        }
    }

    [Fact]
    public void ControlMessagesDecodeJsonFromPhones()
    {
        // Bentuk persis yang dikirim app Android (org.json, urutan kunci bebas).
        ControlMessage? Decode(string json) => ControlMessage.Decode(Encoding.UTF8.GetBytes(json));
        Assert.Equal(new ControlMessage.Hello(1, "5f1e", "auth"), Decode("""{"t":"hello","v":1,"deviceId":"5f1e","mode":"auth"}"""));
        Assert.Equal(new ControlMessage.Settings(1.5, 2, false), Decode("""{"t":"settings","sensitivity":1.5,"scrollSpeed":2.0}"""));
        Assert.Equal(new ControlMessage.Screen(new ScreenRequest(2712, 1220)), Decode("""{"t":"screen","on":true,"maxWidth":2712,"maxHeight":1220}"""));
        Assert.Equal(new ControlMessage.ScreenAck(42), Decode("""{"t":"screen_ack","seq":42}"""));
        Assert.Equal(
            new ControlMessage.Screen(new ScreenRequest(2712, 1220, cursor: true)),
            Decode("""{"t":"screen","on":true,"maxWidth":2712,"maxHeight":1220,"cursor":true}"""));
        Assert.Equal(
            new ControlMessage.Settings(1.5, 2, false, true),
            Decode("""{"t":"settings","sensitivity":1.5,"scrollSpeed":2.0,"focusUpdates":false,"volumeUpdates":true}"""));
        Assert.Equal(new ControlMessage.Power(PowerAction.Shutdown), Decode("""{"t":"power","action":"shutdown"}"""));
        Assert.Null(Decode("""{"t":"power","action":"hibernate"}"""));
        Assert.Null(Decode("""{"t":"nope"}"""));
        Assert.Null(Decode("bukan json"));
        Assert.Null(Decode("""{"t":"hello","v":1,"deviceId":"x","mode":"lain"}"""));
    }

    [Fact]
    public void NewlinesAndTabsBecomeKeys()
    {
        Assert.Equal(
            new TextPiece[] { new TextPiece.Unicode("Halo"), new TextPiece.Key(KeyCode.Return), new TextPiece.Unicode("dunia"), new TextPiece.Key(KeyCode.Tab), new TextPiece.Unicode("ok") },
            TextChunker.Pieces("Halo\ndunia\tok"));
        Assert.True(TextChunker.IsAllowed("Halo 👋\n\t"));
        Assert.False(TextChunker.IsAllowed(""));
        Assert.False(TextChunker.IsAllowed("a\0b"));
        Assert.False(TextChunker.IsAllowed("a\rb"));
        Assert.False(TextChunker.IsAllowed("a\u007f"));
    }

    [Fact]
    public void VolumeMessagesFromPhonesAreStrict()
    {
        ControlMessage? Decode(string json) => ControlMessage.Decode(Encoding.UTF8.GetBytes(json));
        Assert.Equal(new ControlMessage.VolumeMessage(new VolumeCommand.Step(1)), Decode("""{"t":"volume","step":1}"""));
        Assert.Equal(new ControlMessage.VolumeMessage(new VolumeCommand.Step(-16)), Decode("""{"t":"volume","step":-40}"""));
        Assert.Equal(new ControlMessage.VolumeMessage(new VolumeCommand.Level(0.4)), Decode("""{"t":"volume","level":0.4}"""));
        Assert.Equal(new ControlMessage.VolumeMessage(new VolumeCommand.Level(1)), Decode("""{"t":"volume","level":7}"""));
        Assert.Equal(new ControlMessage.VolumeMessage(new VolumeCommand.Muted(true)), Decode("""{"t":"volume","muted":true}"""));
        // Bool JSON bukan angka, dan langkah harus bilangan bulat.
        Assert.Null(Decode("""{"t":"volume","step":true}"""));
        Assert.Null(Decode("""{"t":"volume","step":1.5}"""));
        Assert.Null(Decode("""{"t":"volume","muted":1}"""));
        Assert.Null(Decode("""{"t":"volume"}"""));
        Assert.Equal(new ControlMessage.VolumeStatus(new VolumeState(null, false)), Decode("""{"t":"volume_status","muted":false}"""));
        // Empat desimal, sama dengan agent Mac.
        Assert.Equal("""{"t":"screen_cursor","x":0.4213,"y":0.1}""", Encoding.UTF8.GetString(new ControlMessage.ScreenCursor(0.42131234, 0.1).Encode()));
    }

    [Fact]
    public void MacAddressFormat()
    {
        Assert.Equal("a4:83:e7:12:34:56", MacAddress.Format([0xA4, 0x83, 0xE7, 0x12, 0x34, 0x56]));
        Assert.Null(MacAddress.Format([0, 0, 0, 0, 0, 0]));
        Assert.Null(MacAddress.Format([0xA4, 0x83, 0xE7]));
    }
}
