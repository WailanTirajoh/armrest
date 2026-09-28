import Foundation

/// Platform komputer di `auth_result` dan TXT Bonjour (`os`). HP lama dan agent lama menganggapnya macOS.
public enum AgentPlatform {
    public static let macOS = "macos"
    public static let windows = "windows"
}

/// Nama fitur di `auth_result` untuk HP yang ingin tahu apakah agent bisa mengirim layar.
public enum AgentFeature {
    public static let focus = "focus"
    public static let screen = "screen"
    public static let volume = "volume"
    public static let media = "media"
    public static let power = "power"
}

/// Permintaan HP untuk melihat layar: ukuran video maksimum dalam piksel, biasanya ukuran layar HP.
public struct ScreenRequest: Equatable, Sendable {
    public let maxWidth: Int
    public let maxHeight: Int
    /// HP ingin menerima posisi kursor (`screen_cursor`), supaya tampilan yang di-zoom bisa mengikutinya.
    public let cursor: Bool

    public init(maxWidth: Int, maxHeight: Int, cursor: Bool = false) {
        self.maxWidth = min(max(maxWidth, 64), 8192)
        self.maxHeight = min(max(maxHeight, 64), 8192)
        self.cursor = cursor
    }
}

/// Status aliran layar yang dikirim ke HP (pesan `screen_status`).
public enum ScreenStatus: String, Sendable {
    /// Tangkapan layar berjalan; frame menyusul.
    case streaming
    /// Mac belum memberi izin Screen Recording.
    case denied
    /// Tangkapan layar gagal dimulai atau berhenti karena error.
    case failed
}

public struct PixelSize: Equatable, Sendable {
    public let width: Int
    public let height: Int

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
    }
}

/// Frame biner Mac → HP untuk video layar (protocol/PROTOCOL.md, bagian "Layar Mac").
public enum ScreenPacket: Equatable, Sendable {
    /// Ukuran video dan parameter decoder H.264 (SPS dan PPS, Annex B). Dikirim sebelum setiap keyframe.
    case config(width: Int, height: Int, parameterSets: Data)
    /// Satu frame video (access unit Annex B). `seq` naik satu per frame dan dikonfirmasi HP lewat `screen_ack`.
    case frame(seq: UInt32, keyframe: Bool, data: Data)

    public static let configType: UInt8 = 0x81
    public static let frameType: UInt8 = 0x82
    static let h264: UInt8 = 1
    static let headerLength = 6

    public func encoded() -> Data {
        var out = Data()
        switch self {
        case let .config(width, height, parameterSets):
            out.reserveCapacity(Self.headerLength + parameterSets.count)
            out.append(contentsOf: [Self.configType, Self.h264])
            out.append(contentsOf: Self.bytes(UInt16(width)) + Self.bytes(UInt16(height)))
            out.append(parameterSets)
        case let .frame(seq, keyframe, data):
            out.reserveCapacity(Self.headerLength + data.count)
            out.append(Self.frameType)
            out.append(contentsOf: Self.bytes(UInt16(seq & 0xFFFF)) + Self.bytes(UInt16(seq >> 16)))
            out.append(keyframe ? 1 : 0)
            out.append(data)
        }
        return out
    }

    /// nil untuk tipe atau codec tidak dikenal, ukuran 0, flag tidak dikenal, atau tanpa isi.
    public static func decode(_ data: Data) -> ScreenPacket? {
        let b = [UInt8](data)
        guard b.count > headerLength else { return nil }
        switch b[0] {
        case configType:
            let width = Int(b[2]) | Int(b[3]) << 8
            let height = Int(b[4]) | Int(b[5]) << 8
            guard b[1] == h264, width > 0, height > 0 else { return nil }
            return .config(width: width, height: height, parameterSets: Data(b[headerLength...]))
        case frameType:
            let seq = UInt32(b[1]) | UInt32(b[2]) << 8 | UInt32(b[3]) << 16 | UInt32(b[4]) << 24
            guard b[5] <= 1 else { return nil }
            return .frame(seq: seq, keyframe: b[5] == 1, data: Data(b[headerLength...]))
        default:
            return nil
        }
    }

    private static func bytes(_ value: UInt16) -> [UInt8] {
        [UInt8(value & 0xFF), UInt8(value >> 8)]
    }
}

/// Ukuran dan bitrate video layar.
public enum ScreenSizing {
    public static let fps = 30
    /// Batas sisi panjang dan pendek video, supaya bitrate dan beban decoder HP tetap wajar.
    public static let maxLongSide = 1920
    public static let maxShortSide = 1200

    /// Ukuran video untuk layar berukuran `display` (piksel): muat di layar HP (`request`) dan di batas
    /// 1920 × 1200, rasio tetap, tanpa memperbesar, dan sisi genap (syarat H.264 4:2:0).
    /// Orientasi kotak mengikuti layar Mac, jadi monitor yang diputar tetap tajam.
    public static func videoSize(display: PixelSize, request: ScreenRequest) -> PixelSize {
        let long = min(max(request.maxWidth, request.maxHeight), maxLongSide)
        let short = min(min(request.maxWidth, request.maxHeight), maxShortSide)
        let landscape = display.width >= display.height
        let boxWidth = min(landscape ? long : short, display.width)
        let boxHeight = min(landscape ? short : long, display.height)
        let width: Int
        let height: Int
        if boxWidth * display.height <= boxHeight * display.width {
            width = boxWidth
            height = display.height * boxWidth / display.width
        } else {
            height = boxHeight
            width = display.width * boxHeight / display.height
        }
        return PixelSize(width: max(2, width & ~1), height: max(2, height & ~1))
    }

    /// Bitrate rata-rata sekitar 0,12 bit per piksel per frame, antara 2 dan 10 Mbit/s.
    public static func bitrate(for size: PixelSize) -> Int {
        min(max(size.width * size.height * fps * 12 / 100, 2_000_000), 10_000_000)
    }
}

/// Batas frame yang sudah dikirim tapi belum dikonfirmasi HP. Kalau penuh, frame berikutnya ditahan, jadi
/// saat WiFi lambat gambar dilewati alih-alih menumpuk di antrean dan membuat jeda makin panjang.
public struct ScreenFlowControl: Sendable {
    public let window: UInt32
    public private(set) var lastSent: UInt32 = 0
    public private(set) var lastAcked: UInt32 = 0

    public init(window: UInt32 = 4) {
        self.window = window
    }

    /// Untuk test: mulai dari nomor tertentu.
    init(window: UInt32, lastSent: UInt32, lastAcked: UInt32) {
        self.window = window
        self.lastSent = lastSent
        self.lastAcked = lastAcked
    }

    public var canSend: Bool { lastSent &- lastAcked < window }

    /// Nomor untuk frame berikutnya.
    public mutating func next() -> UInt32 {
        lastSent &+= 1
        return lastSent
    }

    /// Konfirmasi bersifat kumulatif. Nomor lama atau yang belum pernah dikirim diabaikan.
    public mutating func ack(_ seq: UInt32) {
        let ahead = seq &- lastAcked
        guard ahead > 0, ahead <= lastSent &- lastAcked else { return }
        lastAcked = seq
    }
}

/// NAL unit H.264 dalam format Annex B (diawali start code 00 00 00 01), format yang diminta decoder Android.
public enum AnnexB {
    public static let startCode: [UInt8] = [0, 0, 0, 1]

    /// Mengubah NAL unit berawalan panjang big-endian (keluaran VideoToolbox) menjadi Annex B.
    /// nil kalau panjangnya tidak cocok dengan data.
    public static func fromLengthPrefixed(_ data: Data, lengthSize: Int = 4) -> Data? {
        let b = [UInt8](data)
        var out = Data(capacity: b.count + 16)
        var i = 0
        while i < b.count {
            guard i + lengthSize <= b.count else { return nil }
            let length = b[i..<(i + lengthSize)].reduce(0) { $0 << 8 | Int($1) }
            i += lengthSize
            guard length > 0, i + length <= b.count else { return nil }
            out.append(contentsOf: startCode)
            out.append(contentsOf: b[i..<(i + length)])
            i += length
        }
        return out.isEmpty ? nil : out
    }

    public static func join(_ units: [Data]) -> Data {
        units.reduce(into: Data()) { out, unit in
            out.append(contentsOf: startCode)
            out.append(unit)
        }
    }

    /// NAL unit tanpa start code. Start code 3 dan 4 byte sama-sama dikenali.
    public static func split(_ data: Data) -> [Data] {
        let b = [UInt8](data)
        var starts: [(code: Int, unit: Int)] = []
        var i = 0
        while i + 2 < b.count {
            if b[i] == 0, b[i + 1] == 0, b[i + 2] == 1 {
                starts.append((i > 0 && b[i - 1] == 0 ? i - 1 : i, i + 3))
                i += 3
            } else {
                i += 1
            }
        }
        return starts.indices.map { k in
            let end = k + 1 < starts.count ? starts[k + 1].code : b.count
            return Data(b[starts[k].unit..<end])
        }
    }
}
