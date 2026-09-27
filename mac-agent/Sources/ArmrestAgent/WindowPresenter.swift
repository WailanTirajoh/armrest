import AppKit
import SwiftUI

/// Menampilkan jendela SwiftUI dari app menu bar (tanpa Dock) dan melaporkan kalau user menutupnya.
final class WindowPresenter: NSObject, NSWindowDelegate {
    enum Kind: CaseIterable {
        case pairing
        case approval
        case onboarding
    }

    var onUserClose: ((Kind) -> Void)?
    private var windows: [Kind: NSWindow] = [:]
    private var closingProgrammatically = false

    func show<Content: View>(_ kind: Kind, title: String, floating: Bool = false, @ViewBuilder content: () -> Content) {
        let window = windows[kind] ?? makeWindow(floating: floating)
        windows[kind] = window
        window.title = title
        window.contentViewController = NSHostingController(rootView: content())
        window.center()
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }

    func close(_ kind: Kind) {
        guard let window = windows[kind], window.isVisible else { return }
        closingProgrammatically = true
        window.close()
        closingProgrammatically = false
    }

    func windowWillClose(_ notification: Notification) {
        guard !closingProgrammatically,
              let window = notification.object as? NSWindow,
              let kind = windows.first(where: { $0.value === window })?.key else { return }
        onUserClose?(kind)
    }

    private func makeWindow(floating: Bool) -> NSWindow {
        let window: NSWindow = floating
            ? NSPanel(contentRect: .zero, styleMask: [.titled, .closable], backing: .buffered, defer: false)
            : NSWindow(contentRect: .zero, styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.level = floating ? .floating : .normal
        window.delegate = self
        return window
    }
}
