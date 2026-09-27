import CoreGraphics
import Foundation
import Testing
@testable import AgentCore

final class Clock {
    var now = Date(timeIntervalSince1970: 1_790_000_000)
}

@Test func tokenIsSingleUse() {
    let clock = Clock()
    let tokens = PairingTokens(now: { clock.now })
    let token = tokens.issue()
    #expect(tokens.check(token.value) == .valid)
    #expect(tokens.check(token.value) == .invalid)
}

@Test func tokenExpiresAfter120Seconds() {
    let clock = Clock()
    let tokens = PairingTokens(now: { clock.now })
    let token = tokens.issue()
    #expect(tokens.secondsRemaining() == 120)
    clock.now.addTimeInterval(120)
    #expect(tokens.check(token.value) == .expired)
}

@Test func tokenIsCancelledAfterFiveWrongAttempts() {
    let tokens = PairingTokens()
    let token = tokens.issue()
    for _ in 1...4 { #expect(tokens.check("salah") == .invalid) }
    #expect(tokens.check("salah") == .tooManyAttempts)
    #expect(tokens.check(token.value) == .invalid)
}

@Test func pairingURIEncodesEveryField() {
    let uri = PairingURI(hostId: "h-1", hostName: "Mac Wailan+Kantor", address: "192.168.1.20", port: 47810, token: "a_b-c", fingerprint: "f_p")
    #expect(uri.string == "armrest://pair?h=h-1&n=Mac%20Wailan%2BKantor&a=192.168.1.20%3A47810&t=a_b-c&fp=f_p")
}

@Test func slowMovementUsesBaseSensitivityAndKeepsRemainder() {
    var math = PointerMath()
    // 1 dp dalam 16 ms: di bawah v0, jadi gain = 1,5.
    #expect(math.pointerDelta(dx: 10, dy: 0, time: 0).dx == 1)
    #expect(math.pointerDelta(dx: 10, dy: 0, time: 0.016).dx == 2) // 1,5 + sisa 0,5
}

@Test func fastMovementIsCappedAtMaxGain() {
    var math = PointerMath(curve: AccelerationCurve(sensitivity: 1, accel: 2, v0: 0.2, maxGain: 6))
    _ = math.pointerDelta(dx: 0, dy: 0, time: 0)
    // 100 dp dalam 16 ms = 6,25 dp/ms → gain maksimal 6.
    #expect(math.pointerDelta(dx: 1000, dy: 0, time: 0.016).dx == 600)
}

@Test func negativeMovementRoundsTowardZero() {
    var math = PointerMath(curve: AccelerationCurve(sensitivity: 1))
    let delta = math.pointerDelta(dx: -15, dy: -5, time: 0)
    #expect(delta.dx == -1 && delta.dy == 0)
}

@Test func scrollIsScaledWithoutAcceleration() {
    var math = PointerMath(scrollSpeed: 2)
    let delta = math.scrollDelta(dx: 0, dy: -120)
    #expect(delta.dx == 0 && delta.dy == -24)
}

@Test func clampKeepsCursorOnScreensAndAllowsCrossingToAdjacentDisplay() {
    let main = CGRect(x: 0, y: 0, width: 1440, height: 900)
    let right = CGRect(x: 1440, y: 0, width: 1920, height: 1080)
    let displays = [main, right]
    #expect(DisplayClamp.clamp(CGPoint(x: 1500, y: 100), previous: CGPoint(x: 1430, y: 100), displays: displays) == CGPoint(x: 1500, y: 100))
    #expect(DisplayClamp.clamp(CGPoint(x: -50, y: 100), previous: CGPoint(x: 10, y: 100), displays: displays) == CGPoint(x: 0, y: 100))
    // Di bawah layar utama (lebih pendek) → dijepit ke tepi bawah layar utama.
    #expect(DisplayClamp.clamp(CGPoint(x: 100, y: 1000), previous: CGPoint(x: 100, y: 890), displays: displays) == CGPoint(x: 100, y: 899))
}

@Test func trustedDeviceStorePersists() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("devices.json")
    let store = TrustedDeviceStore(fileURL: url)
    let device = TrustedDevice(id: "d1", name: "Pixel 8", publicKey: Data([1, 2, 3]), pairedAt: Date(timeIntervalSince1970: 0))
    try store.upsert(device)
    try store.touch(id: "d1", at: Date(timeIntervalSince1970: 60))

    let reloaded = TrustedDeviceStore(fileURL: url)
    #expect(reloaded.device(id: "d1")?.lastSeen == Date(timeIntervalSince1970: 60))
    try reloaded.remove(id: "d1")
    #expect(TrustedDeviceStore(fileURL: url).devices.isEmpty)
}
