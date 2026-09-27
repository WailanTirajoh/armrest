using System.Text;

namespace Armrest.Agent.Protocol;

/// <summary>Tombol yang bisa dikirim HP (kode protokol, lihat PROTOCOL.md). Huruf dan tanda baca = posisi ANSI.</summary>
public enum KeyCode : byte
{
    Return = 0x01, Backspace, Tab, Escape, Space, ForwardDelete,
    Left = 0x07, Right, Up, Down,
    Home = 0x0B, End, PageUp, PageDown,
    F1 = 0x10, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    A = 0x20, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    Digit0 = 0x40, Digit1, Digit2, Digit3, Digit4, Digit5, Digit6, Digit7, Digit8, Digit9,
    Minus = 0x50, Equal, LeftBracket, RightBracket, Backslash, Semicolon, Quote, Comma, Period, Slash, Grave,

    // Tombol media sistem; modifier diabaikan.
    PlayPause = 0x60, NextTrack, PreviousTrack,
}

/// <summary>Bit modifier. Di Windows: Control = Ctrl, Option = Alt, Command = tombol Windows.</summary>
[Flags]
public enum KeyModifiers : byte
{
    None = 0,
    Shift = 0x01,
    Control = 0x02,
    Option = 0x04,
    Command = 0x08,
    All = Shift | Control | Option | Command,
}

/// <summary>Potongan teks untuk diketik: teks biasa, atau tombol untuk baris baru dan tab.</summary>
public abstract record TextPiece
{
    public sealed record Unicode(string Text) : TextPiece;

    public sealed record Key(KeyCode Code) : TextPiece;
}

public static class TextChunker
{
    /// <summary>Baris baru dan tab menjadi tombol Return/Tab karena banyak app mengabaikannya di event Unicode.</summary>
    public static IReadOnlyList<TextPiece> Pieces(string text)
    {
        var pieces = new List<TextPiece>();
        var current = new StringBuilder();
        void Flush()
        {
            if (current.Length == 0) return;
            pieces.Add(new TextPiece.Unicode(current.ToString()));
            current.Clear();
        }
        for (var i = 0; i < text.Length; i++)
        {
            var c = text[i];
            if (c == '\r' && i + 1 < text.Length && text[i + 1] == '\n') continue;
            if (c is '\n' or '\r')
            {
                Flush();
                pieces.Add(new TextPiece.Key(KeyCode.Return));
            }
            else if (c == '\t')
            {
                Flush();
                pieces.Add(new TextPiece.Key(KeyCode.Tab));
            }
            else
            {
                current.Append(c);
            }
        }
        Flush();
        return pieces;
    }

    /// <summary>Teks valid: tidak kosong, dan tanpa karakter kontrol selain "\n" dan "\t".</summary>
    public static bool IsAllowed(string text) =>
        text.Length > 0 && text.EnumerateRunes().All(r => r.Value is '\n' or '\t' || (r.Value >= 0x20 && r.Value != 0x7F));
}
