using System.Diagnostics;

namespace CursorController.Agent.Focus;

/// <summary>
/// Meredam kedipan status fokus. Masuk ke kolom teks langsung dilaporkan; keluar baru dilaporkan setelah bertahan
/// <see cref="ReleaseDelay"/>, supaya pindah dari satu kolom ke kolom lain tidak menutup lalu membuka keyboard HP.
/// </summary>
public sealed class TextFocusFilter(double releaseDelay = 0.5)
{
    private double? leftAt;

    public double ReleaseDelay { get; } = releaseDelay;
    public bool? Reported { get; private set; }

    /// <summary>
    /// Hasil satu pemeriksaan pada waktu <paramref name="now"/> (detik, monoton). Mengembalikan status yang perlu
    /// dikirim ke HP, atau null kalau tidak berubah. Status pertama selalu dilaporkan.
    /// </summary>
    public bool? Update(bool focused, double now)
    {
        if (focused || Reported != true)
        {
            leftAt = null;
            return Report(focused);
        }
        var since = leftAt ?? now;
        leftAt = since;
        if (now - since < ReleaseDelay) return null;
        leftAt = null;
        return Report(false);
    }

    private bool? Report(bool value)
    {
        if (Reported == value) return null;
        Reported = value;
        return value;
    }
}

/// <summary>
/// Memeriksa apakah kolom teks sedang fokus setiap 250 ms di thread latar, selama ada HP yang memintanya.
/// Pemeriksaan bisa tertahan app yang hang, jadi tidak pernah berjalan di thread UI.
/// </summary>
public sealed class FocusMonitor(Func<bool> probe, Action<Action> post)
{
    public static readonly TimeSpan Interval = TimeSpan.FromMilliseconds(250);

    private readonly object gate = new();
    private Timer? timer;
    private int generation;
    private TextFocusFilter filter = new();
    private bool busy;

    /// <summary>Dipanggil lewat <c>post</c> setiap status yang perlu dikirim ke HP berubah.</summary>
    public Action<bool>? OnChange { get; set; }

    public void Start()
    {
        lock (gate)
        {
            if (timer is not null) return;
            generation++;
            filter = new TextFocusFilter();
            var round = generation;
            timer = new Timer(_ => Tick(round), null, TimeSpan.Zero, Interval);
        }
    }

    public void Stop()
    {
        lock (gate)
        {
            timer?.Dispose();
            timer = null;
            generation++;
        }
    }

    private void Tick(int round)
    {
        lock (gate)
        {
            if (busy || round != generation) return;
            busy = true;
        }
        bool focused;
        try
        {
            focused = probe();
        }
        catch (Exception)
        {
            focused = false;
        }
        bool? value;
        lock (gate)
        {
            busy = false;
            if (round != generation) return;
            value = filter.Update(focused, Stopwatch.GetTimestamp() / (double)Stopwatch.Frequency);
        }
        if (value is { } changed)
        {
            post(() =>
            {
                lock (gate)
                {
                    if (round != generation) return;
                }
                OnChange?.Invoke(changed);
            });
        }
    }
}
