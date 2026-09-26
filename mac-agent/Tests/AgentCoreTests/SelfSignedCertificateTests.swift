import CryptoKit
import Foundation
import Security
import Testing
@testable import AgentCore

@Test func certificateIsAcceptedBySecurityFrameworkAndSelfSigned() throws {
    let key = P256.Signing.PrivateKey()
    let der = try SelfSignedCertificate.make(
        key: key,
        commonName: "Cursor Controller",
        notBefore: Date(timeIntervalSince1970: 1_790_000_000),
        notAfter: Date(timeIntervalSince1970: 1_790_000_000 + 20 * 365 * 86_400)
    )

    let certificate = try #require(SecCertificateCreateWithData(nil, der as CFData))
    #expect(SecCertificateCopySubjectSummary(certificate) as String? == "Cursor Controller")

    // Kunci publik di sertifikat sama dengan kunci yang dipakai.
    let publicKey = try #require(SecCertificateCopyKey(certificate))
    let x963 = try #require(SecKeyCopyExternalRepresentation(publicKey, nil) as Data?)
    #expect(x963 == key.publicKey.x963Representation)

    // Signature atas TBSCertificate valid, dan ikut di akhir sertifikat.
    let parts = try SelfSignedCertificate.build(
        key: key, commonName: "Cursor Controller", notBefore: Date(), notAfter: Date().addingTimeInterval(86_400), serial: [0x42]
    )
    let signature = try P256.Signing.ECDSASignature(derRepresentation: parts.signature)
    #expect(key.publicKey.isValidSignature(signature, for: parts.tbs))
    #expect(parts.der.suffix(parts.signature.count) == parts.signature)
    #expect(SecCertificateCreateWithData(nil, parts.der as CFData) != nil)
}

@Test func derLengthUsesLongFormAbove127() {
    #expect(DER.length(5) == [5])
    #expect(DER.length(200) == [0x81, 200])
    #expect(DER.length(300) == [0x82, 0x01, 0x2C])
    #expect(DER.integer([0x80]) == [0x02, 0x02, 0x00, 0x80])
    #expect(DER.integer([0x00, 0x05]) == [0x02, 0x01, 0x05])
}
