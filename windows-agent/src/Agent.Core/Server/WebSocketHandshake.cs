using System.Security.Cryptography;
using System.Text;

namespace CursorController.Agent.Server;

/// <summary>Sisi server handshake WebSocket (RFC 6455): baca request upgrade, balas 101.</summary>
internal static class WebSocketHandshake
{
    private const string AcceptGuid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private const int MaxHeaderBytes = 8192;

    public static async Task<bool> AcceptAsync(Stream stream, CancellationToken cancellationToken)
    {
        var head = await ReadHeadAsync(stream, cancellationToken);
        if (head is null) return false;
        var lines = head.Split("\r\n");
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in lines.Skip(1))
        {
            var colon = line.IndexOf(':');
            if (colon > 0) headers[line[..colon].Trim()] = line[(colon + 1)..].Trim();
        }
        var valid = lines[0].StartsWith("GET ", StringComparison.Ordinal)
            && headers.TryGetValue("Upgrade", out var upgrade) && upgrade.Contains("websocket", StringComparison.OrdinalIgnoreCase)
            && headers.TryGetValue("Sec-WebSocket-Key", out var key) && key.Length > 0
            && (!headers.TryGetValue("Sec-WebSocket-Version", out var version) || version == "13");
        if (!valid)
        {
            await WriteAsync(stream, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n", cancellationToken);
            return false;
        }
        var accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(headers["Sec-WebSocket-Key"] + AcceptGuid)));
        await WriteAsync(
            stream,
            $"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n",
            cancellationToken);
        return true;
    }

    /// <summary>Baca sampai baris kosong, satu byte sekali supaya tidak ikut menelan frame WebSocket pertama.</summary>
    private static async Task<string?> ReadHeadAsync(Stream stream, CancellationToken cancellationToken)
    {
        var bytes = new List<byte>(512);
        var one = new byte[1];
        while (bytes.Count < MaxHeaderBytes)
        {
            if (await stream.ReadAsync(one, cancellationToken) == 0) return null;
            bytes.Add(one[0]);
            var n = bytes.Count;
            if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n' && bytes[n - 2] == '\r' && bytes[n - 1] == '\n')
            {
                return Encoding.ASCII.GetString([.. bytes], 0, n - 4);
            }
        }
        return null;
    }

    private static async Task WriteAsync(Stream stream, string text, CancellationToken cancellationToken)
    {
        await stream.WriteAsync(Encoding.ASCII.GetBytes(text), cancellationToken);
        await stream.FlushAsync(cancellationToken);
    }
}
