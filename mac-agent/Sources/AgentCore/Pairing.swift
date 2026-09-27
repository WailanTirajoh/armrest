import Foundation

public enum PairingTokenCheck: Equatable, Sendable {
    case valid
    case invalid
    case expired
    case tooManyAttempts

    /// Kode `error` di `pair_result`.
    public var errorCode: String? {
        switch self {
        case .valid: return nil
        case .invalid: return "token_invalid"
        case .expired: return "token_expired"
        case .tooManyAttempts: return "too_many_attempts"
        }
    }
}

public struct PairingToken: Equatable, Sendable {
    public let value: String
    public let expiresAt: Date
}

/// Token pairing: satu token aktif, TTL 120 detik, sekali pakai, hangus setelah 5 percobaan salah.
public final class PairingTokens {
    public static let ttl: TimeInterval = 120
    public static let maxFailures = 5

    public private(set) var current: PairingToken?
    private var failures = 0
    private let now: () -> Date
    private let random: () -> Data

    public init(
        now: @escaping () -> Date = Date.init,
        random: @escaping () -> Data = { Data((0..<32).map { _ in UInt8.random(in: .min ... .max) }) }
    ) {
        self.now = now
        self.random = random
    }

    @discardableResult
    public func issue() -> PairingToken {
        let token = PairingToken(value: random().base64URLEncodedString(), expiresAt: now().addingTimeInterval(Self.ttl))
        current = token
        failures = 0
        return token
    }

    public func invalidate() {
        current = nil
    }

    public func secondsRemaining() -> Int {
        guard let current else { return 0 }
        return max(0, Int(current.expiresAt.timeIntervalSince(now()).rounded(.up)))
    }

    /// Token yang cocok langsung hangus, apa pun keputusan user setelahnya.
    public func check(_ token: String) -> PairingTokenCheck {
        guard let current else { return .invalid }
        if now() >= current.expiresAt {
            self.current = nil
            return .expired
        }
        if constantTimeEquals(token, current.value) {
            self.current = nil
            return .valid
        }
        failures += 1
        if failures >= Self.maxFailures {
            self.current = nil
            return .tooManyAttempts
        }
        return .invalid
    }

    private func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        return zip(x, y).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }
}

/// Isi QR pairing: armrest://pair?h=&n=&a=&t=&fp=
public struct PairingURI: Equatable, Sendable {
    public var hostId: String
    public var hostName: String
    public var address: String
    public var port: UInt16
    public var token: String
    public var fingerprint: String

    public init(hostId: String, hostName: String, address: String, port: UInt16, token: String, fingerprint: String) {
        self.hostId = hostId
        self.hostName = hostName
        self.address = address
        self.port = port
        self.token = token
        self.fingerprint = fingerprint
    }

    public var string: String {
        // Hanya karakter unreserved yang dibiarkan, supaya "+" dan spasi tidak ambigu di Android.
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s }
        let query = [
            "h=\(enc(hostId))",
            "n=\(enc(hostName))",
            "a=\(enc("\(address):\(port)"))",
            "t=\(enc(token))",
            "fp=\(enc(fingerprint))",
        ].joined(separator: "&")
        return "armrest://pair?\(query)"
    }
}
