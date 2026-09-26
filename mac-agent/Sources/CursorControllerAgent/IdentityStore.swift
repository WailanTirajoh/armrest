import AgentCore
import CryptoKit
import Foundation
import Security

struct AgentIdentity {
    let identity: SecIdentity
    /// base64url SHA-256 sertifikat, dibawa lewat QR untuk pinning di HP.
    let fingerprint: String
}

/// Kunci + sertifikat TLS agent di Keychain login. Dibuat sekali, dipakai ulang setiap start.
enum IdentityStore {
    struct Failure: LocalizedError {
        let step: String
        let status: OSStatus
        var errorDescription: String? { "Gagal \(step) di Keychain (kode \(status))." }
    }

    static func loadOrCreate(name: String) throws -> AgentIdentity {
        if let existing = try? load(name: name) {
            return existing
        }
        delete(name: name)
        return try create(name: name)
    }

    static func delete(name: String) {
        SecItemDelete([kSecClass: kSecClassCertificate, kSecAttrLabel: name] as CFDictionary)
        SecItemDelete([kSecClass: kSecClassKey, kSecAttrLabel: name] as CFDictionary)
    }

    private static func load(name: String) throws -> AgentIdentity {
        var item: CFTypeRef?
        let query: [CFString: Any] = [
            kSecClass: kSecClassCertificate,
            kSecAttrLabel: name,
            kSecReturnRef: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let item, CFGetTypeID(item) == SecCertificateGetTypeID() else {
            throw Failure(step: "mencari sertifikat", status: status)
        }
        let certificate = item as! SecCertificate
        var identity: SecIdentity?
        let identityStatus = SecIdentityCreateWithCertificate(nil, certificate, &identity)
        guard identityStatus == errSecSuccess, let identity else {
            throw Failure(step: "mencari kunci privat", status: identityStatus)
        }
        let der = SecCertificateCopyData(certificate) as Data
        return AgentIdentity(identity: identity, fingerprint: AuthCrypto.fingerprint(of: der))
    }

    private static func create(name: String) throws -> AgentIdentity {
        // Kunci dibuat langsung di Keychain login (file-based). Data protection keychain butuh
        // entitlement dengan Team ID, yang tidak dimiliki build self-signed ini.
        var error: Unmanaged<CFError>?
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecAttrLabel: name,
            kSecUseDataProtectionKeychain: false,
            kSecPrivateKeyAttrs: [kSecAttrIsPermanent: true, kSecAttrLabel: name],
        ]
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error),
              let publicKey = SecKeyCopyPublicKey(privateKey),
              let x963 = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            throw Failure(step: "membuat kunci", status: errSecParam)
        }
        let publicKeyDER = try P256.Signing.PublicKey(x963Representation: x963).derRepresentation

        let now = Date()
        let der = try SelfSignedCertificate.make(
            publicKeyDER: publicKeyDER,
            commonName: name,
            notBefore: now.addingTimeInterval(-86_400),
            notAfter: now.addingTimeInterval(20 * 365 * 86_400)
        ) { tbs in
            var signError: Unmanaged<CFError>?
            guard let signature = SecKeyCreateSignature(privateKey, .ecdsaSignatureMessageX962SHA256, tbs as CFData, &signError) else {
                throw Failure(step: "menandatangani sertifikat", status: errSecParam)
            }
            return signature as Data
        }

        guard let certificate = SecCertificateCreateWithData(nil, der as CFData) else {
            throw Failure(step: "membuat sertifikat", status: errSecParam)
        }
        let status = SecItemAdd([
            kSecClass: kSecClassCertificate,
            kSecValueRef: certificate,
            kSecAttrLabel: name,
            kSecUseDataProtectionKeychain: false,
        ] as CFDictionary, nil)
        guard status == errSecSuccess else { throw Failure(step: "menyimpan sertifikat", status: status) }

        return try load(name: name)
    }
}
