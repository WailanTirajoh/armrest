import AgentCore
import AppKit
import SwiftUI

@main
struct ArmrestApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        MenuBarExtra {
            MenuPanel(model: appDelegate.model)
        } label: {
            MenuBarLabel(model: appDelegate.model)
        }
        .menuBarExtraStyle(.window)
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    let model = AppModel()

    func applicationDidFinishLaunching(_ notification: Notification) {
        model.start()
    }
}

struct MenuBarLabel: View {
    @ObservedObject var model: AppModel

    var body: some View {
        Image(nsImage: MenuBarGlyph.image(model.glyphState))
            .accessibilityLabel(Text(verbatim: "Armrest"))
    }
}

enum Palette {
    static let accent = Color(red: 0x1F / 255, green: 0x5F / 255, blue: 0xBF / 255)
    static let warning = Color(nsColor: NSColor(name: nil) { appearance in
        appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            ? NSColor(srgbRed: 0xF0 / 255, green: 0xB0 / 255, blue: 0x70 / 255, alpha: 1)
            : NSColor(srgbRed: 0x9A / 255, green: 0x4A / 255, blue: 0x06 / 255, alpha: 1)
    })
    static let warningFill = Color(nsColor: NSColor(name: nil) { appearance in
        appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            ? NSColor(srgbRed: 0x3F / 255, green: 0x2A / 255, blue: 0x14 / 255, alpha: 1)
            : NSColor(srgbRed: 0xFB / 255, green: 0xEB / 255, blue: 0xDC / 255, alpha: 1)
    })
    static let success = Color(nsColor: NSColor(name: nil) { appearance in
        appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            ? NSColor(srgbRed: 0x8F / 255, green: 0xD3 / 255, blue: 0xA6 / 255, alpha: 1)
            : NSColor(srgbRed: 0x1C / 255, green: 0x6B / 255, blue: 0x3A / 255, alpha: 1)
    })
}

/// Ikon app (pointer + HP) dalam kotak biru, dipakai di panel dan dialog.
struct AppGlyph: View {
    var size: CGFloat = 32

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.24)
            .fill(Palette.accent)
            .frame(width: size, height: size)
            .overlay(
                Image(nsImage: MenuBarGlyph.image(.idle))
                    .resizable()
                    .renderingMode(.template)
                    .foregroundColor(.white)
                    .frame(width: size * 0.62, height: size * 0.62)
            )
    }
}
