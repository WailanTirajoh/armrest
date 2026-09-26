import Foundation

public struct TrustedDevice: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public var name: String
    public var publicKey: Data
    public var pairedAt: Date
    public var lastSeen: Date?

    public init(id: String, name: String, publicKey: Data, pairedAt: Date, lastSeen: Date? = nil) {
        self.id = id
        self.name = name
        self.publicKey = publicKey
        self.pairedAt = pairedAt
        self.lastSeen = lastSeen
    }
}

/// Daftar perangkat terpercaya, disimpan sebagai JSON (Application Support) dengan izin 0600.
public final class TrustedDeviceStore {
    public private(set) var devices: [TrustedDevice] = []
    private let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let decoded = try? Self.decoder.decode([TrustedDevice].self, from: data) {
            devices = decoded
        }
    }

    public func device(id: String) -> TrustedDevice? {
        devices.first { $0.id == id }
    }

    public func upsert(_ device: TrustedDevice) throws {
        if let index = devices.firstIndex(where: { $0.id == device.id }) {
            devices[index] = device
        } else {
            devices.append(device)
        }
        try save()
    }

    public func remove(id: String) throws {
        devices.removeAll { $0.id == id }
        try save()
    }

    public func touch(id: String, at date: Date) throws {
        guard let index = devices.firstIndex(where: { $0.id == id }) else { return }
        devices[index].lastSeen = date
        try save()
    }

    private func save() throws {
        let directory = fileURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Self.encoder.encode(devices).write(to: fileURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}
