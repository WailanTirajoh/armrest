using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Armrest.Agent.Hosting;
using Armrest.Agent.Server;
using Armrest.Agent.Session;
using Armrest.Agent.Windows.Platform;

namespace Armrest.Agent.Windows.Ui;

/// <summary>Panel dari ikon tray (padanan panel menu bar di Mac). Tertutup sendiri saat klik di luar.</summary>
internal sealed class PanelWindow : Window
{
    private readonly AgentHost host;
    private readonly Action pair;
    private readonly Action quit;
    private readonly StackPanel content = new() { Margin = new Thickness(16) };

    public PanelWindow(AgentHost host, Action pair, Action quit)
    {
        this.host = host;
        this.pair = pair;
        this.quit = quit;
        Title = "Armrest";
        Width = 340;
        SizeToContent = SizeToContent.Height;
        WindowStyle = WindowStyle.None;
        ResizeMode = ResizeMode.NoResize;
        ShowInTaskbar = false;
        Topmost = true;
        BorderThickness = new Thickness(1);
        BorderBrush = SystemColors.ActiveBorderBrush;
        Content = content;
        Deactivated += (_, _) => Hide();
        host.Changed += () =>
        {
            if (IsVisible) Render();
        };
    }

    public void Toggle()
    {
        if (IsVisible)
        {
            Hide();
            return;
        }
        Render();
        Show();
        UpdateLayout();
        // Pojok kanan bawah area kerja, di atas taskbar.
        var area = SystemParameters.WorkArea;
        Left = area.Right - ActualWidth - 12;
        Top = area.Bottom - ActualHeight - 12;
        Activate();
    }

    private void Render()
    {
        content.Children.Clear();
        content.Children.Add(Header());
        if (host.ServerState is ServerState.Failed { Message: var message })
        {
            content.Children.Add(Spaced(Theme.Text($"Server tidak bisa berjalan: {message}", 12, color: Theme.Warning)));
        }
        foreach (var device in host.ActiveDevices) content.Children.Add(ActiveSession(device));

        var add = Theme.Button("Tambah perangkat…", () =>
        {
            Hide();
            pair();
        }, primary: true);
        add.HorizontalAlignment = HorizontalAlignment.Stretch;
        add.IsEnabled = host.ListeningAddress is not null;
        content.Children.Add(Spaced(add, 12));

        content.Children.Add(Spaced(Theme.Text("PERANGKAT TERPERCAYA", 11, bold: true, color: Theme.Secondary), 16));
        if (host.Devices.Count == 0) content.Children.Add(Spaced(Theme.Text("Belum ada perangkat.", 12, color: Theme.Secondary), 6));
        foreach (var device in host.Devices) content.Children.Add(DeviceRow(device));

        content.Children.Add(Spaced(new Separator(), 12));
        var autostart = new CheckBox { Content = "Buka saat login", IsChecked = Autostart.Enabled, Margin = new Thickness(0, 8, 0, 0) };
        autostart.Click += (_, _) => Autostart.Enabled = autostart.IsChecked == true;
        content.Children.Add(autostart);
        content.Children.Add(Spaced(new Separator(), 8));
        var exit = Theme.Button("Keluar", quit);
        exit.HorizontalAlignment = HorizontalAlignment.Left;
        content.Children.Add(Spaced(exit, 8));
    }

    private UIElement Header()
    {
        var text = new StackPanel { Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(Theme.Text("Armrest", 13, bold: true));
        text.Children.Add(Theme.Text(Status(), 12, color: Theme.Secondary));
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(Theme.Icon(32));
        row.Children.Add(text);
        return row;
    }

    private string Status()
    {
        if (host.ActiveDevices.Count > 0) return $"{host.ActiveDevices.Count} sesi aktif";
        if (host.Pairing is not null) return "Menunggu pairing…";
        return host.ListeningAddress is { } address ? $"Siap · {address}" : "Menyiapkan…";
    }

    private UIElement ActiveSession(TrustedDevice device)
    {
        var text = new StackPanel();
        text.Children.Add(Theme.Text($"Dikontrol oleh {device.Name}", 13, bold: true, color: Theme.Warning));
        var detail = host.ScreenViewers.Contains(device.Id) ? "Sesi aktif · melihat layar" : "Sesi aktif";
        text.Children.Add(Theme.Text(detail, 12, color: Theme.Warning));
        var disconnect = Theme.Button("Putuskan", () => host.Disconnect(device.Id));
        disconnect.VerticalAlignment = VerticalAlignment.Center;
        var grid = Row(text, disconnect);
        return Spaced(new Border { Background = Theme.WarningFill, CornerRadius = new CornerRadius(8), Padding = new Thickness(10), Child = grid }, 10);
    }

    private UIElement DeviceRow(TrustedDevice device)
    {
        var active = host.ActiveDevices.Any(d => d.Id == device.Id);
        var text = new StackPanel();
        text.Children.Add(Theme.Text(device.Name, 13, bold: true));
        text.Children.Add(Theme.Text(active ? "Aktif sekarang" : LastSeen(device), 12, bold: active, color: active ? Theme.Warning : Theme.Secondary));
        var revoke = Theme.Button("Cabut…", () => Revoke(device));
        revoke.VerticalAlignment = VerticalAlignment.Center;
        return Spaced(Row(text, revoke), 8);
    }

    private void Revoke(TrustedDevice device)
    {
        var answer = MessageBox.Show(
            this,
            $"{device.Name} tidak bisa mengontrol komputer ini lagi sampai dipasangkan ulang lewat QR.",
            $"Cabut akses {device.Name}?",
            MessageBoxButton.OKCancel,
            MessageBoxImage.Warning);
        if (answer == MessageBoxResult.OK) host.Revoke(device.Id);
    }

    private static string LastSeen(TrustedDevice device)
    {
        if (device.LastSeen is not { } seen) return "Belum pernah terhubung";
        var ago = DateTimeOffset.UtcNow - seen;
        return ago.TotalMinutes < 1 ? "Terakhir: baru saja"
            : ago.TotalHours < 1 ? $"Terakhir: {(int)ago.TotalMinutes} menit lalu"
            : ago.TotalDays < 1 ? $"Terakhir: {(int)ago.TotalHours} jam lalu"
            : $"Terakhir: {(int)ago.TotalDays} hari lalu";
    }

    private static Grid Row(UIElement left, UIElement right)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(right, 1);
        grid.Children.Add(left);
        grid.Children.Add(right);
        return grid;
    }

    private static T Spaced<T>(T element, double top = 8) where T : FrameworkElement
    {
        element.Margin = new Thickness(0, top, 0, 0);
        return element;
    }
}
