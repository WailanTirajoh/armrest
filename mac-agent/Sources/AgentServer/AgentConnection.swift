import AgentCore
import Foundation
import Network

/// Satu koneksi WebSocket dari HP: meneruskan frame ke SessionMachine dan menjalankan aksinya.
final class AgentConnection {
    static let heartbeatInterval: TimeInterval = 5
    static let heartbeatTimeout: TimeInterval = 15

    let id = UUID()
    private(set) var device: TrustedDevice?
    /// HP meminta status fokus kolom teks (`focusUpdates` di pesan settings).
    private(set) var wantsFocusUpdates = false
    /// HP meminta status volume (`volumeUpdates` di pesan settings).
    private(set) var wantsVolumeUpdates = false
    /// HP sedang meminta video layar.
    private(set) var screenRequested = false
    private let connection: NWConnection
    private let machine: SessionMachine
    private weak var server: AgentServer?
    private var lastReceived = Date()
    private var heartbeat: DispatchSourceTimer?
    private var closing = false

    init(connection: NWConnection, server: AgentServer) {
        self.connection = connection
        self.server = server
        machine = SessionMachine(environment: server)
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.lastReceived = Date()
                self.startHeartbeat()
                self.receive()
            case .failed, .cancelled:
                self.finish()
            default:
                break
            }
        }
        connection.start(queue: server?.queue ?? .main)
    }

    /// Tutup dengan close frame setelah pesan yang sudah antre terkirim.
    func close() {
        guard !closing else { return }
        closing = true
        machine.markClosed()
        let metadata = NWProtocolWebSocket.Metadata(opcode: .close)
        metadata.closeCode = .protocolCode(.normalClosure)
        let context = NWConnection.ContentContext(identifier: "close", metadata: [metadata])
        connection.send(content: nil, contentContext: context, isComplete: true, completion: .contentProcessed { [weak self] _ in
            self?.connection.cancel()
        })
    }

    private func receive() {
        connection.receiveMessage { [weak self] data, context, _, error in
            guard let self, !self.closing else { return }
            if error != nil {
                self.connection.cancel()
                return
            }
            self.lastReceived = Date()
            let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata
            switch metadata?.opcode {
            case .text?:
                self.perform(self.machine.handleText(data ?? Data()))
            case .binary?:
                self.perform(self.machine.handleBinary(data ?? Data()))
            case .close?:
                self.connection.cancel()
                return
            default:
                break
            }
            if !self.closing {
                self.receive()
            }
        }
    }

    private func perform(_ actions: [SessionAction]) {
        guard let server else { return }
        for action in actions {
            switch action {
            case let .send(message):
                send(message)
            case let .requestApproval(pending):
                guard let request = server.onApprovalRequest else {
                    perform(machine.approvalDecided(false))
                    continue
                }
                request(pending) { [weak self] approved in
                    guard let self, !self.closing else { return }
                    self.perform(self.machine.approvalDecided(approved))
                }
            case let .trustDevice(device):
                server.trust(device)
            case let .authenticated(device):
                self.device = device
                server.connectionAuthenticated(self, device: device)
            case let .input(message):
                if let device { server.onInput?(device.id, message) }
            case let .settings(sensitivity, scrollSpeed, focusUpdates, volumeUpdates):
                if let device { server.onSettings?(device.id, sensitivity, scrollSpeed) }
                if focusUpdates != wantsFocusUpdates {
                    wantsFocusUpdates = focusUpdates
                    server.focusSubscriptionChanged(self)
                }
                if volumeUpdates != wantsVolumeUpdates {
                    wantsVolumeUpdates = volumeUpdates
                    server.volumeSubscriptionChanged(self)
                }
            case let .volume(command):
                if let device { server.onVolume?(device.id, command) }
            case let .screen(request):
                guard let device else { continue }
                screenRequested = request != nil
                server.onScreenRequest?(id, device, request)
            case let .screenAck(seq):
                server.onScreenAck?(id, seq)
            case .close:
                close()
            }
        }
    }

    func send(_ message: ControlMessage) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "text", metadata: [metadata])
        connection.send(content: message.encoded(), contentContext: context, isComplete: true, completion: .contentProcessed { _ in })
    }

    func sendBinary(_ data: Data) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        let context = NWConnection.ContentContext(identifier: "binary", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed { _ in })
    }

    private func startHeartbeat() {
        let timer = DispatchSource.makeTimerSource(queue: server?.queue ?? .main)
        timer.schedule(deadline: .now() + Self.heartbeatInterval, repeating: Self.heartbeatInterval)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            if Date().timeIntervalSince(self.lastReceived) > Self.heartbeatTimeout {
                self.connection.cancel()
                return
            }
            self.send(.ping(ts: Int64(Date().timeIntervalSince1970 * 1000)))
        }
        heartbeat = timer
        timer.resume()
    }

    private func finish() {
        heartbeat?.cancel()
        heartbeat = nil
        machine.markClosed()
        server?.connectionEnded(self)
    }
}
