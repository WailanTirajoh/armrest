using System;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Microsoft.Win32;

namespace Armrest.Agent.Windows.Platform;

/// <summary>
/// Identitas TLS agent: kunci ECDSA P-256 non-exportable (CNG) dan sertifikat self-signed di CurrentUser\My.
/// Sertifikatnya tetap, jadi fingerprint yang dipin HP tidak berubah antar-restart.
/// </summary>
internal static class IdentityStore
{
    public static X509Certificate2 LoadOrCreate(string name)
    {
        using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
        store.Open(OpenFlags.ReadWrite);
        var subject = $"CN={name}";
        foreach (var existing in store.Certificates)
        {
            if (existing.Subject == subject && existing.HasPrivateKey && existing.NotAfter > DateTime.Now.AddDays(1)) return existing;
        }

        var keyName = $"{name} TLS";
        using var key = CngKey.Exists(keyName)
            ? CngKey.Open(keyName)
            : CngKey.Create(CngAlgorithm.ECDsaP256, keyName, new CngKeyCreationParameters
            {
                ExportPolicy = CngExportPolicies.None,
                KeyUsage = CngKeyUsages.AllUsages,
            });
        using var ecdsa = new ECDsaCng(key);
        var request = new CertificateRequest(subject, ecdsa, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, critical: false));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension([new Oid("1.3.6.1.5.5.7.3.1")], critical: false));
        var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(20));
        certificate.FriendlyName = name;
        store.Add(certificate);
        return certificate;
    }
}

/// <summary>Buka saat login lewat HKCU\...\Run.</summary>
internal static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "Armrest";

    public static bool Enabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string;
        }
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (value) key.SetValue(ValueName, $"\"{Environment.ProcessPath}\"");
            else key.DeleteValue(ValueName, throwOnMissingValue: false);
        }
    }
}
