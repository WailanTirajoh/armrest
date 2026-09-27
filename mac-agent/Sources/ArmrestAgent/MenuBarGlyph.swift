import AppKit

/// Ikon menu bar (mockup B1): pointer + HP, digambar sebagai template image 18 pt
/// supaya warnanya mengikuti menu bar terang/gelap.
enum MenuBarGlyph {
    enum State: Sendable {
        case idle
        case pairing
        case controlled
        case needsPermission
    }

    static func image(_ state: State) -> NSImage {
        let size: CGFloat = 18
        let image = NSImage(size: NSSize(width: size, height: size), flipped: true) { _ in
            // Koordinat mengikuti viewBox 24×24 di mockup (sumbu y ke bawah).
            let scale = AffineTransform(scaleByX: size / 24, byY: size / 24)
            let lineWidth: CGFloat = 1.75 * size / 24

            let pointer = NSBezierPath()
            pointer.move(to: NSPoint(x: 3.5, y: 3))
            pointer.line(to: NSPoint(x: 13, y: 7.2))
            pointer.line(to: NSPoint(x: 8.9, y: 8.7))
            pointer.line(to: NSPoint(x: 7.4, y: 12.8))
            pointer.close()

            let phone = NSBezierPath(roundedRect: NSRect(x: 13.5, y: 11, width: 7, height: 11), xRadius: 1.6, yRadius: 1.6)

            var paths = [pointer, phone]
            if state == .needsPermission {
                let warning = NSBezierPath()
                warning.move(to: NSPoint(x: 18.5, y: 1.5))
                warning.line(to: NSPoint(x: 22.5, y: 8.5))
                warning.line(to: NSPoint(x: 14.5, y: 8.5))
                warning.close()
                warning.move(to: NSPoint(x: 18.5, y: 4.3))
                warning.line(to: NSPoint(x: 18.5, y: 5.9))
                paths.append(warning)
            }

            NSColor.black.set()
            for path in paths {
                path.transform(using: scale)
                path.lineWidth = lineWidth
                path.lineJoinStyle = .round
                path.lineCapStyle = .round
            }
            if state == .pairing {
                let dash: [CGFloat] = [2.2 * size / 24, 2.2 * size / 24]
                phone.setLineDash(dash, count: dash.count, phase: 0)
            }
            if state == .controlled {
                pointer.fill()
                phone.fill()
            }
            paths.forEach { $0.stroke() }
            return true
        }
        image.isTemplate = true
        return image
    }
}
