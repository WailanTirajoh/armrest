import Foundation

public enum HelloMode: String, Sendable {
    case pair
    case auth
}

public enum PairResult: Equatable, Sendable {
    case ok(hostId: String, hostName: String)
    case failed(String)
}

/// Pesan kontrol JSON (protocol/PROTOCOL.md, bagian "Pesan kontrol").
public enum ControlMessage: Equatable, Sendable {
    case hello(version: Int, deviceId: String, mode: HelloMode)
    case pairRequest(token: String, deviceName: String, publicKey: String)
    case pairResult(PairResult)
    case challenge(nonce: String)
    case auth(sig: String)
    /// Hanya saat ok: `features` = fitur opsional agent (lihat `AgentFeature`), `platform` = `macos` atau `windows`,
    /// `mac` = alamat hardware komputer untuk Wake-on-LAN.
    case authResult(ok: Bool, error: String?, features: [String] = [], platform: String? = nil, mac: String? = nil)
    /// `focusUpdates`: HP ingin menerima pesan `focus`. `volumeUpdates`: HP ingin menerima pesan `volume_status`.
    case settings(sensitivity: Double, scrollSpeed: Double, focusUpdates: Bool, volumeUpdates: Bool = false)
    /// Apakah kolom teks sedang fokus di Mac, supaya HP bisa membuka keyboard sendiri.
    case focus(text: Bool)
    /// HP mulai (request) atau berhenti (nil) melihat layar. Permintaan ulang saat aktif = minta keyframe.
    case screen(ScreenRequest?)
    /// HP sudah menerima frame layar sampai `seq`.
    case screenAck(seq: UInt32)
    case screenStatus(ScreenStatus)
    /// Posisi kursor di video layar, 0–1 dari kiri atas. Hanya untuk HP yang meminta `cursor`.
    case screenCursor(x: Double, y: Double)
    /// Perintah volume dari HP.
    case volume(VolumeCommand)
    /// Volume output komputer, untuk HP yang meminta `volumeUpdates`.
    case volumeStatus(VolumeState)
    /// Tidurkan, mulai ulang, atau matikan komputer.
    case power(PowerAction)
    case ping(ts: Int64)
    case pong(ts: Int64)
    case error(String)

    public func encoded() -> Data {
        var object: [String: Any]
        switch self {
        case let .hello(version, deviceId, mode):
            object = ["t": "hello", "v": version, "deviceId": deviceId, "mode": mode.rawValue]
        case let .pairRequest(token, deviceName, publicKey):
            object = ["t": "pair_request", "token": token, "deviceName": deviceName, "publicKey": publicKey]
        case let .pairResult(.ok(hostId, hostName)):
            object = ["t": "pair_result", "ok": true, "hostId": hostId, "hostName": hostName]
        case let .pairResult(.failed(error)):
            object = ["t": "pair_result", "ok": false, "error": error]
        case let .challenge(nonce):
            object = ["t": "challenge", "nonce": nonce]
        case let .auth(sig):
            object = ["t": "auth", "sig": sig]
        case let .authResult(ok, error, features, platform, mac):
            object = ["t": "auth_result", "ok": ok]
            if let error { object["error"] = error }
            if !features.isEmpty { object["features"] = features }
            if let platform { object["platform"] = platform }
            if let mac { object["mac"] = mac }
        case let .settings(sensitivity, scrollSpeed, focusUpdates, volumeUpdates):
            object = [
                "t": "settings", "sensitivity": sensitivity, "scrollSpeed": scrollSpeed, "focusUpdates": focusUpdates,
                "volumeUpdates": volumeUpdates,
            ]
        case let .focus(text):
            object = ["t": "focus", "text": text]
        case let .screen(request):
            object = ["t": "screen", "on": request != nil]
            if let request {
                object["maxWidth"] = request.maxWidth
                object["maxHeight"] = request.maxHeight
                if request.cursor { object["cursor"] = true }
            }
        case let .screenAck(seq):
            object = ["t": "screen_ack", "seq": seq]
        case let .screenStatus(status):
            object = ["t": "screen_status", "state": status.rawValue]
        case let .screenCursor(x, y):
            object = ["t": "screen_cursor", "x": Self.decimal(x), "y": Self.decimal(y)]
        case let .volume(.step(count)):
            object = ["t": "volume", "step": count]
        case let .volume(.level(level)):
            object = ["t": "volume", "level": Self.decimal(level)]
        case let .volume(.muted(muted)):
            object = ["t": "volume", "muted": muted]
        case let .volumeStatus(state):
            object = ["t": "volume_status", "muted": state.muted]
            if let level = state.level { object["level"] = Self.decimal(level) }
        case let .power(action):
            object = ["t": "power", "action": action.rawValue]
        case let .ping(ts):
            object = ["t": "ping", "ts": ts]
        case let .pong(ts):
            object = ["t": "pong", "ts": ts]
        case let .error(error):
            object = ["t": "error", "error": error]
        }
        // Semua nilai di atas valid untuk JSON, jadi serialisasi tidak bisa gagal.
        return (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data()
    }

    /// Empat desimal. Double biasa ditulis JSONSerialization dengan 17 digit (0.42130000000000001).
    private static func decimal(_ value: Double) -> NSDecimalNumber {
        NSDecimalNumber(string: String(format: "%.4f", locale: Locale(identifier: "en_US_POSIX"), value))
    }

    public static func decode(_ data: Data) -> ControlMessage? {
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = o["t"] as? String else { return nil }
        func string(_ key: String) -> String? { o[key] as? String }
        func number(_ key: String) -> NSNumber? { o[key] as? NSNumber }
        // JSONSerialization memberi NSNumber juga untuk true/false; di sini hanya angka sungguhan.
        func isBoolean(_ value: NSNumber) -> Bool { CFGetTypeID(value) == CFBooleanGetTypeID() }
        func numeric(_ key: String) -> Double? { number(key).flatMap { isBoolean($0) ? nil : $0.doubleValue } }

        switch type {
        case "hello":
            guard let v = number("v")?.intValue, let deviceId = string("deviceId"),
                  let mode = string("mode").flatMap(HelloMode.init(rawValue:)) else { return nil }
            return .hello(version: v, deviceId: deviceId, mode: mode)
        case "pair_request":
            guard let token = string("token"), let name = string("deviceName"), let key = string("publicKey") else { return nil }
            return .pairRequest(token: token, deviceName: name, publicKey: key)
        case "pair_result":
            guard let ok = o["ok"] as? Bool else { return nil }
            if ok {
                guard let hostId = string("hostId"), let hostName = string("hostName") else { return nil }
                return .pairResult(.ok(hostId: hostId, hostName: hostName))
            }
            return .pairResult(.failed(string("error") ?? "unknown"))
        case "challenge":
            return string("nonce").map { .challenge(nonce: $0) }
        case "auth":
            return string("sig").map { .auth(sig: $0) }
        case "auth_result":
            guard let ok = o["ok"] as? Bool else { return nil }
            return .authResult(
                ok: ok, error: string("error"), features: o["features"] as? [String] ?? [], platform: string("platform"), mac: string("mac")
            )
        case "settings":
            guard let sensitivity = number("sensitivity")?.doubleValue,
                  let scrollSpeed = number("scrollSpeed")?.doubleValue else { return nil }
            // HP v0.3 belum mengirim focusUpdates, dan HP sebelum v0.7 belum mengirim volumeUpdates.
            return .settings(
                sensitivity: sensitivity, scrollSpeed: scrollSpeed, focusUpdates: o["focusUpdates"] as? Bool ?? false,
                volumeUpdates: o["volumeUpdates"] as? Bool ?? false
            )
        case "focus":
            return (o["text"] as? Bool).map { .focus(text: $0) }
        case "screen":
            guard let on = o["on"] as? Bool else { return nil }
            guard on else { return .screen(nil) }
            guard let width = number("maxWidth")?.intValue, let height = number("maxHeight")?.intValue else { return nil }
            return .screen(ScreenRequest(maxWidth: width, maxHeight: height, cursor: o["cursor"] as? Bool ?? false))
        case "screen_ack":
            return number("seq").map { .screenAck(seq: $0.uint32Value) }
        case "screen_status":
            return string("state").flatMap(ScreenStatus.init(rawValue:)).map { .screenStatus($0) }
        case "screen_cursor":
            guard let x = number("x")?.doubleValue, let y = number("y")?.doubleValue else { return nil }
            return .screenCursor(x: x, y: y)
        case "volume":
            // Tepat satu field. `step` harus bilangan bulat.
            if let step = numeric("step") {
                guard step == step.rounded() else { return nil }
                return .volume(.step(Int(min(max(step, -Double(VolumeMath.steps)), Double(VolumeMath.steps)))))
            }
            if let level = numeric("level") {
                return .volume(.level(min(max(level, 0), 1)))
            }
            guard let muted = o["muted"] as? NSNumber, isBoolean(muted) else { return nil }
            return .volume(.muted(muted.boolValue))
        case "volume_status":
            guard let muted = o["muted"] as? Bool else { return nil }
            return .volumeStatus(VolumeState(level: number("level")?.doubleValue, muted: muted))
        case "power":
            return string("action").flatMap(PowerAction.init(rawValue:)).map { .power($0) }
        case "ping":
            return number("ts").map { .ping(ts: $0.int64Value) }
        case "pong":
            return number("ts").map { .pong(ts: $0.int64Value) }
        case "error":
            return .error(string("error") ?? "unknown")
        default:
            return nil
        }
    }
}
