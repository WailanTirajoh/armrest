namespace Armrest.Agent.Streaming;

/// <summary>
/// Konversi gambar BGRA (hasil tangkapan GDI) ke NV12 BT.709 rentang terbatas, format masukan encoder H.264.
/// Koefisien bulat dipilih supaya tiap barisnya berjumlah 0, jadi abu-abu tetap netral (U = V = 128).
/// </summary>
public static class Nv12
{
    /// <param name="bgra">Piksel BGRA, baris dari atas ke bawah.</param>
    /// <param name="stride">Byte per baris BGRA.</param>
    /// <remarks>Lebar dan tinggi harus genap.</remarks>
    public static byte[] FromBgra(ReadOnlySpan<byte> bgra, int width, int height, int stride)
    {
        var output = new byte[width * height * 3 / 2];
        var chroma = width * height;
        for (var y = 0; y < height; y += 2)
        {
            var row0 = bgra.Slice(y * stride, width * 4);
            var row1 = bgra.Slice((y + 1) * stride, width * 4);
            var luma0 = y * width;
            var luma1 = luma0 + width;
            var uv = chroma + y / 2 * width;
            for (var x = 0; x < width; x += 2)
            {
                int sumR = 0, sumG = 0, sumB = 0;
                for (var dy = 0; dy < 2; dy++)
                {
                    var row = dy == 0 ? row0 : row1;
                    var luma = dy == 0 ? luma0 : luma1;
                    for (var dx = 0; dx < 2; dx++)
                    {
                        var p = (x + dx) * 4;
                        int b = row[p], g = row[p + 1], r = row[p + 2];
                        output[luma + x + dx] = (byte)(((47 * r + 157 * g + 16 * b + 128) >> 8) + 16);
                        sumR += r;
                        sumG += g;
                        sumB += b;
                    }
                }
                int ar = sumR >> 2, ag = sumG >> 2, ab = sumB >> 2;
                output[uv + x] = (byte)(((-26 * ar - 86 * ag + 112 * ab + 128) >> 8) + 128);
                output[uv + x + 1] = (byte)(((112 * ar - 102 * ag - 10 * ab + 128) >> 8) + 128);
            }
        }
        return output;
    }
}
