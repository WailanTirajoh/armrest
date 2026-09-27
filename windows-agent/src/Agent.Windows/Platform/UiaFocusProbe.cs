using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Windows.Automation;
using CursorController.Agent.Windows.Native;

namespace CursorController.Agent.Windows.Platform;

/// <summary>
/// Apakah elemen yang fokus menerima ketikan, lewat UI Automation. Dipanggil dari thread latar FocusMonitor.
/// </summary>
internal static class UiaFocusProbe
{
    /// <summary>
    /// Editor berbasis Electron yang berganti ke mode screen reader kalau aksesibilitasnya ikut menyala. Sama
    /// seperti di agent Mac, keyboard tidak terbuka otomatis di app ini.
    /// </summary>
    private static readonly HashSet<string> SkippedProcesses = new(StringComparer.OrdinalIgnoreCase)
    {
        "Code", "Code - Insiders", "VSCodium", "Cursor", "Windsurf",
    };

    public static bool TextInputFocused()
    {
        var window = Win32.GetForegroundWindow();
        if (window == 0) return false;
        Win32.GetWindowThreadProcessId(window, out var processId);
        if (processId == Environment.ProcessId || SkippedProcesses.Contains(ProcessName(processId))) return false;
        try
        {
            if (AutomationElement.FocusedElement is not { } element) return false;
            var type = element.Current.ControlType;
            if (type == ControlType.Edit) return !ValueReadOnly(element) ?? true;
            if (type == ControlType.ComboBox) return ValueReadOnly(element) == false;
            if (type == ControlType.Document) return SelectionEditable(element);
            return false;
        }
        catch (Exception e) when (e is ElementNotAvailableException or InvalidOperationException or System.Runtime.InteropServices.COMException)
        {
            return false;
        }
    }

    /// <summary>null kalau elemen tidak punya ValuePattern.</summary>
    private static bool? ValueReadOnly(AutomationElement element) =>
        element.TryGetCurrentPattern(ValuePattern.Pattern, out var pattern) ? ((ValuePattern)pattern).Current.IsReadOnly : null;

    /// <summary>Dokumen (Word, halaman web) dianggap kolom teks hanya kalau posisi kursor tulisnya bisa diedit.</summary>
    private static bool SelectionEditable(AutomationElement element)
    {
        if (!element.TryGetCurrentPattern(TextPattern.Pattern, out var pattern)) return false;
        var selection = ((TextPattern)pattern).GetSelection();
        return selection.Length > 0 && selection[0].GetAttributeValue(TextPattern.IsReadOnlyAttribute) is false;
    }

    private static string ProcessName(uint processId)
    {
        try
        {
            using var process = Process.GetProcessById((int)processId);
            return process.ProcessName;
        }
        catch (Exception e) when (e is ArgumentException or InvalidOperationException)
        {
            return "";
        }
    }
}
