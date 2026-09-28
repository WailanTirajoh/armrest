using System;
using System.Diagnostics;
using System.Threading.Tasks;
using Armrest.Agent.Protocol;
using Armrest.Agent.Windows.Native;

namespace Armrest.Agent.Windows.Platform;

/// <summary>Tidur lewat <c>SetSuspendState</c>; mulai ulang dan matikan lewat <c>shutdown.exe</c>, tanpa jeda.</summary>
internal static class WindowsPower
{
    public static void Perform(PowerAction action)
    {
        switch (action)
        {
            case PowerAction.Sleep:
                // Di thread latar: SetSuspendState baru kembali setelah komputer bangun lagi.
                Task.Run(() => Win32.SetSuspendState(false, false, false));
                break;
            case PowerAction.Restart:
                Run("/r /t 0");
                break;
            case PowerAction.Shutdown:
                Run("/s /t 0");
                break;
        }
    }

    private static void Run(string arguments)
    {
        try
        {
            Process.Start(new ProcessStartInfo("shutdown.exe", arguments) { CreateNoWindow = true, UseShellExecute = false })?.Dispose();
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            // shutdown.exe tidak ada atau diblokir kebijakan; tidak ada yang bisa dilakukan selain diam.
        }
    }
}
