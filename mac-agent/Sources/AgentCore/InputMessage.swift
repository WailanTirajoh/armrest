import Foundation

public enum MouseButton: UInt8, Sendable {
    case left = 0
    case right = 1
}

/// Event input biner dari HP (protocol/PROTOCOL.md, bagian "Event input"). dx/dy dalam satuan 0,1 dp.
public enum InputMessage: Equatable, Sendable {
    case move(dx: Int16, dy: Int16)
    case button(MouseButton, down: Bool)
    case click(MouseButton, count: UInt8)
    case scroll(dx: Int16, dy: Int16)

    public func encoded() -> Data {
        switch self {
        case let .move(dx, dy): return Data([0x01] + Self.bytes(dx) + Self.bytes(dy))
        case let .button(button, down): return Data([0x02, button.rawValue, down ? 1 : 0])
        case let .click(button, count): return Data([0x03, button.rawValue, count])
        case let .scroll(dx, dy): return Data([0x04] + Self.bytes(dx) + Self.bytes(dy))
        }
    }

    /// nil untuk frame dengan panjang salah, tipe tidak dikenal, atau nilai di luar rentang.
    public static func decode(_ data: Data) -> InputMessage? {
        let b = [UInt8](data)
        guard let type = b.first else { return nil }
        switch (type, b.count) {
        case (0x01, 5):
            return .move(dx: int16(b[1], b[2]), dy: int16(b[3], b[4]))
        case (0x02, 3):
            guard let button = MouseButton(rawValue: b[1]), b[2] <= 1 else { return nil }
            return .button(button, down: b[2] == 1)
        case (0x03, 3):
            guard let button = MouseButton(rawValue: b[1]), b[2] == 1 || b[2] == 2 else { return nil }
            return .click(button, count: b[2])
        case (0x04, 5):
            return .scroll(dx: int16(b[1], b[2]), dy: int16(b[3], b[4]))
        default:
            return nil
        }
    }

    private static func bytes(_ value: Int16) -> [UInt8] {
        let raw = UInt16(bitPattern: value)
        return [UInt8(raw & 0xFF), UInt8(raw >> 8)]
    }

    private static func int16(_ low: UInt8, _ high: UInt8) -> Int16 {
        Int16(bitPattern: UInt16(low) | UInt16(high) << 8)
    }
}
