using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Text.Json.Nodes;
using CursorController.Agent.Focus;
using CursorController.Agent.Protocol;
using CursorController.Agent.Server;
using CursorController.Agent.Session;
using CursorController.Agent.Streaming;
using CursorController.Agent.Volume;

namespace CursorController.Agent.Hosting;

/// <summary>Menjalankan input dari HP di komputer. Dipanggil di dispatcher.</summary>
public interface IInputSink
{
    void Apply(double sensitivity, double scrollSpeed);
    void Handle(InputMessage message);
    /// <summary>Sesi berakhir: lepas tombol yang masih ditahan supaya tidak tersangkut di tengah drag.</summary>
    void ReleaseButtons();
}

/// <summary>Iklan DNS-SD (<c>_cursorctl._tcp</c>) supaya HP menemukan komputer ini.</summary>
public interface IServiceAdvertiser : IDisposable
{
    void Advertise(string instanceName, int port, IReadOnlyDictionary<string, string> txt);
}

public sealed record PairingDisplay(string Uri, string Address, int SecondsRemaining)
{
    public bool Expired => SecondsRemaining <= 0;
}

public sealed class AgentHostOptions
{
    public required Profile Profile { get; init; }
    public required IDispatcher Dispatcher { get; init; }
    public required X509Certificate2 Certificate { get; init; }
    public required string HostName { get; init; }
    public string Platform { get; init; } = AgentPlatform.Windows;
    /// <summary>null: input hanya dicatat (profil uji tidak pernah menggerakkan kursor sungguhan).</summary>
    public IInputSink? Input { get; init; }
    /// <summary>Pemeriksa kolom teks yang fokus; null = fitur keyboard otomatis tidak diumumkan.</summary>
    public Func<bool>? FocusProbe { get; init; }
    /// <summary>Tangkapan layar dan encoder; null = fitur layar tidak diumumkan.</summary>
    public IScreenBackend? Screen { get; init; }
    /// <summary>Volume output; null = fitur volume tidak diumumkan. Profil uji selalu memakai volume tiruan.</summary>
    public IVolumeControl? Volume { get; init; }
    public IServiceAdvertiser? Advertiser { get; init; }
}

/// <summary>
/// Inti agent tanpa UI: server, pairing, fokus kolom teks, dan aliran layar (padanan AppModel di agent Mac).
/// Semua method dan event berjalan di dispatcher.
/// </summary>
public sealed class AgentHost
{
    private readonly AgentHostOptions options;
    private readonly Profile profile;
    private readonly AgentLog log;
    private readonly PairingTokens tokens = new();
    private readonly TrustedDeviceStore store;
    private readonly FocusMonitor? focusMonitor;
    private readonly VolumeMonitor? volumeMonitor;
    private readonly Dictionary<Guid, (string DeviceId, ScreenStreamer Streamer)> streamers = [];
    private AgentServer? server;
    private Timer? ticker;
    private Action<bool>? pendingDecision;

    public AgentHost(AgentHostOptions options)
    {
        this.options = options;
        profile = options.Profile;
        log = new AgentLog(profile.LogInput);
        HostId = LoadOrCreateHostId(profile.SupportDirectory);
        store = new TrustedDeviceStore(Path.Combine(profile.SupportDirectory, "trusted-devices.json"));
        Fingerprint = AuthCrypto.Fingerprint(options.Certificate.RawData);
        if (FocusProbeFor(options) is { } probe) focusMonitor = new FocusMonitor(probe, options.Dispatcher.Post);
        // Profil uji tidak pernah mengubah volume sungguhan.
        if ((profile.Headless ? new InMemoryVolume() : options.Volume) is { } volume) volumeMonitor = new VolumeMonitor(volume, options.Dispatcher.Post);
    }

    public string HostId { get; }
    public string HostName => options.HostName;
    public string Fingerprint { get; }
    public IReadOnlyList<TrustedDevice> Devices => store.Devices;
    public IReadOnlyList<TrustedDevice> ActiveDevices { get; private set; } = [];
    public ServerState ServerState { get; private set; } = new ServerState.Starting();
    public PairingDisplay? Pairing { get; private set; }
    /// <summary>Id perangkat yang sedang melihat layar komputer.</summary>
    public IReadOnlySet<string> ScreenViewers { get; private set; } = new HashSet<string>();

    public string? ListeningAddress => ServerState is ServerState.Listening { Port: var port }
        ? $"{profile.AdvertisedAddress ?? LocalNetwork.PrimaryIPv4() ?? "?"}:{port}"
        : null;

    /// <summary>State apa pun berubah; UI cukup menggambar ulang.</summary>
    public event Action? Changed;
    /// <summary>QR baru dibuat; UI membuka jendela pairing.</summary>
    public event Action? PairingShown;
    /// <summary>Pairing selesai atau dibatalkan; UI menutup jendela pairing.</summary>
    public event Action? PairingClosed;
    /// <summary>Perangkat baru minta izin. UI menampilkan dialog dan memanggil <see cref="Decide"/>.</summary>
    public event Action<PendingDevice>? ApprovalRequested;
    /// <summary>Dialog izin sudah tidak relevan (sudah diputuskan atau koneksinya putus).</summary>
    public event Action? ApprovalClosed;

    public void Start()
    {
        var features = new List<string>();
        if (focusMonitor is not null) features.Add(AgentFeature.Focus);
        if (options.Screen is not null) features.Add(AgentFeature.Screen);
        if (volumeMonitor is not null) features.Add(AgentFeature.Volume);
        log.Write($"features: {string.Join(", ", features)}");
        var server = new AgentServer(
            new AgentServer.Configuration(profile.Port, HostId, HostName, options.Certificate, features, options.Platform),
            store,
            tokens,
            options.Dispatcher);
        Wire(server);
        this.server = server;
        server.Start();
        ticker = new Timer(_ => options.Dispatcher.Post(Tick), null, TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(1));
    }

    public void Stop()
    {
        ticker?.Dispose();
        focusMonitor?.Stop();
        volumeMonitor?.Stop();
        server?.Stop();
        options.Advertiser?.Dispose();
    }

    // Pairing

    public void ShowPairing()
    {
        if (ServerState is not ServerState.Listening { Port: var port }) return;
        var token = tokens.Issue();
        var address = profile.AdvertisedAddress ?? LocalNetwork.PrimaryIPv4() ?? "0.0.0.0";
        var uri = new PairingUri(HostId, HostName, address, port, token.Value, Fingerprint).ToString();
        Pairing = new PairingDisplay(uri, $"{address}:{port}", tokens.SecondsRemaining());
        if (profile.PairingFile is { } file) File.WriteAllText(file, uri);
        Changed?.Invoke();
        PairingShown?.Invoke();
    }

    public void ClosePairing()
    {
        tokens.Invalidate();
        Pairing = null;
        Changed?.Invoke();
        PairingClosed?.Invoke();
    }

    /// <summary>Keputusan user untuk perangkat yang sedang minta izin.</summary>
    public void Decide(bool approved)
    {
        var decide = pendingDecision;
        pendingDecision = null;
        ApprovalClosed?.Invoke();
        decide?.Invoke(approved);
        if (approved) ClosePairing();
    }

    // Perangkat

    public void Disconnect(string deviceId) => server?.Disconnect(deviceId);

    public void Revoke(string deviceId) => server?.Revoke(deviceId);

    private void Wire(AgentServer server)
    {
        server.OnStateChange = state =>
        {
            log.Write($"server: {state}");
            ServerState = state;
            Changed?.Invoke();
            if (state is not ServerState.Listening { Port: var port }) return;
            options.Advertiser?.Advertise(HostName, port, new Dictionary<string, string>
            {
                ["hostId"] = HostId,
                ["v"] = AgentConstants.ProtocolVersion.ToString(),
                ["os"] = options.Platform,
            });
            // Uji end-to-end: langsung terbitkan QR begitu server siap.
            if (profile.PairingFile is not null) ShowPairing();
        };
        server.OnApprovalRequest = (pending, decide) =>
        {
            if (profile.AutoApprove)
            {
                decide(true);
                ClosePairing();
                return;
            }
            pendingDecision?.Invoke(false);
            pendingDecision = decide;
            ApprovalRequested?.Invoke(pending);
        };
        server.OnDevicesChanged = () => Changed?.Invoke();
        server.OnSessionsChanged = active =>
        {
            ActiveDevices = active;
            Changed?.Invoke();
        };
        server.OnSessionEnded = _ => options.Input?.ReleaseButtons();
        server.OnSettings = (_, sensitivity, scrollSpeed) => options.Input?.Apply(sensitivity, scrollSpeed);
        server.OnFocusInterestChanged = interested =>
        {
            if (interested) focusMonitor?.Start();
            else focusMonitor?.Stop();
        };
        if (focusMonitor is not null)
        {
            focusMonitor.OnChange = focused =>
            {
                log.Write($"focus: {focused.ToString().ToLowerInvariant()}");
                server.UpdateTextFocus(focused);
            };
        }
        server.OnVolumeInterestChanged = interested =>
        {
            if (interested) volumeMonitor?.Start();
            else volumeMonitor?.Stop();
        };
        if (volumeMonitor is not null) volumeMonitor.OnChange = server.UpdateVolume;
        server.OnVolume = (_, command) =>
        {
            log.Write($"volume: {command}");
            volumeMonitor?.Apply(command);
        };
        server.OnScreenRequest = ScreenRequested;
        server.OnScreenAck = (connection, seq) =>
        {
            if (streamers.TryGetValue(connection, out var entry)) entry.Streamer.Ack(seq);
        };
        server.OnInput = (_, message) =>
        {
            log.Write($"input: {message}");
            // Profil uji hanya mencatat input, tidak pernah mengetik atau menggerakkan kursor sungguhan.
            if (!profile.Headless) options.Input?.Handle(message);
        };
    }

    private void Tick()
    {
        if (Pairing is not { } pairing) return;
        var remaining = tokens.SecondsRemaining();
        if (remaining == pairing.SecondsRemaining) return;
        Pairing = pairing with { SecondsRemaining = remaining };
        Changed?.Invoke();
    }

    // Layar

    private void ScreenRequested(Guid connection, TrustedDevice device, ScreenRequest? request)
    {
        if (request is null)
        {
            if (streamers.Remove(connection, out var ended))
            {
                ended.Streamer.Stop();
                log.Write("screen: stop");
            }
            UpdateScreenViewers();
            return;
        }
        if (streamers.TryGetValue(connection, out var existing))
        {
            existing.Streamer.Update(request);
            return;
        }
        if (options.Screen is not { } backend) return;
        // Profil uji memakai pola uji: tidak pernah menangkap layar sungguhan.
        var source = profile.Headless ? new TestPatternSource() : backend.CreateSource();
        var dispatcher = options.Dispatcher;
        var streamer = new ScreenStreamer(
            source,
            backend.CreateEncoder,
            packet => dispatcher.Post(() => server?.SendScreen(packet, connection)),
            status => dispatcher.Post(() => ScreenStatusChanged(status, connection)),
            error => log.Write($"screen: error {error}"),
            cursor => dispatcher.Post(() => server?.SendScreenCursor(cursor.X, cursor.Y, connection)));
        streamers[connection] = (device.Id, streamer);
        streamer.Start(request);
        UpdateScreenViewers();
        log.Write($"screen: start {request.MaxWidth}x{request.MaxHeight}");
    }

    private void ScreenStatusChanged(ScreenStatus status, Guid connection)
    {
        log.Write($"screen: {status.WireName()}");
        server?.SendScreenStatus(status, connection);
        // Streamer yang gagal sudah berhenti sendiri; permintaan berikutnya dari HP membuat yang baru.
        if (status != ScreenStatus.Streaming && streamers.Remove(connection)) UpdateScreenViewers();
    }

    private void UpdateScreenViewers()
    {
        var viewers = streamers.Values.Select(s => s.DeviceId).ToHashSet();
        if (viewers.SetEquals(ScreenViewers)) return;
        ScreenViewers = viewers;
        Changed?.Invoke();
    }

    /// <summary>Profil uji tidak membaca app lain: status fokus diambil dari file, atau selalu "bukan kolom teks".</summary>
    private static Func<bool>? FocusProbeFor(AgentHostOptions options)
    {
        if (!options.Profile.Headless) return options.FocusProbe;
        if (options.Profile.FocusFile is not { } file) return () => false;
        return () =>
        {
            try
            {
                return File.ReadAllText(file).Trim() == "1";
            }
            catch (IOException)
            {
                return false;
            }
        };
    }

    private static string LoadOrCreateHostId(string directory)
    {
        var path = Path.Combine(directory, "settings.json");
        try
        {
            if (JsonNode.Parse(File.ReadAllText(path))?["hostId"]?.GetValue<string>() is { Length: > 0 } saved) return saved;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException or InvalidOperationException)
        {
        }
        var hostId = Guid.NewGuid().ToString().ToLowerInvariant();
        Directory.CreateDirectory(directory);
        File.WriteAllText(path, new JsonObject { ["hostId"] = hostId }.ToJsonString());
        return hostId;
    }
}
