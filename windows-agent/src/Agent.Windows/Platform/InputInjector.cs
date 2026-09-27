using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using CursorController.Agent.Hosting;
using CursorController.Agent.Input;
using CursorController.Agent.Protocol;
using CursorController.Agent.Windows.Native;
using Forms = System.Windows.Forms;

namespace CursorController.Agent.Windows.Platform;

/// <summary>
/// Menerjemahkan input dari HP menjadi SendInput. Jendela yang berjalan sebagai administrator, UAC, dan layar kunci
/// tidak bisa dikontrol (batasan Windows untuk app biasa).
/// </summary>
internal sealed class InputInjector : IInputSink
{
    private readonly PointerMath math = new();
    private readonly WheelMath wheel = new();
    private double remainderX;
    private double remainderY;
    private bool leftDown;
    private bool rightDown;

    public void Apply(double sensitivity, double scrollSpeed)
    {
        math.Curve = math.Curve with { Sensitivity = sensitivity };
        math.ScrollSpeed = scrollSpeed;
    }

    public void Handle(InputMessage message)
    {
        switch (message)
        {
            case InputMessage.Move move:
                Move(move.Dx, move.Dy);
                break;
            case InputMessage.Button button:
                Press(button.Which, button.Down);
                break;
            case InputMessage.Click click:
                // Dua klik berdekatan dikenali Windows sebagai klik ganda.
                for (var i = 0; i < click.Count; i++)
                {
                    Press(click.Which, true);
                    Press(click.Which, false);
                }
                break;
            case InputMessage.Scroll scroll:
                Scroll(scroll.Dx, scroll.Dy);
                break;
            case InputMessage.Text text:
                Type(text.Value);
                break;
            case InputMessage.Key key:
                PressKey(key.Code, key.Modifiers);
                break;
        }
    }

    public void ReleaseButtons()
    {
        if (leftDown) Press(MouseButton.Left, false);
        if (rightDown) Press(MouseButton.Right, false);
        math.Reset();
        wheel.Reset();
        remainderX = remainderY = 0;
    }

    private void Move(short dx, short dy)
    {
        var delta = math.PointerDelta(dx, dy, Stopwatch.GetTimestamp() / (double)Stopwatch.Frequency);
        if (delta.Dx == 0 && delta.Dy == 0) return;
        if (!Win32.GetCursorPos(out var cursor)) return;
        // PointerMath menghitung piksel logis (seperti poin di Mac); skala DPI monitor membuatnya terasa sama.
        var scale = DpiScale(cursor);
        var fx = delta.Dx * scale + remainderX;
        var fy = delta.Dy * scale + remainderY;
        var ix = Math.Truncate(fx);
        var iy = Math.Truncate(fy);
        remainderX = fx - ix;
        remainderY = fy - iy;
        var displays = Forms.Screen.AllScreens
            .Select(s => new DisplayRect(s.Bounds.X, s.Bounds.Y, s.Bounds.Width, s.Bounds.Height))
            .ToList();
        var (x, y) = DisplayClamp.Clamp((cursor.X + ix, cursor.Y + iy), (cursor.X, cursor.Y), displays);
        // Posisi absolut di virtual desktop, supaya akselerasi pointer Windows tidak ikut menumpuk.
        var left = Win32.GetSystemMetrics(Win32.SmXVirtualScreen);
        var top = Win32.GetSystemMetrics(Win32.SmYVirtualScreen);
        var width = Math.Max(2, Win32.GetSystemMetrics(Win32.SmCxVirtualScreen));
        var height = Math.Max(2, Win32.GetSystemMetrics(Win32.SmCyVirtualScreen));
        var nx = (int)Math.Round((x - left) * 65535.0 / (width - 1));
        var ny = (int)Math.Round((y - top) * 65535.0 / (height - 1));
        Send(Win32.Input.ForMouse(Win32.MouseMove | Win32.MouseAbsolute | Win32.MouseVirtualDesk | Win32.MouseMoveNoCoalesce, nx, ny));
    }

    private void Press(MouseButton button, bool down)
    {
        uint flags;
        if (button == MouseButton.Left)
        {
            leftDown = down;
            flags = down ? Win32.MouseLeftDown : Win32.MouseLeftUp;
        }
        else
        {
            rightDown = down;
            flags = down ? Win32.MouseRightDown : Win32.MouseRightUp;
        }
        Send(Win32.Input.ForMouse(flags));
    }

    private void Scroll(short dx, short dy)
    {
        var delta = math.ScrollDelta(dx, dy);
        var (vertical, horizontal) = wheel.Units(delta.Dx, delta.Dy);
        if (vertical != 0) Send(Win32.Input.ForMouse(Win32.MouseWheel, data: vertical));
        if (horizontal != 0) Send(Win32.Input.ForMouse(Win32.MouseHWheel, data: horizontal));
    }

    /// <summary>Teks diketik sebagai karakter Unicode, jadi hasilnya tidak bergantung pada layout keyboard.</summary>
    private void Type(string text)
    {
        foreach (var piece in TextChunker.Pieces(text))
        {
            switch (piece)
            {
                case TextPiece.Unicode unicode:
                    var inputs = new List<Win32.Input>(unicode.Text.Length * 2);
                    foreach (var unit in unicode.Text)
                    {
                        inputs.Add(Win32.Input.ForKey(0, unit, Win32.KeyUnicode));
                        inputs.Add(Win32.Input.ForKey(0, unit, Win32.KeyUnicode | Win32.KeyUp));
                    }
                    Send([.. inputs]);
                    break;
                case TextPiece.Key key:
                    PressKey(key.Code, KeyModifiers.None);
                    break;
            }
        }
    }

    /// <summary>Modifier ditekan dulu, lalu tombolnya, lalu modifier dilepas dengan urutan terbalik.</summary>
    private static void PressKey(KeyCode code, KeyModifiers modifiers)
    {
        var modifierKeys = WindowsKeys.ModifierKeys(modifiers);
        var key = WindowsKeys.For(code);
        var inputs = new List<Win32.Input>();
        inputs.AddRange(modifierKeys.Select(vk => Modifier(vk, up: false)));
        inputs.Add(Key(key, up: false));
        inputs.Add(Key(key, up: true));
        inputs.AddRange(modifierKeys.Reverse().Select(vk => Modifier(vk, up: true)));
        Send([.. inputs]);
    }

    private static Win32.Input Modifier(ushort virtualKey, bool up)
    {
        var flags = (up ? Win32.KeyUp : 0) | (virtualKey == WindowsKeys.WinKey ? Win32.KeyExtended : 0);
        return Win32.Input.ForKey(virtualKey, (ushort)Win32.MapVirtualKey(virtualKey, Win32.MapVkToVsc), flags);
    }

    private static Win32.Input Key(WindowsKey key, bool up)
    {
        var upFlag = up ? Win32.KeyUp : 0;
        if (key.ScanCode != 0) return Win32.Input.ForKey(0, key.ScanCode, Win32.KeyScanCode | upFlag);
        var scanCode = (ushort)Win32.MapVirtualKey(key.VirtualKey, Win32.MapVkToVsc);
        return Win32.Input.ForKey(key.VirtualKey, scanCode, (key.Extended ? Win32.KeyExtended : 0) | upFlag);
    }

    private static void Send(params Win32.Input[] inputs) =>
        Win32.SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Win32.Input>());

    private static double DpiScale(Win32.Point point)
    {
        var monitor = Win32.MonitorFromPoint(point, Win32.MonitorDefaultToNearest);
        return Win32.GetDpiForMonitor(monitor, Win32.MdtEffectiveDpi, out var dpiX, out _) == 0 && dpiX > 0 ? dpiX / 96.0 : 1.0;
    }
}
