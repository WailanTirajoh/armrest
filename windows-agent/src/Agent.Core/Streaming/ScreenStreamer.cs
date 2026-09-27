using CursorController.Agent.Protocol;
using CursorController.Agent.Server;

namespace CursorController.Agent.Streaming;

/// <summary>Gambar layar dalam NV12: bidang Y penuh, lalu bidang UV berselang-seling setengah resolusi.</summary>
public sealed class VideoFrame(int width, int height, byte[] nv12)
{
    public int Width { get; } = width;
    public int Height { get; } = height;
    public byte[] Nv12 { get; } = nv12;
}

public abstract record ScreenSourceEvent
{
    /// <summary>Tangkapan berjalan.</summary>
    public sealed record Started : ScreenSourceEvent;

    /// <summary>Ukuran video: sekali di awal, lalu setiap berubah (mis. kursor pindah ke monitor lain).</summary>
    public sealed record Size(PixelSize Value) : ScreenSourceEvent;

    /// <summary>Gambar baru. Layar yang diam tidak menghasilkan gambar.</summary>
    public sealed record Frame(VideoFrame Value) : ScreenSourceEvent;

    /// <summary>Berhenti sendiri karena error, termasuk gagal mulai. Tidak ada event lain sesudahnya.</summary>
    public sealed record Stopped(Exception? Error, bool Denied = false) : ScreenSourceEvent;
}

/// <summary>Sumber gambar untuk <see cref="ScreenStreamer"/>. Event boleh dipanggil dari thread mana pun.</summary>
public interface IScreenSource
{
    void Start(ScreenRequest request, Action<ScreenSourceEvent> events);
    void Update(ScreenRequest request);
    void Stop();
}

/// <summary>Satu frame hasil encoder. <c>ParameterSets</c> (SPS dan PPS, Annex B) hanya ada di keyframe.</summary>
public sealed record EncodedFrame(uint Seq, bool Keyframe, byte[] Data, byte[]? ParameterSets);

/// <summary>Encoder H.264 tanpa B-frame. Keluaran lewat callback saat dibuat, urut sesuai <see cref="Encode"/>.</summary>
public interface IVideoEncoder : IDisposable
{
    PixelSize Size { get; }
    void Encode(VideoFrame frame, uint seq, bool keyframe);
}

/// <summary>Penyedia tangkapan layar dan encoder untuk platform tertentu.</summary>
public interface IScreenBackend
{
    IScreenSource CreateSource();
    IVideoEncoder CreateEncoder(PixelSize size, Action<EncodedFrame> output);
}

/// <summary>
/// Satu aliran layar ke satu HP: gambar dari <c>source</c> dikompres H.264 lalu dikirim sebagai <see cref="ScreenPacket"/>.
/// Paling banyak <see cref="ScreenFlowControl.Window"/> frame boleh belum dikonfirmasi HP. Selama penuh, hanya gambar
/// terbaru yang disimpan, lalu dikirim begitu ada konfirmasi.
/// </summary>
public sealed class ScreenStreamer
{
    private readonly IScreenSource source;
    private readonly Func<PixelSize, Action<EncodedFrame>, IVideoEncoder> makeEncoder;
    private readonly Action<byte[]> send;
    private readonly Action<ScreenStatus> report;
    private readonly SerialQueue queue = new("cursorctl-screen");

    // Hanya diakses di queue.
    private IVideoEncoder? encoder;
    private int encoderGeneration;
    private readonly ScreenFlowControl flow = new();
    private VideoFrame? latest;
    private bool latestSent = true;
    private bool keyframeNeeded = true;
    private bool stopped;

    /// <summary><c>send</c> dan <c>report</c> dipanggil di antrean internal streamer.</summary>
    public ScreenStreamer(
        IScreenSource source,
        Func<PixelSize, Action<EncodedFrame>, IVideoEncoder> makeEncoder,
        Action<byte[]> send,
        Action<ScreenStatus> report)
    {
        this.source = source;
        this.makeEncoder = makeEncoder;
        this.send = send;
        this.report = report;
    }

    public void Start(ScreenRequest request) =>
        queue.Post(() => source.Start(request, e => queue.Post(() => Handle(e))));

    /// <summary>HP meminta lagi: decoder-nya butuh keyframe, mungkin dengan ukuran baru.</summary>
    public void Update(ScreenRequest request) => queue.Post(() =>
    {
        if (stopped) return;
        keyframeNeeded = true;
        source.Update(request);
        EncodeLatest();
    });

    public void Ack(uint seq) => queue.Post(() =>
    {
        flow.Ack(seq);
        EncodeLatest();
    });

    public void Stop()
    {
        queue.Post(Shutdown);
        queue.Dispose();
    }

    private void Handle(ScreenSourceEvent e)
    {
        if (stopped) return;
        switch (e)
        {
            case ScreenSourceEvent.Started:
                report(ScreenStatus.Streaming);
                break;
            case ScreenSourceEvent.Size { Value: var size }:
                if (encoder?.Size == size) return;
                encoder?.Dispose();
                var generation = ++encoderGeneration;
                try
                {
                    encoder = makeEncoder(size, frame => queue.Post(() => Deliver(frame, size, generation)));
                }
                catch (Exception)
                {
                    Shutdown();
                    report(ScreenStatus.Failed);
                    return;
                }
                keyframeNeeded = true;
                EncodeLatest();
                break;
            case ScreenSourceEvent.Frame { Value: var frame }:
                latest = frame;
                latestSent = false;
                EncodeLatest();
                break;
            case ScreenSourceEvent.Stopped stoppedEvent:
                Shutdown();
                report(stoppedEvent.Denied ? ScreenStatus.Denied : ScreenStatus.Failed);
                break;
        }
    }

    private void EncodeLatest()
    {
        if (stopped || encoder is null || latest is null || (latestSent && !keyframeNeeded) || !flow.CanSend) return;
        if (latest.Width != encoder.Size.Width || latest.Height != encoder.Size.Height) return;
        try
        {
            encoder.Encode(latest, flow.Next(), keyframeNeeded);
        }
        catch (Exception)
        {
            Shutdown();
            report(ScreenStatus.Failed);
            return;
        }
        keyframeNeeded = false;
        latestSent = true;
    }

    private void Deliver(EncodedFrame frame, PixelSize size, int generation)
    {
        if (stopped || generation != encoderGeneration) return;
        if (frame.ParameterSets is { } parameterSets)
        {
            send(new ScreenPacket.Config(size.Width, size.Height, parameterSets).Encode());
        }
        send(new ScreenPacket.Frame(frame.Seq, frame.Keyframe, frame.Data).Encode());
    }

    private void Shutdown()
    {
        if (stopped) return;
        stopped = true;
        source.Stop();
        encoder?.Dispose();
        encoder = null;
        latest = null;
    }
}
