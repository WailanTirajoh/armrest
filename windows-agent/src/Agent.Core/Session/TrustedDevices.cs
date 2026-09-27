using System.Text.Json;
using System.Text.Json.Serialization;

namespace Armrest.Agent.Session;

public sealed record TrustedDevice(string Id, string Name, byte[] PublicKey, DateTimeOffset PairedAt, DateTimeOffset? LastSeen = null)
{
    public bool Equals(TrustedDevice? other) =>
        other is not null && Id == other.Id && Name == other.Name && PublicKey.AsSpan().SequenceEqual(other.PublicKey)
        && PairedAt == other.PairedAt && LastSeen == other.LastSeen;

    public override int GetHashCode() => HashCode.Combine(Id, Name, PairedAt, LastSeen);
}

/// <summary>Daftar perangkat terpercaya, disimpan sebagai JSON di folder data user.</summary>
public sealed class TrustedDeviceStore
{
    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly string path;
    private List<TrustedDevice> devices = [];

    public TrustedDeviceStore(string path)
    {
        this.path = path;
        try
        {
            devices = JsonSerializer.Deserialize<List<TrustedDevice>>(File.ReadAllBytes(path), Json) ?? [];
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException)
        {
        }
    }

    public IReadOnlyList<TrustedDevice> Devices => devices;

    public TrustedDevice? Device(string id) => devices.FirstOrDefault(d => d.Id == id);

    public void Upsert(TrustedDevice device)
    {
        var index = devices.FindIndex(d => d.Id == device.Id);
        if (index >= 0) devices[index] = device;
        else devices.Add(device);
        Save();
    }

    public void Remove(string id)
    {
        devices.RemoveAll(d => d.Id == id);
        Save();
    }

    public void Touch(string id, DateTimeOffset at)
    {
        var index = devices.FindIndex(d => d.Id == id);
        if (index < 0) return;
        devices[index] = devices[index] with { LastSeen = at };
        Save();
    }

    private void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + ".tmp";
        File.WriteAllBytes(temp, JsonSerializer.SerializeToUtf8Bytes(devices, Json));
        File.Move(temp, path, overwrite: true);
    }
}
