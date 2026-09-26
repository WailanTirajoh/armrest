import AgentCore
import ApplicationServices
import Foundation

/// Memantau apakah kolom teks sedang fokus di Mac, selama ada HP yang memintanya (`focusUpdates`).
/// Pemeriksaan berjalan di antrean sendiri, karena panggilan Accessibility ke app yang sedang hang bisa tertahan.
final class FocusMonitor {
    static let interval: DispatchTimeInterval = .milliseconds(250)

    /// Dipanggil di main thread setiap kali status yang perlu dikirim ke HP berubah.
    var onChange: ((Bool) -> Void)?

    private let probe: () -> Bool
    private let queue = DispatchQueue(label: "io.github.wailantirajoh.cursorcontroller.focus")
    private var timer: DispatchSourceTimer?
    /// Naik setiap start/stop, supaya hasil dari pemantauan yang sudah dihentikan dibuang.
    private var generation = 0
    /// Hanya diakses di `queue`.
    private var filter = TextFocusFilter()

    /// `probe` dipanggil di antrean pemantau dan mengembalikan true kalau kolom teks sedang fokus.
    init(probe: @escaping () -> Bool) {
        self.probe = probe
    }

    func start() {
        guard timer == nil else { return }
        generation += 1
        let round = generation
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: Self.interval, leeway: .milliseconds(50))
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            let focused = self.probe()
            guard let value = self.filter.update(focused, now: ProcessInfo.processInfo.systemUptime) else { return }
            DispatchQueue.main.async {
                guard self.generation == round else { return }
                self.onChange?(value)
            }
        }
        self.timer = timer
        timer.resume()
    }

    func stop() {
        guard let timer else { return }
        timer.cancel()
        self.timer = nil
        generation += 1
        queue.async { self.filter = TextFocusFilter() }
    }
}

/// Pemeriksaan lewat Accessibility API, dengan izin yang sama seperti kontrol kursor. Hanya membaca, tidak mengubah app lain.
/// Sengaja tidak menyalakan AXManualAccessibility di app Electron: VS Code lalu mengira ada screen reader dan
/// menawarkan mode khusus screen reader. Akibatnya kolom teks di app Electron tidak terdeteksi.
enum AccessibilityFocus {
    private static let systemWide: AXUIElement = {
        let element = AXUIElementCreateSystemWide()
        // Timeout di elemen system-wide berlaku untuk semua panggilan: app yang hang tidak menahan lebih dari 0,25 detik.
        AXUIElementSetMessagingTimeout(element, 0.25)
        return element
    }()

    private static let ownPid = ProcessInfo.processInfo.processIdentifier

    static func textInputFocused() -> Bool {
        guard let app = element(systemWide, kAXFocusedApplicationAttribute) else { return false }
        var pid: pid_t = 0
        // Panel menu bar app ini tidak punya kolom teks; dilewati supaya tidak memanggil proses sendiri.
        guard AXUIElementGetPid(app, &pid) == .success, pid != ownPid else { return false }
        // Chromium baru membuka isi halaman web ke Accessibility setelah ada yang membaca role app-nya.
        _ = string(app, kAXRoleAttribute)
        guard let focused = element(app, kAXFocusedUIElementAttribute) else { return false }
        if TextFocus.isTextRole(string(focused, kAXRoleAttribute)) { return true }
        // Editor contenteditable di Safari dan Chrome tidak selalu ber-role kolom teks.
        return element(focused, "AXEditableAncestor") != nil
    }

    private static func element(_ parent: AXUIElement, _ attribute: String) -> AXUIElement? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(parent, attribute as CFString, &value) == .success,
              let value, CFGetTypeID(value) == AXUIElementGetTypeID() else { return nil }
        return (value as! AXUIElement)
    }

    private static func string(_ element: AXUIElement, _ attribute: String) -> String? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, attribute as CFString, &value) == .success else { return nil }
        return value as? String
    }
}
