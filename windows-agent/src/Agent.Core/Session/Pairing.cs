using System.Buffers.Text;
using System.Security.Cryptography;

namespace CursorController.Agent.Session;

public enum PairingTokenCheck
{
    Valid,
    Invalid,
    Expired,
    TooManyAttempts,
}

public static class PairingTokenCheckExtensions
{
    /// <summary>Kode <c>error</c> di <c>pair_result</c>; null kalau valid.</summary>
    public static string? ErrorCode(this PairingTokenCheck check) => check switch
    {
        PairingTokenCheck.Valid => null,
        PairingTokenCheck.Invalid => "token_invalid",
        PairingTokenCheck.Expired => "token_expired",
        _ => "too_many_attempts",
    };
}

public sealed record PairingToken(string Value, DateTimeOffset ExpiresAt);

/// <summary>Token pairing: satu token aktif, TTL 120 detik, sekali pakai, hangus setelah 5 percobaan salah.</summary>
public sealed class PairingTokens(Func<DateTimeOffset>? now = null, Func<byte[]>? random = null)
{
    public static readonly TimeSpan Ttl = TimeSpan.FromSeconds(120);
    public const int MaxFailures = 5;

    private readonly Func<DateTimeOffset> now = now ?? (() => DateTimeOffset.UtcNow);
    private readonly Func<byte[]> random = random ?? (() => RandomNumberGenerator.GetBytes(32));
    private int failures;

    public PairingToken? Current { get; private set; }

    public PairingToken Issue()
    {
        Current = new PairingToken(Base64Url.EncodeToString(random()), now() + Ttl);
        failures = 0;
        return Current;
    }

    public void Invalidate() => Current = null;

    public int SecondsRemaining() =>
        Current is null ? 0 : Math.Max(0, (int)Math.Ceiling((Current.ExpiresAt - now()).TotalSeconds));

    /// <summary>Token yang cocok langsung hangus, apa pun keputusan user setelahnya.</summary>
    public PairingTokenCheck Check(string token)
    {
        if (Current is null) return PairingTokenCheck.Invalid;
        if (now() >= Current.ExpiresAt)
        {
            Current = null;
            return PairingTokenCheck.Expired;
        }
        if (CryptographicOperations.FixedTimeEquals(System.Text.Encoding.UTF8.GetBytes(token), System.Text.Encoding.UTF8.GetBytes(Current.Value)))
        {
            Current = null;
            return PairingTokenCheck.Valid;
        }
        if (++failures >= MaxFailures)
        {
            Current = null;
            return PairingTokenCheck.TooManyAttempts;
        }
        return PairingTokenCheck.Invalid;
    }
}

/// <summary>Isi QR pairing: cursorctl://pair?h=&amp;n=&amp;a=&amp;t=&amp;fp=</summary>
public sealed record PairingUri(string HostId, string HostName, string Address, int Port, string Token, string Fingerprint)
{
    // Uri.EscapeDataString hanya membiarkan karakter unreserved, sama dengan agent Mac.
    public override string ToString() =>
        "cursorctl://pair?" + string.Join('&',
            $"h={Uri.EscapeDataString(HostId)}",
            $"n={Uri.EscapeDataString(HostName)}",
            $"a={Uri.EscapeDataString($"{Address}:{Port}")}",
            $"t={Uri.EscapeDataString(Token)}",
            $"fp={Uri.EscapeDataString(Fingerprint)}");
}
