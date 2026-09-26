import AgentCore
import AgentServer
import AppKit
import ApplicationServices
import ServiceManagement
import SwiftUI

struct PairingDisplay {
    let uri: String
    let image: NSImage?
    let address: String
    var secondsRemaining: Int
    var expired: Bool { secondsRemaining <= 0 }
}

/// State app untuk UI menu bar. Semua akses di main thread.
final class AppModel: ObservableObject {
    @Published private(set) var devices: [TrustedDevice] = []
    @Published private(set) var activeDevices: [TrustedDevice] = []
    @Published private(set) var serverState: ServerState = .starting
    @Published private(set) var accessibilityGranted = AXIsProcessTrusted()
    @Published private(set) var screenRecordingGranted = CGPreflightScreenCaptureAccess()
    /// Id perangkat yang sedang melihat layar Mac.
    @Published private(set) var screenViewers: Set<String> = []
    @Published private(set) var launchAtLogin = SMAppService.mainApp.status == .enabled
    @Published private(set) var pairing: PairingDisplay?
    @Published private(set) var startupError: String?

    let version = AppVersion(infoDictionary: Bundle.main.infoDictionary)
    let hostId: String
    let hostName = LocalNetwork.computerName
    private(set) var fingerprint = ""

    private let profile = Profile.current
    private let store: TrustedDeviceStore
    private let tokens = PairingTokens()
    private let injector = InputInjector()
    private lazy var focusMonitor = FocusMonitor(probe: makeFocusProbe())
    private var streamers: [UUID: (deviceId: String, streamer: ScreenStreamer)] = [:]
    private var server: AgentServer?
    private var timer: Timer?
    private let windows = WindowPresenter()

    init() {
        let defaults = profile.defaults
        if let saved = defaults.string(forKey: "hostId") {
            hostId = saved
        } else {
            hostId = UUID().uuidString.lowercased()
            defaults.set(hostId, forKey: "hostId")
        }
        store = TrustedDeviceStore(fileURL: profile.supportDirectory.appendingPathComponent("trusted-devices.json"))
        devices = store.devices
        windows.onUserClose = { [weak self] kind in self?.windowClosedByUser(kind) }
    }

    var glyphState: MenuBarGlyph.State {
        if !accessibilityGranted { return .needsPermission }
        if !activeDevices.isEmpty { return .controlled }
        if pairing != nil { return .pairing }
        return .idle
    }

    var listeningAddress: String? {
        guard case let .listening(port) = serverState else { return nil }
        return "\(profile.advertisedAddress ?? LocalNetwork.primaryIPv4() ?? "?"):\(port)"
    }

    // MARK: Start

    func start() {
        do {
            let identity = try IdentityStore.loadOrCreate(name: profile.identityName)
            fingerprint = identity.fingerprint
            let server = AgentServer(
                configuration: .init(
                    port: profile.port, hostId: hostId, hostName: hostName, tlsIdentity: identity.identity, advertise: true,
                    features: [AgentFeature.focus, AgentFeature.screen]
                ),
                devices: store,
                tokens: tokens
            )
            wire(server)
            try server.start()
            self.server = server
        } catch {
            startupError = error.localizedDescription
            log("gagal start: \(error.localizedDescription)")
        }

        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        if !accessibilityGranted && !headless {
            showOnboarding()
        }
    }

    /// Profil uji end-to-end berjalan tanpa jendela supaya tidak mengganggu layar.
    private var headless: Bool { profile.pairingFile != nil }

    private func wire(_ server: AgentServer) {
        server.onStateChange = { [weak self] state in
            guard let self else { return }
            self.log("server: \(state)")
            self.serverState = state
            // Uji end-to-end: langsung terbitkan QR begitu server siap.
            if case .listening = state, self.profile.pairingFile != nil { self.showPairing() }
        }
        server.onApprovalRequest = { [weak self] pending, decide in
            self?.requestApproval(pending, decide: decide) ?? decide(false)
        }
        server.onDevicesChanged = { [weak self] in
            guard let self else { return }
            self.devices = self.store.devices
        }
        server.onSessionsChanged = { [weak self] active in
            self?.activeDevices = active
        }
        server.onSessionEnded = { [weak self] _ in
            self?.injector.releaseButtons()
        }
        server.onSettings = { [weak self] _, sensitivity, scrollSpeed in
            self?.injector.apply(sensitivity: sensitivity, scrollSpeed: scrollSpeed)
        }
        server.onFocusInterestChanged = { [weak self] interested in
            guard let self else { return }
            if interested {
                self.focusMonitor.start()
            } else {
                self.focusMonitor.stop()
            }
        }
        focusMonitor.onChange = { [weak self, weak server] focused in
            self?.log("focus: \(focused)")
            server?.updateTextFocus(focused)
        }
        server.onScreenRequest = { [weak self] connection, device, request in
            self?.screenRequested(connection: connection, device: device, request: request)
        }
        server.onScreenAck = { [weak self] connection, seq in
            self?.streamers[connection]?.streamer.ack(seq)
        }
        server.onInput = { [weak self] _, message in
            guard let self else { return }
            self.log("input: \(message)")
            // Profil uji hanya mencatat input, tidak pernah mengetik atau menggerakkan kursor sungguhan.
            guard !self.headless else { return }
            self.injector.handle(message)
        }
    }

    /// Profil uji tidak membaca app lain: status fokus diambil dari file, atau selalu "bukan kolom teks".
    private func makeFocusProbe() -> () -> Bool {
        guard headless else { return AccessibilityFocus.textInputFocused }
        guard let file = profile.focusFile else { return { false } }
        return { (try? String(contentsOf: file, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) == "1" }
    }

    private func tick() {
        let trusted = AXIsProcessTrusted()
        if trusted != accessibilityGranted { accessibilityGranted = trusted }
        let screen = CGPreflightScreenCaptureAccess()
        if screen != screenRecordingGranted { screenRecordingGranted = screen }
        if var current = pairing {
            let remaining = tokens.secondsRemaining()
            if remaining != current.secondsRemaining {
                current.secondsRemaining = remaining
                pairing = current
            }
        }
    }

    // MARK: Layar

    private func screenRequested(connection: UUID, device: TrustedDevice, request: ScreenRequest?) {
        guard let request else {
            if streamers.removeValue(forKey: connection)?.streamer.stop() != nil { log("screen: stop") }
            updateScreenViewers()
            return
        }
        if let existing = streamers[connection] {
            existing.streamer.update(request)
            return
        }
        // Profil uji memakai pola uji: tidak butuh izin dan tidak pernah menangkap layar sungguhan.
        if !headless && !CGPreflightScreenCaptureAccess() {
            // Menampilkan prompt sistem (sekali) dan memasukkan app ke daftar Screen Recording.
            CGRequestScreenCaptureAccess()
            server?.sendScreenStatus(.denied, to: connection)
            return
        }
        let streamer = ScreenStreamer(
            source: headless ? TestPatternSource() : DisplayCapture(),
            send: { [weak self] packet in
                DispatchQueue.main.async { self?.server?.sendScreen(packet, to: connection) }
            },
            report: { [weak self] status in
                DispatchQueue.main.async { self?.screenStatusChanged(status, connection: connection) }
            }
        )
        streamers[connection] = (device.id, streamer)
        streamer.start(request)
        updateScreenViewers()
        log("screen: start \(request.maxWidth)x\(request.maxHeight)")
    }

    private func screenStatusChanged(_ status: ScreenStatus, connection: UUID) {
        log("screen: \(status.rawValue)")
        server?.sendScreenStatus(status, to: connection)
        // Streamer yang gagal sudah berhenti sendiri; permintaan berikutnya dari HP membuat yang baru.
        if status != .streaming {
            streamers.removeValue(forKey: connection)
            updateScreenViewers()
        }
    }

    private func updateScreenViewers() {
        let viewers = Set(streamers.values.map(\.deviceId))
        if viewers != screenViewers { screenViewers = viewers }
    }

    // MARK: Pairing

    func showPairing() {
        guard case let .listening(port) = serverState else { return }
        let token = tokens.issue()
        let address = profile.advertisedAddress ?? LocalNetwork.primaryIPv4() ?? "0.0.0.0"
        let uri = PairingURI(
            hostId: hostId, hostName: hostName, address: address, port: port, token: token.value, fingerprint: fingerprint
        ).string
        pairing = PairingDisplay(uri: uri, image: QRCode.image(for: uri), address: "\(address):\(port)", secondsRemaining: tokens.secondsRemaining())
        if let file = profile.pairingFile {
            try? uri.write(to: file, atomically: true, encoding: .utf8)
        }
        if !headless {
            windows.show(.pairing, title: "Tambah perangkat") { PairingView(model: self) }
        }
    }

    func closePairing() {
        tokens.invalidate()
        pairing = nil
        windows.close(.pairing)
    }

    private func requestApproval(_ pending: PendingDevice, decide: @escaping (Bool) -> Void) {
        let finish: (Bool) -> Void = { [weak self] approved in
            self?.windows.close(.approval)
            self?.pendingDecision = nil
            decide(approved)
            if approved { self?.closePairing() }
        }
        if profile.autoApprove {
            finish(true)
            return
        }
        pendingDecision = finish
        windows.show(.approval, title: "Izinkan perangkat", floating: true) {
            ApprovalView(device: pending, onDecision: finish)
        }
    }

    private var pendingDecision: ((Bool) -> Void)?

    private func windowClosedByUser(_ kind: WindowPresenter.Kind) {
        switch kind {
        case .pairing:
            tokens.invalidate()
            pairing = nil
        case .approval:
            pendingDecision?(false)
        case .onboarding:
            break
        }
    }

    // MARK: Perangkat

    func disconnect(_ device: TrustedDevice) {
        server?.disconnect(deviceId: device.id)
    }

    func revoke(_ device: TrustedDevice) {
        let alert = NSAlert()
        alert.messageText = "Cabut akses \(device.name)?"
        alert.informativeText = "\(device.name) tidak bisa mengontrol Mac ini lagi sampai dipasangkan ulang lewat QR."
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Cabut")
        alert.addButton(withTitle: "Batal")
        NSApp.activate(ignoringOtherApps: true)
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        server?.revoke(deviceId: device.id)
    }

    // MARK: Pengaturan

    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            NSSound.beep()
        }
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }

    func showOnboarding() {
        windows.show(.onboarding, title: "Cursor Controller") { OnboardingView(model: self) }
    }

    func closeOnboarding() {
        windows.close(.onboarding)
    }

    func openAccessibilitySettings() {
        // Prompt ini juga menambahkan app ke daftar Accessibility, jadi user tinggal menyalakan toggle.
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    func openScreenRecordingSettings() {
        // Prompt ini juga memasukkan app ke daftar Screen Recording, jadi user tinggal menyalakan toggle.
        CGRequestScreenCaptureAccess()
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
            NSWorkspace.shared.open(url)
        }
    }

    /// Log ke stderr, hanya untuk profil uji (CURSORCTL_E2E_LOG=1).
    private func log(_ text: String) {
        guard profile.logInput else { return }
        FileHandle.standardError.write(Data("\(text)\n".utf8))
    }

    func quit() {
        server?.stop()
        NSApp.terminate(nil)
    }
}
