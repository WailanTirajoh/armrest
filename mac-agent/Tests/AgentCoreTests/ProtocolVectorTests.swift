import Foundation
import Testing
@testable import AgentCore

/// Membaca test vector bersama di protocol/vectors (juga dipakai test Kotlin).
enum Vectors {
    static let directory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // AgentCoreTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // mac-agent
        .deletingLastPathComponent() // root repo
        .appendingPathComponent("protocol/vectors")

    static func load(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: directory.appendingPathComponent(name))
        return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}

extension Data {
    init(hex: String) {
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16)!)
            index = next
        }
        self.init(bytes)
    }

    var hex: String { map { String(format: "%02x", $0) }.joined() }
}

@Test func inputVectorsRoundTrip() throws {
    let cases = try #require(try Vectors.load("input.json")["cases"] as? [[String: Any]])
    #expect(cases.count >= 12)
    for c in cases {
        let hex = try #require(c["hex"] as? String)
        let expected: InputMessage
        switch c["message"] as? String {
        case "move":
            expected = .move(dx: Int16(c["dx"] as! Int), dy: Int16(c["dy"] as! Int))
        case "scroll":
            expected = .scroll(dx: Int16(c["dx"] as! Int), dy: Int16(c["dy"] as! Int))
        case "button":
            expected = .button(MouseButton(rawValue: UInt8(c["button"] as! Int))!, down: c["down"] as! Bool)
        case "click":
            expected = .click(MouseButton(rawValue: UInt8(c["button"] as! Int))!, count: UInt8(c["count"] as! Int))
        case "text":
            expected = .text(c["text"] as! String)
        case "key":
            expected = .key(KeyCode(rawValue: UInt8(c["key"] as! Int))!, KeyModifiers(rawValue: UInt8(c["modifiers"] as! Int)))
        default:
            Issue.record("tipe tidak dikenal: \(c)")
            continue
        }
        #expect(InputMessage.decode(Data(hex: hex)) == expected)
        #expect(expected.encoded().hex == hex)
    }
}

@Test func invalidInputFramesAreRejected() throws {
    let invalid = try #require(try Vectors.load("input.json")["invalid_hex"] as? [String])
    for hex in invalid {
        #expect(InputMessage.decode(Data(hex: hex)) == nil, "harus ditolak: \(hex)")
    }
}

@Test func authVectorsVerifyLikeOpenSSL() throws {
    let root = try Vectors.load("auth.json")
    let cases = try #require(root["cases"] as? [[String: Any]])
    for c in cases {
        let payload = AuthCrypto.payload(
            nonce: Data(hex: c["nonce_hex"] as! String),
            hostId: c["hostId"] as! String,
            deviceId: c["deviceId"] as! String
        )
        #expect(payload.hex == c["payload_hex"] as? String)
        let valid = AuthCrypto.verify(
            signatureDER: try #require(Data(base64Encoded: c["signature_b64"] as! String)),
            payload: payload,
            publicKeyDER: try #require(Data(base64Encoded: c["publicKey_b64"] as! String))
        )
        #expect(valid == (c["valid"] as! Bool), "\(c["name"] ?? "")")
    }

    let fingerprint = try #require(root["fingerprint"] as? [String: Any])
    #expect(AuthCrypto.fingerprint(of: Data(hex: fingerprint["input_hex"] as! String)) == fingerprint["sha256_b64url"] as? String)
}

@Test func controlMessagesRoundTrip() {
    let messages: [ControlMessage] = [
        .hello(version: 1, deviceId: "d", mode: .pair),
        .hello(version: 1, deviceId: "d", mode: .auth),
        .pairRequest(token: "tok", deviceName: "Pixel 8", publicKey: "AAA="),
        .pairResult(.ok(hostId: "h", hostName: "MacBook")),
        .pairResult(.failed("denied")),
        .challenge(nonce: "bm9uY2U="),
        .auth(sig: "c2ln"),
        .authResult(ok: true, error: nil),
        .authResult(ok: false, error: "bad_sig"),
        .authResult(ok: true, error: nil, features: ["focus", "screen"]),
        .authResult(ok: true, error: nil, features: ["focus", "screen"], platform: "windows"),
        .authResult(ok: true, error: nil, features: ["power"], platform: "macos", mac: "a4:83:e7:12:34:56"),
        .settings(sensitivity: 1.5, scrollSpeed: 2, focusUpdates: true),
        .settings(sensitivity: 1.5, scrollSpeed: 2, focusUpdates: false),
        .settings(sensitivity: 1.5, scrollSpeed: 2, focusUpdates: true, volumeUpdates: true),
        .focus(text: true),
        .focus(text: false),
        .screen(ScreenRequest(maxWidth: 2712, maxHeight: 1220)),
        .screen(ScreenRequest(maxWidth: 2712, maxHeight: 1220, cursor: true)),
        .screen(nil),
        .screenAck(seq: 4_294_967_295),
        .screenStatus(.streaming),
        .screenStatus(.denied),
        .screenStatus(.failed),
        .screenCursor(x: 0.4213, y: 1),
        .volume(.step(1)),
        .volume(.step(-3)),
        .volume(.level(0.25)),
        .volume(.muted(true)),
        .volumeStatus(VolumeState(level: 0.5625, muted: false)),
        .volumeStatus(VolumeState(level: nil, muted: true)),
        .power(.sleep),
        .power(.restart),
        .power(.shutdown),
        .ping(ts: 1_790_000_000_000),
        .pong(ts: 42),
        .error("bad_message"),
    ]
    for message in messages {
        #expect(ControlMessage.decode(message.encoded()) == message)
    }
}

@Test func controlMessageDecodesJsonFromAndroid() {
    let json = #"{"deviceId":"5f1e","mode":"auth","t":"hello","v":1}"#
    #expect(ControlMessage.decode(Data(json.utf8)) == .hello(version: 1, deviceId: "5f1e", mode: .auth))
    let settings = #"{"focusUpdates":true,"scrollSpeed":2.0,"sensitivity":1.5,"t":"settings"}"#
    #expect(ControlMessage.decode(Data(settings.utf8)) == .settings(sensitivity: 1.5, scrollSpeed: 2, focusUpdates: true))
    // HP v0.3 belum mengenal focusUpdates.
    let oldSettings = #"{"scrollSpeed":2.0,"sensitivity":1.5,"t":"settings"}"#
    #expect(ControlMessage.decode(Data(oldSettings.utf8)) == .settings(sensitivity: 1.5, scrollSpeed: 2, focusUpdates: false))
    let screen = #"{"maxHeight":1220,"maxWidth":2712,"on":true,"t":"screen"}"#
    #expect(ControlMessage.decode(Data(screen.utf8)) == .screen(ScreenRequest(maxWidth: 2712, maxHeight: 1220)))
    #expect(ControlMessage.decode(Data(#"{"on":false,"t":"screen"}"#.utf8)) == .screen(nil))
    #expect(ControlMessage.decode(Data(#"{"seq":42,"t":"screen_ack"}"#.utf8)) == .screenAck(seq: 42))
    let cursorScreen = #"{"cursor":true,"maxHeight":1220,"maxWidth":2712,"on":true,"t":"screen"}"#
    #expect(ControlMessage.decode(Data(cursorScreen.utf8)) == .screen(ScreenRequest(maxWidth: 2712, maxHeight: 1220, cursor: true)))
    let volumeSettings = #"{"focusUpdates":false,"scrollSpeed":2.0,"sensitivity":1.5,"t":"settings","volumeUpdates":true}"#
    #expect(
        ControlMessage.decode(Data(volumeSettings.utf8)) == .settings(sensitivity: 1.5, scrollSpeed: 2, focusUpdates: false, volumeUpdates: true)
    )
    // Agent lama tidak mengirim features; HP lama mengabaikannya.
    #expect(ControlMessage.decode(Data(#"{"ok":true,"t":"auth_result"}"#.utf8)) == .authResult(ok: true, error: nil))
    #expect(ControlMessage.decode(Data(#"{"action":"shutdown","t":"power"}"#.utf8)) == .power(.shutdown))
    #expect(ControlMessage.decode(Data(#"{"action":"hibernate","t":"power"}"#.utf8)) == nil)
    #expect(ControlMessage.decode(Data(#"{"t":"nope"}"#.utf8)) == nil)
    #expect(ControlMessage.decode(Data("bukan json".utf8)) == nil)
}

@Test func base64URLRoundTrip() {
    let data = Data((0...255).map { UInt8($0) })
    let encoded = data.base64URLEncodedString()
    #expect(!encoded.contains("+") && !encoded.contains("/") && !encoded.contains("="))
    #expect(Data(base64URLEncoded: encoded) == data)
}

@Test func volumeMessagesFromAndroidAreStrict() {
    func decode(_ json: String) -> ControlMessage? { ControlMessage.decode(Data(json.utf8)) }
    #expect(decode(#"{"step":1,"t":"volume"}"#) == .volume(.step(1)))
    #expect(decode(#"{"step":-40,"t":"volume"}"#) == .volume(.step(-16)))
    #expect(decode(#"{"level":0.4,"t":"volume"}"#) == .volume(.level(0.4)))
    #expect(decode(#"{"level":7,"t":"volume"}"#) == .volume(.level(1)))
    #expect(decode(#"{"muted":true,"t":"volume"}"#) == .volume(.muted(true)))
    // Bool JSON bukan angka, dan langkah harus bilangan bulat.
    #expect(decode(#"{"step":true,"t":"volume"}"#) == nil)
    #expect(decode(#"{"step":1.5,"t":"volume"}"#) == nil)
    #expect(decode(#"{"muted":1,"t":"volume"}"#) == nil)
    #expect(decode(#"{"t":"volume"}"#) == nil)
    #expect(decode(#"{"muted":false,"t":"volume_status"}"#) == .volumeStatus(VolumeState(level: nil, muted: false)))
    // Empat desimal, bukan 17 digit dari JSONSerialization.
    #expect(String(decoding: ControlMessage.screenCursor(x: 0.42131234, y: 0.1).encoded(), as: UTF8.self) == #"{"t":"screen_cursor","x":0.4213,"y":0.1}"#)
}

@Test func macAddressFormat() {
    #expect(MacAddress.format([0xA4, 0x83, 0xE7, 0x12, 0x34, 0x56]) == "a4:83:e7:12:34:56")
    #expect(MacAddress.format([0, 0, 0, 0, 0, 0]) == nil)
    #expect(MacAddress.format([0xA4, 0x83, 0xE7]) == nil)
}
