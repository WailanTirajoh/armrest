namespace CursorController.Agent.Protocol;

/// <summary>Nilai yang harus sama persis dengan protocol/PROTOCOL.md, agent Mac, dan app HP.</summary>
public static class AgentConstants
{
    public const int ProtocolVersion = 1;
    public const int DefaultPort = 47810;
    public const string BonjourServiceType = "_cursorctl._tcp";
}

/// <summary>Platform komputer di <c>auth_result</c> dan TXT Bonjour (<c>os</c>).</summary>
public static class AgentPlatform
{
    public const string MacOS = "macos";
    public const string Windows = "windows";
}

/// <summary>Nama fitur opsional di <c>auth_result</c>.</summary>
public static class AgentFeature
{
    public const string Focus = "focus";
    public const string Screen = "screen";
    public const string Volume = "volume";
}
