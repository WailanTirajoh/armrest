using Armrest.Agent.Protocol;

namespace Armrest.Agent.Streaming;

/// <summary>
/// Pola uji bergerak untuk profil e2e: melewati encoder sungguhan tanpa menangkap layar pengguna.
/// Ukurannya seolah layar 1440 × 900, sama dengan agent Mac (lihat protocol/E2E.md).
/// </summary>
public sealed class TestPatternSource : IScreenSource
{
    public static readonly PixelSize Display = new(1440, 900);

    private readonly object gate = new();
    private Action<ScreenSourceEvent>? events;
    private Timer? timer;
    private PixelSize size = Display;
    private int tick;

    public void Start(ScreenRequest request, Action<ScreenSourceEvent> events)
    {
        lock (gate)
        {
            this.events = events;
            size = ScreenSizing.VideoSize(Display, request);
            events(new ScreenSourceEvent.Size(size));
            events(new ScreenSourceEvent.Started());
            timer = new Timer(_ => Draw(), null, TimeSpan.Zero, TimeSpan.FromSeconds(1.0 / ScreenSizing.Fps));
        }
    }

    public void Update(ScreenRequest request)
    {
        lock (gate)
        {
            var next = ScreenSizing.VideoSize(Display, request);
            if (next == size) return;
            size = next;
            events?.Invoke(new ScreenSourceEvent.Size(size));
        }
    }

    public void Stop()
    {
        lock (gate)
        {
            timer?.Dispose();
            timer = null;
            events = null;
        }
    }

    private void Draw()
    {
        lock (gate)
        {
            if (events is null) return;
            tick++;
            events(new ScreenSourceEvent.Frame(Render(size, tick), Cursor(tick)));
        }
    }

    /// <summary>Kursor tiruan di tengah balok yang bergerak (lihat protocol/E2E.md).</summary>
    internal static CursorPosition Cursor(int tick) => new((tick % 90) / 90.0 * 0.9 + 0.05, 0.725);

    /// <summary>Latar gelap, papan catur untuk ketajaman, dan balok yang bergerak supaya setiap frame berubah.</summary>
    internal static VideoFrame Render(PixelSize size, int tick)
    {
        int w = size.Width, h = size.Height;
        var nv12 = new byte[w * h * 3 / 2];
        nv12.AsSpan(0, w * h).Fill(52);
        nv12.AsSpan(w * h).Fill(128);
        var cell = h / 12;
        for (var row = 0; row < 4; row++)
        {
            for (var column = 0; column < 6; column++)
            {
                if ((row + column) % 2 != 0) continue;
                FillLuma(nv12, w, w / 20 + column * cell, h / 10 + row * cell, cell, cell, 225);
            }
        }
        var bar = w / 10;
        var x = (int)((long)(tick % 90) * (w - bar) / 90) & ~1;
        var y = (int)(h * 0.6) & ~1;
        var barHeight = (h / 4) & ~1;
        FillLuma(nv12, w, x, y, bar, barHeight, 170);
        // Oranye: U rendah, V tinggi.
        for (var row = y / 2; row < (y + barHeight) / 2; row++)
        {
            for (var column = x / 2; column < (x + bar) / 2; column++)
            {
                var index = w * h + row * w + column * 2;
                nv12[index] = 70;
                nv12[index + 1] = 190;
            }
        }
        return new VideoFrame(w, h, nv12);
    }

    private static void FillLuma(byte[] nv12, int stride, int x, int y, int width, int height, byte value)
    {
        for (var row = y; row < y + height; row++) nv12.AsSpan(row * stride + x, width).Fill(value);
    }
}
