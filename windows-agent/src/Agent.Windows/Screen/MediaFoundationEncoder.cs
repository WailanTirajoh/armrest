using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using Armrest.Agent.Protocol;
using Armrest.Agent.Streaming;
using SharpGen.Runtime;
using Vortice.MediaFoundation;

namespace Armrest.Agent.Windows.Screen;

/// <summary>
/// Encoder H.264 Media Foundation (MFT sinkron bawaan Windows) dalam mode latensi rendah, profil Main tanpa B-frame.
/// Keluarannya Annex B; SPS/PPS dipisah untuk <c>screen_config</c>.
/// </summary>
internal sealed partial class MediaFoundationEncoder : IVideoEncoder
{
    private const long FrameDuration = 10_000_000 / ScreenSizing.Fps;
    private const uint ProfileMain = 77;
    private const uint Progressive = 2;
    private const int OutputProvidesSamples = 0x100;
    private static readonly object StartupGate = new();
    private static bool started;

    private readonly Action<EncodedFrame> output;
    private readonly int frameBytes;
    private readonly Queue<uint> pending = new();
    private IMFTransform transform;
    private long frameIndex;
    private bool fresh = true;
    // SPS/PPS terakhir dari encoder ini, untuk keyframe yang tidak membawanya sendiri.
    private byte[]? parameterSets;

    public MediaFoundationEncoder(PixelSize size, Action<EncodedFrame> output)
    {
        Size = size;
        this.output = output;
        frameBytes = size.Width * size.Height * 3 / 2;
        EnsureStarted();
        transform = CreateTransform(size);
    }

    public PixelSize Size { get; }

    /// <summary>Apakah encoder bisa dibuat di sistem ini (Windows Server tanpa Media Foundation: tidak).</summary>
    public static bool IsAvailable()
    {
        try
        {
            using var probe = new MediaFoundationEncoder(new PixelSize(640, 360), _ => { });
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    public void Encode(VideoFrame frame, uint seq, bool keyframe)
    {
        EnsureComInitialized();
        if (keyframe && !fresh)
        {
            // Tanpa ICodecAPI tidak ada cara sederhana memaksa keyframe; encoder baru selalu mulai dengan IDR.
            transform.Dispose();
            pending.Clear();
            parameterSets = null;
            transform = CreateTransform(Size);
        }
        fresh = false;
        using var buffer = MediaFactory.MFCreateMemoryBuffer(frameBytes);
        buffer.Lock(out var pointer, out _, out _);
        Marshal.Copy(frame.Nv12, 0, pointer, frameBytes);
        buffer.Unlock();
        buffer.CurrentLength = frameBytes;
        using var sample = MediaFactory.MFCreateSample();
        sample.AddBuffer(buffer);
        sample.SampleTime = frameIndex * FrameDuration;
        sample.SampleDuration = FrameDuration;
        frameIndex++;
        pending.Enqueue(seq);
        transform.ProcessInput(0, sample, 0);
        Drain();
    }

    public void Dispose() => transform.Dispose();

    private void Drain()
    {
        while (true)
        {
            var info = transform.GetOutputStreamInfo(0);
            var providesSamples = (info.Flags & OutputProvidesSamples) != 0;
            IMFSample? allocated = null;
            if (!providesSamples)
            {
                allocated = MediaFactory.MFCreateSample();
                using var buffer = MediaFactory.MFCreateMemoryBuffer(Math.Max(info.Size, frameBytes));
                allocated.AddBuffer(buffer);
            }
            var data = new OutputDataBuffer { StreamID = 0, Sample = allocated! };
            try
            {
                var result = transform.ProcessOutput(ProcessOutputFlags.None, 1, ref data, out _);
                if (result == ResultCode.TransformNeedMoreInput) return;
                if (result == ResultCode.TransformStreamChange)
                {
                    using var type = transform.GetOutputAvailableType(0, 0);
                    transform.SetOutputType(0, type, 0);
                    continue;
                }
                result.CheckError();
                using var produced = data.Sample.ConvertToContiguousBuffer();
                produced.Lock(out var pointer, out _, out var length);
                var bytes = new byte[length];
                Marshal.Copy(pointer, bytes, 0, length);
                produced.Unlock();
                Emit(bytes);
            }
            finally
            {
                data.Events?.Dispose();
                if (providesSamples) data.Sample?.Dispose();
                allocated?.Dispose();
            }
        }
    }

    private void Emit(byte[] annexB)
    {
        var units = AnnexB.Split(annexB);
        if (units.Any(u => AnnexB.NalType(u) is 7 or 8)) parameterSets = AnnexB.Join(units.Where(u => AnnexB.NalType(u) is 7 or 8));
        // Sampel tanpa slice (mis. hanya SPS/PPS) bukan frame: tidak memakai seq, parameternya disimpan untuk keyframe.
        if (!units.Any(u => AnnexB.NalType(u) is >= 1 and <= 5)) return;
        var keyframe = units.Any(u => AnnexB.NalType(u) == 5);
        var frameData = AnnexB.Join(units.Where(u => AnnexB.NalType(u) is not (7 or 8 or 9)));
        var seq = pending.Count > 0 ? pending.Dequeue() : 0;
        output(new EncodedFrame(seq, keyframe, frameData, keyframe ? parameterSets ?? SequenceHeader() : null));
    }

    /// <summary>SPS/PPS dari tipe keluaran, untuk encoder yang tidak menaruhnya di dalam keyframe.</summary>
    private byte[] SequenceHeader()
    {
        using var type = transform.GetOutputCurrentType(0);
        return type.GetBlob(MediaTypeAttributeKeys.MpegSequenceHeader);
    }

    private static IMFTransform CreateTransform(PixelSize size)
    {
        var input = new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.NV12 };
        var encoded = new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.H264 };
        var flags = (uint)(EnumFlag.EnumFlagSyncmft | EnumFlag.EnumFlagLocalmft | EnumFlag.EnumFlagSortandfilter);
        Exception? last = null;
        using var activates = MediaFactory.MFTEnumEx(TransformCategoryGuids.VideoEncoder, flags, input, encoded);
        foreach (var activate in activates)
        {
            IMFTransform? transform = null;
            try
            {
                transform = activate.ActivateObject<IMFTransform>();
                Configure(transform, size);
                return transform;
            }
            catch (Exception e)
            {
                transform?.Dispose();
                last = e;
            }
        }
        throw new InvalidOperationException("Encoder H.264 Media Foundation tidak tersedia", last);
    }

    private static void Configure(IMFTransform transform, PixelSize size)
    {
        transform.Attributes?.Set(SinkWriterAttributeKeys.LowLatency, 1u);
        using var outputType = VideoType(size, VideoFormatGuids.H264);
        outputType.Set(MediaTypeAttributeKeys.AvgBitrate, (uint)ScreenSizing.Bitrate(size));
        outputType.Set(MediaTypeAttributeKeys.Mpeg2Profile, ProfileMain);
        // Encoder mewajibkan tipe keluaran di-set sebelum tipe masukan.
        transform.SetOutputType(0, outputType, 0);
        using var inputType = VideoType(size, VideoFormatGuids.NV12);
        transform.SetInputType(0, inputType, 0);
        transform.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
        transform.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
    }

    private static IMFMediaType VideoType(PixelSize size, Guid subtype)
    {
        var type = MediaFactory.MFCreateMediaType();
        type.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
        type.Set(MediaTypeAttributeKeys.Subtype, subtype);
        type.Set(MediaTypeAttributeKeys.InterlaceMode, Progressive);
        MediaFactory.MFSetAttributeSize(type, MediaTypeAttributeKeys.FrameSize, (uint)size.Width, (uint)size.Height);
        MediaFactory.MFSetAttributeRatio(type, MediaTypeAttributeKeys.FrameRate, ScreenSizing.Fps, 1);
        MediaFactory.MFSetAttributeRatio(type, MediaTypeAttributeKeys.PixelAspectRatio, 1, 1);
        return type;
    }

    private static void EnsureStarted()
    {
        EnsureComInitialized();
        lock (StartupGate)
        {
            if (started) return;
            MediaFactory.MFStartup(useLightVersion: true).CheckError();
            started = true;
        }
    }

    /// <summary>Vortice memanggil COM langsung, jadi thread pemanggil perlu diinisialisasi sebagai MTA.</summary>
    private static void EnsureComInitialized() => CoInitializeEx(0, 0);

    [LibraryImport("ole32.dll")]
    private static partial int CoInitializeEx(nint reserved, uint concurrency);
}
