import CoreGraphics
import Foundation

/// Tombol yang bisa dikirim HP (kode protokol, lihat PROTOCOL.md) dan virtual key code macOS-nya (posisi ANSI).
public enum KeyCode: UInt8, CaseIterable, Sendable {
    case returnKey = 0x01, backspace, tab, escape, space, forwardDelete
    case left = 0x07, right, up, down
    case home = 0x0B, end, pageUp, pageDown
    case f1 = 0x10, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12
    case a = 0x20, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w, x, y, z
    case digit0 = 0x40, digit1, digit2, digit3, digit4, digit5, digit6, digit7, digit8, digit9
    case minus = 0x50, equal, leftBracket, rightBracket, backslash, semicolon, quote, comma, period, slash, grave
    /// Tombol media sistem; modifier diabaikan.
    case playPause = 0x60, nextTrack, previousTrack

    /// Tombol media dikirim sebagai event sistem NX_KEYTYPE_* (IOKit ev_keymap.h), bukan virtual key.
    public var mediaKeyType: Int32? {
        switch self {
        case .playPause: return 16 // NX_KEYTYPE_PLAY
        case .nextTrack: return 17 // NX_KEYTYPE_NEXT
        case .previousTrack: return 18 // NX_KEYTYPE_PREVIOUS
        default: return nil
        }
    }

    /// Nilai kVK_* dari Carbon (HIToolbox/Events.h). nil untuk tombol media.
    public var virtualKey: CGKeyCode? {
        switch self {
        case .playPause, .nextTrack, .previousTrack: return nil
        case .returnKey: return 0x24
        case .backspace: return 0x33
        case .tab: return 0x30
        case .escape: return 0x35
        case .space: return 0x31
        case .forwardDelete: return 0x75
        case .left: return 0x7B
        case .right: return 0x7C
        case .up: return 0x7E
        case .down: return 0x7D
        case .home: return 0x73
        case .end: return 0x77
        case .pageUp: return 0x74
        case .pageDown: return 0x79
        case .f1: return 0x7A
        case .f2: return 0x78
        case .f3: return 0x63
        case .f4: return 0x76
        case .f5: return 0x60
        case .f6: return 0x61
        case .f7: return 0x62
        case .f8: return 0x64
        case .f9: return 0x65
        case .f10: return 0x6D
        case .f11: return 0x67
        case .f12: return 0x6F
        case .a: return 0x00
        case .b: return 0x0B
        case .c: return 0x08
        case .d: return 0x02
        case .e: return 0x0E
        case .f: return 0x03
        case .g: return 0x05
        case .h: return 0x04
        case .i: return 0x22
        case .j: return 0x26
        case .k: return 0x28
        case .l: return 0x25
        case .m: return 0x2E
        case .n: return 0x2D
        case .o: return 0x1F
        case .p: return 0x23
        case .q: return 0x0C
        case .r: return 0x0F
        case .s: return 0x01
        case .t: return 0x11
        case .u: return 0x20
        case .v: return 0x09
        case .w: return 0x0D
        case .x: return 0x07
        case .y: return 0x10
        case .z: return 0x06
        case .digit0: return 0x1D
        case .digit1: return 0x12
        case .digit2: return 0x13
        case .digit3: return 0x14
        case .digit4: return 0x15
        case .digit5: return 0x17
        case .digit6: return 0x16
        case .digit7: return 0x1A
        case .digit8: return 0x1C
        case .digit9: return 0x19
        case .minus: return 0x1B
        case .equal: return 0x18
        case .leftBracket: return 0x21
        case .rightBracket: return 0x1E
        case .backslash: return 0x2A
        case .semicolon: return 0x29
        case .quote: return 0x27
        case .comma: return 0x2B
        case .period: return 0x2F
        case .slash: return 0x2C
        case .grave: return 0x32
        }
    }
}

public struct KeyModifiers: OptionSet, Hashable, Sendable {
    public let rawValue: UInt8

    public init(rawValue: UInt8) {
        self.rawValue = rawValue
    }

    public static let shift = KeyModifiers(rawValue: 0x01)
    public static let control = KeyModifiers(rawValue: 0x02)
    public static let option = KeyModifiers(rawValue: 0x04)
    public static let command = KeyModifiers(rawValue: 0x08)
    public static let all: KeyModifiers = [.shift, .control, .option, .command]

    /// Modifier yang ditekan lebih dulu (dan dilepas terakhir), dengan flag dan kVK tombolnya.
    public var keys: [(flag: CGEventFlags, virtualKey: CGKeyCode)] {
        var keys: [(CGEventFlags, CGKeyCode)] = []
        if contains(.command) { keys.append((.maskCommand, 0x37)) }
        if contains(.control) { keys.append((.maskControl, 0x3B)) }
        if contains(.option) { keys.append((.maskAlternate, 0x3A)) }
        if contains(.shift) { keys.append((.maskShift, 0x38)) }
        return keys
    }

    public var eventFlags: CGEventFlags {
        keys.reduce(into: CGEventFlags()) { $0.insert($1.flag) }
    }
}

public enum TextPiece: Equatable, Sendable {
    /// Maksimal `TextChunker.maxUTF16` unit UTF-16, tidak pernah memotong satu grapheme.
    case unicode(String)
    case key(KeyCode)
}

public enum TextChunker {
    /// Batas panjang string di `CGEvent.keyboardSetUnicodeString`.
    public static let maxUTF16 = 20

    /// Baris baru dan tab menjadi tombol Return/Tab karena banyak app mengabaikan "\n" di event Unicode.
    public static func pieces(_ text: String) -> [TextPiece] {
        var pieces: [TextPiece] = []
        var current = ""
        var units = 0
        func flush() {
            guard !current.isEmpty else { return }
            pieces.append(.unicode(current))
            current = ""
            units = 0
        }
        for character in text {
            if character.isNewline {
                flush()
                pieces.append(.key(.returnKey))
            } else if character == "\t" {
                flush()
                pieces.append(.key(.tab))
            } else {
                let count = character.utf16.count
                if units + count > maxUTF16 { flush() }
                current.append(character)
                units += count
            }
        }
        flush()
        return pieces
    }

    /// Teks valid: tidak kosong, dan tanpa karakter kontrol selain "\n" dan "\t".
    public static func isAllowed(_ text: String) -> Bool {
        !text.isEmpty && text.unicodeScalars.allSatisfy { scalar in
            scalar == "\n" || scalar == "\t" || (scalar.value >= 0x20 && scalar.value != 0x7F)
        }
    }
}
