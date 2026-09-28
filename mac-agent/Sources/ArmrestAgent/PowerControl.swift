import AgentCore
import Foundation

/// Menjalankan perintah daya dari HP. Mulai ulang dan matikan lewat System Events, sama seperti menu Apple, jadi
/// app masih bisa meminta dokumen disimpan. Pertama kali, macOS meminta izin Armrest mengontrol System Events.
enum PowerControl {
    static func perform(_ action: PowerAction) {
        switch action {
        case .sleep:
            run("/usr/bin/pmset", ["sleepnow"])
        case .restart:
            run("/usr/bin/osascript", ["-e", #"tell application "System Events" to restart"#])
        case .shutdown:
            run("/usr/bin/osascript", ["-e", #"tell application "System Events" to shut down"#])
        }
    }

    private static func run(_ path: String, _ arguments: [String]) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: path)
        process.arguments = arguments
        try? process.run()
    }
}
