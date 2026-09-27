import AgentCore
import Foundation

/// Konfigurasi dari environment. Tanpa variabel apa pun = app normal.
/// Profil lain (mis. ARMREST_PROFILE=e2e) memakai data, Keychain, dan port terpisah untuk uji end-to-end.
struct Profile {
    let suffix: String
    let port: UInt16
    /// Alamat di QR, mis. 10.0.2.2 untuk emulator Android. Default: IPv4 LAN Mac.
    let advertisedAddress: String?
    let autoApprove: Bool
    let pairingFile: URL?
    let logInput: Bool
    /// Profil uji tidak membaca fokus app lain; status fokus kolom teks diambil dari file ini ("1" = fokus).
    let focusFile: URL?

    static let current: Profile = {
        let env = ProcessInfo.processInfo.environment
        let pairingFile = env["ARMREST_E2E_PAIRING_FILE"].map { URL(fileURLWithPath: $0) }
        // Mode tanpa UI tidak pernah memakai data app normal: tanpa nama profil, dipakai profil "e2e".
        let name = env["ARMREST_PROFILE"].flatMap { $0.isEmpty ? nil : $0 } ?? (pairingFile == nil ? nil : "e2e")
        return Profile(
            suffix: name.map { "-\($0)" } ?? "",
            port: env["ARMREST_PORT"].flatMap(UInt16.init) ?? AgentConstants.defaultPort,
            advertisedAddress: env["ARMREST_E2E_ADDRESS"],
            autoApprove: env["ARMREST_E2E_AUTO_APPROVE"] == "1",
            pairingFile: pairingFile,
            logInput: env["ARMREST_E2E_LOG"] == "1",
            focusFile: env["ARMREST_E2E_FOCUS_FILE"].map { URL(fileURLWithPath: $0) }
        )
    }()

    var defaults: UserDefaults {
        suffix.isEmpty ? .standard : UserDefaults(suiteName: "io.github.wailantirajoh.armrest.agent\(suffix)") ?? .standard
    }

    var supportDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Armrest\(suffix)")
    }

    /// Label item Keychain sekaligus common name sertifikat.
    var identityName: String { "Armrest\(suffix)" }
}
