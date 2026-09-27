using System.Buffers.Binary;
using System.Text;

namespace CursorController.Agent.Protocol;

public enum MouseButton : byte
{
    Left = 0,
    Right = 1,
}

/// <summary>Event input biner dari HP (protocol/PROTOCOL.md, bagian "Event input"). dx/dy dalam satuan 0,1 dp.</summary>
public abstract record InputMessage
{
    public const int MaxTextBytes = 1024;

    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    public sealed record Move(short Dx, short Dy) : InputMessage
    {
        public override string ToString() => $"move(dx: {Dx}, dy: {Dy})";
    }

    public sealed record Button(MouseButton Which, bool Down) : InputMessage
    {
        public override string ToString() => $"button({Which.ToString().ToLowerInvariant()}, down: {Down.ToString().ToLowerInvariant()})";
    }

    public sealed record Click(MouseButton Which, byte Count) : InputMessage
    {
        public override string ToString() => $"click({Which.ToString().ToLowerInvariant()}, count: {Count})";
    }

    public sealed record Scroll(short Dx, short Dy) : InputMessage
    {
        public override string ToString() => $"scroll(dx: {Dx}, dy: {Dy})";
    }

    public sealed record Text(string Value) : InputMessage
    {
        public override string ToString() => $"text(\"{Value.Replace("\n", "\\n").Replace("\t", "\\t")}\")";
    }

    public sealed record Key(KeyCode Code, KeyModifiers Modifiers) : InputMessage
    {
        public override string ToString() => $"key({Code.ToString().ToLowerInvariant()}, modifiers: {(byte)Modifiers})";
    }

    public byte[] Encode() => this switch
    {
        Move m => [0x01, .. Int16Bytes(m.Dx), .. Int16Bytes(m.Dy)],
        Button b => [0x02, (byte)b.Which, (byte)(b.Down ? 1 : 0)],
        Click c => [0x03, (byte)c.Which, c.Count],
        Scroll s => [0x04, .. Int16Bytes(s.Dx), .. Int16Bytes(s.Dy)],
        Text t => [0x05, .. Encoding.UTF8.GetBytes(t.Value)],
        Key k => [0x06, (byte)k.Code, (byte)k.Modifiers],
        _ => throw new InvalidOperationException(),
    };

    /// <summary>null untuk frame dengan panjang salah, tipe tidak dikenal, atau nilai di luar rentang.</summary>
    public static InputMessage? Decode(ReadOnlySpan<byte> b)
    {
        if (b.IsEmpty) return null;
        switch (b[0])
        {
            case 0x01 when b.Length == 5:
                return new Move(Int16(b[1..]), Int16(b[3..]));
            case 0x02 when b.Length == 3 && b[1] <= 1 && b[2] <= 1:
                return new Button((MouseButton)b[1], b[2] == 1);
            case 0x03 when b.Length == 3 && b[1] <= 1 && b[2] is 1 or 2:
                return new Click((MouseButton)b[1], b[2]);
            case 0x04 when b.Length == 5:
                return new Scroll(Int16(b[1..]), Int16(b[3..]));
            case 0x05 when b.Length >= 2 && b.Length <= MaxTextBytes + 1:
                try
                {
                    var text = StrictUtf8.GetString(b[1..]);
                    return TextChunker.IsAllowed(text) ? new Text(text) : null;
                }
                catch (DecoderFallbackException)
                {
                    return null;
                }
            case 0x06 when b.Length == 3:
                var key = (KeyCode)b[1];
                var modifiers = (KeyModifiers)b[2];
                if (!Enum.IsDefined(key) || (modifiers & ~KeyModifiers.All) != 0) return null;
                return new Key(key, modifiers);
            default:
                return null;
        }
    }

    private static short Int16(ReadOnlySpan<byte> bytes) => BinaryPrimitives.ReadInt16LittleEndian(bytes);

    private static byte[] Int16Bytes(short value)
    {
        var bytes = new byte[2];
        BinaryPrimitives.WriteInt16LittleEndian(bytes, value);
        return bytes;
    }
}
