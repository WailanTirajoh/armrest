import AgentCore
import CryptoKit
import Foundation
import Testing
@testable import AgentServer

enum ScreenEvent: Equatable {
    case request(UUID, ScreenRequest?)
    case ack(UUID, UInt32)
}

/// Menjalankan server sungguhan (ws:// tanpa TLS) dan HP tiruan lewat URLSessionWebSocketTask.
final class ServerHarness {
    let queue = DispatchQueue(label: "test.agent-server")
    let server: AgentServer
    let inputs: AsyncStream<InputMessage>
    let focusInterest: AsyncStream<Bool>
    let screenEvents: AsyncStream<ScreenEvent>
    private let inputContinuation: AsyncStream<InputMessage>.Continuation

    init(approve: Bool, features: [String] = []) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("devices.json")
        server = AgentServer(
            configuration: .init(port: 0, hostId: "host-1", hostName: "Mac Test", tlsIdentity: nil, advertise: false, features: features),
            devices: TrustedDeviceStore(fileURL: url),
            tokens: PairingTokens(),
            queue: queue
        )
        (inputs, inputContinuation) = AsyncStream.makeStream(of: InputMessage.self)
        let (focusInterest, focusContinuation) = AsyncStream.makeStream(of: Bool.self)
        self.focusInterest = focusInterest
        server.onApprovalRequest = { _, decide in decide(approve) }
        server.onInput = { [inputContinuation] _, message in inputContinuation.yield(message) }
        server.onFocusInterestChanged = { focusContinuation.yield($0) }
        let (screenEvents, screenContinuation) = AsyncStream.makeStream(of: ScreenEvent.self)
        self.screenEvents = screenEvents
        server.onScreenRequest = { connection, _, request in screenContinuation.yield(.request(connection, request)) }
        server.onScreenAck = { connection, seq in screenContinuation.yield(.ack(connection, seq)) }
    }

    func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                self.server.onStateChange = { state in
                    switch state {
                    case let .listening(port):
                        self.server.onStateChange = nil
                        continuation.resume(returning: port)
                    case let .failed(message):
                        self.server.onStateChange = nil
                        continuation.resume(throwing: NSError(domain: "server", code: 1, userInfo: [NSLocalizedDescriptionKey: message]))
                    case .starting:
                        break
                    }
                }
                do { try self.server.start() } catch { continuation.resume(throwing: error) }
            }
        }
    }

    func issueToken() -> String {
        queue.sync { server.tokens.issue().value }
    }

    func trustedDeviceIds() -> [String] {
        queue.sync { server.devices.devices.map(\.id) }
    }

    func sendScreen(_ packet: Data, to connection: UUID) {
        queue.sync { server.sendScreen(packet, to: connection) }
    }

    func updateTextFocus(_ focused: Bool) {
        queue.sync { server.updateTextFocus(focused) }
    }

    func stop() {
        queue.sync { server.stop() }
    }
}

private func sendJSON(_ task: URLSessionWebSocketTask, _ message: ControlMessage) async throws {
    try await task.send(.string(String(decoding: message.encoded(), as: UTF8.self)))
}

private func receiveBinary(_ task: URLSessionWebSocketTask) async throws -> Data? {
    while true {
        switch try await task.receive() {
        case let .data(data):
            return data
        case .string:
            continue
        @unknown default:
            return nil
        }
    }
}

private func receiveJSON(_ task: URLSessionWebSocketTask) async throws -> ControlMessage? {
    while true {
        switch try await task.receive() {
        case let .string(text):
            let message = ControlMessage.decode(Data(text.utf8))
            if case .ping = message { continue } // heartbeat, tidak relevan untuk test
            return message
        case .data:
            continue
        @unknown default:
            return nil
        }
    }
}

/// HP tiruan: pairing lewat QR, lalu autentikasi di koneksi yang sama.
private func pairPhone(
    _ harness: ServerHarness, port: UInt16, features: [String] = []
) async throws -> (task: URLSessionWebSocketTask, deviceId: String) {
    let token = harness.issueToken()
    let key = P256.Signing.PrivateKey()
    let deviceId = UUID().uuidString.lowercased()
    let task = URLSession.shared.webSocketTask(with: URL(string: "ws://127.0.0.1:\(port)")!)
    task.resume()

    try await sendJSON(task, .hello(version: 1, deviceId: deviceId, mode: .pair))
    try await sendJSON(task, .pairRequest(token: token, deviceName: "Pixel 8", publicKey: key.publicKey.derRepresentation.base64EncodedString()))
    #expect(try await receiveJSON(task) == .pairResult(.ok(hostId: "host-1", hostName: "Mac Test")))

    var nonceB64: String?
    if case let .challenge(value)? = try await receiveJSON(task) { nonceB64 = value }
    let nonce = try #require(nonceB64.flatMap { Data(base64Encoded: $0) }, "harus menerima challenge")
    let payload = AuthCrypto.payload(nonce: nonce, hostId: "host-1", deviceId: deviceId)
    try await sendJSON(task, .auth(sig: try key.signature(for: payload).derRepresentation.base64EncodedString()))
    #expect(try await receiveJSON(task) == .authResult(ok: true, error: nil, features: features))
    return (task, deviceId)
}

@Test func pairingAndInputOverRealWebSocket() async throws {
    let harness = ServerHarness(approve: true)
    let port = try await harness.start()
    defer { harness.stop() }

    let phone = try await pairPhone(harness, port: port)
    defer { phone.task.cancel(with: .normalClosure, reason: nil) }
    #expect(harness.trustedDeviceIds() == [phone.deviceId])

    try await phone.task.send(.data(InputMessage.click(.left, count: 2).encoded()))
    var iterator = harness.inputs.makeAsyncIterator()
    #expect(await iterator.next() == .click(.left, count: 2))
}

@Test func textFocusReachesPhonesThatAskForIt() async throws {
    let harness = ServerHarness(approve: true)
    let port = try await harness.start()
    defer { harness.stop() }
    var interest = harness.focusInterest.makeAsyncIterator()

    let first = try await pairPhone(harness, port: port)
    defer { first.task.cancel(with: .normalClosure, reason: nil) }
    try await sendJSON(first.task, .settings(sensitivity: 1, scrollSpeed: 1, focusUpdates: true))
    #expect(await interest.next() == true)

    harness.updateTextFocus(true)
    harness.updateTextFocus(true) // tidak berubah, jadi tidak dikirim ulang
    harness.updateTextFocus(false)
    #expect(try await receiveJSON(first.task) == .focus(text: true))
    #expect(try await receiveJSON(first.task) == .focus(text: false))

    // HP yang baru meminta langsung menerima status saat ini.
    harness.updateTextFocus(true)
    #expect(try await receiveJSON(first.task) == .focus(text: true))
    let second = try await pairPhone(harness, port: port)
    defer { second.task.cancel(with: .normalClosure, reason: nil) }
    try await sendJSON(second.task, .settings(sensitivity: 1, scrollSpeed: 1, focusUpdates: true))
    #expect(try await receiveJSON(second.task) == .focus(text: true))

    // Pemantauan berhenti setelah tidak ada lagi HP yang meminta.
    try await sendJSON(first.task, .settings(sensitivity: 1, scrollSpeed: 1, focusUpdates: false))
    try await sendJSON(second.task, .settings(sensitivity: 1, scrollSpeed: 1, focusUpdates: false))
    #expect(await interest.next() == false)
}

@Test func deniedPairingDoesNotTrustDevice() async throws {
    let harness = ServerHarness(approve: false)
    let port = try await harness.start()
    defer { harness.stop() }

    let token = harness.issueToken()
    let key = P256.Signing.PrivateKey()
    let task = URLSession.shared.webSocketTask(with: URL(string: "ws://127.0.0.1:\(port)")!)
    task.resume()
    defer { task.cancel(with: .normalClosure, reason: nil) }

    try await sendJSON(task, .hello(version: 1, deviceId: "d-2", mode: .pair))
    try await sendJSON(task, .pairRequest(token: token, deviceName: "Pixel", publicKey: key.publicKey.derRepresentation.base64EncodedString()))
    #expect(try await receiveJSON(task) == .pairResult(.failed("denied")))
    #expect(harness.trustedDeviceIds().isEmpty)
}

@Test func screenRequestsReachAppAndFramesReachPhone() async throws {
    let harness = ServerHarness(approve: true, features: ["focus", "screen"])
    let port = try await harness.start()
    defer { harness.stop() }
    var events = harness.screenEvents.makeAsyncIterator()

    let phone = try await pairPhone(harness, port: port, features: ["focus", "screen"])
    try await sendJSON(phone.task, .screen(ScreenRequest(maxWidth: 1920, maxHeight: 1080)))
    guard case let .request(connection, request)? = await events.next() else {
        Issue.record("app harus menerima permintaan layar")
        return
    }
    #expect(request == ScreenRequest(maxWidth: 1920, maxHeight: 1080))

    let packet = ScreenPacket.frame(seq: 1, keyframe: true, data: Data([0, 0, 0, 1, 0x65, 0x88]))
    harness.sendScreen(packet.encoded(), to: connection)
    #expect(try await receiveBinary(phone.task) == packet.encoded())
    try await sendJSON(phone.task, .screenAck(seq: 1))
    #expect(await events.next() == .ack(connection, 1))

    // Koneksi putus: app diberi tahu supaya tangkapan layar berhenti.
    phone.task.cancel(with: .normalClosure, reason: nil)
    #expect(await events.next() == .request(connection, nil))
}
