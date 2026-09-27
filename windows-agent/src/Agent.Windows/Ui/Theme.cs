using System;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Microsoft.Win32;

namespace Armrest.Agent.Windows.Ui;

/// <summary>Warna dan komponen kecil yang dipakai semua jendela; warnanya sama dengan agent Mac.</summary>
internal static class Theme
{
    public static bool Dark
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("AppsUseLightTheme") is 0;
        }
    }

    public static Brush Accent { get; } = Frozen(Color.FromRgb(0x1F, 0x5F, 0xBF));
    public static Brush Warning => Dark ? Frozen(Color.FromRgb(0xF0, 0xB0, 0x70)) : Frozen(Color.FromRgb(0x9A, 0x4A, 0x06));
    public static Brush WarningFill => Dark ? Frozen(Color.FromRgb(0x3F, 0x2A, 0x14)) : Frozen(Color.FromRgb(0xFB, 0xEB, 0xDC));
    public static Brush Success => Dark ? Frozen(Color.FromRgb(0x7F, 0xD1, 0x9B)) : Frozen(Color.FromRgb(0x1E, 0x7A, 0x3C));
    public static Brush Secondary => Dark ? Frozen(Color.FromRgb(0xA8, 0xA8, 0xA8)) : Frozen(Color.FromRgb(0x5F, 0x5F, 0x5F));

    public static ImageSource AppIcon { get; } = LoadAppIcon();

    public static TextBlock Text(string text, double size = 13, bool bold = false, Brush? color = null) => new()
    {
        Text = text,
        FontSize = size,
        FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal,
        Foreground = color ?? SystemColors.ControlTextBrush,
        TextWrapping = TextWrapping.Wrap,
    };

    public static Button Button(string text, Action onClick, bool primary = false)
    {
        var button = new Button { Content = text, Padding = new Thickness(14, 6, 14, 6), MinWidth = 88, IsDefault = primary };
        if (primary)
        {
            button.Background = Accent;
            button.Foreground = Brushes.White;
        }
        button.Click += (_, _) => onClick();
        return button;
    }

    public static Image Icon(double size) => new() { Source = AppIcon, Width = size, Height = size };

    /// <summary>Lingkaran bernomor untuk langkah-langkah, seperti di agent Mac.</summary>
    public static StackPanel Step(int number, string text)
    {
        var badge = new Border
        {
            Width = 20,
            Height = 20,
            CornerRadius = new CornerRadius(10),
            Background = Accent,
            Margin = new Thickness(0, 1, 10, 0),
            VerticalAlignment = VerticalAlignment.Top,
            Child = new TextBlock
            {
                Text = number.ToString(),
                Foreground = Brushes.White,
                FontSize = 11,
                FontWeight = FontWeights.Bold,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        };
        var body = Text(text);
        body.Width = 250;
        var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 10) };
        row.Children.Add(badge);
        row.Children.Add(body);
        return row;
    }

    private static SolidColorBrush Frozen(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    private static ImageSource LoadAppIcon()
    {
        using var stream = Application.GetResourceStream(new Uri("pack://application:,,,/Assets/AppIcon.ico")).Stream;
        var decoder = new IconBitmapDecoder(stream, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
        BitmapFrame best = decoder.Frames[0];
        foreach (var frame in decoder.Frames)
        {
            if (frame.PixelWidth > best.PixelWidth) best = frame;
        }
        best.Freeze();
        return best;
    }
}
