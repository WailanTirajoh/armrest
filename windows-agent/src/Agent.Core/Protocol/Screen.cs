using System.Buffers.Binary;

namespace CursorController.Agent.Protocol;

/// <summary>Permintaan HP untuk melihat layar: ukuran video maksimum dalam piksel, biasanya ukuran layar HP.</summary>
public sealed record ScreenRequest
{
    public ScreenRequest(int maxWidth, int maxHeight)
    {
        MaxWidth = Math.Clamp(maxWidth, 64, 8192);
        MaxHeight = Math.Clamp(maxHeight, 64, 8192);
    }

    public int MaxWidth { get; }
    public int MaxHeight { get; }
}

/// <summary>Status aliran layar yang dikirim ke HP (pesan <c>screen_status</c>).</summary>
public enum ScreenStatus
{
    /// <summary>Tangkapan layar berjalan; frame menyusul.</summary>
    Streaming,
    /// <summary>Komputer tidak mengizinkan tangkapan layar.</summary>
    Denied,
    /// <summary>Tangkapan layar gagal dimulai atau berhenti karena error.</summary>
    Failed,
}

public static class ScreenStatusExtensions
{
    public static string WireName(this ScreenStatus status) => status switch
    {
        ScreenStatus.Streaming => "streaming",
        ScreenStatus.Denied => "denied",
        _ => "failed",
    };

    public static ScreenStatus? FromWireName(string? name) => name switch
    {
        "streaming" => ScreenStatus.Streaming,
        "denied" => ScreenStatus.Denied,
        "failed" => ScreenStatus.Failed,
        _ => null,
    };
}

public readonly record struct PixelSize(int Width, int Height)
{
    public override string ToString() => $"{Width}x{Height}";
}

/// <summary>Frame biner komputer → HP untuk video layar (protocol/PROTOCOL.md, bagian "Layar komputer").</summary>
public abstract record ScreenPacket
{
    public const byte ConfigType = 0x81;
    public const byte FrameType = 0x82;
    private const byte H264 = 1;
    private const int HeaderLength = 6;

    /// <summary>Ukuran video dan parameter decoder H.264 (SPS dan PPS, Annex B). Dikirim sebelum setiap keyframe.</summary>
    public sealed record Config(int Width, int Height, byte[] ParameterSets) : ScreenPacket
    {
        public bool Equals(Config? other) =>
            other is not null && Width == other.Width && Height == other.Height && ParameterSets.AsSpan().SequenceEqual(other.ParameterSets);

        public override int GetHashCode() => HashCode.Combine(Width, Height, ParameterSets.Length);
    }

    /// <summary>Satu frame video (access unit Annex B). <c>Seq</c> naik satu per frame.</summary>
    public sealed record Frame(uint Seq, bool Keyframe, byte[] Data) : ScreenPacket
    {
        public bool Equals(Frame? other) =>
            other is not null && Seq == other.Seq && Keyframe == other.Keyframe && Data.AsSpan().SequenceEqual(other.Data);

        public override int GetHashCode() => HashCode.Combine(Seq, Keyframe, Data.Length);
    }

    public byte[] Encode()
    {
        switch (this)
        {
            case Config c:
            {
                var bytes = new byte[HeaderLength + c.ParameterSets.Length];
                bytes[0] = ConfigType;
                bytes[1] = H264;
                BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(2), (ushort)c.Width);
                BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(4), (ushort)c.Height);
                c.ParameterSets.CopyTo(bytes, HeaderLength);
                return bytes;
            }
            case Frame f:
            {
                var bytes = new byte[HeaderLength + f.Data.Length];
                bytes[0] = FrameType;
                BinaryPrimitives.WriteUInt32LittleEndian(bytes.AsSpan(1), f.Seq);
                bytes[5] = (byte)(f.Keyframe ? 1 : 0);
                f.Data.CopyTo(bytes, HeaderLength);
                return bytes;
            }
            default:
                throw new InvalidOperationException();
        }
    }

    /// <summary>null untuk tipe atau codec tidak dikenal, ukuran 0, flag tidak dikenal, atau tanpa isi.</summary>
    public static ScreenPacket? Decode(ReadOnlySpan<byte> b)
    {
        if (b.Length <= HeaderLength) return null;
        switch (b[0])
        {
            case ConfigType:
                var width = BinaryPrimitives.ReadUInt16LittleEndian(b[2..]);
                var height = BinaryPrimitives.ReadUInt16LittleEndian(b[4..]);
                if (b[1] != H264 || width == 0 || height == 0) return null;
                return new Config(width, height, b[HeaderLength..].ToArray());
            case FrameType:
                if (b[5] > 1) return null;
                return new Frame(BinaryPrimitives.ReadUInt32LittleEndian(b[1..]), b[5] == 1, b[HeaderLength..].ToArray());
            default:
                return null;
        }
    }
}

/// <summary>Ukuran dan bitrate video layar.</summary>
public static class ScreenSizing
{
    public const int Fps = 30;
    /// <summary>Batas sisi panjang dan pendek video, supaya bitrate dan beban decoder HP tetap wajar.</summary>
    public const int MaxLongSide = 1920;
    public const int MaxShortSide = 1200;

    /// <summary>
    /// Ukuran video untuk layar berukuran <paramref name="display"/> (piksel): muat di layar HP dan di batas
    /// 1920 × 1200, rasio tetap, tanpa memperbesar, dan sisi genap. Orientasi kotak mengikuti layar komputer.
    /// </summary>
    public static PixelSize VideoSize(PixelSize display, ScreenRequest request)
    {
        var longSide = Math.Min(Math.Max(request.MaxWidth, request.MaxHeight), MaxLongSide);
        var shortSide = Math.Min(Math.Min(request.MaxWidth, request.MaxHeight), MaxShortSide);
        var landscape = display.Width >= display.Height;
        var boxWidth = Math.Min(landscape ? longSide : shortSide, display.Width);
        var boxHeight = Math.Min(landscape ? shortSide : longSide, display.Height);
        int width, height;
        if ((long)boxWidth * display.Height <= (long)boxHeight * display.Width)
        {
            width = boxWidth;
            height = (int)((long)display.Height * boxWidth / display.Width);
        }
        else
        {
            height = boxHeight;
            width = (int)((long)display.Width * boxHeight / display.Height);
        }
        return new PixelSize(Math.Max(2, width & ~1), Math.Max(2, height & ~1));
    }

    /// <summary>Bitrate rata-rata sekitar 0,12 bit per piksel per frame, antara 2 dan 10 Mbit/s.</summary>
    public static int Bitrate(PixelSize size) =>
        (int)Math.Clamp((long)size.Width * size.Height * Fps * 12 / 100, 2_000_000, 10_000_000);
}

/// <summary>
/// Batas frame yang sudah dikirim tapi belum dikonfirmasi HP. Kalau penuh, frame berikutnya ditahan, jadi saat
/// WiFi lambat gambar dilewati alih-alih menumpuk di antrean.
/// </summary>
public sealed class ScreenFlowControl(uint window = 4)
{
    public uint Window { get; } = window;
    public uint LastSent { get; private set; }
    public uint LastAcked { get; private set; }

    internal ScreenFlowControl(uint window, uint lastSent, uint lastAcked) : this(window)
    {
        LastSent = lastSent;
        LastAcked = lastAcked;
    }

    public bool CanSend => unchecked(LastSent - LastAcked) < Window;

    /// <summary>Nomor untuk frame berikutnya.</summary>
    public uint Next() => unchecked(++LastSent);

    /// <summary>Konfirmasi bersifat kumulatif. Nomor lama atau yang belum pernah dikirim diabaikan.</summary>
    public void Ack(uint seq)
    {
        var ahead = unchecked(seq - LastAcked);
        if (ahead == 0 || ahead > unchecked(LastSent - LastAcked)) return;
        LastAcked = seq;
    }
}

/// <summary>NAL unit H.264 dalam format Annex B (diawali start code 00 00 00 01), format yang diminta decoder HP.</summary>
public static class AnnexB
{
    public static ReadOnlySpan<byte> StartCode => [0, 0, 0, 1];

    /// <summary>NAL unit berawalan panjang big-endian → Annex B. null kalau panjangnya tidak cocok dengan data.</summary>
    public static byte[]? FromLengthPrefixed(ReadOnlySpan<byte> data, int lengthSize = 4)
    {
        using var output = new MemoryStream(data.Length + 16);
        var i = 0;
        while (i < data.Length)
        {
            if (i + lengthSize > data.Length) return null;
            var length = 0;
            for (var k = 0; k < lengthSize; k++) length = (length << 8) | data[i + k];
            i += lengthSize;
            if (length <= 0 || i + length > data.Length) return null;
            output.Write(StartCode);
            output.Write(data.Slice(i, length));
            i += length;
        }
        return output.Length == 0 ? null : output.ToArray();
    }

    public static byte[] Join(IEnumerable<byte[]> units)
    {
        using var output = new MemoryStream();
        foreach (var unit in units)
        {
            output.Write(StartCode);
            output.Write(unit);
        }
        return output.ToArray();
    }

    /// <summary>NAL unit tanpa start code. Start code 3 dan 4 byte sama-sama dikenali.</summary>
    public static List<byte[]> Split(ReadOnlySpan<byte> data)
    {
        var starts = new List<(int Code, int Unit)>();
        var i = 0;
        while (i + 2 < data.Length)
        {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1)
            {
                starts.Add((i > 0 && data[i - 1] == 0 ? i - 1 : i, i + 3));
                i += 3;
            }
            else
            {
                i++;
            }
        }
        var units = new List<byte[]>(starts.Count);
        for (var k = 0; k < starts.Count; k++)
        {
            var end = k + 1 < starts.Count ? starts[k + 1].Code : data.Length;
            units.Add(data[starts[k].Unit..end].ToArray());
        }
        return units;
    }

    public static int NalType(ReadOnlySpan<byte> unit) => unit.IsEmpty ? -1 : unit[0] & 0x1F;
}
