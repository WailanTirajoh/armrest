import AgentCore
import AppKit
import CoreGraphics
import Foundation

/// Menerjemahkan event input dari HP menjadi CGEvent sistem (mouse dan keyboard). Butuh izin Accessibility.
final class InputInjector {
    private var math = PointerMath()
    private var leftDown = false
    private var rightDown = false
    private let source = CGEventSource(stateID: .hidSystemState)

    func apply(sensitivity: Double, scrollSpeed: Double) {
        math.curve.sensitivity = sensitivity
        math.scrollSpeed = scrollSpeed
    }

    func handle(_ message: InputMessage) {
        switch message {
        case let .move(dx, dy):
            move(dx: dx, dy: dy)
        case let .button(button, down):
            press(button, down: down, clickState: 1)
        case let .click(button, count):
            press(button, down: true, clickState: Int64(count))
            press(button, down: false, clickState: Int64(count))
        case let .scroll(dx, dy):
            scroll(dx: dx, dy: dy)
        case let .text(text):
            type(text)
        case let .key(key, modifiers):
            if let media = key.mediaKeyType {
                pressMediaKey(media)
            } else {
                press(key, modifiers: modifiers)
            }
        }
    }

    /// Dipanggil saat sesi berakhir supaya tombol tidak "tersangkut" di tengah drag.
    func releaseButtons() {
        if leftDown { press(.left, down: false, clickState: 1) }
        if rightDown { press(.right, down: false, clickState: 1) }
        math.reset()
    }

    private func move(dx: Int16, dy: Int16) {
        let delta = math.pointerDelta(dx: dx, dy: dy, time: ProcessInfo.processInfo.systemUptime)
        guard delta.dx != 0 || delta.dy != 0 else { return }
        let current = currentLocation()
        let target = DisplayClamp.clamp(
            CGPoint(x: current.x + CGFloat(delta.dx), y: current.y + CGFloat(delta.dy)),
            previous: current,
            displays: activeDisplays()
        )
        let type: CGEventType = leftDown ? .leftMouseDragged : rightDown ? .rightMouseDragged : .mouseMoved
        guard let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: target, mouseButton: rightDown && !leftDown ? .right : .left) else { return }
        event.setIntegerValueField(.mouseEventDeltaX, value: Int64(delta.dx))
        event.setIntegerValueField(.mouseEventDeltaY, value: Int64(delta.dy))
        event.post(tap: .cghidEventTap)
    }

    private func press(_ button: MouseButton, down: Bool, clickState: Int64) {
        let type: CGEventType
        switch (button, down) {
        case (.left, true): type = .leftMouseDown
        case (.left, false): type = .leftMouseUp
        case (.right, true): type = .rightMouseDown
        case (.right, false): type = .rightMouseUp
        }
        if button == .left { leftDown = down } else { rightDown = down }
        guard let event = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: currentLocation(), mouseButton: button == .left ? .left : .right) else { return }
        event.setIntegerValueField(.mouseEventClickState, value: clickState)
        event.post(tap: .cghidEventTap)
    }

    private func scroll(dx: Int16, dy: Int16) {
        let delta = math.scrollDelta(dx: dx, dy: dy)
        guard delta.dx != 0 || delta.dy != 0 else { return }
        // Event sintetis tidak dibalik otomatis oleh macOS, jadi arah natural scrolling diterapkan di sini:
        // jari turun → konten ikut turun (wheel positif).
        let sign: Int32 = naturalScrolling ? 1 : -1
        CGEvent(
            scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2,
            wheel1: Int32(delta.dy) * sign, wheel2: Int32(delta.dx) * sign, wheel3: 0
        )?.post(tap: .cghidEventTap)
    }

    /// Teks diketik lewat event Unicode, jadi hasilnya tidak bergantung pada layout keyboard Mac.
    private func type(_ text: String) {
        for piece in TextChunker.pieces(text) {
            switch piece {
            case let .unicode(chunk):
                let units = Array(chunk.utf16)
                for down in [true, false] {
                    guard let event = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: down) else { continue }
                    event.flags = []
                    event.keyboardSetUnicodeString(stringLength: units.count, unicodeString: units)
                    event.post(tap: .cghidEventTap)
                }
            case let .key(key):
                press(key, modifiers: [])
            }
        }
    }

    /// Modifier ditekan sebagai tombol tersendiri dulu (supaya ⌘Tab dan app Electron ikut membaca),
    /// lalu tombol utama dengan flag lengkap, lalu modifier dilepas dengan urutan terbalik.
    private func press(_ key: KeyCode, modifiers: KeyModifiers) {
        guard let virtualKey = key.virtualKey else { return }
        var held: CGEventFlags = []
        for modifier in modifiers.keys {
            held.insert(modifier.flag)
            postKey(modifier.virtualKey, down: true, flags: held)
        }
        postKey(virtualKey, down: true, flags: held)
        postKey(virtualKey, down: false, flags: held)
        for modifier in modifiers.keys.reversed() {
            held.remove(modifier.flag)
            postKey(modifier.virtualKey, down: false, flags: held)
        }
    }

    /// Tombol media seperti di keyboard Mac: event sistem (subtype 8) turun lalu naik, diterima pemutar yang sedang aktif.
    private func pressMediaKey(_ type: Int32) {
        for down in [true, false] {
            let state = down ? 0xA : 0xB
            guard let event = NSEvent.otherEvent(
                with: .systemDefined, location: .zero, modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(state) << 8),
                timestamp: 0, windowNumber: 0, context: nil, subtype: 8, data1: Int(type) << 16 | state << 8, data2: -1
            ) else { continue }
            event.cgEvent?.post(tap: .cghidEventTap)
        }
    }

    private func postKey(_ virtualKey: CGKeyCode, down: Bool, flags: CGEventFlags) {
        guard let event = CGEvent(keyboardEventSource: source, virtualKey: virtualKey, keyDown: down) else { return }
        event.flags = flags
        event.post(tap: .cghidEventTap)
    }

    private var naturalScrolling: Bool {
        UserDefaults.standard.object(forKey: "com.apple.swipescrolldirection") as? Bool ?? true
    }

    private func currentLocation() -> CGPoint {
        CGEvent(source: nil)?.location ?? .zero
    }

    private func activeDisplays() -> [CGRect] {
        var count: UInt32 = 0
        guard CGGetActiveDisplayList(0, nil, &count) == .success, count > 0 else { return [] }
        var ids = [CGDirectDisplayID](repeating: 0, count: Int(count))
        guard CGGetActiveDisplayList(count, &ids, &count) == .success else { return [] }
        return ids.prefix(Int(count)).map { CGDisplayBounds($0) }
    }
}
