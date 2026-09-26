import Foundation
import Testing
@testable import AgentCore

@Test func screenVectorsRoundTrip() throws {
    let cases = try #require(try Vectors.load("screen.json")["cases"] as? [[String: Any]])
    #expect(cases.count >= 3)
    for c in cases {
        let hex = try #require(c["hex"] as? String)
        let expected: ScreenPacket
        switch c["packet"] as? String {
        case "config":
            expected = .config(
                width: c["width"] as! Int, height: c["height"] as! Int, parameterSets: Data(hex: c["parameterSets_hex"] as! String)
            )
        case "frame":
            expected = .frame(
                seq: UInt32(c["seq"] as! Int), keyframe: c["keyframe"] as! Bool, data: Data(hex: c["data_hex"] as! String)
            )
        default:
            Issue.record("paket tidak dikenal: \(c)")
            continue
        }
        #expect(ScreenPacket.decode(Data(hex: hex)) == expected)
        #expect(expected.encoded().hex == hex)
    }
}

@Test func invalidScreenPacketsAreRejected() throws {
    let invalid = try #require(try Vectors.load("screen.json")["invalid_hex"] as? [String])
    for hex in invalid {
        #expect(ScreenPacket.decode(Data(hex: hex)) == nil, "harus ditolak: \(hex)")
    }
}

@Test func lengthPrefixedNalUnitsBecomeAnnexB() throws {
    let vector = try #require(try Vectors.load("screen.json")["annexb"] as? [String: Any])
    let annexB = Data(hex: vector["annexb_hex"] as! String)
    #expect(AnnexB.fromLengthPrefixed(Data(hex: vector["lengthPrefixed_hex"] as! String)) == annexB)
    let units = (vector["units_hex"] as! [String]).map { Data(hex: $0) }
    #expect(AnnexB.join(units) == annexB)
    #expect(AnnexB.split(annexB) == units)
    #expect(AnnexB.split(Data(hex: vector["mixedStartCodes_hex"] as! String)) == units)
    // Panjang yang melebihi data berarti frame rusak.
    #expect(AnnexB.fromLengthPrefixed(Data([0, 0, 0, 9, 0x65, 0x88])) == nil)
}

@Test func videoFitsPhoneAndCapsWithoutUpscaling() {
    let phone = ScreenRequest(maxWidth: 2712, maxHeight: 1220)
    // MacBook Pro 14" Retina: dibatasi 1200 tinggi, rasio tetap, lebar dibulatkan ke genap.
    #expect(ScreenSizing.videoSize(display: PixelSize(width: 3024, height: 1964), request: phone) == PixelSize(width: 1846, height: 1200))
    // Layar kecil tidak diperbesar.
    #expect(ScreenSizing.videoSize(display: PixelSize(width: 1280, height: 800), request: phone) == PixelSize(width: 1280, height: 800))
    // Monitor yang diputar tetap memakai sisi panjang untuk tingginya.
    #expect(ScreenSizing.videoSize(display: PixelSize(width: 1200, height: 1920), request: phone) == PixelSize(width: 1200, height: 1920))
    // HP kecil membatasi ukuran; ukuran ganjil dibulatkan ke genap.
    let small = ScreenRequest(maxWidth: 480, maxHeight: 800)
    #expect(ScreenSizing.videoSize(display: PixelSize(width: 2560, height: 1600), request: small) == PixelSize(width: 768, height: 480))
    let box = ScreenRequest(maxWidth: 1000, maxHeight: 600)
    #expect(ScreenSizing.videoSize(display: PixelSize(width: 1366, height: 768), request: box) == PixelSize(width: 1000, height: 562))
}

@Test func bitrateScalesWithSizeWithinLimits() {
    #expect(ScreenSizing.bitrate(for: PixelSize(width: 640, height: 400)) == 2_000_000)
    #expect(ScreenSizing.bitrate(for: PixelSize(width: 1280, height: 800)) == 3_686_400)
    #expect(ScreenSizing.bitrate(for: PixelSize(width: 3840, height: 2400)) == 10_000_000)
}

@Test func flowControlHoldsFramesUntilAcknowledged() {
    var flow = ScreenFlowControl(window: 3)
    #expect(flow.next() == 1)
    #expect(flow.next() == 2)
    #expect(flow.next() == 3)
    #expect(!flow.canSend)
    flow.ack(2)
    #expect(flow.canSend)
    flow.ack(1) // konfirmasi lama diabaikan
    #expect(flow.lastAcked == 2)
    flow.ack(9) // nomor yang belum pernah dikirim diabaikan
    #expect(flow.lastAcked == 2)
    flow.ack(3)
    #expect(flow.lastAcked == 3)
}

@Test func flowControlSurvivesSequenceWraparound() {
    var flow = ScreenFlowControl(window: 2, lastSent: UInt32.max - 1, lastAcked: UInt32.max - 1)
    #expect(flow.next() == UInt32.max)
    #expect(flow.next() == 0)
    #expect(!flow.canSend)
    flow.ack(UInt32.max)
    #expect(flow.canSend)
    flow.ack(0)
    #expect(flow.lastAcked == 0)
    flow.ack(UInt32.max) // sudah lewat
    #expect(flow.lastAcked == 0)
}
