using System;
using System.IO;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using Armrest.Agent.Hosting;
using Armrest.Agent.Protocol;
using Armrest.Agent.Server;
using Armrest.Agent.Windows.Platform;
using Armrest.Agent.Windows.Screen;
using Armrest.Agent.Windows.Ui;
using static Armrest.Agent.Hosting.Localized;

namespace Armrest.Agent.Windows;

internal static class Program
{
    [STAThread]
    private static int Main()
    {
        var profile = Profile.Current;
        using var instance = new Mutex(initiallyOwned: true, $@"Local\Armrest{profile.Suffix}", out var first);
        using var showPanel = new EventWaitHandle(false, EventResetMode.AutoReset, $@"Local\Armrest{profile.Suffix}-show");
        if (!first)
        {
            // App sudah berjalan (mis. dibuka lagi dari Start menu): minta panelnya ditampilkan.
            showPanel.Set();
            return 0;
        }
        return new App(profile, showPanel).Run();
    }
}

/// <summary>State agent ada di thread UI WPF.</summary>
internal sealed class WpfDispatcher(Dispatcher dispatcher) : IDispatcher
{
    public void Post(Action action) => dispatcher.BeginInvoke(action);
}

internal sealed class App : Application
{
    private readonly Profile profile;
    private readonly EventWaitHandle showPanel;
    private AgentHost? host;
    private TrayIcon? tray;
    private PanelWindow? panel;
    private PairingWindow? pairingWindow;
    private ApprovalWindow? approvalWindow;

    public App(Profile profile, EventWaitHandle showPanel)
    {
        this.profile = profile;
        this.showPanel = showPanel;
        ShutdownMode = ShutdownMode.OnExplicitShutdown;
    }

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        ThemeMode = ThemeMode.System;
        var dispatcher = new WpfDispatcher(Dispatcher);
        // Encoder dicoba di thread latar (MTA); Windows tanpa Media Foundation tidak mengumumkan fitur layar.
        var screenAvailable = Task.Run(MediaFoundationEncoder.IsAvailable).GetAwaiter().GetResult();
        host = new AgentHost(new AgentHostOptions
        {
            Profile = profile,
            Dispatcher = dispatcher,
            // Profil uji memakai sertifikat sementara supaya tidak meninggalkan apa pun di certificate store.
            Certificate = profile.Headless ? LocalNetwork.EphemeralCertificate(profile.IdentityName) : IdentityStore.LoadOrCreate(profile.IdentityName),
            HostName = profile.HostName ?? Dns.GetHostName(),
            Platform = AgentPlatform.Windows,
            Input = profile.Headless ? null : new InputInjector(),
            FocusProbe = UiaFocusProbe.TextInputFocused,
            Screen = screenAvailable ? new WindowsScreenBackend() : null,
            Volume = new CoreAudioVolume(),
            Advertiser = new DnsSdAdvertiser(),
        });
        if (!profile.Headless) BuildUi(host);
        host.Start();
        if (!profile.Headless) ListenForShowRequests(dispatcher);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        host?.Stop();
        tray?.Dispose();
        base.OnExit(e);
    }

    private void BuildUi(AgentHost agent)
    {
        panel = new PanelWindow(agent, ShowPairing, Quit);
        tray = new TrayIcon(panel.Toggle, ShowPairing, Quit);
        agent.Changed += () => tray.Update(TrayStateFor(agent), Tooltip(agent));
        agent.PairingShown += () =>
        {
            if (pairingWindow is null)
            {
                pairingWindow = new PairingWindow(agent);
                pairingWindow.Closed += (_, _) => pairingWindow = null;
                pairingWindow.Show();
            }
            pairingWindow.Activate();
        };
        agent.PairingClosed += () => pairingWindow?.CloseFromHost();
        agent.ApprovalRequested += device =>
        {
            approvalWindow?.CloseQuietly();
            approvalWindow = new ApprovalWindow(device, agent.Decide);
            approvalWindow.Closed += (_, _) => approvalWindow = null;
            approvalWindow.Show();
            approvalWindow.Activate();
        };
        agent.ApprovalClosed += () => approvalWindow?.CloseQuietly();

        var welcomed = Path.Combine(profile.SupportDirectory, "welcomed");
        if (!File.Exists(welcomed))
        {
            Directory.CreateDirectory(profile.SupportDirectory);
            File.WriteAllText(welcomed, "");
            new WelcomeWindow(ShowPairing).Show();
        }
    }

    private void ShowPairing() => host?.ShowPairing();

    private void Quit() => Shutdown();

    private void ListenForShowRequests(IDispatcher dispatcher)
    {
        new Thread(() =>
        {
            while (showPanel.WaitOne()) dispatcher.Post(() => panel?.Toggle());
        }) { IsBackground = true, Name = "armrest-show" }.Start();
    }

    private static TrayState TrayStateFor(AgentHost agent) =>
        agent.ActiveDevices.Count > 0 ? TrayState.Controlled : agent.Pairing is not null ? TrayState.Pairing : TrayState.Idle;

    private static string Tooltip(AgentHost agent) =>
        agent.ActiveDevices.Count > 0 ? T($"Armrest · controlled by {agent.ActiveDevices[0].Name}", $"Armrest · dikontrol oleh {agent.ActiveDevices[0].Name}")
        : agent.ListeningAddress is { } address ? $"Armrest · {address}"
        : "Armrest";
}
