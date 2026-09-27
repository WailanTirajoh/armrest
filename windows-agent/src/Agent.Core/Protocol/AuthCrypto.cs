using System.Buffers.Text;
using System.Security.Cryptography;
using System.Text;

namespace CursorController.Agent.Protocol;

public static class AuthCrypto
{
    private const string P256Oid = "1.2.840.10045.3.1.7";

    /// <summary>Payload yang ditandatangani HP: nonce ‖ UTF-8(hostId) ‖ UTF-8(deviceId).</summary>
    public static byte[] Payload(ReadOnlySpan<byte> nonce, string hostId, string deviceId) =>
        [.. nonce, .. Encoding.UTF8.GetBytes(hostId), .. Encoding.UTF8.GetBytes(deviceId)];

    /// <summary>Signature ECDSA P-256 (SHA-256, DER) terhadap public key DER X.509 SubjectPublicKeyInfo.</summary>
    public static bool Verify(byte[] signatureDer, byte[] payload, byte[] publicKeyDer)
    {
        using var key = ImportP256(publicKeyDer);
        if (key is null) return false;
        try
        {
            return key.VerifyData(payload, signatureDer, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (CryptographicException)
        {
            return false;
        }
    }

    public static bool IsValidPublicKey(byte[] der)
    {
        using var key = ImportP256(der);
        return key is not null;
    }

    /// <summary>Fingerprint sertifikat: base64url tanpa padding dari SHA-256 DER.</summary>
    public static string Fingerprint(byte[] der) => Base64Url.EncodeToString(SHA256.HashData(der));

    private static ECDsa? ImportP256(byte[] der)
    {
        var key = ECDsa.Create();
        try
        {
            key.ImportSubjectPublicKeyInfo(der, out var read);
            if (read == der.Length && key.ExportParameters(false).Curve.Oid.Value == P256Oid) return key;
        }
        catch (CryptographicException)
        {
        }
        key.Dispose();
        return null;
    }
}
