using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Armrest.Agent.Protocol;

namespace Armrest.Agent.Hosting;

public static class LocalNetwork
{
    /// <summary>
    /// Alamat IPv4 LAN untuk QR: kartu jaringan aktif yang punya gateway lebih dulu, supaya adapter virtual
    /// (Hyper-V, VPN) tidak terpilih.
    /// </summary>
    public static string? PrimaryIPv4() => PrimaryInterface()?.Address.ToString();

    /// <summary>Alamat hardware kartu jaringan yang sama dengan <see cref="PrimaryIPv4"/>, untuk Wake-on-LAN.</summary>
    public static string? PrimaryMacAddress()
    {
        try
        {
            return PrimaryInterface() is { } primary ? MacAddress.Format(primary.Interface.GetPhysicalAddress().GetAddressBytes()) : null;
        }
        catch (NetworkInformationException)
        {
            return null;
        }
    }

    private static (NetworkInterface Interface, IPAddress Address)? PrimaryInterface()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up
                    && n.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel))
                .Select(n => (Interface: n, Properties: n.GetIPProperties()))
                .OrderByDescending(n => n.Properties.GatewayAddresses.Any(g =>
                    g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any)))
                .SelectMany(n => n.Properties.UnicastAddresses.Select(a => (n.Interface, a.Address)))
                .Where(n => n.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(n.Address)
                    && !n.Address.ToString().StartsWith("169.254.", StringComparison.Ordinal))
                .Select(n => ((NetworkInterface, IPAddress)?)n)
                .FirstOrDefault();
        }
        catch (NetworkInformationException)
        {
            return null;
        }
    }

    /// <summary>
    /// Sertifikat TLS sementara (ECDSA P-256, self-signed) untuk mode tanpa UI. Diekspor lalu dimuat ulang
    /// sebagai PKCS#12 karena TLS server di Windows tidak bisa memakai kunci sementara secara langsung.
    /// </summary>
    public static X509Certificate2 EphemeralCertificate(string commonName)
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest($"CN={commonName}", key, HashAlgorithmName.SHA256);
        using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        return X509CertificateLoader.LoadPkcs12(certificate.Export(X509ContentType.Pkcs12), null);
    }
}
