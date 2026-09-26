import AgentCore
import AppKit
import SwiftUI

@main
struct CursorControllerApp: App {
    private let version = AppVersion(infoDictionary: Bundle.main.infoDictionary)

    var body: some Scene {
        MenuBarExtra {
            MenuPanel(version: version)
        } label: {
            Image(nsImage: MenuBarGlyph.image(.idle))
        }
        .menuBarExtraStyle(.window)
    }
}

/// Panel yang muncul saat ikon menu bar diklik (mockup B2). Skeleton: versi dan Keluar.
struct MenuPanel: View {
    let version: AppVersion

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Cursor Controller")
                    .font(.system(size: 13, weight: .bold))
                Text("Versi \(version.display)")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            Divider()
            Button("Keluar") { NSApplication.shared.terminate(nil) }
                .buttonStyle(.plain)
                .keyboardShortcut("q")
        }
        .padding(12)
        .frame(width: 300, alignment: .leading)
    }
}
