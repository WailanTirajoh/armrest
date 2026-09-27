using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using Armrest.Agent.Hosting;
using Armrest.Agent.Protocol;
using Armrest.Agent.Windows.Native;

namespace Armrest.Agent.Windows.Platform;

/// <summary>
/// Iklan <c>_armrest._tcp</c> lewat DNS-SD bawaan Windows (dnsapi, Windows 10 1809+). Kalau gagal, HP masih bisa
/// tersambung lewat alamat terakhir atau "Sambungkan via IP".
/// </summary>
internal sealed unsafe class DnsSdAdvertiser : IServiceAdvertiser
{
    // Memori request dan instance dipakai dnsapi secara asinkron, jadi dibiarkan hidup sampai proses selesai.
    private nint request;

    public void Advertise(string instanceName, int port, IReadOnlyDictionary<string, string> txt)
    {
        if (request != 0) return;
        try
        {
            var keys = txt.Keys.Select(Marshal.StringToHGlobalUni).ToArray();
            var values = txt.Values.Select(Marshal.StringToHGlobalUni).ToArray();
            var service = $"{instanceName.Replace('.', '-')}.{AgentConstants.BonjourServiceType}.local";
            var instance = Win32.DnsServiceConstructInstance(
                service, $"{Dns.GetHostName()}.local", 0, 0, (ushort)port, 0, 0, (uint)keys.Length, keys, values);
            if (instance == 0) return;
            var memory = Marshal.AllocHGlobal(sizeof(Win32.DnsServiceRegisterRequest));
            *(Win32.DnsServiceRegisterRequest*)memory = new Win32.DnsServiceRegisterRequest
            {
                Version = Win32.DnsQueryRequestVersion1,
                ServiceInstance = instance,
                RegisterCompletionCallback = (nint)(delegate* unmanaged[Stdcall]<uint, nint, nint, void>)&Completed,
            };
            if (Win32.DnsServiceRegister(memory, 0) == Win32.DnsRequestPending) request = memory;
        }
        catch (Exception e) when (e is EntryPointNotFoundException or DllNotFoundException)
        {
            // Windows terlalu lama untuk DNS-SD bawaan.
        }
    }

    public void Dispose()
    {
        if (request == 0) return;
        Win32.DnsServiceDeRegister(request, 0);
        request = 0;
    }

    [UnmanagedCallersOnly(CallConvs = [typeof(CallConvStdcall)])]
    private static void Completed(uint status, nint context, nint instance)
    {
        if (instance != 0) Win32.DnsServiceFreeInstance(instance);
    }
}
