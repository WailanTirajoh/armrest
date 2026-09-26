import AgentCore
import Foundation

/// Konfigurasi dari environment. Tanpa variabel apa pun = app normal.
/// Profil lain (mis. CURSORCTL_PROFILE=e2e) memakai data, Keychain, dan port terpisah untuk uji end-to-end.
struct Profile {
    let suffix: String
    let port: UInt16
    /// Alamat di QR, mis. 10.0.2.2 untuk emulator Android. Default: IPv4 LAN Mac.
    let advertisedAddress: String?
    let autoApprove: Bool
    let pairingFile: URL?
    let logInput: Bool

    static let current: Profile = {
        let env = ProcessInfo.processInfo.environment
        let name = env["CURSORCTL_PROFILE"].flatMap { $0.isEmpty ? nil : $0 }
        return Profile(
            suffix: name.map { "-\($0)" } ?? "",
            port: env["CURSORCTL_PORT"].flatMap(UInt16.init) ?? AgentConstants.defaultPort,
            advertisedAddress: env["CURSORCTL_E2E_ADDRESS"],
            autoApprove: env["CURSORCTL_E2E_AUTO_APPROVE"] == "1",
            pairingFile: env["CURSORCTL_E2E_PAIRING_FILE"].map { URL(fileURLWithPath: $0) },
            logInput: env["CURSORCTL_E2E_LOG"] == "1"
        )
    }()

    var defaults: UserDefaults {
        suffix.isEmpty ? .standard : UserDefaults(suiteName: "io.github.wailantirajoh.cursorcontroller.agent\(suffix)") ?? .standard
    }

    var supportDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Cursor Controller\(suffix)")
    }

    /// Label item Keychain sekaligus common name sertifikat.
    var identityName: String { "Cursor Controller\(suffix)" }
}
