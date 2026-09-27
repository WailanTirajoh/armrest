using System.Collections;
using System.Text;
using CursorController.Agent.Protocol;

namespace CursorController.Agent.Hosting;

/// <summary>
/// Konfigurasi dari environment. Tanpa variabel apa pun = app normal. Profil lain (mis. CURSORCTL_PROFILE=e2e)
/// memakai data, identitas, dan port terpisah untuk uji end-to-end (protocol/E2E.md).
/// </summary>
public sealed record Profile(
    string Suffix,
    int Port,
    string? AdvertisedAddress,
    bool AutoApprove,
    string? PairingFile,
    bool LogInput,
    string? FocusFile)
{
    public static Profile Current { get; } = FromEnvironment(Environment.GetEnvironmentVariables());

    public static Profile FromEnvironment(IDictionary env)
    {
        string? Get(string name) => env[name] is string value && value.Length > 0 ? value : null;
        // Mode tanpa UI tidak pernah memakai data app normal: tanpa nama profil, dipakai profil "e2e".
        var name = Get("CURSORCTL_PROFILE") ?? (Get("CURSORCTL_E2E_PAIRING_FILE") is null ? null : "e2e");
        return new Profile(
            Suffix: name is null ? "" : $"-{name}",
            Port: int.TryParse(Get("CURSORCTL_PORT"), out var port) ? port : AgentConstants.DefaultPort,
            AdvertisedAddress: Get("CURSORCTL_E2E_ADDRESS"),
            AutoApprove: Get("CURSORCTL_E2E_AUTO_APPROVE") == "1",
            PairingFile: Get("CURSORCTL_E2E_PAIRING_FILE"),
            LogInput: Get("CURSORCTL_E2E_LOG") == "1",
            FocusFile: Get("CURSORCTL_E2E_FOCUS_FILE"));
    }

    /// <summary>Profil uji berjalan tanpa jendela dan tidak pernah menyuntikkan input sungguhan.</summary>
    public bool Headless => PairingFile is not null;

    public string SupportDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), $"Cursor Controller{Suffix}");

    /// <summary>Nama identitas TLS sekaligus common name sertifikat.</summary>
    public string IdentityName => $"Cursor Controller{Suffix}";
}

/// <summary>Log ke stderr, hanya untuk profil uji (CURSORCTL_E2E_LOG=1).</summary>
public sealed class AgentLog(bool enabled)
{
    // Selalu UTF-8: di Windows, Console.Error memakai code page konsol, jadi emoji di log input menjadi "??".
    private static readonly Lazy<TextWriter> Writer = new(() =>
        TextWriter.Synchronized(new StreamWriter(Console.OpenStandardError(), new UTF8Encoding(false)) { AutoFlush = true }));

    public void Write(string text)
    {
        if (enabled) Writer.Value.WriteLine(text);
    }
}
