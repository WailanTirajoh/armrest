using System.Globalization;

namespace CursorController.Agent.Volume;

/// <summary>Volume output komputer (pesan <c>volume_status</c>). <c>Level</c> null = output tidak bisa diatur volumenya.</summary>
public sealed record VolumeState
{
    public VolumeState(double? level, bool muted)
    {
        Level = level is { } value ? Math.Clamp(value, 0, 1) : null;
        Muted = muted;
    }

    public double? Level { get; }
    public bool Muted { get; }

    public override string ToString() =>
        $"{(Level is { } level ? level.ToString("0.0000", CultureInfo.InvariantCulture) : "-")} muted: {(Muted ? "true" : "false")}";
}

/// <summary>Perintah volume dari HP (pesan <c>volume</c>). Format <c>ToString</c> sama dengan log agent Mac.</summary>
public abstract record VolumeCommand
{
    /// <summary>Naik (positif) atau turun sekian langkah 1/16, seperti tombol volume Mac.</summary>
    public sealed record Step(int Count) : VolumeCommand
    {
        public override string ToString() => $"step({Count.ToString(CultureInfo.InvariantCulture)})";
    }

    /// <summary>Volume langsung, 0–1.</summary>
    public sealed record Level(double Value) : VolumeCommand
    {
        public override string ToString() => $"level({Value.ToString(CultureInfo.InvariantCulture)})";
    }

    public sealed record Muted(bool Value) : VolumeCommand
    {
        public override string ToString() => $"muted({(Value ? "true" : "false")})";
    }
}

public static class VolumeMath
{
    /// <summary>Jumlah langkah dari bisu sampai penuh, sama dengan tombol volume Mac.</summary>
    public const int Steps = 16;

    /// <summary>State setelah perintah dijalankan. <c>Step</c> dan <c>Level</c> juga menyalakan suara yang bisu.</summary>
    public static VolumeState Apply(VolumeCommand command, VolumeState state) => command switch
    {
        VolumeCommand.Step { Count: not 0 } step when state.Level is { } level =>
            // Selalu mendarat di kelipatan 1/16: dari 0,53 naik ke 0,5625 atau turun ke 0,5.
            new VolumeState(
                (step.Count > 0 ? Math.Floor(level * Steps + 1e-6) + step.Count : Math.Ceiling(level * Steps - 1e-6) + step.Count) / Steps,
                false),
        VolumeCommand.Level to when state.Level is not null => new VolumeState(to.Value, false),
        VolumeCommand.Muted muted => new VolumeState(state.Level, muted.Value),
        _ => state,
    };
}

/// <summary>Output audio yang volumenya bisa dibaca dan diubah. Dipanggil dari thread pemantau volume, bukan thread UI.</summary>
public interface IVolumeControl
{
    VolumeState Read();
    void Apply(VolumeCommand command);
}

/// <summary>Volume tiruan untuk profil uji: mulai 0,5 dan tidak bisu. Tidak pernah menyentuh audio sungguhan.</summary>
public sealed class InMemoryVolume : IVolumeControl
{
    private readonly object gate = new();
    private VolumeState state = new(0.5, false);

    public VolumeState Read()
    {
        lock (gate) return state;
    }

    public void Apply(VolumeCommand command)
    {
        lock (gate) state = VolumeMath.Apply(command, state);
    }
}

/// <summary>
/// Memantau volume output setiap 250 ms selama ada HP yang memintanya, dan menjalankan perintah volume dari HP.
/// Semua panggilan ke <c>control</c> berjalan berurutan di thread latar, jadi thread UI tidak pernah menunggu audio.
/// </summary>
public sealed class VolumeMonitor(IVolumeControl control, Action<Action> post)
{
    public static readonly TimeSpan Interval = TimeSpan.FromMilliseconds(250);

    private readonly object gate = new();
    private readonly SemaphoreSlim worker = new(1, 1);
    private Timer? timer;
    private int generation;
    private VolumeState? last;

    /// <summary>Dipanggil lewat <c>post</c> setiap kali volume berubah selama pemantauan berjalan.</summary>
    public Action<VolumeState>? OnChange { get; set; }

    public void Start()
    {
        lock (gate)
        {
            if (timer is not null) return;
            generation++;
            last = null;
            var round = generation;
            timer = new Timer(_ => Run(() => Poll(round), skipIfBusy: true), null, TimeSpan.Zero, Interval);
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

    /// <summary>Jalankan perintah dari HP, lalu langsung laporkan hasilnya tanpa menunggu pemeriksaan berikutnya.</summary>
    public void Apply(VolumeCommand command)
    {
        int round;
        bool polling;
        lock (gate)
        {
            round = generation;
            polling = timer is not null;
        }
        _ = Task.Run(() => Run(
            () =>
            {
                control.Apply(command);
                if (polling) Poll(round);
            },
            skipIfBusy: false));
    }

    /// <summary>
    /// Satu pekerjaan sekali jalan. Pemeriksaan berkala dilewati kalau yang sebelumnya belum selesai; perintah dari HP
    /// menunggu gilirannya.
    /// </summary>
    private void Run(Action work, bool skipIfBusy)
    {
        if (skipIfBusy)
        {
            if (!worker.Wait(0)) return;
        }
        else
        {
            worker.Wait();
        }
        try
        {
            work();
        }
        catch (Exception)
        {
            // Perangkat audio bisa hilang di tengah jalan (mis. headset dicabut); pemeriksaan berikutnya mencoba lagi.
        }
        finally
        {
            worker.Release();
        }
    }

    private void Poll(int round)
    {
        var state = control.Read();
        lock (gate)
        {
            if (round != generation || state == last) return;
            last = state;
        }
        post(() =>
        {
            lock (gate)
            {
                if (round != generation) return;
            }
            OnChange?.Invoke(state);
        });
    }
}
