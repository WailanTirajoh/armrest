namespace Armrest.Agent.Input;

/// <summary>Kurva akselerasi dari spec: gain = sensitivity × min(maxGain, 1 + accel × max(0, v − v0)), v dalam dp/ms.</summary>
public sealed record AccelerationCurve(double Sensitivity = 1.5, double Accel = 2.0, double V0 = 0.2, double MaxGain = 6)
{
    public double Gain(double velocity) => Sensitivity * Math.Min(MaxGain, 1 + Accel * Math.Max(0, velocity - V0));
}

/// <summary>Mengubah delta dari HP (0,1 dp) menjadi delta piksel logis, dengan sisa pecahan disimpan antar paket.</summary>
public sealed class PointerMath
{
    private double remainderX;
    private double remainderY;
    private double scrollRemainderX;
    private double scrollRemainderY;
    private double? lastMoveTime;

    public AccelerationCurve Curve { get; set; } = new();
    public double ScrollSpeed { get; set; } = 2.0;

    /// <summary>
    /// <paramref name="time"/> dalam detik dari jam monotonic. Selang antar paket dibatasi 4–100 ms supaya
    /// paket yang datang berdempetan tidak dianggap gerakan super cepat.
    /// </summary>
    public (int Dx, int Dy) PointerDelta(short dx, short dy, double time)
    {
        var dxDp = dx / 10.0;
        var dyDp = dy / 10.0;
        var dtMs = lastMoveTime is { } last ? Math.Clamp((time - last) * 1000, 4, 100) : 16;
        lastMoveTime = time;
        var gain = Curve.Gain(Math.Sqrt(dxDp * dxDp + dyDp * dyDp) / dtMs);
        return Split(dxDp * gain, dyDp * gain, ref remainderX, ref remainderY);
    }

    public (int Dx, int Dy) ScrollDelta(short dx, short dy) =>
        Split(dx / 10.0 * ScrollSpeed, dy / 10.0 * ScrollSpeed, ref scrollRemainderX, ref scrollRemainderY);

    public void Reset()
    {
        remainderX = remainderY = scrollRemainderX = scrollRemainderY = 0;
        lastMoveTime = null;
    }

    private static (int, int) Split(double x, double y, ref double remX, ref double remY)
    {
        var fx = x + remX;
        var fy = y + remY;
        var ix = Math.Truncate(fx);
        var iy = Math.Truncate(fy);
        remX = fx - ix;
        remY = fy - iy;
        return ((int)ix, (int)iy);
    }
}

/// <summary>Persegi panjang layar dalam koordinat global (kiri atas), batas kanan dan bawah eksklusif.</summary>
public readonly record struct DisplayRect(double X, double Y, double Width, double Height)
{
    public double MaxX => X + Width;
    public double MaxY => Y + Height;

    public bool Contains(double px, double py) => px >= X && px < MaxX && py >= Y && py < MaxY;

    public double Distance(double px, double py)
    {
        var dx = Math.Max(Math.Max(X - px, 0), px - MaxX);
        var dy = Math.Max(Math.Max(Y - py, 0), py - MaxY);
        return Math.Sqrt(dx * dx + dy * dy);
    }
}

public static class DisplayClamp
{
    /// <summary>
    /// Titik di dalam salah satu layar dibiarkan, sehingga kursor bisa pindah antar monitor lewat tepi yang
    /// bersebelahan. Selain itu dijepit ke layar tempat kursor sebelumnya berada.
    /// </summary>
    public static (double X, double Y) Clamp((double X, double Y) point, (double X, double Y) previous, IReadOnlyList<DisplayRect> displays)
    {
        if (displays.Count == 0 || displays.Any(d => d.Contains(point.X, point.Y))) return point;
        var baseRect = displays.FirstOrDefault(d => d.Contains(previous.X, previous.Y), displays.MinBy(d => d.Distance(previous.X, previous.Y)));
        return (Math.Clamp(point.X, baseRect.X, baseRect.MaxX - 1), Math.Clamp(point.Y, baseRect.Y, baseRect.MaxY - 1));
    }
}
