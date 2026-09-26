/// Versi app dari Info.plist: `CFBundleShortVersionString` (file VERSION) dan `CFBundleVersion` (nomor run CI).
public struct AppVersion: Equatable, Sendable {
    public let short: String
    public let build: String

    public init(short: String?, build: String?) {
        self.short = short ?? "0.0.0"
        self.build = build ?? "0"
    }

    public init(infoDictionary: [String: Any]?) {
        self.init(
            short: infoDictionary?["CFBundleShortVersionString"] as? String,
            build: infoDictionary?["CFBundleVersion"] as? String
        )
    }

    public var display: String { "\(short) (build \(build))" }
}
