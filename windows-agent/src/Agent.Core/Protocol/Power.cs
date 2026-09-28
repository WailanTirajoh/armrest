namespace Armrest.Agent.Protocol;

/// <summary>Perintah daya dari HP (pesan <c>power</c>).</summary>
public enum PowerAction
{
    Sleep,
    Restart,
    Shutdown,
}

public static class PowerActionExtensions
{
    public static string WireName(this PowerAction action) => action switch
    {
        PowerAction.Sleep => "sleep",
        PowerAction.Restart => "restart",
        _ => "shutdown",
    };

    public static PowerAction? FromWireName(string? name) => name switch
    {
        "sleep" => PowerAction.Sleep,
        "restart" => PowerAction.Restart,
        "shutdown" => PowerAction.Shutdown,
        _ => null,
    };
}

public static class MacAddress
{
    /// <summary>6 byte → <c>a4:83:e7:12:34:56</c>. null kalau panjangnya bukan 6 atau semuanya 0.</summary>
    public static string? Format(byte[] bytes) =>
        bytes.Length == 6 && bytes.Any(b => b != 0) ? string.Join(":", bytes.Select(b => b.ToString("x2"))) : null;
}
