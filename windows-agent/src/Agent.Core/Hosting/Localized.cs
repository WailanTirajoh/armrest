using System.Globalization;

namespace Armrest.Agent.Hosting;

/// <summary>
/// Teks UI dalam bahasa tampilan Windows: Inggris sebagai dasar, Indonesia kalau Windows memakai Bahasa Indonesia.
/// Kedua teks ditulis berdampingan di pemanggil, jadi tidak ada terjemahan yang bisa tertinggal.
/// </summary>
public static class Localized
{
    public static string T(string english, string indonesian) => Pick(CultureInfo.CurrentUICulture, english, indonesian);

    public static string Pick(CultureInfo culture, string english, string indonesian) =>
        IsIndonesian(culture) ? indonesian : english;

    /// <summary>"id" (dan kode lama "in") untuk semua varian, mis. id-ID.</summary>
    public static bool IsIndonesian(CultureInfo culture) => culture.TwoLetterISOLanguageName is "id" or "in";
}
