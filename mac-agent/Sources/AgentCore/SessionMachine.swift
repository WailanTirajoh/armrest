import Foundation

/// Yang dibutuhkan sesi dari luar. Diimplementasikan oleh server (dan fake di test).
public protocol SessionEnvironment: AnyObject {
    var hostId: String { get }
    var hostName: String { get }
    func checkPairingToken(_ token: String) -> PairingTokenCheck
    func trustedDevice(id: String) -> TrustedDevice?
    func makeNonce() -> Data
    /// Fitur opsional yang diumumkan ke HP di `auth_result`.
    var features: [String] { get }
    /// Platform yang diumumkan di `auth_result` (lihat `AgentPlatform`).
    var platform: String? { get }
}

public extension SessionEnvironment {
    var features: [String] { [] }
    var platform: String? { nil }
}

public struct PendingDevice: Equatable, Sendable {
    public let id: String
    public let name: String
    public let publicKey: Data
}

public enum SessionAction: Equatable, Sendable {
    case send(ControlMessage)
    /// UI menampilkan dialog "Izinkan?", lalu memanggil `approvalDecided`.
    case requestApproval(PendingDevice)
    case trustDevice(TrustedDevice)
    case authenticated(TrustedDevice)
    case input(InputMessage)
    case settings(sensitivity: Double, scrollSpeed: Double, focusUpdates: Bool, volumeUpdates: Bool)
    case screen(ScreenRequest?)
    case volume(VolumeCommand)
    case screenAck(UInt32)
    case close(reason: String)
}

/// State machine per koneksi: awaitingHello → pairing / challenge → authenticated → closed.
/// Tidak menyentuh jaringan; pemanggil menjalankan aksi yang dikembalikan.
public final class SessionMachine {
    public enum State: Equatable {
        case awaitingHello
        case awaitingPairRequest(deviceId: String)
        case awaitingApproval(PendingDevice)
        case awaitingAuth(deviceId: String, nonce: Data)
        case authenticated(TrustedDevice)
        case closed
    }

    public private(set) var state: State = .awaitingHello
    // Referensi kuat: server menghapus sesi saat koneksi selesai, jadi tidak ada siklus yang tertinggal.
    private let environment: SessionEnvironment
    private let now: () -> Date

    public init(environment: SessionEnvironment, now: @escaping () -> Date = Date.init) {
        self.environment = environment
        self.now = now
    }

    public var isAuthenticated: Bool {
        if case .authenticated = state { return true }
        return false
    }

    public func handleText(_ data: Data) -> [SessionAction] {
        guard state != .closed else { return [] }
        guard let message = ControlMessage.decode(data) else { return fail("bad_message") }

        switch (state, message) {
        case let (_, .ping(ts)):
            return [.send(.pong(ts: ts))]
        case (_, .pong):
            return []

        case let (.awaitingHello, .hello(version, deviceId, mode)):
            guard version == AgentConstants.protocolVersion else { return fail("unsupported_version") }
            switch mode {
            case .pair:
                state = .awaitingPairRequest(deviceId: deviceId)
                return []
            case .auth:
                guard environment.trustedDevice(id: deviceId) != nil else {
                    return close(.authResult(ok: false, error: "unknown_device"), reason: "unknown_device")
                }
                return sendChallenge(deviceId: deviceId)
            }

        case let (.awaitingPairRequest(deviceId), .pairRequest(token, deviceName, publicKey)):
            let check = environment.checkPairingToken(token)
            if let code = check.errorCode {
                return close(.pairResult(.failed(code)), reason: code)
            }
            guard let keyData = Data(base64Encoded: publicKey), AuthCrypto.isValidPublicKey(keyData) else {
                return fail("bad_message")
            }
            let name = String(deviceName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(64))
            let pending = PendingDevice(id: deviceId, name: name.isEmpty ? "Perangkat Android" : name, publicKey: keyData)
            state = .awaitingApproval(pending)
            return [.requestApproval(pending)]

        case let (.awaitingAuth(deviceId, nonce), .auth(sig)):
            guard let device = environment.trustedDevice(id: deviceId) else {
                return close(.authResult(ok: false, error: "unknown_device"), reason: "unknown_device")
            }
            let payload = AuthCrypto.payload(nonce: nonce, hostId: environment.hostId, deviceId: deviceId)
            guard let signature = Data(base64Encoded: sig),
                  AuthCrypto.verify(signatureDER: signature, payload: payload, publicKeyDER: device.publicKey) else {
                return close(.authResult(ok: false, error: "bad_sig"), reason: "bad_sig")
            }
            state = .authenticated(device)
            let result = ControlMessage.authResult(ok: true, error: nil, features: environment.features, platform: environment.platform)
            return [.send(result), .authenticated(device)]

        case let (.authenticated, .settings(sensitivity, scrollSpeed, focusUpdates, volumeUpdates)):
            return [.settings(
                sensitivity: min(max(sensitivity, 0.3), 5), scrollSpeed: min(max(scrollSpeed, 0.3), 8), focusUpdates: focusUpdates,
                volumeUpdates: volumeUpdates
            )]

        case let (.authenticated, .volume(command)):
            return [.volume(command)]

        case let (.authenticated, .screen(request)):
            return [.screen(request)]

        case let (.authenticated, .screenAck(seq)):
            return [.screenAck(seq)]

        default:
            return fail("bad_message")
        }
    }

    /// Event input sebelum autentikasi selesai dibuang.
    public func handleBinary(_ data: Data) -> [SessionAction] {
        guard isAuthenticated, let message = InputMessage.decode(data) else { return [] }
        return [.input(message)]
    }

    public func approvalDecided(_ approved: Bool) -> [SessionAction] {
        guard case let .awaitingApproval(pending) = state else { return [] }
        guard approved else {
            return close(.pairResult(.failed("denied")), reason: "denied")
        }
        let device = TrustedDevice(id: pending.id, name: pending.name, publicKey: pending.publicKey, pairedAt: now())
        let result: [SessionAction] = [
            .trustDevice(device),
            .send(.pairResult(.ok(hostId: environment.hostId, hostName: environment.hostName))),
        ]
        return result + sendChallenge(deviceId: pending.id)
    }

    /// Dipanggil saat koneksi putus atau ditutup dari sisi agent.
    public func markClosed() {
        state = .closed
    }

    private func sendChallenge(deviceId: String) -> [SessionAction] {
        let nonce = environment.makeNonce()
        state = .awaitingAuth(deviceId: deviceId, nonce: nonce)
        return [.send(.challenge(nonce: nonce.base64EncodedString()))]
    }

    private func fail(_ code: String) -> [SessionAction] {
        close(.error(code), reason: code)
    }

    private func close(_ message: ControlMessage, reason: String) -> [SessionAction] {
        state = .closed
        return [.send(message), .close(reason: reason)]
    }
}
