import AgentCore
import Testing

@Test func volumeStepsLandOnSixteenths() {
    let half = VolumeState(level: 0.5, muted: false)
    #expect(VolumeMath.applying(.step(1), to: half) == VolumeState(level: 0.5625, muted: false))
    #expect(VolumeMath.applying(.step(-1), to: half) == VolumeState(level: 0.4375, muted: false))
    // Di antara dua kelipatan: naik ke kelipatan berikutnya, turun ke kelipatan sebelumnya.
    let between = VolumeState(level: 0.53, muted: false)
    #expect(VolumeMath.applying(.step(1), to: between).level == 0.5625)
    #expect(VolumeMath.applying(.step(-1), to: between).level == 0.5)
    #expect(VolumeMath.applying(.step(3), to: VolumeState(level: 0.95, muted: false)).level == 1)
    #expect(VolumeMath.applying(.step(-2), to: VolumeState(level: 0.05, muted: false)).level == 0)
}

@Test func volumeChangesUnmuteLikeVolumeKeys() {
    let muted = VolumeState(level: 0.5, muted: true)
    #expect(VolumeMath.applying(.step(1), to: muted).muted == false)
    #expect(VolumeMath.applying(.level(0.2), to: muted) == VolumeState(level: 0.2, muted: false))
    #expect(VolumeMath.applying(.muted(true), to: VolumeState(level: 0.5, muted: false)) == muted)
}

@Test func outputWithoutVolumeControlIgnoresLevelChanges() {
    let fixed = VolumeState(level: nil, muted: false)
    #expect(VolumeMath.applying(.step(1), to: fixed) == fixed)
    #expect(VolumeMath.applying(.level(0.3), to: fixed) == fixed)
    #expect(VolumeMath.applying(.muted(true), to: fixed) == VolumeState(level: nil, muted: true))
}

@Test func inMemoryVolumeStartsAtHalf() {
    let volume = InMemoryVolume()
    #expect(volume.read() == VolumeState(level: 0.5, muted: false))
    volume.apply(.step(1))
    volume.apply(.muted(true))
    #expect(volume.read() == VolumeState(level: 0.5625, muted: true))
}
