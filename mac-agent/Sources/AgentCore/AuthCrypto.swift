import CryptoKit
import Foundation

public enum AuthCrypto {
    /// Payload yang ditandatangani HP: nonce ‖ UTF-8(hostId) ‖ UTF-8(deviceId).
    public static func payload(nonce: Data, hostId: String, deviceId: String) -> Data {
        nonce + Data(hostId.utf8) + Data(deviceId.utf8)
    }

    /// Signature ECDSA P-256 (SHA-256, DER) terhadap public key DER X.509 SubjectPublicKeyInfo.
    public static func verify(signatureDER: Data, payload: Data, publicKeyDER: Data) -> Bool {
        guard let key = try? P256.Signing.PublicKey(derRepresentation: publicKeyDER),
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: signatureDER) else { return false }
        return key.isValidSignature(signature, for: payload)
    }

    public static func isValidPublicKey(_ der: Data) -> Bool {
        (try? P256.Signing.PublicKey(derRepresentation: der)) != nil
    }

    /// Fingerprint sertifikat: base64url tanpa padding dari SHA-256 DER.
    public static func fingerprint(of der: Data) -> String {
        Data(SHA256.hash(data: der)).base64URLEncodedString()
    }
}

extension Data {
    public func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public init?(base64URLEncoded string: String) {
        var s = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        self.init(base64Encoded: s)
    }
}
