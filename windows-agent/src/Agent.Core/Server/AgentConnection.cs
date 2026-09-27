using System.Net.Sockets;
using System.Net.WebSockets;
using System.Threading.Channels;
using CursorController.Agent.Protocol;
using CursorController.Agent.Session;

namespace CursorController.Agent.Server;

/// <summary>
/// Satu koneksi WebSocket dari HP: meneruskan frame ke <see cref="SessionMachine"/> dan menjalankan aksinya.
/// State hanya diubah di dispatcher server; baca, tulis, dan heartbeat berjalan di thread pool.
/// </summary>
internal sealed class AgentConnection
{
    public static readonly TimeSpan HeartbeatInterval = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan HeartbeatTimeout = TimeSpan.FromSeconds(15);
    private const int MaxMessageBytes = 64 * 1024;

    private readonly WebSocket socket;
    private readonly TcpClient client;
    private readonly AgentServer server;
    private readonly SessionMachine machine;
    private readonly Channel<Outgoing> outgoing = Channel.CreateUnbounded<Outgoing>(new UnboundedChannelOptions { SingleReader = true });
    private readonly CancellationTokenSource cancellation = new();
    private DateTime lastReceived = DateTime.UtcNow;
    private bool closing;
    private bool finished;
    private int aborted;

    public AgentConnection(WebSocket socket, TcpClient client, AgentServer server)
    {
        this.socket = socket;
        this.client = client;
        this.server = server;
        machine = new SessionMachine(server);
    }

    public Guid Id { get; } = Guid.NewGuid();
    public TrustedDevice? Device { get; private set; }
    /// <summary>HP meminta status fokus kolom teks (<c>focusUpdates</c> di pesan settings).</summary>
    public bool WantsFocusUpdates { get; private set; }
    /// <summary>HP sedang meminta video layar.</summary>
    public bool ScreenRequested { get; private set; }

    public void Start()
    {
        _ = ReceiveLoopAsync();
        _ = SendLoopAsync();
        _ = HeartbeatLoopAsync();
    }

    public void Send(ControlMessage message) => outgoing.Writer.TryWrite(new Outgoing(WebSocketMessageType.Text, message.Encode()));

    public void SendBinary(byte[] data) => outgoing.Writer.TryWrite(new Outgoing(WebSocketMessageType.Binary, data));

    /// <summary>Tutup dengan close frame setelah pesan yang sudah antre terkirim.</summary>
    public void Close()
    {
        if (closing) return;
        closing = true;
        machine.MarkClosed();
        outgoing.Writer.TryWrite(Outgoing.CloseFrame);
        outgoing.Writer.TryComplete();
    }

    public void Abort()
    {
        if (Interlocked.Exchange(ref aborted, 1) == 1) return;
        cancellation.Cancel();
        socket.Abort();
        client.Dispose();
    }

    private void Received(WebSocketMessageType type, byte[] data)
    {
        if (closing || finished) return;
        lastReceived = DateTime.UtcNow;
        Perform(type == WebSocketMessageType.Text ? machine.HandleText(data) : machine.HandleBinary(data));
    }

    private void Perform(IReadOnlyList<SessionAction> actions)
    {
        foreach (var action in actions)
        {
            switch (action)
            {
                case SessionAction.Send send:
                    Send(send.Message);
                    break;
                case SessionAction.RequestApproval request:
                    if (server.OnApprovalRequest is not { } ask)
                    {
                        Perform(machine.ApprovalDecided(false));
                        break;
                    }
                    ask(request.Device, approved => server.Post(() =>
                    {
                        if (!closing && !finished) Perform(machine.ApprovalDecided(approved));
                    }));
                    break;
                case SessionAction.TrustDevice trust:
                    server.Trust(trust.Device);
                    break;
                case SessionAction.Authenticated authenticated:
                    Device = authenticated.Device;
                    server.ConnectionAuthenticated(this, authenticated.Device);
                    break;
                case SessionAction.Input input:
                    if (Device is not null) server.OnInput?.Invoke(Device.Id, input.Message);
                    break;
                case SessionAction.Settings settings:
                    if (Device is not null) server.OnSettings?.Invoke(Device.Id, settings.Sensitivity, settings.ScrollSpeed);
                    if (settings.FocusUpdates != WantsFocusUpdates)
                    {
                        WantsFocusUpdates = settings.FocusUpdates;
                        server.FocusSubscriptionChanged(this);
                    }
                    break;
                case SessionAction.Screen screen:
                    if (Device is null) break;
                    ScreenRequested = screen.Request is not null;
                    server.OnScreenRequest?.Invoke(Id, Device, screen.Request);
                    break;
                case SessionAction.ScreenAck ack:
                    server.OnScreenAck?.Invoke(Id, ack.Seq);
                    break;
                case SessionAction.Close:
                    Close();
                    break;
            }
        }
    }

    private async Task ReceiveLoopAsync()
    {
        var buffer = new byte[16 * 1024];
        using var message = new MemoryStream();
        try
        {
            while (true)
            {
                var result = await socket.ReceiveAsync(buffer, cancellation.Token);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    server.Post(Close);
                    break;
                }
                message.Write(buffer, 0, result.Count);
                if (message.Length > MaxMessageBytes) break;
                if (!result.EndOfMessage) continue;
                var data = message.ToArray();
                message.SetLength(0);
                var type = result.MessageType;
                server.Post(() => Received(type, data));
            }
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or IOException or ObjectDisposedException)
        {
        }
        finally
        {
            server.Post(Finish);
        }
    }

    private async Task SendLoopAsync()
    {
        try
        {
            await foreach (var item in outgoing.Reader.ReadAllAsync(cancellation.Token))
            {
                if (item.IsClose)
                {
                    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                    await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, timeout.Token);
                    break;
                }
                await socket.SendAsync(item.Data, item.Type, endOfMessage: true, cancellation.Token);
            }
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or IOException or ObjectDisposedException)
        {
        }
        // Beri waktu HP membalas close frame, lalu putuskan.
        await Task.Delay(TimeSpan.FromSeconds(2)).ConfigureAwait(false);
        Abort();
    }

    private async Task HeartbeatLoopAsync()
    {
        using var timer = new PeriodicTimer(HeartbeatInterval);
        try
        {
            while (await timer.WaitForNextTickAsync(cancellation.Token)) server.Post(Heartbeat);
        }
        catch (OperationCanceledException)
        {
        }
    }

    private void Heartbeat()
    {
        if (closing || finished) return;
        if (DateTime.UtcNow - lastReceived > HeartbeatTimeout)
        {
            Abort();
            return;
        }
        Send(new ControlMessage.Ping(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
    }

    private void Finish()
    {
        if (finished) return;
        finished = true;
        machine.MarkClosed();
        outgoing.Writer.TryComplete();
        Abort();
        server.ConnectionEnded(this);
    }

    private readonly record struct Outgoing(WebSocketMessageType Type, byte[] Data, bool IsClose = false)
    {
        public static Outgoing CloseFrame => new(WebSocketMessageType.Close, [], true);
    }
}
