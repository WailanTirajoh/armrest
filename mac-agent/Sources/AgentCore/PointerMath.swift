import CoreGraphics
import Foundation

/// Kurva akselerasi dari spec: gain = sensitivity × min(maxGain, 1 + accel × max(0, v − v0)), v dalam dp/ms.
public struct AccelerationCurve: Equatable, Sendable {
    public var sensitivity: Double
    public var accel: Double
    public var v0: Double
    public var maxGain: Double

    public init(sensitivity: Double = 1.5, accel: Double = 2.0, v0: Double = 0.2, maxGain: Double = 6) {
        self.sensitivity = sensitivity
        self.accel = accel
        self.v0 = v0
        self.maxGain = maxGain
    }

    public func gain(velocity: Double) -> Double {
        sensitivity * min(maxGain, 1 + accel * max(0, velocity - v0))
    }
}

/// Mengubah delta dari HP (0,1 dp) menjadi delta piksel, dengan sisa pecahan disimpan antar paket.
public struct PointerMath: Sendable {
    public var curve: AccelerationCurve
    public var scrollSpeed: Double

    private var remainderX = 0.0
    private var remainderY = 0.0
    private var scrollRemainderX = 0.0
    private var scrollRemainderY = 0.0
    private var lastMoveTime: TimeInterval?

    public init(curve: AccelerationCurve = AccelerationCurve(), scrollSpeed: Double = 2.0) {
        self.curve = curve
        self.scrollSpeed = scrollSpeed
    }

    /// `time` dalam detik dari jam monotonic. Selang antar paket dibatasi 4–100 ms supaya
    /// paket yang datang berdempetan tidak dianggap gerakan super cepat.
    public mutating func pointerDelta(dx: Int16, dy: Int16, time: TimeInterval) -> (dx: Int, dy: Int) {
        let dxDp = Double(dx) / 10
        let dyDp = Double(dy) / 10
        let dtMs = lastMoveTime.map { min(max((time - $0) * 1000, 4), 100) } ?? 16
        lastMoveTime = time
        let gain = curve.gain(velocity: hypot(dxDp, dyDp) / dtMs)
        return Self.split(dxDp * gain, dyDp * gain, &remainderX, &remainderY)
    }

    public mutating func scrollDelta(dx: Int16, dy: Int16) -> (dx: Int, dy: Int) {
        Self.split(Double(dx) / 10 * scrollSpeed, Double(dy) / 10 * scrollSpeed, &scrollRemainderX, &scrollRemainderY)
    }

    public mutating func reset() {
        remainderX = 0
        remainderY = 0
        scrollRemainderX = 0
        scrollRemainderY = 0
        lastMoveTime = nil
    }

    private static func split(_ x: Double, _ y: Double, _ remX: inout Double, _ remY: inout Double) -> (dx: Int, dy: Int) {
        let fx = x + remX
        let fy = y + remY
        let ix = fx.rounded(.towardZero)
        let iy = fy.rounded(.towardZero)
        remX = fx - ix
        remY = fy - iy
        return (Int(ix), Int(iy))
    }
}

public enum DisplayClamp {
    /// Titik di dalam salah satu layar dibiarkan, sehingga kursor bisa pindah antar monitor lewat
    /// tepi yang bersebelahan. Selain itu dijepit ke layar tempat kursor sebelumnya berada.
    public static func clamp(_ point: CGPoint, previous: CGPoint, displays: [CGRect]) -> CGPoint {
        guard !displays.isEmpty else { return point }
        if displays.contains(where: { $0.contains(point) }) { return point }
        let base = displays.first(where: { $0.contains(previous) })
            ?? displays.min(by: { distance($0, previous) < distance($1, previous) })!
        return CGPoint(
            x: min(max(point.x, base.minX), base.maxX - 1),
            y: min(max(point.y, base.minY), base.maxY - 1)
        )
    }

    private static func distance(_ rect: CGRect, _ point: CGPoint) -> CGFloat {
        let dx = max(rect.minX - point.x, 0, point.x - rect.maxX)
        let dy = max(rect.minY - point.y, 0, point.y - rect.maxY)
        return hypot(dx, dy)
    }
}
