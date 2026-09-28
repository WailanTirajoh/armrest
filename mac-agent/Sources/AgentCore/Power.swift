import Foundation

/// Perintah daya dari HP (pesan `power`).
public enum PowerAction: String, CaseIterable, Sendable {
    case sleep
    case restart
    case shutdown
}

public enum MacAddress {
    /// 6 byte → `a4:83:e7:12:34:56`. nil kalau panjangnya bukan 6 atau semuanya 0 (antarmuka tanpa alamat hardware).
    public static func format(_ bytes: [UInt8]) -> String? {
        guard bytes.count == 6, bytes.contains(where: { $0 != 0 }) else { return nil }
        return bytes.map { String(format: "%02x", $0) }.joined(separator: ":")
    }
}
