using System;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Armrest.Agent.Hosting;
using Armrest.Agent.Session;
using QRCoder;

namespace Armrest.Agent.Windows.Ui;

/// <summary>Jendela QR pairing (mockup B3). Ditutup user = QR dibatalkan.</summary>
internal sealed class PairingWindow : Window
{
    private readonly AgentHost host;
    private readonly StackPanel content = new() { Margin = new Thickness(24) };
    private string? renderedUri;
    private ImageSource? qr;
    private bool closingFromHost;

    public PairingWindow(AgentHost host)
    {
        this.host = host;
        Title = "Tambah perangkat";
        Icon = Theme.AppIcon;
        SizeToContent = SizeToContent.WidthAndHeight;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Content = content;
        host.Changed += Render;
        Closed += (_, _) =>
        {
            host.Changed -= Render;
            if (!closingFromHost) host.ClosePairing();
        };
        Render();
    }

    /// <summary>Pairing selesai (diizinkan) atau dibatalkan dari panel.</summary>
    public void CloseFromHost()
    {
        closingFromHost = true;
        Close();
    }

    private void Render()
    {
        if (host.Pairing is not { } pairing) return;
        content.Children.Clear();
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        row.Children.Add(Qr(pairing));
        var details = new StackPanel { Margin = new Thickness(20, 0, 0, 0), Width = 290 };
        if (pairing.Expired)
        {
            details.Children.Add(Theme.Text("QR sudah tidak berlaku", 14, bold: true));
            details.Children.Add(Theme.Text("Setiap QR hanya berlaku 120 detik dan sekali pakai. Buat QR baru untuk memasangkan HP.", 13, color: Theme.Secondary));
        }
        else
        {
            details.Children.Add(Theme.Step(1, "Buka Armrest di HP."));
            details.Children.Add(Theme.Step(2, "Ketuk Pair komputer baru."));
            details.Children.Add(Theme.Step(3, "Arahkan kamera ke QR ini, lalu klik Izinkan di komputer."));
            var remaining = Theme.Text($"Berlaku {pairing.SecondsRemaining / 60}:{pairing.SecondsRemaining % 60:00}", 12, bold: true);
            details.Children.Add(remaining);
            details.Children.Add(new ProgressBar
            {
                Maximum = PairingTokens.Ttl.TotalSeconds,
                Value = pairing.SecondsRemaining,
                Height = 6,
                Margin = new Thickness(0, 6, 0, 12),
                Foreground = Theme.Accent,
            });
        }
        details.Children.Add(Theme.Text($"{host.HostName} · {pairing.Address}", 12, color: Theme.Secondary));
        var fingerprint = host.Fingerprint.Length > 12 ? $"{host.Fingerprint[..6]}…{host.Fingerprint[^6..]}" : host.Fingerprint;
        var fingerprintText = Theme.Text($"Sidik jari: {fingerprint}", 11, color: Theme.Secondary);
        fingerprintText.FontFamily = new FontFamily("Consolas");
        details.Children.Add(fingerprintText);
        row.Children.Add(details);
        content.Children.Add(row);

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        buttons.Children.Add(Theme.Button(pairing.Expired ? "Buat QR baru" : "Buat ulang", host.ShowPairing, primary: pairing.Expired));
        var close = Theme.Button("Tutup", Close);
        close.IsCancel = true;
        close.Margin = new Thickness(8, 0, 0, 0);
        buttons.Children.Add(close);
        content.Children.Add(buttons);
    }

    private UIElement Qr(PairingDisplay pairing)
    {
        if (renderedUri != pairing.Uri)
        {
            renderedUri = pairing.Uri;
            using var generator = new QRCodeGenerator();
            using var data = generator.CreateQrCode(pairing.Uri, QRCodeGenerator.ECCLevel.M);
            var png = new PngByteQRCode(data).GetGraphic(8);
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = new MemoryStream(png);
            image.EndInit();
            image.Freeze();
            qr = image;
        }
        var grid = new Grid { Width = 200, Height = 200 };
        grid.Children.Add(new Image { Source = qr, Opacity = pairing.Expired ? 0.12 : 1 });
        RenderOptions.SetBitmapScalingMode(grid, BitmapScalingMode.NearestNeighbor);
        if (pairing.Expired)
        {
            grid.Children.Add(new Border
            {
                Background = Theme.WarningFill,
                CornerRadius = new CornerRadius(10),
                Padding = new Thickness(10, 4, 10, 4),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Child = Theme.Text("Kedaluwarsa", 13, bold: true, color: Theme.Warning),
            });
        }
        return new Border { Background = Brushes.White, CornerRadius = new CornerRadius(12), Padding = new Thickness(8), Child = grid };
    }
}

/// <summary>Dialog "Izinkan?" untuk perangkat baru (mockup B4). Ditutup tanpa memilih = ditolak.</summary>
internal sealed class ApprovalWindow : Window
{
    private bool decided;

    public ApprovalWindow(PendingDevice device, Action<bool> decide)
    {
        Title = "Izinkan perangkat";
        Icon = Theme.AppIcon;
        Width = 360;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Topmost = true;
        void Decide(bool approved)
        {
            decided = true;
            decide(approved);
        }
        Closed += (_, _) =>
        {
            if (!decided) decide(false);
        };

        var content = new StackPanel { Margin = new Thickness(22) };
        var icon = Theme.Icon(64);
        icon.Margin = new Thickness(0, 0, 0, 12);
        content.Children.Add(icon);
        content.Children.Add(Centered(Theme.Text($"Izinkan “{device.Name}” mengontrol komputer ini?", 14, bold: true)));
        content.Children.Add(Centered(Theme.Text(
            $"{device.Name} akan bisa menggerakkan kursor, klik, scroll, mengetik, dan melihat layar komputer ini. Akses bisa dicabut kapan saja dari ikon Armrest di tray.",
            12,
            color: Theme.Secondary)));
        var key = Centered(Theme.Text($"Kunci perangkat: {KeySummary(device.PublicKey)}", 11, color: Theme.Secondary));
        key.FontFamily = new FontFamily("Consolas");
        content.Children.Add(key);
        var allow = Theme.Button("Izinkan", () => Decide(true), primary: true);
        allow.Margin = new Thickness(0, 16, 0, 8);
        allow.HorizontalAlignment = HorizontalAlignment.Stretch;
        var deny = Theme.Button("Tolak", () => Decide(false));
        deny.IsCancel = true;
        deny.HorizontalAlignment = HorizontalAlignment.Stretch;
        content.Children.Add(allow);
        content.Children.Add(deny);
        Content = content;
    }

    /// <summary>Ditutup karena keputusan sudah dibuat di tempat lain.</summary>
    public void CloseQuietly()
    {
        decided = true;
        Close();
    }

    private static TextBlock Centered(TextBlock text)
    {
        text.TextAlignment = TextAlignment.Center;
        text.Margin = new Thickness(0, 0, 0, 8);
        return text;
    }

    private static string KeySummary(byte[] publicKey)
    {
        var hex = SHA256.HashData(publicKey).Select(b => b.ToString("X2")).ToArray();
        return $"{hex[0]}{hex[1]} {hex[2]}{hex[3]} … {hex[28]}{hex[29]} {hex[30]}{hex[31]}";
    }
}

/// <summary>Sapaan saat pertama jalan: app ada di tray, cara pairing, dan soal firewall.</summary>
internal sealed class WelcomeWindow : Window
{
    public WelcomeWindow(Action pair)
    {
        Title = "Armrest";
        Icon = Theme.AppIcon;
        Width = 480;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;

        var content = new StackPanel { Margin = new Thickness(28) };
        content.Children.Add(Theme.Text("Armrest sudah berjalan", 20, bold: true));
        var intro = Theme.Text(
            "App ini ada di area notifikasi (tray) di pojok kanan bawah, bukan di taskbar. HP Android kamu bisa jadi touchpad dan keyboard untuk komputer ini.",
            13,
            color: Theme.Secondary);
        intro.Margin = new Thickness(0, 8, 0, 16);
        content.Children.Add(intro);
        content.Children.Add(Theme.Step(1, "Klik Tambah perangkat di bawah, atau ikon Armrest di tray."));
        content.Children.Add(Theme.Step(2, "Scan QR-nya dengan app Armrest di HP, lalu klik Izinkan."));
        content.Children.Add(Theme.Step(3, "Kalau Windows Firewall bertanya, izinkan untuk jaringan Private."));
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 12, 0, 0) };
        var later = Theme.Button("Nanti saja", Close);
        later.IsCancel = true;
        buttons.Children.Add(later);
        var add = Theme.Button("Tambah perangkat…", () =>
        {
            Close();
            pair();
        }, primary: true);
        add.Margin = new Thickness(8, 0, 0, 0);
        buttons.Children.Add(add);
        content.Children.Add(buttons);
        Content = content;
    }
}
