using Armrest.Agent.Protocol;
using Armrest.Agent.Server;

namespace Armrest.Agent.Streaming;

/// <summary>Gambar layar dalam NV12: bidang Y penuh, lalu bidang UV berselang-seling setengah resolusi.</summary>
public sealed class VideoFrame(int width, int height, byte[] nv12)
{
    public int Width { get; } = width;
    public int Height { get; } = height;
    public byte[] Nv12 { get; } = nv12;
}

/// <summary>Posisi kursor di gambar, 0–1 dari kiri atas.</summary>
public readonly record struct CursorPosition(double X, double Y);

public abstract record ScreenSourceEvent
{
    /// <summary>Tangkapan berjalan.</summary>
    public sealed record Started : ScreenSourceEvent;

    /// <summary>Ukuran video: sekali di awal, lalu setiap berubah (mis. kursor pindah ke monitor lain).</summary>
    public sealed record Size(PixelSize Value) : ScreenSourceEvent;

    /// <summary>Gambar baru, dengan posisi kursor kalau diketahui. Layar yang diam tidak menghasilkan gambar.</summary>
    public sealed record Frame(VideoFrame Value, CursorPosition? Cursor = null) : ScreenSourceEvent;

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
    private readonly Action<Exception>? error;
    private readonly Action<CursorPosition>? sendCursor;
    private readonly SerialQueue queue = new("armrest-screen");

    // Hanya diakses di queue.
    private IVideoEncoder? encoder;
    private int encoderGeneration;
    private readonly ScreenFlowControl flow = new();
    private VideoFrame? latest;
    private bool latestSent = true;
    private bool keyframeNeeded = true;
    private bool stopped;
    private bool wantsCursor;
    private CursorPosition? lastCursor;

    /// <summary>
    /// <c>send</c>, <c>report</c>, <c>error</c>, dan <c>sendCursor</c> dipanggil di antrean internal streamer.
    /// <c>error</c> menerima penyebab sebelum <see cref="ScreenStatus.Failed"/> dilaporkan. <c>sendCursor</c> menerima
    /// posisi kursor (4 desimal) setiap kali berubah, kalau HP memintanya.
    /// </summary>
    public ScreenStreamer(
        IScreenSource source,
        Func<PixelSize, Action<EncodedFrame>, IVideoEncoder> makeEncoder,
        Action<byte[]> send,
        Action<ScreenStatus> report,
        Action<Exception>? error = null,
        Action<CursorPosition>? sendCursor = null)
    {
        this.source = source;
        this.makeEncoder = makeEncoder;
        this.send = send;
        this.report = report;
        this.error = error;
        this.sendCursor = sendCursor;
    }

    public void Start(ScreenRequest request) => queue.Post(() =>
    {
        wantsCursor = request.Cursor;
        source.Start(request, e => queue.Post(() => Handle(e)));
    });

    /// <summary>HP meminta lagi: decoder-nya butuh keyframe, mungkin dengan ukuran baru.</summary>
    public void Update(ScreenRequest request) => queue.Post(() =>
    {
        if (stopped) return;
        keyframeNeeded = true;
        wantsCursor = request.Cursor;
        source.Update(request);
        EncodeLatest();
        // Tampilan HP dibuat ulang: kirim lagi posisi kursor, juga kalau layar sedang diam.
        if (wantsCursor && lastCursor is { } cursor) sendCursor?.Invoke(cursor);
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
                catch (Exception failure)
                {
                    Fail(failure);
                    return;
                }
                keyframeNeeded = true;
                EncodeLatest();
                break;
            case ScreenSourceEvent.Frame { Value: var frame, Cursor: var cursor }:
                latest = frame;
                latestSent = false;
                EncodeLatest();
                if (cursor is { } position) CursorMoved(position);
                break;
            case ScreenSourceEvent.Stopped stoppedEvent:
                if (stoppedEvent.Error is { } sourceError) error?.Invoke(sourceError);
                Shutdown();
                report(stoppedEvent.Denied ? ScreenStatus.Denied : ScreenStatus.Failed);
                break;
        }
    }

    /// <summary>Dibulatkan ke 4 desimal, sama dengan pesan <c>screen_cursor</c>, dan hanya dikirim kalau berubah.</summary>
    private void CursorMoved(CursorPosition position)
    {
        var rounded = new CursorPosition(Math.Round(position.X, 4), Math.Round(position.Y, 4));
        if (!wantsCursor || rounded == lastCursor) return;
        lastCursor = rounded;
        sendCursor?.Invoke(rounded);
    }

    private void EncodeLatest()
    {
        if (stopped || encoder is null || latest is null || (latestSent && !keyframeNeeded) || !flow.CanSend) return;
        if (latest.Width != encoder.Size.Width || latest.Height != encoder.Size.Height) return;
        try
        {
            encoder.Encode(latest, flow.Next(), keyframeNeeded);
        }
        catch (Exception failure)
        {
            Fail(failure);
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

    private void Fail(Exception failure)
    {
        error?.Invoke(failure);
        Shutdown();
        report(ScreenStatus.Failed);
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
