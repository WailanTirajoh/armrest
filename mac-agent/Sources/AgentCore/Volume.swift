import Foundation

/// Volume output komputer (pesan `volume_status`). `level` nil = perangkat output tidak bisa diatur volumenya.
public struct VolumeState: Equatable, Sendable, CustomStringConvertible {
    public var level: Double?
    public var muted: Bool

    public init(level: Double?, muted: Bool) {
        self.level = level.map { min(max($0, 0), 1) }
        self.muted = muted
    }

    public var description: String {
        "\(level.map { String(format: "%.4f", $0) } ?? "-") muted: \(muted)"
    }
}

/// Perintah volume dari HP (pesan `volume`).
public enum VolumeCommand: Equatable, Sendable {
    /// Naik (positif) atau turun sekian langkah 1/16, seperti tombol volume Mac.
    case step(Int)
    /// Volume langsung, 0–1.
    case level(Double)
    case muted(Bool)
}

public enum VolumeMath {
    /// Jumlah langkah dari bisu sampai penuh, sama dengan tombol volume Mac.
    public static let steps = 16

    /// State setelah perintah dijalankan. `step` dan `level` juga menyalakan suara yang bisu, seperti tombol volume.
    public static func applying(_ command: VolumeCommand, to state: VolumeState) -> VolumeState {
        switch command {
        case let .step(count):
            guard let level = state.level, count != 0 else { return state }
            let position = level * Double(steps)
            // Selalu mendarat di kelipatan 1/16: dari 0,53 naik ke 0,5625 atau turun ke 0,5.
            let target = count > 0 ? (position + 1e-6).rounded(.down) + Double(count) : (position - 1e-6).rounded(.up) + Double(count)
            return VolumeState(level: target / Double(steps), muted: false)
        case let .level(value):
            guard state.level != nil else { return state }
            return VolumeState(level: value, muted: false)
        case let .muted(muted):
            return VolumeState(level: state.level, muted: muted)
        }
    }
}

/// Output audio yang volumenya bisa dibaca dan diubah. Dipanggil dari antrean pemantau volume, tidak pernah di main thread.
public protocol VolumeControl: AnyObject {
    func read() -> VolumeState
    func apply(_ command: VolumeCommand)
}

/// Volume tiruan untuk profil uji: mulai 0,5 dan tidak bisu. Tidak pernah menyentuh audio sungguhan.
public final class InMemoryVolume: VolumeControl {
    private let lock = NSLock()
    private var state: VolumeState

    public init(_ state: VolumeState = VolumeState(level: 0.5, muted: false)) {
        self.state = state
    }

    public func read() -> VolumeState {
        lock.lock()
        defer { lock.unlock() }
        return state
    }

    public func apply(_ command: VolumeCommand) {
        lock.lock()
        defer { lock.unlock() }
        state = VolumeMath.applying(command, to: state)
    }
}
