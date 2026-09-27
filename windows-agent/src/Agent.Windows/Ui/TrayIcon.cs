using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using Microsoft.Win32;
using Forms = System.Windows.Forms;
using static Armrest.Agent.Hosting.Localized;

namespace Armrest.Agent.Windows.Ui;

internal enum TrayState
{
    Idle,
    Pairing,
    Controlled,
}

/// <summary>Ikon di area notifikasi: klik kiri membuka panel, klik kanan membuka menu singkat.</summary>
internal sealed class TrayIcon : IDisposable
{
    private readonly Forms.NotifyIcon icon;
    private readonly Dictionary<(TrayState, bool), Icon> cache = [];

    public TrayIcon(Action open, Action pair, Action quit)
    {
        var menu = new Forms.ContextMenuStrip();
        menu.Items.Add(T("Open Armrest", "Buka Armrest"), null, (_, _) => open());
        menu.Items.Add(T("Add device…", "Tambah perangkat…"), null, (_, _) => pair());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(T("Quit", "Keluar"), null, (_, _) => quit());
        icon = new Forms.NotifyIcon { Text = "Armrest", ContextMenuStrip = menu };
        icon.MouseClick += (_, e) =>
        {
            if (e.Button == Forms.MouseButtons.Left) open();
        };
        Update(TrayState.Idle, "Armrest");
        icon.Visible = true;
    }

    public void Update(TrayState state, string tooltip)
    {
        var dark = TaskbarIsDark();
        if (!cache.TryGetValue((state, dark), out var image))
        {
            image = Draw(state, dark);
            cache[(state, dark)] = image;
        }
        icon.Icon = image;
        // Batas tooltip NotifyIcon 63 karakter.
        icon.Text = tooltip.Length > 63 ? tooltip[..63] : tooltip;
    }

    public void Dispose()
    {
        icon.Visible = false;
        icon.Dispose();
        foreach (var image in cache.Values) image.Dispose();
    }

    private static bool TaskbarIsDark()
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return key?.GetValue("SystemUsesLightTheme") is not 1;
    }

    /// <summary>Glyph pointer + HP (sama dengan ikon app), dengan titik status saat pairing atau dikontrol.</summary>
    private static Icon Draw(TrayState state, bool dark)
    {
        var size = Math.Max(16, Forms.SystemInformation.SmallIconSize.Width);
        using var bitmap = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bitmap))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            var scale = size / 24f;
            var color = dark ? Color.White : Color.FromArgb(0x1C, 0x1C, 0x1C);
            using var pen = new Pen(color, 2.1f * scale) { LineJoin = LineJoin.Round, StartCap = LineCap.Round, EndCap = LineCap.Round };
            PointF P(float x, float y) => new(x * scale, y * scale);
            g.DrawPolygon(pen, [P(2.5f, 2f), P(13f, 6.6f), P(8.6f, 8.3f), P(6.9f, 12.8f)]);
            using var phone = RoundedRect(new RectangleF(13.5f * scale, 10.5f * scale, 8f * scale, 12f * scale), 1.8f * scale);
            g.DrawPath(pen, phone);
            if (state != TrayState.Idle)
            {
                var badge = state == TrayState.Pairing ? Color.FromArgb(0x3B, 0x82, 0xF6) : Color.FromArgb(0xF0, 0x8A, 0x24);
                using var brush = new SolidBrush(badge);
                g.FillEllipse(brush, 14f * scale, 0, 10f * scale, 10f * scale);
            }
        }
        var handle = bitmap.GetHicon();
        return Icon.FromHandle(handle);
    }

    private static GraphicsPath RoundedRect(RectangleF rect, float radius)
    {
        var path = new GraphicsPath();
        var d = radius * 2;
        path.AddArc(rect.X, rect.Y, d, d, 180, 90);
        path.AddArc(rect.Right - d, rect.Y, d, d, 270, 90);
        path.AddArc(rect.Right - d, rect.Bottom - d, d, d, 0, 90);
        path.AddArc(rect.X, rect.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }
}
