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
    case authResult(ok: Bool, error: String?)
    /// `focusUpdates`: HP ingin menerima pesan `focus`.
    case settings(sensitivity: Double, scrollSpeed: Double, focusUpdates: Bool)
    /// Apakah kolom teks sedang fokus di Mac, supaya HP bisa membuka keyboard sendiri.
    case focus(text: Bool)
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
        case let .authResult(ok, error):
            object = ["t": "auth_result", "ok": ok]
            if let error { object["error"] = error }
        case let .settings(sensitivity, scrollSpeed, focusUpdates):
            object = ["t": "settings", "sensitivity": sensitivity, "scrollSpeed": scrollSpeed, "focusUpdates": focusUpdates]
        case let .focus(text):
            object = ["t": "focus", "text": text]
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

    public static func decode(_ data: Data) -> ControlMessage? {
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = o["t"] as? String else { return nil }
        func string(_ key: String) -> String? { o[key] as? String }
        func number(_ key: String) -> NSNumber? { o[key] as? NSNumber }

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
            return .authResult(ok: ok, error: string("error"))
        case "settings":
            guard let sensitivity = number("sensitivity")?.doubleValue,
                  let scrollSpeed = number("scrollSpeed")?.doubleValue else { return nil }
            // HP v0.3 belum mengirim focusUpdates.
            return .settings(sensitivity: sensitivity, scrollSpeed: scrollSpeed, focusUpdates: o["focusUpdates"] as? Bool ?? false)
        case "focus":
            return (o["text"] as? Bool).map { .focus(text: $0) }
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
