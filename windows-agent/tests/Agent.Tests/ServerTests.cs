using System.Collections.Concurrent;
using System.Net.WebSockets;
using Armrest.Agent.Protocol;
using Armrest.Agent.Server;
using Armrest.Agent.Session;
using Armrest.Agent.Volume;

namespace Armrest.Agent.Tests;

/// <summary>Server sungguhan (ws:// tanpa TLS) dan HP tiruan lewat ClientWebSocket.</summary>
internal sealed class ServerHarness : IDisposable
{
    private readonly SerialQueue queue = new("test-agent");

    public ServerHarness(bool approve, IReadOnlyList<string>? features = null)
    {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString(), "devices.json");
        Server = new AgentServer(
            new AgentServer.Configuration(0, "host-1", "PC Test", null, features, AgentPlatform.Windows),
            new TrustedDeviceStore(path),
            new PairingTokens(),
            queue);
        Server.OnApprovalRequest = (_, decide) => decide(approve);
        Server.OnInput = (_, message) => Inputs.Add(message);
        Server.OnFocusInterestChanged = FocusInterest.Add;
        Server.OnScreenRequest = (connection, _, request) => Screen.Add((connection, request, null));
        Server.OnScreenAck = (connection, seq) => Screen.Add((connection, null, seq));
        Server.OnVolume = (_, command) => VolumeCommands.Add(command);
        Server.OnVolumeInterestChanged = VolumeInterest.Add;
    }

    public AgentServer Server { get; }
    public BlockingCollection<InputMessage> Inputs { get; } = [];
    public BlockingCollection<bool> FocusInterest { get; } = [];
    public BlockingCollection<(Guid Connection, ScreenRequest? Request, uint? Ack)> Screen { get; } = [];
    public BlockingCollection<VolumeCommand> VolumeCommands { get; } = [];
    public BlockingCollection<bool> VolumeInterest { get; } = [];

    public int Start()
    {
        queue.Invoke(Server.Start);
        return ((ServerState.Listening)queue.Invoke(() => Server.State)).Port;
    }

    public void Run(Action action) => queue.Invoke(action);

    public T Run<T>(Func<T> func) => queue.Invoke(func);

    public void Dispose()
    {
        queue.Invoke(Server.Stop);
        queue.Dispose();
    }
}

public class ServerTests
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);

    private static async Task SendJson(ClientWebSocket socket, ControlMessage message) =>
        await socket.SendAsync(message.Encode(), WebSocketMessageType.Text, true, CancellationToken.None);

    private static async Task<(WebSocketMessageType Type, byte[] Data)> Receive(ClientWebSocket socket)
    {
        using var cancel = new CancellationTokenSource(Timeout);
        var buffer = new byte[64 * 1024];
        using var message = new MemoryStream();
        while (true)
        {
            var result = await socket.ReceiveAsync(buffer, cancel.Token);
            message.Write(buffer, 0, result.Count);
            if (result.EndOfMessage) return (result.MessageType, message.ToArray());
        }
    }

    private static async Task<ControlMessage?> ReceiveJson(ClientWebSocket socket)
    {
        while (true)
        {
            var (type, data) = await Receive(socket);
            if (type != WebSocketMessageType.Text) continue;
            var message = ControlMessage.Decode(data);
            if (message is ControlMessage.Ping) continue; // heartbeat, tidak relevan untuk test
            return message;
        }
    }

    private static async Task<byte[]> ReceiveBinary(ClientWebSocket socket)
    {
        while (true)
        {
            var (type, data) = await Receive(socket);
            if (type == WebSocketMessageType.Binary) return data;
        }
    }

    /// <summary>HP tiruan: pairing lewat QR, lalu autentikasi di koneksi yang sama.</summary>
    private static async Task<ClientWebSocket> PairPhone(ServerHarness harness, int port, IReadOnlyList<string>? features = null)
    {
        var token = harness.Run(() => harness.Server.Tokens.Issue().Value);
        using var key = new PhoneKey();
        var deviceId = Guid.NewGuid().ToString();
        var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{port}"), CancellationToken.None);
        await SendJson(socket, new ControlMessage.Hello(1, deviceId, "pair"));
        await SendJson(socket, new ControlMessage.PairRequest(token, "Pixel 8", key.PublicKeyBase64));
        Assert.Equal(new ControlMessage.PairResult(true, "host-1", "PC Test", null), await ReceiveJson(socket));
        var challenge = Assert.IsType<ControlMessage.Challenge>(await ReceiveJson(socket));
        var payload = AuthCrypto.Payload(Convert.FromBase64String(challenge.Nonce), "host-1", deviceId);
        await SendJson(socket, new ControlMessage.Auth(key.Sign(payload)));
        Assert.Equal(new ControlMessage.AuthResult(true, null, features ?? [], AgentPlatform.Windows), await ReceiveJson(socket));
        return socket;
    }

    [Fact]
    public async Task PairingAndInputOverRealWebSocket()
    {
        using var harness = new ServerHarness(approve: true);
        var port = harness.Start();
        using var phone = await PairPhone(harness, port);
        Assert.Single(harness.Run(() => harness.Server.Devices.Devices));

        await phone.SendAsync(new InputMessage.Click(MouseButton.Left, 2).Encode(), WebSocketMessageType.Binary, true, CancellationToken.None);
        Assert.True(harness.Inputs.TryTake(out var input, Timeout));
        Assert.Equal(new InputMessage.Click(MouseButton.Left, 2), input);
    }

    [Fact]
    public async Task DeniedPairingDoesNotTrustDevice()
    {
        using var harness = new ServerHarness(approve: false);
        var port = harness.Start();
        var token = harness.Run(() => harness.Server.Tokens.Issue().Value);
        using var key = new PhoneKey();
        using var socket = new ClientWebSocket();
        await socket.ConnectAsync(new Uri($"ws://127.0.0.1:{port}"), CancellationToken.None);
        await SendJson(socket, new ControlMessage.Hello(1, "d-2", "pair"));
        await SendJson(socket, new ControlMessage.PairRequest(token, "Pixel", key.PublicKeyBase64));
        Assert.Equal(new ControlMessage.PairResult(false, null, null, "denied"), await ReceiveJson(socket));
        Assert.Empty(harness.Run(() => harness.Server.Devices.Devices));
    }

    [Fact]
    public async Task TextFocusReachesPhonesThatAskForIt()
    {
        using var harness = new ServerHarness(approve: true, ["focus"]);
        var port = harness.Start();
        using var first = await PairPhone(harness, port, ["focus"]);
        await SendJson(first, new ControlMessage.Settings(1, 1, true));
        Assert.True(harness.FocusInterest.TryTake(out var interested, Timeout) && interested);

        harness.Run(() =>
        {
            harness.Server.UpdateTextFocus(true);
            harness.Server.UpdateTextFocus(true); // tidak berubah, jadi tidak dikirim ulang
            harness.Server.UpdateTextFocus(false);
        });
        Assert.Equal(new ControlMessage.Focus(true), await ReceiveJson(first));
        Assert.Equal(new ControlMessage.Focus(false), await ReceiveJson(first));

        // HP yang baru meminta langsung menerima status saat ini.
        harness.Run(() => harness.Server.UpdateTextFocus(true));
        Assert.Equal(new ControlMessage.Focus(true), await ReceiveJson(first));
        using var second = await PairPhone(harness, port, ["focus"]);
        await SendJson(second, new ControlMessage.Settings(1, 1, true));
        Assert.Equal(new ControlMessage.Focus(true), await ReceiveJson(second));

        await SendJson(first, new ControlMessage.Settings(1, 1, false));
        await SendJson(second, new ControlMessage.Settings(1, 1, false));
        Assert.True(harness.FocusInterest.TryTake(out var stillInterested, Timeout) && !stillInterested);
    }

    [Fact]
    public async Task ScreenRequestsReachAppAndFramesReachPhone()
    {
        using var harness = new ServerHarness(approve: true, ["screen"]);
        var port = harness.Start();
        var phone = await PairPhone(harness, port, ["screen"]);
        await SendJson(phone, new ControlMessage.Screen(new ScreenRequest(1920, 1080)));
        Assert.True(harness.Screen.TryTake(out var request, Timeout));
        Assert.Equal(new ScreenRequest(1920, 1080), request.Request);

        var packet = new ScreenPacket.Frame(1, true, [0, 0, 0, 1, 0x65, 0x88]).Encode();
        harness.Run(() => harness.Server.SendScreen(packet, request.Connection));
        Assert.Equal(packet, await ReceiveBinary(phone));
        await SendJson(phone, new ControlMessage.ScreenAck(1));
        Assert.True(harness.Screen.TryTake(out var ack, Timeout));
        Assert.Equal((request.Connection, (ScreenRequest?)null, (uint?)1), ack);

        // Koneksi putus: app diberi tahu supaya tangkapan layar berhenti.
        phone.Abort();
        phone.Dispose();
        Assert.True(harness.Screen.TryTake(out var ended, Timeout));
        Assert.Equal((request.Connection, (ScreenRequest?)null, (uint?)null), ended);
    }

    [Fact]
    public async Task PhoneCloseIsAnsweredWithCloseFrameWhileFramesAreQueued()
    {
        using var harness = new ServerHarness(approve: true, ["screen"]);
        var port = harness.Start();
        using var phone = await PairPhone(harness, port, ["screen"]);
        await SendJson(phone, new ControlMessage.Screen(new ScreenRequest(1920, 1080)));
        Assert.True(harness.Screen.TryTake(out var request, Timeout));

        // Antrean kirim agent masih berisi frame video saat HP menutup koneksi.
        var packet = new ScreenPacket.Frame(1, false, new byte[256 * 1024]).Encode();
        harness.Run(() =>
        {
            for (var i = 0; i < 40; i++) harness.Server.SendScreen(packet, request.Connection);
        });
        using var cancel = new CancellationTokenSource(Timeout);
        await phone.CloseAsync(WebSocketCloseStatus.NormalClosure, null, cancel.Token);
        Assert.Equal(WebSocketCloseStatus.NormalClosure, phone.CloseStatus);
    }

    [Fact]
    public async Task VolumeCommandsReachAppAndStatusReachesPhonesThatAskForIt()
    {
        using var harness = new ServerHarness(approve: true, ["volume"]);
        var port = harness.Start();
        using var phone = await PairPhone(harness, port, ["volume"]);
        await SendJson(phone, new ControlMessage.VolumeMessage(new VolumeCommand.Step(1)));
        Assert.True(harness.VolumeCommands.TryTake(out var command, Timeout));
        Assert.Equal(new VolumeCommand.Step(1), command);

        await SendJson(phone, new ControlMessage.Settings(1, 1, false, true));
        Assert.True(harness.VolumeInterest.TryTake(out var interested, Timeout) && interested);
        harness.Run(() =>
        {
            harness.Server.UpdateVolume(new VolumeState(0.5, false));
            harness.Server.UpdateVolume(new VolumeState(0.5, false)); // tidak berubah, jadi tidak dikirim ulang
            harness.Server.UpdateVolume(new VolumeState(0.5625, true));
        });
        Assert.Equal(new ControlMessage.VolumeStatus(new VolumeState(0.5, false)), await ReceiveJson(phone));
        Assert.Equal(new ControlMessage.VolumeStatus(new VolumeState(0.5625, true)), await ReceiveJson(phone));

        await SendJson(phone, new ControlMessage.Settings(1, 1, false, false));
        Assert.True(harness.VolumeInterest.TryTake(out var stillInterested, Timeout) && !stillInterested);
    }

    [Fact]
    public async Task ScreenCursorReachesThePhone()
    {
        using var harness = new ServerHarness(approve: true, ["screen"]);
        var port = harness.Start();
        using var phone = await PairPhone(harness, port, ["screen"]);
        await SendJson(phone, new ControlMessage.Screen(new ScreenRequest(1920, 1080, cursor: true)));
        Assert.True(harness.Screen.TryTake(out var request, Timeout));
        Assert.True(request.Request!.Cursor);
        harness.Run(() => harness.Server.SendScreenCursor(0.25, 0.725, request.Connection));
        Assert.Equal(new ControlMessage.ScreenCursor(0.25, 0.725), await ReceiveJson(phone));
    }
}

