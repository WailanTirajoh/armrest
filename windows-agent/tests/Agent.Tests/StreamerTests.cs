using System.Collections.Concurrent;
using CursorController.Agent.Protocol;
using CursorController.Agent.Streaming;

namespace CursorController.Agent.Tests;

public class StreamerTests
{
    private sealed class FakeSource : IScreenSource
    {
        public Action<ScreenSourceEvent>? Events { get; private set; }
        public ConcurrentQueue<ScreenRequest> Updates { get; } = new();
        public bool Stopped { get; private set; }

        public void Start(ScreenRequest request, Action<ScreenSourceEvent> events) => Events = events;

        public void Update(ScreenRequest request) => Updates.Enqueue(request);

        public void Stop() => Stopped = true;

        public void Frame(int width, int height) =>
            Events!(new ScreenSourceEvent.Frame(new VideoFrame(width, height, new byte[width * height * 3 / 2])));
    }

    /// <summary>Encoder tiruan: keluarannya langsung, dengan SPS/PPS palsu di setiap keyframe.</summary>
    private sealed class FakeEncoder(PixelSize size, Action<EncodedFrame> output) : IVideoEncoder
    {
        public static readonly ConcurrentQueue<FakeEncoder> Created = new();
        public ConcurrentQueue<(uint Seq, bool Keyframe)> Encoded { get; } = new();
        public bool Disposed { get; private set; }
        public PixelSize Size { get; } = size;

        public void Encode(VideoFrame frame, uint seq, bool keyframe)
        {
            Encoded.Enqueue((seq, keyframe));
            output(new EncodedFrame(seq, keyframe, [0, 0, 0, 1, (byte)(keyframe ? 0x65 : 0x41)], keyframe ? [0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68] : null));
        }

        public void Dispose() => Disposed = true;
    }

    private sealed class Harness : IDisposable
    {
        public FakeSource Source { get; } = new();
        public ConcurrentQueue<ScreenPacket> Packets { get; } = new();
        public ConcurrentQueue<ScreenStatus> Statuses { get; } = new();
        public List<FakeEncoder> Encoders { get; } = [];
        public ScreenStreamer Streamer { get; }

        public Harness()
        {
            Streamer = new ScreenStreamer(
                Source,
                (size, output) =>
                {
                    var encoder = new FakeEncoder(size, output);
                    lock (Encoders) Encoders.Add(encoder);
                    return encoder;
                },
                bytes => Packets.Enqueue(ScreenPacket.Decode(bytes)!),
                Statuses.Enqueue);
            Streamer.Start(new ScreenRequest(1920, 1080));
            Eventually(() => Source.Events is not null);
            Source.Events!(new ScreenSourceEvent.Size(new PixelSize(640, 400)));
            Source.Events!(new ScreenSourceEvent.Started());
        }

        public FakeEncoder Encoder(int index)
        {
            lock (Encoders) return Encoders[index];
        }

        public void Dispose() => Streamer.Stop();
    }

    private static void Eventually(Func<bool> condition)
    {
        var deadline = DateTime.UtcNow.AddSeconds(5);
        while (!condition())
        {
            if (DateTime.UtcNow > deadline) throw new TimeoutException("kondisi tidak terpenuhi dalam 5 detik");
            Thread.Sleep(10);
        }
    }

    [Fact]
    public void FirstFrameIsKeyframeAfterConfig()
    {
        using var h = new Harness();
        h.Source.Frame(640, 400);
        Eventually(() => h.Packets.Count == 2);
        Assert.Equal([ScreenStatus.Streaming], h.Statuses);
        var packets = h.Packets.ToArray();
        Assert.Equal(new PixelSize(640, 400), new PixelSize(((ScreenPacket.Config)packets[0]).Width, ((ScreenPacket.Config)packets[0]).Height));
        Assert.Equal(new ScreenPacket.Frame(1, true, [0, 0, 0, 1, 0x65]), packets[1]);
    }

    [Fact]
    public void FramesWaitForAcknowledgementAndOnlyTheLatestIsKept()
    {
        using var h = new Harness();
        for (var i = 0; i < 7; i++) h.Source.Frame(640, 400);
        Eventually(() => h.Encoders.Count == 1 && h.Encoder(0).Encoded.Count == 4);
        Thread.Sleep(100);
        Assert.Equal(4, h.Encoder(0).Encoded.Count); // jendela konfirmasi penuh

        h.Streamer.Ack(4);
        Eventually(() => h.Encoder(0).Encoded.Count == 5);
        Assert.Equal((5u, false), h.Encoder(0).Encoded.Last());
        Thread.Sleep(100);
        Assert.Equal(5, h.Encoder(0).Encoded.Count); // gambar yang tertahan hanya yang terakhir
    }

    [Fact]
    public void RepeatedRequestResendsLatestAsKeyframeEvenOnStillScreen()
    {
        using var h = new Harness();
        h.Source.Frame(640, 400);
        Eventually(() => h.Packets.Count == 2);
        h.Streamer.Ack(1);
        h.Streamer.Update(new ScreenRequest(1920, 1080));
        Eventually(() => h.Packets.Count == 4);
        var packets = h.Packets.ToArray();
        Assert.IsType<ScreenPacket.Config>(packets[2]);
        Assert.Equal(new ScreenPacket.Frame(2, true, [0, 0, 0, 1, 0x65]), packets[3]);
        Assert.Single(h.Source.Updates);
    }

    [Fact]
    public void SizeChangeCreatesNewEncoderAndDropsStaleFrames()
    {
        using var h = new Harness();
        h.Source.Frame(640, 400);
        Eventually(() => h.Packets.Count == 2);
        h.Streamer.Ack(1);
        h.Source.Events!(new ScreenSourceEvent.Size(new PixelSize(800, 450)));
        h.Source.Frame(640, 400); // gambar ukuran lama dibuang
        h.Source.Frame(800, 450);
        Eventually(() => h.Packets.Count == 4);
        Assert.True(h.Encoder(0).Disposed);
        var config = Assert.IsType<ScreenPacket.Config>(h.Packets.ToArray()[2]);
        Assert.Equal((800, 450), (config.Width, config.Height));
        Assert.True(((ScreenPacket.Frame)h.Packets.ToArray()[3]).Keyframe);
    }

    [Fact]
    public void SourceFailureIsReportedAndStops()
    {
        using var h = new Harness();
        h.Source.Events!(new ScreenSourceEvent.Stopped(null, Denied: true));
        Eventually(() => h.Statuses.Count == 2);
        Assert.Equal([ScreenStatus.Streaming, ScreenStatus.Denied], h.Statuses);
        Assert.True(h.Source.Stopped);
    }
}
