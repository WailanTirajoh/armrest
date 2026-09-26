// Membuat Resources/AppIcon.icns dari glyph app (pointer + HP, mockup B4).
// Jalankan dari mac-agent/: swift scripts/make-icon.swift Resources/AppIcon.icns
import AppKit

let sizes: [(String, Int)] = [
    ("16x16", 16), ("16x16@2x", 32), ("32x32", 32), ("32x32@2x", 64),
    ("128x128", 128), ("128x128@2x", 256), ("256x256", 256), ("256x256@2x", 512),
    ("512x512", 512), ("512x512@2x", 1024),
]

func render(_ px: Int) -> Data {
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8, samplesPerPixel: 4,
        hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
    )!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    let s = CGFloat(px) / 1024
    // Grid ikon macOS: kotak 824 pt di tengah kanvas 1024.
    let background = NSBezierPath(
        roundedRect: NSRect(x: 100 * s, y: 100 * s, width: 824 * s, height: 824 * s),
        xRadius: 185 * s, yRadius: 185 * s
    )
    NSColor(srgbRed: 0x1F / 255, green: 0x5F / 255, blue: 0xBF / 255, alpha: 1).setFill()
    background.fill()

    // Glyph viewBox 24×24 (y ke bawah) diskalakan ke kotak 560 px di tengah.
    let g = 560 * s / 24
    let origin = 232 * s
    func point(_ x: CGFloat, _ y: CGFloat) -> NSPoint {
        NSPoint(x: origin + x * g, y: CGFloat(px) - (origin + y * g))
    }
    let pointer = NSBezierPath()
    pointer.move(to: point(3.5, 3))
    pointer.line(to: point(13, 7.2))
    pointer.line(to: point(8.9, 8.7))
    pointer.line(to: point(7.4, 12.8))
    pointer.close()
    let phone = NSBezierPath(
        roundedRect: NSRect(x: origin + 13.5 * g, y: CGFloat(px) - (origin + 22 * g), width: 7 * g, height: 11 * g),
        xRadius: 1.6 * g, yRadius: 1.6 * g
    )
    NSColor.white.setStroke()
    for path in [pointer, phone] {
        path.lineWidth = 1.75 * g
        path.lineJoinStyle = .round
        path.lineCapStyle = .round
        path.stroke()
    }

    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

let output = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "Resources/AppIcon.icns")
let iconset = FileManager.default.temporaryDirectory.appendingPathComponent("AppIcon.iconset")
try? FileManager.default.removeItem(at: iconset)
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
for (name, px) in sizes {
    try render(px).write(to: iconset.appendingPathComponent("icon_\(name).png"))
}

let iconutil = Process()
iconutil.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
iconutil.arguments = ["-c", "icns", iconset.path, "-o", output.path]
try iconutil.run()
iconutil.waitUntilExit()
guard iconutil.terminationStatus == 0 else { fatalError("iconutil gagal") }
print("Ikon ditulis ke \(output.path)")
