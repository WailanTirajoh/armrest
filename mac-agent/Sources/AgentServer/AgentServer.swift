import AgentCore
import Foundation
import Network
import Security

public enum ServerState: Equatable {
    case starting
    case listening(port: UInt16)
    case failed(String)
}

/// Server WebSocket untuk HP. Semua callback berjalan di `queue` (di app: main queue).
public final class AgentServer: SessionEnvironment {
    public struct Configuration {
        public var port: UInt16
        public var hostId: String
        public var hostName: String
        /// nil = tanpa TLS (hanya untuk test).
        public var tlsIdentity: SecIdentity?
        public var advertise: Bool
        /// Fitur opsional yang diumumkan ke HP (lihat `AgentFeature`).
        public var features: [String]
        /// Platform yang diumumkan di `auth_result` dan TXT Bonjour (lihat `AgentPlatform`).
        public var platform: String?
        /// Alamat hardware yang diumumkan di `auth_result`, untuk Wake-on-LAN.
        public var macAddress: String?

        public init(
            port: UInt16, hostId: String, hostName: String, tlsIdentity: SecIdentity?, advertise: Bool, features: [String] = [],
            platform: String? = nil, macAddress: String? = nil
        ) {
            self.port = port
            self.hostId = hostId
            self.hostName = hostName
            self.tlsIdentity = tlsIdentity
            self.advertise = advertise
            self.features = features
            self.platform = platform
            self.macAddress = macAddress
        }
    }

    public let configuration: Configuration
    public let devices: TrustedDeviceStore
    public let tokens: PairingTokens
    public let queue: DispatchQueue

    /// Minta keputusan user untuk perangkat baru; panggil closure dengan true (Izinkan) atau false.
    public var onApprovalRequest: ((PendingDevice, @escaping (Bool) -> Void) -> Void)?
    public var onInput: ((String, InputMessage) -> Void)?
    public var onSettings: ((String, Double, Double) -> Void)?
    public var onDevicesChanged: (() -> Void)?
    public var onSessionsChanged: (([TrustedDevice]) -> Void)?
    public var onSessionEnded: ((String) -> Void)?
    public var onStateChange: ((ServerState) -> Void)?
    /// true saat mulai ada HP yang meminta status fokus kolom teks, false saat tidak ada lagi.
    /// App menyalakan atau mematikan pemantauan fokus mengikuti ini.
    public var onFocusInterestChanged: ((Bool) -> Void)?
    /// HP mulai (request) atau berhenti (nil) melihat layar, per koneksi. Dipanggil dengan nil juga saat koneksinya
    /// putus. Permintaan ulang selagi aktif berarti HP butuh keyframe atau ukuran baru.
    public var onScreenRequest: ((UUID, TrustedDevice, ScreenRequest?) -> Void)?
    /// HP sudah menerima frame layar sampai nomor ini.
    public var onScreenAck: ((UUID, UInt32) -> Void)?
    /// Perintah volume dari HP (id perangkat, perintah).
    public var onVolume: ((String, VolumeCommand) -> Void)?
    /// true saat mulai ada HP yang meminta status volume, false saat tidak ada lagi.
    /// App menyalakan atau mematikan pemantauan volume mengikuti ini.
    public var onVolumeInterestChanged: ((Bool) -> Void)?
    /// Perintah daya dari HP (id perangkat, perintah).
    public var onPower: ((String, PowerAction) -> Void)?

    public private(set) var state: ServerState = .starting
    private var listener: NWListener?
    private var connections: [UUID: AgentConnection] = [:]
    private var focusInterest = false
    /// Status fokus terakhir yang dikirim ke HP; nil selama tidak ada yang meminta.
    private var textFocus: Bool?
    private var volumeInterest = false
    /// Status volume terakhir yang dikirim ke HP; nil selama tidak ada yang meminta.
    private var volume: VolumeState?

    public var hostId: String { configuration.hostId }
    public var hostName: String { configuration.hostName }
    public var features: [String] { configuration.features }
    public var platform: String? { configuration.platform }
    public var macAddress: String? { configuration.macAddress }

    public init(configuration: Configuration, devices: TrustedDeviceStore, tokens: PairingTokens, queue: DispatchQueue = .main) {
        self.configuration = configuration
        self.devices = devices
        self.tokens = tokens
        self.queue = queue
    }

    public func start() throws {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parameters: NWParameters
        if let identity = configuration.tlsIdentity, let secIdentity = sec_identity_create(identity) {
            let tls = NWProtocolTLS.Options()
            sec_protocol_options_set_local_identity(tls.securityProtocolOptions, secIdentity)
            sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
            parameters = NWParameters(tls: tls, tcp: tcp)
        } else {
            parameters = NWParameters(tls: nil, tcp: tcp)
        }
        let webSocket = NWProtocolWebSocket.Options()
        webSocket.autoReplyPing = true
        webSocket.maximumMessageSize = 64 * 1024
        parameters.defaultProtocolStack.applicationProtocols.insert(webSocket, at: 0)
        parameters.allowLocalEndpointReuse = true

        let port = NWEndpoint.Port(rawValue: configuration.port) ?? .any
        let listener = try NWListener(using: parameters, on: port)
        if configuration.advertise {
            var txt = NWTXTRecord(["hostId": configuration.hostId, "v": String(AgentConstants.protocolVersion)])
            if let platform = configuration.platform { txt["os"] = platform }
            listener.service = NWListener.Service(
                name: configuration.hostName, type: AgentConstants.bonjourServiceType, domain: nil, txtRecord: txt.data
            )
        }
        listener.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.update(.listening(port: listener.port?.rawValue ?? self.configuration.port))
            case let .failed(error):
                self.update(.failed(error.localizedDescription))
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    public func stop() {
        listener?.cancel()
        listener = nil
        for connection in connections.values {
            endScreen(of: connection)
            connection.close()
        }
        connections.removeAll()
        updateFocusInterest()
        updateVolumeInterest()
    }

    /// Perangkat yang sesinya sedang aktif (sudah terautentikasi).
    public var activeDevices: [TrustedDevice] {
        var seen = Set<String>()
        return connections.values.compactMap(\.device).filter { seen.insert($0.id).inserted }
    }

    /// Putus sesi aktif perangkat tanpa menghapusnya dari daftar terpercaya.
    public func disconnect(deviceId: String) {
        connections.values.filter { $0.device?.id == deviceId }.forEach { $0.close() }
    }

    /// Revoke: hapus dari daftar terpercaya dan putus sesinya.
    public func revoke(deviceId: String) {
        try? devices.remove(id: deviceId)
        disconnect(deviceId: deviceId)
        onDevicesChanged?()
    }

    /// Status fokus kolom teks dari pemantau fokus. Dikirim ke HP yang memintanya, hanya kalau berubah.
    public func updateTextFocus(_ focused: Bool) {
        guard focusInterest, focused != textFocus else { return }
        textFocus = focused
        for connection in connections.values where connection.wantsFocusUpdates {
            connection.send(.focus(text: focused))
        }
    }

    /// Status volume dari pemantau volume. Dikirim ke HP yang memintanya, hanya kalau berubah.
    public func updateVolume(_ state: VolumeState) {
        guard volumeInterest, state != volume else { return }
        volume = state
        for connection in connections.values where connection.wantsVolumeUpdates {
            connection.send(.volumeStatus(state))
        }
    }

    /// Posisi kursor di video layar (0–1) untuk satu koneksi yang meminta `cursor`.
    public func sendScreenCursor(x: Double, y: Double, to connection: UUID) {
        connections[connection]?.send(.screenCursor(x: x, y: y))
    }

    /// Kirim paket video layar (`ScreenPacket`) ke satu koneksi. Diabaikan kalau koneksinya sudah tidak ada.
    public func sendScreen(_ packet: Data, to connection: UUID) {
        connections[connection]?.sendBinary(packet)
    }

    public func sendScreenStatus(_ status: ScreenStatus, to connection: UUID) {
        connections[connection]?.send(.screenStatus(status))
    }

    // MARK: SessionEnvironment

    public func checkPairingToken(_ token: String) -> PairingTokenCheck {
        tokens.check(token)
    }

    public func trustedDevice(id: String) -> TrustedDevice? {
        devices.device(id: id)
    }

    public func makeNonce() -> Data {
        Data((0..<32).map { _ in UInt8.random(in: .min ... .max) })
    }

    // MARK: Koneksi

    private func accept(_ nwConnection: NWConnection) {
        let connection = AgentConnection(connection: nwConnection, server: self)
        connections[connection.id] = connection
        connection.start()
    }

    func connectionEnded(_ connection: AgentConnection) {
        guard connections.removeValue(forKey: connection.id) != nil else { return }
        endScreen(of: connection)
        updateFocusInterest()
        updateVolumeInterest()
        if let device = connection.device {
            onSessionEnded?(device.id)
            onSessionsChanged?(activeDevices)
        }
    }

    func connectionAuthenticated(_ connection: AgentConnection, device: TrustedDevice) {
        // Satu sesi aktif per perangkat: koneksi lama dari perangkat yang sama ditutup.
        for other in connections.values where other.id != connection.id && other.device?.id == device.id {
            other.close()
        }
        try? devices.touch(id: device.id, at: Date())
        onDevicesChanged?()
        onSessionsChanged?(activeDevices)
    }

    func focusSubscriptionChanged(_ connection: AgentConnection) {
        // HP yang baru meminta langsung menerima status saat ini, kalau sudah diketahui.
        if connection.wantsFocusUpdates, let textFocus {
            connection.send(.focus(text: textFocus))
        }
        updateFocusInterest()
    }

    func volumeSubscriptionChanged(_ connection: AgentConnection) {
        // HP yang baru meminta langsung menerima status saat ini, kalau sudah diketahui.
        if connection.wantsVolumeUpdates, let volume {
            connection.send(.volumeStatus(volume))
        }
        updateVolumeInterest()
    }

    private func updateVolumeInterest() {
        let interested = connections.values.contains { $0.wantsVolumeUpdates }
        guard interested != volumeInterest else { return }
        volumeInterest = interested
        if !interested { volume = nil }
        onVolumeInterestChanged?(interested)
    }

    private func updateFocusInterest() {
        let interested = connections.values.contains { $0.wantsFocusUpdates }
        guard interested != focusInterest else { return }
        focusInterest = interested
        if !interested { textFocus = nil }
        onFocusInterestChanged?(interested)
    }

    private func endScreen(of connection: AgentConnection) {
        if connection.screenRequested, let device = connection.device {
            onScreenRequest?(connection.id, device, nil)
        }
    }

    func trust(_ device: TrustedDevice) {
        try? devices.upsert(device)
        onDevicesChanged?()
    }

    private func update(_ newState: ServerState) {
        state = newState
        onStateChange?(newState)
    }
}
