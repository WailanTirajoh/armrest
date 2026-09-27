import AgentCore
import AudioToolbox
import CoreAudio
import Foundation

/// Memantau volume output selama ada HP yang memintanya (`volumeUpdates`), dan menjalankan perintah volume dari HP.
/// Semua panggilan ke `control` berjalan di antrean sendiri, jadi main thread tidak pernah menunggu CoreAudio.
final class VolumeMonitor {
    static let interval: DispatchTimeInterval = .milliseconds(250)

    /// Dipanggil di main thread setiap kali volume berubah selama pemantauan berjalan.
    var onChange: ((VolumeState) -> Void)?

    private let control: VolumeControl
    private let queue = DispatchQueue(label: "io.github.wailantirajoh.armrest.volume")
    private var timer: DispatchSourceTimer?
    /// Naik setiap start/stop, supaya hasil dari pemantauan yang sudah dihentikan dibuang. Hanya di main thread.
    private var generation = 0
    /// Status terakhir yang dilaporkan. Hanya diakses di `queue`.
    private var last: VolumeState?

    init(control: VolumeControl) {
        self.control = control
    }

    func start() {
        guard timer == nil else { return }
        generation += 1
        let round = generation
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: Self.interval, leeway: .milliseconds(50))
        timer.setEventHandler { [weak self] in self?.poll(round) }
        self.timer = timer
        timer.resume()
    }

    func stop() {
        guard let timer else { return }
        timer.cancel()
        self.timer = nil
        generation += 1
        queue.async { self.last = nil }
    }

    /// Jalankan perintah dari HP, lalu langsung laporkan hasilnya supaya HP tidak menunggu pemeriksaan berikutnya.
    func apply(_ command: VolumeCommand) {
        let round = generation
        let polling = timer != nil
        queue.async {
            self.control.apply(command)
            if polling { self.poll(round) }
        }
    }

    /// Dipanggil di `queue`.
    private func poll(_ round: Int) {
        let state = control.read()
        guard state != last else { return }
        last = state
        DispatchQueue.main.async {
            guard self.generation == round else { return }
            self.onChange?(state)
        }
    }
}

/// Volume perangkat output default Mac lewat CoreAudio. Tidak butuh izin apa pun.
final class SystemVolume: VolumeControl {
    func read() -> VolumeState {
        guard let device = Self.defaultOutputDevice() else { return VolumeState(level: nil, muted: false) }
        return VolumeState(level: Self.level(of: device), muted: Self.muted(device) ?? false)
    }

    func apply(_ command: VolumeCommand) {
        guard let device = Self.defaultOutputDevice() else { return }
        let current = VolumeState(level: Self.level(of: device), muted: Self.muted(device) ?? false)
        let target = VolumeMath.applying(command, to: current)
        if let level = target.level, level != current.level {
            var address = Self.address(kAudioHardwareServiceDeviceProperty_VirtualMainVolume)
            var value = Float32(level)
            AudioObjectSetPropertyData(device, &address, 0, nil, UInt32(MemoryLayout<Float32>.size), &value)
        }
        if target.muted != current.muted, Self.muted(device) != nil {
            var address = Self.address(kAudioDevicePropertyMute)
            var value: UInt32 = target.muted ? 1 : 0
            AudioObjectSetPropertyData(device, &address, 0, nil, UInt32(MemoryLayout<UInt32>.size), &value)
        }
    }

    private static func defaultOutputDevice() -> AudioObjectID? {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var device = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        let status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device)
        return status == noErr && device != kAudioObjectUnknown ? device : nil
    }

    /// Volume utama "virtual": mengatur kanal-kanal perangkat sekaligus dengan balance tetap, sama dengan tombol
    /// volume Mac. Sejak macOS 10.11 bisa lewat AudioObject biasa. nil kalau tidak bisa diatur, mis. monitor HDMI
    /// yang volumenya hanya bisa diubah di monitor itu sendiri.
    private static func level(of device: AudioObjectID) -> Double? {
        var address = address(kAudioHardwareServiceDeviceProperty_VirtualMainVolume)
        var settable: DarwinBoolean = false
        guard AudioObjectHasProperty(device, &address),
              AudioObjectIsPropertySettable(device, &address, &settable) == noErr, settable.boolValue else { return nil }
        var value: Float32 = 0
        var size = UInt32(MemoryLayout<Float32>.size)
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value) == noErr else { return nil }
        return Double(value)
    }

    /// nil kalau perangkat tidak punya tombol bisu.
    private static func muted(_ device: AudioObjectID) -> Bool? {
        var address = address(kAudioDevicePropertyMute)
        guard AudioObjectHasProperty(device, &address) else { return nil }
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value) == noErr else { return nil }
        return value != 0
    }

    private static func address(_ selector: AudioObjectPropertySelector) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioDevicePropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)
    }
}
