using System.Collections.Concurrent;
using CursorController.Agent.Volume;

namespace CursorController.Agent.Tests;

public class VolumeTests
{
    [Fact]
    public void StepsLandOnSixteenths()
    {
        var half = new VolumeState(0.5, false);
        Assert.Equal(new VolumeState(0.5625, false), VolumeMath.Apply(new VolumeCommand.Step(1), half));
        Assert.Equal(new VolumeState(0.4375, false), VolumeMath.Apply(new VolumeCommand.Step(-1), half));
        // Di antara dua kelipatan: naik ke kelipatan berikutnya, turun ke kelipatan sebelumnya.
        var between = new VolumeState(0.53, false);
        Assert.Equal(0.5625, VolumeMath.Apply(new VolumeCommand.Step(1), between).Level);
        Assert.Equal(0.5, VolumeMath.Apply(new VolumeCommand.Step(-1), between).Level);
        Assert.Equal(1, VolumeMath.Apply(new VolumeCommand.Step(3), new VolumeState(0.95, false)).Level);
        Assert.Equal(0, VolumeMath.Apply(new VolumeCommand.Step(-2), new VolumeState(0.05, false)).Level);
    }

    [Fact]
    public void ChangesUnmuteLikeVolumeKeys()
    {
        var muted = new VolumeState(0.5, true);
        Assert.False(VolumeMath.Apply(new VolumeCommand.Step(1), muted).Muted);
        Assert.Equal(new VolumeState(0.2, false), VolumeMath.Apply(new VolumeCommand.Level(0.2), muted));
        Assert.Equal(muted, VolumeMath.Apply(new VolumeCommand.Muted(true), new VolumeState(0.5, false)));
    }

    [Fact]
    public void OutputWithoutVolumeControlIgnoresLevelChanges()
    {
        var fixedOutput = new VolumeState(null, false);
        Assert.Equal(fixedOutput, VolumeMath.Apply(new VolumeCommand.Step(1), fixedOutput));
        Assert.Equal(fixedOutput, VolumeMath.Apply(new VolumeCommand.Level(0.3), fixedOutput));
        Assert.Equal(new VolumeState(null, true), VolumeMath.Apply(new VolumeCommand.Muted(true), fixedOutput));
    }

    [Fact]
    public void CommandsLogLikeTheMacAgent()
    {
        Assert.Equal("step(1)", new VolumeCommand.Step(1).ToString());
        Assert.Equal("level(0.25)", new VolumeCommand.Level(0.25).ToString());
        Assert.Equal("muted(true)", new VolumeCommand.Muted(true).ToString());
    }

    [Fact]
    public void MonitorReportsCurrentStateThenEveryChange()
    {
        var volume = new InMemoryVolume();
        var reported = new BlockingCollection<VolumeState>();
        var monitor = new VolumeMonitor(volume, action => action()) { OnChange = reported.Add };
        monitor.Start();
        try
        {
            Assert.True(reported.TryTake(out var first, TimeSpan.FromSeconds(5)));
            Assert.Equal(new VolumeState(0.5, false), first);
            monitor.Apply(new VolumeCommand.Step(1));
            Assert.True(reported.TryTake(out var stepped, TimeSpan.FromSeconds(5)));
            Assert.Equal(new VolumeState(0.5625, false), stepped);
            // Tidak berubah: tidak dilaporkan ulang.
            Assert.False(reported.TryTake(out _, TimeSpan.FromMilliseconds(600)));
        }
        finally
        {
            monitor.Stop();
        }
    }
}
