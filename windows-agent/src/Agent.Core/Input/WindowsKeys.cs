using Armrest.Agent.Protocol;

namespace Armrest.Agent.Input;

/// <summary>Cara menekan satu tombol di Windows lewat SendInput.</summary>
/// <param name="VirtualKey">Virtual key (tombol khusus), atau 0 kalau memakai scan code.</param>
/// <param name="ScanCode">Scan code set 1 posisi ANSI (huruf, angka, tanda baca), atau 0.</param>
/// <param name="Extended">Perlu flag KEYEVENTF_EXTENDEDKEY (panah, Home/End, Page Up/Down, Delete).</param>
public readonly record struct WindowsKey(ushort VirtualKey, ushort ScanCode, bool Extended);

/// <summary>
/// Peta kode tombol protokol ke Windows. Huruf dan tanda baca memakai scan code posisi ANSI, jadi Ctrl+C selalu
/// tombol di posisi C apa pun layout keyboardnya, sama dengan agent Mac.
/// </summary>
public static class WindowsKeys
{
    public const ushort ShiftKey = 0xA0;   // VK_LSHIFT
    public const ushort ControlKey = 0xA2; // VK_LCONTROL
    public const ushort AltKey = 0xA4;     // VK_LMENU
    public const ushort WinKey = 0x5B;     // VK_LWIN

    public static WindowsKey For(KeyCode key) => key switch
    {
        KeyCode.Return => Vk(0x0D),
        KeyCode.Backspace => Vk(0x08),
        KeyCode.Tab => Vk(0x09),
        KeyCode.Escape => Vk(0x1B),
        KeyCode.Space => Vk(0x20),
        KeyCode.ForwardDelete => Vk(0x2E, extended: true),
        KeyCode.Left => Vk(0x25, extended: true),
        KeyCode.Right => Vk(0x27, extended: true),
        KeyCode.Up => Vk(0x26, extended: true),
        KeyCode.Down => Vk(0x28, extended: true),
        KeyCode.Home => Vk(0x24, extended: true),
        KeyCode.End => Vk(0x23, extended: true),
        KeyCode.PageUp => Vk(0x21, extended: true),
        KeyCode.PageDown => Vk(0x22, extended: true),
        >= KeyCode.F1 and <= KeyCode.F12 => Vk((ushort)(0x70 + (key - KeyCode.F1))),
        // Tombol media diterima pemutar yang sedang aktif lewat System Media Transport Controls.
        KeyCode.PlayPause => Vk(0xB3, extended: true),    // VK_MEDIA_PLAY_PAUSE
        KeyCode.NextTrack => Vk(0xB0, extended: true),    // VK_MEDIA_NEXT_TRACK
        KeyCode.PreviousTrack => Vk(0xB1, extended: true), // VK_MEDIA_PREV_TRACK
        _ => new WindowsKey(0, ScanCodes[key], false),
    };

    /// <summary>Modifier ditekan lebih dulu dan dilepas terakhir: Win, Ctrl, Alt, lalu Shift (urutan agent Mac).</summary>
    public static IReadOnlyList<ushort> ModifierKeys(KeyModifiers modifiers)
    {
        var keys = new List<ushort>(4);
        if (modifiers.HasFlag(KeyModifiers.Command)) keys.Add(WinKey);
        if (modifiers.HasFlag(KeyModifiers.Control)) keys.Add(ControlKey);
        if (modifiers.HasFlag(KeyModifiers.Option)) keys.Add(AltKey);
        if (modifiers.HasFlag(KeyModifiers.Shift)) keys.Add(ShiftKey);
        return keys;
    }

    private static WindowsKey Vk(ushort virtualKey, bool extended = false) => new(virtualKey, 0, extended);

    private static readonly Dictionary<KeyCode, ushort> ScanCodes = new()
    {
        [KeyCode.A] = 0x1E, [KeyCode.B] = 0x30, [KeyCode.C] = 0x2E, [KeyCode.D] = 0x20, [KeyCode.E] = 0x12,
        [KeyCode.F] = 0x21, [KeyCode.G] = 0x22, [KeyCode.H] = 0x23, [KeyCode.I] = 0x17, [KeyCode.J] = 0x24,
        [KeyCode.K] = 0x25, [KeyCode.L] = 0x26, [KeyCode.M] = 0x32, [KeyCode.N] = 0x31, [KeyCode.O] = 0x18,
        [KeyCode.P] = 0x19, [KeyCode.Q] = 0x10, [KeyCode.R] = 0x13, [KeyCode.S] = 0x1F, [KeyCode.T] = 0x14,
        [KeyCode.U] = 0x16, [KeyCode.V] = 0x2F, [KeyCode.W] = 0x11, [KeyCode.X] = 0x2D, [KeyCode.Y] = 0x15,
        [KeyCode.Z] = 0x2C,
        [KeyCode.Digit1] = 0x02, [KeyCode.Digit2] = 0x03, [KeyCode.Digit3] = 0x04, [KeyCode.Digit4] = 0x05,
        [KeyCode.Digit5] = 0x06, [KeyCode.Digit6] = 0x07, [KeyCode.Digit7] = 0x08, [KeyCode.Digit8] = 0x09,
        [KeyCode.Digit9] = 0x0A, [KeyCode.Digit0] = 0x0B,
        [KeyCode.Minus] = 0x0C, [KeyCode.Equal] = 0x0D, [KeyCode.LeftBracket] = 0x1A, [KeyCode.RightBracket] = 0x1B,
        [KeyCode.Backslash] = 0x2B, [KeyCode.Semicolon] = 0x27, [KeyCode.Quote] = 0x28, [KeyCode.Comma] = 0x33,
        [KeyCode.Period] = 0x34, [KeyCode.Slash] = 0x35, [KeyCode.Grave] = 0x29,
    };
}

/// <summary>Scroll dari HP (piksel logis) menjadi satuan roda mouse Windows, arah natural seperti touchpad.</summary>
public sealed class WheelMath
{
    /// <summary>120 satuan = satu takik roda ≈ 3 baris ≈ 60 piksel di kebanyakan app.</summary>
    public const double UnitsPerPixel = 2.0;

    private double remainderX;
    private double remainderY;

    /// <summary>
    /// Jari naik (dy negatif) → konten ikut naik → gulir ke bawah (wheel negatif). Jari ke kiri → gulir ke kanan
    /// (hwheel positif).
    /// </summary>
    public (int Wheel, int HWheel) Units(int dx, int dy)
    {
        var y = dy * UnitsPerPixel + remainderY;
        var x = -dx * UnitsPerPixel + remainderX;
        var wheel = (int)Math.Truncate(y);
        var hwheel = (int)Math.Truncate(x);
        remainderY = y - wheel;
        remainderX = x - hwheel;
        return (wheel, hwheel);
    }

    public void Reset() => remainderX = remainderY = 0;
}
