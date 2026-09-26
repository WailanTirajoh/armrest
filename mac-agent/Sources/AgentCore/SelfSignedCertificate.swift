import CryptoKit
import Foundation

/// Sertifikat X.509 v3 self-signed minimal (ECDSA P-256 + SHA-256) untuk TLS agent.
/// HP tidak memvalidasi rantai sertifikat, hanya mencocokkan fingerprint-nya.
public enum SelfSignedCertificate {
    public static func make(
        key: P256.Signing.PrivateKey,
        commonName: String,
        notBefore: Date,
        notAfter: Date,
        serial: [UInt8] = (0..<16).map { _ in UInt8.random(in: .min ... .max) }
    ) throws -> Data {
        try build(key: key, commonName: commonName, notBefore: notBefore, notAfter: notAfter, serial: serial).der
    }

    /// Varian untuk kunci yang tersimpan di Keychain: `sign` mengembalikan signature ECDSA DER atas SHA-256 data.
    public static func make(
        publicKeyDER: Data,
        commonName: String,
        notBefore: Date,
        notAfter: Date,
        serial: [UInt8] = (0..<16).map { _ in UInt8.random(in: .min ... .max) },
        sign: (Data) throws -> Data
    ) throws -> Data {
        try build(publicKeyDER: publicKeyDER, commonName: commonName, notBefore: notBefore, notAfter: notAfter, serial: serial, sign: sign).der
    }

    static func build(
        key: P256.Signing.PrivateKey, commonName: String, notBefore: Date, notAfter: Date, serial: [UInt8]
    ) throws -> (der: Data, tbs: Data, signature: Data) {
        try build(
            publicKeyDER: key.publicKey.derRepresentation, commonName: commonName, notBefore: notBefore, notAfter: notAfter,
            serial: serial, sign: { try key.signature(for: $0).derRepresentation }
        )
    }

    static func build(
        publicKeyDER: Data, commonName: String, notBefore: Date, notAfter: Date, serial: [UInt8], sign: (Data) throws -> Data
    ) throws -> (der: Data, tbs: Data, signature: Data) {
        let algorithm = DER.sequence(DER.oid([0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02])) // ecdsa-with-SHA256
        let name = DER.sequence(DER.set(DER.sequence(
            DER.oid([0x55, 0x04, 0x03]) + DER.tagged(0x0C, Array(commonName.utf8)) // commonName, UTF8String
        )))
        let tbs = DER.sequence(
            DER.tagged(0xA0, DER.integer([2])) // versi v3
                + DER.integer(serial)
                + algorithm
                + name
                + DER.sequence(DER.utcTime(notBefore) + DER.utcTime(notAfter))
                + name
                + Array(publicKeyDER)
        )
        let signature = try sign(Data(tbs))
        let der = Data(DER.sequence(tbs + algorithm + DER.tagged(0x03, [0x00] + Array(signature))))
        return (der, Data(tbs), signature)
    }
}

/// Encoder DER seadanya untuk kebutuhan sertifikat di atas.
enum DER {
    static func tagged(_ tag: UInt8, _ content: [UInt8]) -> [UInt8] {
        [tag] + length(content.count) + content
    }

    static func sequence(_ content: [UInt8]) -> [UInt8] { tagged(0x30, content) }
    static func set(_ content: [UInt8]) -> [UInt8] { tagged(0x31, content) }
    static func oid(_ encoded: [UInt8]) -> [UInt8] { tagged(0x06, encoded) }

    /// INTEGER positif: nol di depan dibuang, 0x00 ditambahkan kalau bit tertinggi menyala.
    static func integer(_ bytes: [UInt8]) -> [UInt8] {
        var value = Array(bytes.drop(while: { $0 == 0 }))
        if value.isEmpty { value = [0] }
        if value[0] & 0x80 != 0 { value.insert(0, at: 0) }
        return tagged(0x02, value)
    }

    /// UTCTime (YYMMDDHHMMSSZ), berlaku untuk tahun 1950–2049.
    static func utcTime(_ date: Date) -> [UInt8] {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        return tagged(0x17, Array(formatter.string(from: date).utf8))
    }

    static func length(_ count: Int) -> [UInt8] {
        if count < 0x80 { return [UInt8(count)] }
        var bytes: [UInt8] = []
        var remaining = count
        while remaining > 0 {
            bytes.insert(UInt8(remaining & 0xFF), at: 0)
            remaining >>= 8
        }
        return [0x80 | UInt8(bytes.count)] + bytes
    }
}
