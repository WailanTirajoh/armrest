import CryptoKit
import Foundation
import Testing
@testable import AgentCore

final class FakeEnvironment: SessionEnvironment {
    let hostId = "0b7c2f6e-3d4a-4c1b-9e8f-5a6b7c8d9e0f"
    let hostName = "MacBook Pro Kantor"
    let tokens = PairingTokens()
    var devices: [String: TrustedDevice] = [:]
    let nonce = Data(repeating: 7, count: 32)
    var features: [String] = []
    var platform: String?
    var macAddress: String?

    func checkPairingToken(_ token: String) -> PairingTokenCheck { tokens.check(token) }
    func trustedDevice(id: String) -> TrustedDevice? { devices[id] }
    func makeNonce() -> Data { nonce }
}

private let deviceId = "5f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0"

private func text(_ message: ControlMessage) -> Data { message.encoded() }

private func sentMessages(_ actions: [SessionAction]) -> [ControlMessage] {
    actions.compactMap { if case let .send(message) = $0 { return message } else { return nil } }
}

private func sign(_ key: P256.Signing.PrivateKey, nonce: Data, env: FakeEnvironment) throws -> String {
    let payload = AuthCrypto.payload(nonce: nonce, hostId: env.hostId, deviceId: deviceId)
    return try key.signature(for: payload).derRepresentation.base64EncodedString()
}

@Test func fullPairingFlowEndsAuthenticated() throws {
    let env = FakeEnvironment()
    let machine = SessionMachine(environment: env)
    let key = P256.Signing.PrivateKey()
    let token = env.tokens.issue()

    #expect(machine.handleText(text(.hello(version: 1, deviceId: deviceId, mode: .pair))).isEmpty)
    let request = machine.handleText(text(.pairRequest(
        token: token.value, deviceName: "Pixel 8", publicKey: key.publicKey.derRepresentation.base64EncodedString()
    )))
    guard case let .requestApproval(pending)? = request.first else {
        Issue.record("harus minta persetujuan: \(request)")
        return
    }
    #expect(pending.name == "Pixel 8")

    let approved = machine.approvalDecided(true)
    guard case let .trustDevice(device)? = approved.first else {
        Issue.record("harus menyimpan perangkat: \(approved)")
        return
    }
    env.devices[device.id] = device
    #expect(sentMessages(approved) == [
        .pairResult(.ok(hostId: env.hostId, hostName: env.hostName)),
        .challenge(nonce: env.nonce.base64EncodedString()),
    ])

    let auth = machine.handleText(text(.auth(sig: try sign(key, nonce: env.nonce, env: env))))
    #expect(sentMessages(auth) == [.authResult(ok: true, error: nil)])
    #expect(machine.isAuthenticated)
    #expect(machine.handleBinary(InputMessage.move(dx: 5, dy: -5).encoded()) == [.input(.move(dx: 5, dy: -5))])
}

@Test func inputBeforeAuthenticationIsDropped() {
    let env = FakeEnvironment()
    let machine = SessionMachine(environment: env)
    _ = machine.handleText(text(.hello(version: 1, deviceId: deviceId, mode: .pair)))
    #expect(machine.handleBinary(InputMessage.click(.left, count: 1).encoded()).isEmpty)
}

@Test func expiredAndReusedTokensAreRejected() {
    let env = FakeEnvironment()
    let token = env.tokens.issue()
    #expect(env.tokens.check(token.value) == .valid) // dipakai sesi lain

    let machine = SessionMachine(environment: env)
    _ = machine.handleText(text(.hello(version: 1, deviceId: deviceId, mode: .pair)))
    let actions = machine.handleText(text(.pairRequest(token: token.value, deviceName: "Pixel", publicKey: "AAAA")))
    #expect(sentMessages(actions) == [.pairResult(.failed("token_invalid"))])
    #expect(actions.last == .close(reason: "token_invalid"))
}

@Test func deniedPairingClosesWithoutTrustingDevice() {
    let env = FakeEnvironment()
    let machine = SessionMachine(environment: env)
    let key = P256.Signing.PrivateKey()
    let token = env.tokens.issue()
    _ = machine.handleText(text(.hello(version: 1, deviceId: deviceId, mode: .pair)))
    _ = machine.handleText(text(.pairRequest(token: token.value, deviceName: "Pixel", publicKey: key.publicKey.derRepresentation.base64EncodedString())))
    let actions = machine.approvalDecided(false)
    #expect(actions == [.send(.pairResult(.failed("denied"))), .close(reason: "denied")])
}

@Test func badSignatureIsRejected() throws {
    let env = FakeEnvironment()
    let key = P256.Signing.PrivateKey()
    env.devices[deviceId] = TrustedDevice(id: deviceId, name: "Pixel", publicKey: key.publicKey.derRepresentation, pairedAt: Date())
    let machine = SessionMachine(environment: env)
    _ = machine.handleText(text(.hello(version: 1, deviceId: deviceId, mode: .auth)))
    let otherKey = P256.Signing.PrivateKey()
    let actions = machine.handleText(text(.auth(sig: try sign(otherKey, nonce: env.nonce, env: env))))
    #expect(sentMessages(actions) == [.authResult(ok: false, error: "bad_sig")])
    #expect(!machine.isAuthenticated)
}

@Test func unknownDeviceCannotAuthenticate() {
    let machine = SessionMachine(environment: FakeEnvironment())
    let actions = machine.handleText(text(.hello(version: 1, deviceId: "asing", mode: .auth)))
    #expect(sentMessages(actions) == [.authResult(ok: false, error: "unknown_device")])
}

@Test func unsupportedVersionIsRejected() {
    let machine = SessionMachine(environment: FakeEnvironment())
    let actions = machine.handleText(text(.hello(version: 2, deviceId: deviceId, mode: .pair)))
    #expect(actions == [.send(.error("unsupported_version")), .close(reason: "unsupported_version")])
}

@Test func pingIsAnsweredAndSettingsAreClamped() throws {
    let env = FakeEnvironment()
    let key = P256.Signing.PrivateKey()
    env.devices[deviceId] = TrustedDevice(id: deviceId, name: "Pixel", publicKey: key.publicKey.derRepresentation, pairedAt: Date())
    let machine = SessionMachine(environment: env)
    #expect(machine.handleText(text(.ping(ts: 9))) == [.send(.pong(ts: 9))])
    _ = machine.handleText(text(.hello(version: 1, deviceId: deviceId, mode: .auth)))
    _ = machine.handleText(text(.auth(sig: try sign(key, nonce: env.nonce, env: env))))
    #expect(
        machine.handleText(text(.settings(sensitivity: 99, scrollSpeed: 0, focusUpdates: true, volumeUpdates: true)))
            == [.settings(sensitivity: 5, scrollSpeed: 0.3, focusUpdates: true, volumeUpdates: true)]
    )
    #expect(machine.handleText(text(.volume(.step(-1)))) == [.volume(.step(-1))])
}

@Test func powerCommandsOnlyAfterAuthenticationAndMacAddressIsAnnounced() throws {
    let env = FakeEnvironment()
    env.features = ["power"]
    env.macAddress = "a4:83:e7:12:34:56"
    let key = P256.Signing.PrivateKey()
    env.devices[deviceId] = TrustedDevice(id: deviceId, name: "Pixel", publicKey: key.publicKey.derRepresentation, pairedAt: Date())

    let early = SessionMachine(environment: env)
    _ = early.handleText(text(.hello(version: 1, deviceId: deviceId, mode: .auth)))
    #expect(early.handleText(text(.power(.shutdown))) == [.send(.error("bad_message")), .close(reason: "bad_message")])

    let machine = SessionMachine(environment: env)
    _ = machine.handleText(text(.hello(version: 1, deviceId: deviceId, mode: .auth)))
    let auth = machine.handleText(text(.auth(sig: try sign(key, nonce: env.nonce, env: env))))
    #expect(sentMessages(auth) == [.authResult(ok: true, error: nil, features: ["power"], mac: "a4:83:e7:12:34:56")])
    for action in PowerAction.allCases {
        #expect(machine.handleText(text(.power(action))) == [.power(action)])
    }
}

@Test func screenRequestsOnlyAfterAuthenticationAndFeaturesAreAnnounced() throws {
    let env = FakeEnvironment()
    env.features = ["focus", "screen"]
    env.platform = "macos"
    let key = P256.Signing.PrivateKey()
    env.devices[deviceId] = TrustedDevice(id: deviceId, name: "Pixel", publicKey: key.publicKey.derRepresentation, pairedAt: Date())

    let early = SessionMachine(environment: env)
    _ = early.handleText(text(.hello(version: 1, deviceId: deviceId, mode: .auth)))
    let rejected = early.handleText(text(.screen(ScreenRequest(maxWidth: 1920, maxHeight: 1080))))
    #expect(rejected == [.send(.error("bad_message")), .close(reason: "bad_message")])

    let machine = SessionMachine(environment: env)
    _ = machine.handleText(text(.hello(version: 1, deviceId: deviceId, mode: .auth)))
    let auth = machine.handleText(text(.auth(sig: try sign(key, nonce: env.nonce, env: env))))
    #expect(sentMessages(auth) == [.authResult(ok: true, error: nil, features: ["focus", "screen"], platform: "macos")])
    let request = ScreenRequest(maxWidth: 1920, maxHeight: 1080)
    #expect(machine.handleText(text(.screen(request))) == [.screen(request)])
    #expect(machine.handleText(text(.screenAck(seq: 7))) == [.screenAck(7)])
    #expect(machine.handleText(text(.screen(nil))) == [.screen(nil)])
}
