// Membuat src/Agent.Windows/Assets/AppIcon.ico dari glyph app yang sama dengan agent Mac (pointer + HP).
// Ikonnya di-commit; skrip ini hanya perlu dijalankan ulang kalau desainnya berubah, dan butuh macOS.
// Jalankan dari windows-agent/: swift scripts/make-icon.swift src/Agent.Windows/Assets/AppIcon.ico
import AppKit

let sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]

func render(_ px: Int) -> Data {
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8, samplesPerPixel: 4,
        hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
    )!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    // Ikon Windows mengisi hampir seluruh kanvas, tidak seperti grid 824/1024 di macOS.
    let s = CGFloat(px) / 256
    let background = NSBezierPath(
        roundedRect: NSRect(x: 8 * s, y: 8 * s, width: 240 * s, height: 240 * s), xRadius: 52 * s, yRadius: 52 * s
    )
    NSColor(srgbRed: 0x1F / 255, green: 0x5F / 255, blue: 0xBF / 255, alpha: 1).setFill()
    background.fill()

    // Glyph viewBox 24×24 (y ke bawah) di kotak 164 px di tengah; garis lebih tebal di ukuran kecil.
    let g = 164 * s / 24
    let origin = 46 * s
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
        path.lineWidth = (px <= 32 ? 2.4 : 1.9) * g
        path.lineJoinStyle = .round
        path.lineCapStyle = .round
        path.stroke()
    }

    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

func le16(_ v: Int) -> [UInt8] { [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)] }
func le32(_ v: Int) -> [UInt8] { le16(v & 0xFFFF) + le16(v >> 16) }

let images = sizes.map(render)
var ico = Data(le16(0) + le16(1) + le16(sizes.count))
var offset = 6 + 16 * sizes.count
for (px, png) in zip(sizes, images) {
    let side = UInt8(px >= 256 ? 0 : px)
    ico.append(contentsOf: [side, side, 0, 0] + le16(1) + le16(32) + le32(png.count) + le32(offset))
    offset += png.count
}
images.forEach { ico.append($0) }
let output = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "src/Agent.Windows/Assets/AppIcon.ico")
try ico.write(to: output)
print("Ikon ditulis ke \(output.path) (\(ico.count) byte)")
