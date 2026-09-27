using System;
using System.Diagnostics;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Threading;
using CursorController.Agent.Protocol;
using CursorController.Agent.Streaming;
using CursorController.Agent.Windows.Native;
using Forms = System.Windows.Forms;

namespace CursorController.Agent.Windows.Screen;

/// <summary>
/// Tangkapan layar lewat GDI dari monitor tempat kursor berada, sudah diperkecil ke ukuran video dan termasuk
/// kursornya. Jalan di semua Windows 10/11 dan di VM. Gambar yang sama dengan sebelumnya tidak dikirim.
/// </summary>
internal sealed class GdiScreenSource : IScreenSource
{
    private readonly object gate = new();
    private ScreenRequest request = new(ScreenSizing.MaxLongSide, ScreenSizing.MaxShortSide);
    private Action<ScreenSourceEvent>? events;
    private volatile bool running;

    public void Start(ScreenRequest request, Action<ScreenSourceEvent> events)
    {
        lock (gate)
        {
            this.request = request;
            this.events = events;
        }
        running = true;
        new Thread(Run) { IsBackground = true, Name = "cursorctl-capture", Priority = ThreadPriority.AboveNormal }.Start();
    }

    public void Update(ScreenRequest request)
    {
        lock (gate) this.request = request;
    }

    public void Stop()
    {
        running = false;
        lock (gate) events = null;
    }

    private void Emit(ScreenSourceEvent e)
    {
        Action<ScreenSourceEvent>? target;
        lock (gate) target = events;
        target?.Invoke(e);
    }

    private void Run()
    {
        var interval = TimeSpan.FromSeconds(1.0 / ScreenSizing.Fps);
        var screen = Win32.GetDC(0);
        if (screen == 0)
        {
            Emit(new ScreenSourceEvent.Stopped(new InvalidOperationException("GetDC gagal")));
            return;
        }
        Capture? capture = null;
        byte[]? previous = null;
        var started = false;
        try
        {
            while (running)
            {
                var tick = Stopwatch.StartNew();
                Win32.GetCursorPos(out var cursor);
                var bounds = Forms.Screen.FromPoint(new Point(cursor.X, cursor.Y)).Bounds;
                ScreenRequest current;
                lock (gate) current = request;
                var size = ScreenSizing.VideoSize(new PixelSize(bounds.Width, bounds.Height), current);
                if (capture is null || capture.Size != size)
                {
                    capture?.Dispose();
                    capture = new Capture(screen, size);
                    previous = null;
                    Emit(new ScreenSourceEvent.Size(size));
                }
                if (!started)
                {
                    Emit(new ScreenSourceEvent.Started());
                    started = true;
                }
                // Gagal saat desktop aman aktif (UAC, layar kunci): lewati sampai kembali normal.
                if (capture.Grab(screen, bounds))
                {
                    var pixels = capture.Pixels;
                    if (previous is null || !pixels.SequenceEqual(previous))
                    {
                        previous = pixels.ToArray();
                        Emit(new ScreenSourceEvent.Frame(new VideoFrame(size.Width, size.Height, Nv12.FromBgra(previous, size.Width, size.Height, size.Width * 4))));
                    }
                }
                var rest = interval - tick.Elapsed;
                if (rest > TimeSpan.Zero) Thread.Sleep(rest);
            }
        }
        catch (Exception e)
        {
            if (running) Emit(new ScreenSourceEvent.Stopped(e));
        }
        finally
        {
            capture?.Dispose();
            Win32.ReleaseDC(0, screen);
        }
    }

    /// <summary>DC memori dengan DIB 32-bit top-down seukuran video.</summary>
    private sealed unsafe class Capture : IDisposable
    {
        private const int CursorWidthMetric = 13; // SM_CXCURSOR
        private readonly nint memory;
        private readonly nint bitmap;
        private readonly nint previousBitmap;
        private readonly nint bits;

        public Capture(nint screen, PixelSize size)
        {
            Size = size;
            memory = Win32.CreateCompatibleDC(screen);
            var info = new Win32.BitmapInfo
            {
                Header = new Win32.BitmapInfoHeader
                {
                    Size = Marshal.SizeOf<Win32.BitmapInfoHeader>(),
                    Width = size.Width,
                    Height = -size.Height,
                    Planes = 1,
                    BitCount = 32,
                },
            };
            bitmap = Win32.CreateDIBSection(memory, info, Win32.DibRgbColors, out bits, 0, 0);
            if (bitmap == 0 || bits == 0) throw new InvalidOperationException("CreateDIBSection gagal");
            previousBitmap = Win32.SelectObject(memory, bitmap);
            // HALFTONE menjaga teks tetap terbaca saat layar diperkecil.
            Win32.SetStretchBltMode(memory, Win32.Halftone);
            Win32.SetBrushOrgEx(memory, 0, 0, 0);
        }

        public PixelSize Size { get; }

        public ReadOnlySpan<byte> Pixels => new((void*)bits, Size.Width * Size.Height * 4);

        public bool Grab(nint screen, Rectangle bounds)
        {
            if (!Win32.StretchBlt(memory, 0, 0, Size.Width, Size.Height, screen, bounds.X, bounds.Y, bounds.Width, bounds.Height, Win32.SrcCopy)) return false;
            DrawCursor(bounds);
            Win32.GdiFlush();
            return true;
        }

        private void DrawCursor(Rectangle bounds)
        {
            var info = new Win32.CursorInfo { Size = Marshal.SizeOf<Win32.CursorInfo>() };
            if (!Win32.GetCursorInfo(ref info) || (info.Flags & Win32.CursorShowing) == 0) return;
            if (!Win32.GetIconInfo(info.Cursor, out var icon)) return;
            try
            {
                var scale = (double)Size.Width / bounds.Width;
                var x = (int)((info.ScreenPosition.X - bounds.X - icon.HotspotX) * scale);
                var y = (int)((info.ScreenPosition.Y - bounds.Y - icon.HotspotY) * scale);
                var side = Math.Max(8, (int)(Win32.GetSystemMetrics(CursorWidthMetric) * scale));
                Win32.DrawIconEx(memory, x, y, info.Cursor, side, side, 0, 0, Win32.DiNormal);
            }
            finally
            {
                if (icon.MaskBitmap != 0) Win32.DeleteObject(icon.MaskBitmap);
                if (icon.ColorBitmap != 0) Win32.DeleteObject(icon.ColorBitmap);
            }
        }

        public void Dispose()
        {
            Win32.SelectObject(memory, previousBitmap);
            Win32.DeleteObject(bitmap);
            Win32.DeleteDC(memory);
        }
    }
}
