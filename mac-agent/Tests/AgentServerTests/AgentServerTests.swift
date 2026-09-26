import AgentCore
import CryptoKit
import Foundation
import Testing
@testable import AgentServer

/// Menjalankan server sungguhan (ws:// tanpa TLS) dan HP tiruan lewat URLSessionWebSocketTask.
final class ServerHarness {
    let queue = DispatchQueue(label: "test.agent-server")
    let server: AgentServer
    let inputs: AsyncStream<InputMessage>
    private let inputContinuation: AsyncStream<InputMessage>.Continuation

    init(approve: Bool) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("devices.json")
        server = AgentServer(
            configuration: .init(port: 0, hostId: "host-1", hostName: "Mac Test", tlsIdentity: nil, advertise: false),
            devices: TrustedDeviceStore(fileURL: url),
            tokens: PairingTokens(),
            queue: queue
        )
        (inputs, inputContinuation) = AsyncStream.makeStream(of: InputMessage.self)
        server.onApprovalRequest = { _, decide in decide(approve) }
        server.onInput = { [inputContinuation] _, message in inputContinuation.yield(message) }
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

    func stop() {
        queue.sync { server.stop() }
    }
}

private func sendJSON(_ task: URLSessionWebSocketTask, _ message: ControlMessage) async throws {
    try await task.send(.string(String(decoding: message.encoded(), as: UTF8.self)))
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

@Test func pairingAndInputOverRealWebSocket() async throws {
    let harness = ServerHarness(approve: true)
    let port = try await harness.start()
    defer { harness.stop() }

    let token = harness.issueToken()
    let key = P256.Signing.PrivateKey()
    let deviceId = UUID().uuidString.lowercased()
    let task = URLSession.shared.webSocketTask(with: URL(string: "ws://127.0.0.1:\(port)")!)
    task.resume()
    defer { task.cancel(with: .normalClosure, reason: nil) }

    try await sendJSON(task, .hello(version: 1, deviceId: deviceId, mode: .pair))
    try await sendJSON(task, .pairRequest(token: token, deviceName: "Pixel 8", publicKey: key.publicKey.derRepresentation.base64EncodedString()))
    #expect(try await receiveJSON(task) == .pairResult(.ok(hostId: "host-1", hostName: "Mac Test")))

    guard case let .challenge(nonceB64)? = try await receiveJSON(task), let nonce = Data(base64Encoded: nonceB64) else {
        Issue.record("harus menerima challenge")
        return
    }
    let payload = AuthCrypto.payload(nonce: nonce, hostId: "host-1", deviceId: deviceId)
    try await sendJSON(task, .auth(sig: try key.signature(for: payload).derRepresentation.base64EncodedString()))
    #expect(try await receiveJSON(task) == .authResult(ok: true, error: nil))
    #expect(harness.trustedDeviceIds() == [deviceId])

    try await task.send(.data(InputMessage.click(.left, count: 2).encoded()))
    var iterator = harness.inputs.makeAsyncIterator()
    #expect(await iterator.next() == .click(.left, count: 2))
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
