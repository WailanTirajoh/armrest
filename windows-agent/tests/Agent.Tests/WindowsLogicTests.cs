using Armrest.Agent.Input;
using Armrest.Agent.Protocol;
using Armrest.Agent.Streaming;

namespace Armrest.Agent.Tests;

public class WindowsLogicTests
{
    [Fact]
    public void EveryProtocolKeyHasAWindowsKey()
    {
        foreach (var key in Enum.GetValues<KeyCode>())
        {
            var windows = WindowsKeys.For(key);
            Assert.True(windows.VirtualKey != 0 ^ windows.ScanCode != 0, $"{key}");
        }
    }

    [Fact]
    public void SpecialKeysUseVirtualKeysAndLettersUseAnsiScanCodes()
    {
        Assert.Equal(new WindowsKey(0x0D, 0, false), WindowsKeys.For(KeyCode.Return));
        Assert.Equal(new WindowsKey(0x25, 0, true), WindowsKeys.For(KeyCode.Left));
        Assert.Equal(new WindowsKey(0x2E, 0, true), WindowsKeys.For(KeyCode.ForwardDelete));
        Assert.Equal(new WindowsKey(0x7B, 0, false), WindowsKeys.For(KeyCode.F12));
        Assert.Equal(new WindowsKey(0, 0x2E, false), WindowsKeys.For(KeyCode.C));
        Assert.Equal(new WindowsKey(0, 0x0B, false), WindowsKeys.For(KeyCode.Digit0));
        Assert.Equal(new WindowsKey(0, 0x29, false), WindowsKeys.For(KeyCode.Grave));
    }

    [Fact]
    public void ModifiersArePressedWinCtrlAltShift()
    {
        Assert.Equal([WindowsKeys.WinKey, WindowsKeys.ControlKey, WindowsKeys.AltKey, WindowsKeys.ShiftKey], WindowsKeys.ModifierKeys(KeyModifiers.All));
        Assert.Equal([WindowsKeys.ControlKey, WindowsKeys.ShiftKey], WindowsKeys.ModifierKeys(KeyModifiers.Control | KeyModifiers.Shift));
        Assert.Empty(WindowsKeys.ModifierKeys(KeyModifiers.None));
    }

    [Fact]
    public void WheelScrollsNaturallyAndKeepsFractions()
    {
        var wheel = new WheelMath();
        // Jari naik 30 px → gulir ke bawah 60 satuan; jari ke kiri → gulir ke kanan.
        Assert.Equal((-60, 20), wheel.Units(-10, -30));
        var small = new WheelMath();
        Assert.Equal((0, 0), small.Units(0, 0));
        Assert.Equal((2, 0), small.Units(0, 1));
    }

    [Fact]
    public void BgraConvertsToBt709LimitedNv12()
    {
        // 2×2 putih, lalu 2×2 hitam, dalam satu gambar 4×2.
        byte[] bgra =
        [
            255, 255, 255, 255, 255, 255, 255, 255, 0, 0, 0, 255, 0, 0, 0, 255,
            255, 255, 255, 255, 255, 255, 255, 255, 0, 0, 0, 255, 0, 0, 0, 255,
        ];
        var nv12 = Nv12.FromBgra(bgra, 4, 2, 16);
        Assert.Equal(4 * 2 * 3 / 2, nv12.Length);
        Assert.Equal([235, 235, 16, 16], nv12[..4]);
        Assert.Equal([128, 128, 128, 128], nv12[8..]);
    }

    [Fact]
    public void TestPatternMatchesE2eContract()
    {
        var frame = TestPatternSource.Render(new PixelSize(1440, 900), 1);
        Assert.Equal((1440, 900), (frame.Width, frame.Height));
        Assert.Equal(1440 * 900 * 3 / 2, frame.Nv12.Length);
        Assert.NotEqual(TestPatternSource.Render(new PixelSize(1440, 900), 2).Nv12, frame.Nv12);
    }

    [Fact]
    public void MediaKeysUseMediaVirtualKeys()
    {
        Assert.Equal(new WindowsKey(0xB3, 0, true), WindowsKeys.For(KeyCode.PlayPause));
        Assert.Equal(new WindowsKey(0xB0, 0, true), WindowsKeys.For(KeyCode.NextTrack));
        Assert.Equal(new WindowsKey(0xB1, 0, true), WindowsKeys.For(KeyCode.PreviousTrack));
    }
}

