using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using CursorController.Agent.Protocol;
using CursorController.Agent.Session;
using CursorController.Agent.Volume;

namespace CursorController.Agent.Server;

public abstract record ServerState
{
    public sealed record Starting : ServerState
    {
        public override string ToString() => "starting";
    }

    public sealed record Listening(int Port) : ServerState
    {
        public override string ToString() => $"listening(port: {Port})";
    }

    public sealed record Failed(string Message) : ServerState
    {
        public override string ToString() => $"failed({Message})";
    }
}

/// <summary>
/// Server WebSocket (TLS) untuk HP. Semua method publik dan semua callback berjalan di <see cref="IDispatcher"/>.
/// </summary>
public sealed class AgentServer : ISessionEnvironment
{
    public sealed record Configuration(
        int Port,
        string HostId,
        string HostName,
        X509Certificate2? TlsCertificate,
        IReadOnlyList<string>? Features = null,
        string? Platform = null);

    private readonly Configuration configuration;
    private readonly IDispatcher dispatcher;
    private readonly Dictionary<Guid, AgentConnection> connections = [];
    private CancellationTokenSource? cancellation;
    private TcpListener? listener;
    private bool focusInterest;
    private bool? textFocus;
    private bool volumeInterest;
    private VolumeState? volume;

    public AgentServer(Configuration configuration, TrustedDeviceStore devices, PairingTokens tokens, IDispatcher dispatcher)
    {
        this.configuration = configuration;
        this.dispatcher = dispatcher;
        Devices = devices;
        Tokens = tokens;
    }

    public TrustedDeviceStore Devices { get; }
    public PairingTokens Tokens { get; }
    public ServerState State { get; private set; } = new ServerState.Starting();

    /// <summary>Minta keputusan user untuk perangkat baru; panggil callback dengan true (Izinkan) atau false.</summary>
    public Action<PendingDevice, Action<bool>>? OnApprovalRequest { get; set; }
    public Action<string, InputMessage>? OnInput { get; set; }
    public Action<string, double, double>? OnSettings { get; set; }
    public Action? OnDevicesChanged { get; set; }
    public Action<IReadOnlyList<TrustedDevice>>? OnSessionsChanged { get; set; }
    public Action<string>? OnSessionEnded { get; set; }
    public Action<ServerState>? OnStateChange { get; set; }
    /// <summary>true saat mulai ada HP yang meminta status fokus kolom teks, false saat tidak ada lagi.</summary>
    public Action<bool>? OnFocusInterestChanged { get; set; }
    /// <summary>HP mulai (request) atau berhenti (null) melihat layar, per koneksi; null juga saat koneksinya putus.</summary>
    public Action<Guid, TrustedDevice, ScreenRequest?>? OnScreenRequest { get; set; }
    public Action<Guid, uint>? OnScreenAck { get; set; }
    /// <summary>Perintah volume dari HP (id perangkat, perintah).</summary>
    public Action<string, VolumeCommand>? OnVolume { get; set; }
    /// <summary>true saat mulai ada HP yang meminta status volume, false saat tidak ada lagi.</summary>
    public Action<bool>? OnVolumeInterestChanged { get; set; }

    public string HostId => configuration.HostId;
    public string HostName => configuration.HostName;
    public IReadOnlyList<string> Features => configuration.Features ?? [];
    public string? Platform => configuration.Platform;

    /// <summary>Perangkat yang sesinya sedang aktif (sudah terautentikasi).</summary>
    public IReadOnlyList<TrustedDevice> ActiveDevices =>
        connections.Values.Select(c => c.Device).OfType<TrustedDevice>().DistinctBy(d => d.Id).ToList();

    public void Start()
    {
        try
        {
            listener = CreateListener(configuration.Port);
            listener.Start();
        }
        catch (SocketException e)
        {
            Update(new ServerState.Failed(e.Message));
            return;
        }
        cancellation = new CancellationTokenSource();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        _ = AcceptLoopAsync(listener, cancellation.Token);
        Update(new ServerState.Listening(port));
    }

    public void Stop()
    {
        cancellation?.Cancel();
        listener?.Stop();
        listener = null;
        foreach (var connection in connections.Values)
        {
            EndScreen(connection);
            connection.Close();
        }
        connections.Clear();
        UpdateFocusInterest();
        UpdateVolumeInterest();
    }

    /// <summary>Putus sesi aktif perangkat tanpa menghapusnya dari daftar terpercaya.</summary>
    public void Disconnect(string deviceId)
    {
        foreach (var connection in connections.Values.Where(c => c.Device?.Id == deviceId).ToList()) connection.Close();
    }

    /// <summary>Revoke: hapus dari daftar terpercaya dan putus sesinya.</summary>
    public void Revoke(string deviceId)
    {
        Devices.Remove(deviceId);
        Disconnect(deviceId);
        OnDevicesChanged?.Invoke();
    }

    /// <summary>Status fokus kolom teks dari pemantau fokus. Dikirim ke HP yang memintanya, hanya kalau berubah.</summary>
    public void UpdateTextFocus(bool focused)
    {
        if (!focusInterest || focused == textFocus) return;
        textFocus = focused;
        foreach (var connection in connections.Values.Where(c => c.WantsFocusUpdates)) connection.Send(new ControlMessage.Focus(focused));
    }

    /// <summary>Status volume dari pemantau volume. Dikirim ke HP yang memintanya, hanya kalau berubah.</summary>
    public void UpdateVolume(VolumeState state)
    {
        if (!volumeInterest || state == volume) return;
        volume = state;
        foreach (var connection in connections.Values.Where(c => c.WantsVolumeUpdates)) connection.Send(new ControlMessage.VolumeStatus(state));
    }

    /// <summary>Posisi kursor di video layar (0–1) untuk satu koneksi yang meminta <c>cursor</c>.</summary>
    public void SendScreenCursor(double x, double y, Guid connection)
    {
        if (connections.TryGetValue(connection, out var target)) target.Send(new ControlMessage.ScreenCursor(x, y));
    }

    /// <summary>Kirim paket video layar ke satu koneksi. Diabaikan kalau koneksinya sudah tidak ada.</summary>
    public void SendScreen(byte[] packet, Guid connection)
    {
        if (connections.TryGetValue(connection, out var target)) target.SendBinary(packet);
    }

    public void SendScreenStatus(ScreenStatus status, Guid connection)
    {
        if (connections.TryGetValue(connection, out var target)) target.Send(new ControlMessage.ScreenStatusMessage(status));
    }

    // ISessionEnvironment

    public PairingTokenCheck CheckPairingToken(string token) => Tokens.Check(token);

    public TrustedDevice? TrustedDevice(string id) => Devices.Device(id);

    public byte[] MakeNonce() => RandomNumberGenerator.GetBytes(32);

    // Dipanggil AgentConnection, selalu di dispatcher.

    internal void Post(Action action) => dispatcher.Post(action);

    internal void ConnectionEnded(AgentConnection connection)
    {
        if (!connections.Remove(connection.Id)) return;
        EndScreen(connection);
        UpdateFocusInterest();
        UpdateVolumeInterest();
        if (connection.Device is { } device)
        {
            OnSessionEnded?.Invoke(device.Id);
            OnSessionsChanged?.Invoke(ActiveDevices);
        }
    }

    internal void ConnectionAuthenticated(AgentConnection connection, TrustedDevice device)
    {
        // Satu sesi aktif per perangkat: koneksi lama dari perangkat yang sama ditutup.
        foreach (var other in connections.Values.Where(c => c.Id != connection.Id && c.Device?.Id == device.Id).ToList()) other.Close();
        Devices.Touch(device.Id, DateTimeOffset.UtcNow);
        OnDevicesChanged?.Invoke();
        OnSessionsChanged?.Invoke(ActiveDevices);
    }

    internal void FocusSubscriptionChanged(AgentConnection connection)
    {
        // HP yang baru meminta langsung menerima status saat ini, kalau sudah diketahui.
        if (connection.WantsFocusUpdates && textFocus is { } focused) connection.Send(new ControlMessage.Focus(focused));
        UpdateFocusInterest();
    }

    internal void VolumeSubscriptionChanged(AgentConnection connection)
    {
        // HP yang baru meminta langsung menerima status saat ini, kalau sudah diketahui.
        if (connection.WantsVolumeUpdates && volume is { } current) connection.Send(new ControlMessage.VolumeStatus(current));
        UpdateVolumeInterest();
    }

    internal void Trust(TrustedDevice device)
    {
        Devices.Upsert(device);
        OnDevicesChanged?.Invoke();
    }

    private void UpdateFocusInterest()
    {
        var interested = connections.Values.Any(c => c.WantsFocusUpdates);
        if (interested == focusInterest) return;
        focusInterest = interested;
        if (!interested) textFocus = null;
        OnFocusInterestChanged?.Invoke(interested);
    }

    private void UpdateVolumeInterest()
    {
        var interested = connections.Values.Any(c => c.WantsVolumeUpdates);
        if (interested == volumeInterest) return;
        volumeInterest = interested;
        if (!interested) volume = null;
        OnVolumeInterestChanged?.Invoke(interested);
    }

    private void EndScreen(AgentConnection connection)
    {
        if (connection.ScreenRequested && connection.Device is { } device) OnScreenRequest?.Invoke(connection.Id, device, null);
    }

    private void Update(ServerState state)
    {
        State = state;
        OnStateChange?.Invoke(state);
    }

    private static TcpListener CreateListener(int port)
    {
        try
        {
            var dualStack = new TcpListener(IPAddress.IPv6Any, port);
            dualStack.Server.DualMode = true;
            return dualStack;
        }
        catch (SocketException)
        {
            return new TcpListener(IPAddress.Any, port);
        }
    }

    private async Task AcceptLoopAsync(TcpListener tcpListener, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await tcpListener.AcceptTcpClientAsync(cancellationToken);
            }
            catch (Exception e) when (e is OperationCanceledException or SocketException or ObjectDisposedException)
            {
                return;
            }
            _ = HandleClientAsync(client);
        }
    }

    private async Task HandleClientAsync(TcpClient client)
    {
        try
        {
            client.NoDelay = true;
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            Stream stream = client.GetStream();
            if (configuration.TlsCertificate is { } certificate)
            {
                var tls = new SslStream(stream, leaveInnerStreamOpen: false);
                await tls.AuthenticateAsServerAsync(
                    new SslServerAuthenticationOptions
                    {
                        ServerCertificate = certificate,
                        EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
                        ClientCertificateRequired = false,
                        CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                    },
                    timeout.Token);
                stream = tls;
            }
            if (!await WebSocketHandshake.AcceptAsync(stream, timeout.Token))
            {
                client.Dispose();
                return;
            }
            var socket = WebSocket.CreateFromStream(stream, new WebSocketCreationOptions { IsServer = true, KeepAliveInterval = TimeSpan.Zero });
            var connection = new AgentConnection(socket, client, this);
            Post(() =>
            {
                if (listener is null)
                {
                    connection.Abort();
                    return;
                }
                connections[connection.Id] = connection;
                connection.Start();
            });
        }
        catch (Exception e) when (e is IOException or AuthenticationException or OperationCanceledException or SocketException or ObjectDisposedException)
        {
            client.Dispose();
        }
    }
}
