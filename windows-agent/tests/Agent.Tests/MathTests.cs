using Armrest.Agent.Focus;
using Armrest.Agent.Input;
using Armrest.Agent.Protocol;

namespace Armrest.Agent.Tests;

public class MathTests
{
    [Fact]
    public void SlowMovementUsesBaseSensitivityAndKeepsRemainder()
    {
        var math = new PointerMath();
        // 1 dp dalam 16 ms: di bawah v0, jadi gain = 1,5.
        Assert.Equal(1, math.PointerDelta(10, 0, 0).Dx);
        Assert.Equal(2, math.PointerDelta(10, 0, 0.016).Dx); // 1,5 + sisa 0,5
    }

    [Fact]
    public void FastMovementIsCappedAtMaxGain()
    {
        var math = new PointerMath { Curve = new AccelerationCurve(Sensitivity: 1, Accel: 2, V0: 0.2, MaxGain: 6) };
        math.PointerDelta(0, 0, 0);
        // 100 dp dalam 16 ms = 6,25 dp/ms → gain maksimal 6.
        Assert.Equal(600, math.PointerDelta(1000, 0, 0.016).Dx);
    }

    [Fact]
    public void NegativeMovementRoundsTowardZeroAndScrollIsNotAccelerated()
    {
        var math = new PointerMath { Curve = new AccelerationCurve(Sensitivity: 1) };
        Assert.Equal((-1, 0), math.PointerDelta(-15, -5, 0));
        var scroll = new PointerMath { ScrollSpeed = 2 };
        Assert.Equal((0, -24), scroll.ScrollDelta(0, -120));
    }

    [Fact]
    public void ClampKeepsCursorOnScreensAndAllowsCrossingToAdjacentDisplay()
    {
        DisplayRect[] displays = [new(0, 0, 1440, 900), new(1440, 0, 1920, 1080)];
        Assert.Equal((1500.0, 100.0), DisplayClamp.Clamp((1500, 100), (1430, 100), displays));
        Assert.Equal((0.0, 100.0), DisplayClamp.Clamp((-50, 100), (10, 100), displays));
        // Di bawah layar utama (lebih pendek) → dijepit ke tepi bawah layar utama.
        Assert.Equal((100.0, 899.0), DisplayClamp.Clamp((100, 1000), (100, 890), displays));
    }

    [Fact]
    public void TextFocusFilterReportsEnteringImmediatelyAndLeavingAfterDelay()
    {
        var filter = new TextFocusFilter(0.5);
        Assert.False(filter.Update(false, 0)); // status awal selalu dikirim
        Assert.Null(filter.Update(false, 0.25));
        Assert.True(filter.Update(true, 0.5));
        Assert.Null(filter.Update(false, 0.75)); // sesaat di luar kolom teks
        Assert.Null(filter.Update(true, 1.0));
        Assert.Null(filter.Update(false, 1.25)); // hitungan mulai dari awal
        Assert.Null(filter.Update(false, 1.5));
        Assert.False(filter.Update(false, 1.75));
    }

    [Fact]
    public void VideoFitsPhoneAndCapsWithoutUpscaling()
    {
        var phone = new ScreenRequest(2712, 1220);
        Assert.Equal(new PixelSize(1846, 1200), ScreenSizing.VideoSize(new PixelSize(3024, 1964), phone));
        Assert.Equal(new PixelSize(1280, 800), ScreenSizing.VideoSize(new PixelSize(1280, 800), phone));
        Assert.Equal(new PixelSize(1200, 1920), ScreenSizing.VideoSize(new PixelSize(1200, 1920), phone));
        Assert.Equal(new PixelSize(768, 480), ScreenSizing.VideoSize(new PixelSize(2560, 1600), new ScreenRequest(480, 800)));
        Assert.Equal(new PixelSize(1000, 562), ScreenSizing.VideoSize(new PixelSize(1366, 768), new ScreenRequest(1000, 600)));
        // Layar uji untuk e2e: HP meminta 1920 × 1080, video 1440 × 900 (protocol/E2E.md).
        Assert.Equal(new PixelSize(1440, 900), ScreenSizing.VideoSize(new PixelSize(1440, 900), new ScreenRequest(1920, 1080)));
    }

    [Fact]
    public void BitrateScalesWithSizeWithinLimits()
    {
        Assert.Equal(2_000_000, ScreenSizing.Bitrate(new PixelSize(640, 400)));
        Assert.Equal(3_686_400, ScreenSizing.Bitrate(new PixelSize(1280, 800)));
        Assert.Equal(10_000_000, ScreenSizing.Bitrate(new PixelSize(3840, 2400)));
    }

    [Fact]
    public void FlowControlHoldsFramesUntilAcknowledged()
    {
        var flow = new ScreenFlowControl(3);
        Assert.Equal(1u, flow.Next());
        Assert.Equal(2u, flow.Next());
        Assert.Equal(3u, flow.Next());
        Assert.False(flow.CanSend);
        flow.Ack(2);
        Assert.True(flow.CanSend);
        flow.Ack(1); // konfirmasi lama diabaikan
        Assert.Equal(2u, flow.LastAcked);
        flow.Ack(9); // nomor yang belum pernah dikirim diabaikan
        Assert.Equal(2u, flow.LastAcked);
    }

    [Fact]
    public void FlowControlSurvivesSequenceWraparound()
    {
        var flow = new ScreenFlowControl(2, uint.MaxValue - 1, uint.MaxValue - 1);
        Assert.Equal(uint.MaxValue, flow.Next());
        Assert.Equal(0u, flow.Next());
        Assert.False(flow.CanSend);
        flow.Ack(uint.MaxValue);
        Assert.True(flow.CanSend);
        flow.Ack(0);
        Assert.Equal(0u, flow.LastAcked);
    }
}
