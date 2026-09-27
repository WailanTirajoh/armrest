using System.Security.Cryptography;
using System.Text.Json;

namespace CursorController.Agent.Tests;

/// <summary>Test vector bersama di protocol/vectors (juga dipakai test Swift dan Kotlin).</summary>
internal static class Vectors
{
    private static readonly string Directory = FindVectors();

    public static JsonElement Load(string name) => JsonDocument.Parse(File.ReadAllText(Path.Combine(Directory, name))).RootElement;

    public static byte[] Hex(string hex) => Convert.FromHexString(hex);

    public static string Hex(byte[] bytes) => Convert.ToHexStringLower(bytes);

    private static string FindVectors()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "protocol", "vectors");
            if (System.IO.Directory.Exists(candidate)) return candidate;
        }
        throw new DirectoryNotFoundException("protocol/vectors tidak ditemukan");
    }
}

/// <summary>Kunci HP tiruan.</summary>
internal sealed class PhoneKey : IDisposable
{
    private readonly ECDsa key = ECDsa.Create(ECCurve.NamedCurves.nistP256);

    public byte[] PublicKeyDer => key.ExportSubjectPublicKeyInfo();

    public string PublicKeyBase64 => Convert.ToBase64String(PublicKeyDer);

    public string Sign(byte[] payload) =>
        Convert.ToBase64String(key.SignData(payload, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));

    public void Dispose() => key.Dispose();
}
