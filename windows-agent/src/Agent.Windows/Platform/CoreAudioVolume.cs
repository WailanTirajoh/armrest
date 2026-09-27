using System;
using System.Runtime.InteropServices;
using Armrest.Agent.Volume;

namespace Armrest.Agent.Windows.Platform;

/// <summary>
/// Volume perangkat output default Windows lewat Core Audio (<c>IAudioEndpointVolume</c>), sama dengan slider volume
/// di taskbar. Setiap panggilan membuka perangkat default saat itu, jadi mengikuti headset yang baru dicolok, dan objek
/// COM-nya tidak pernah dipakai lintas thread.
/// </summary>
internal sealed class CoreAudioVolume : IVolumeControl
{
    private const int DataFlowRender = 0; // eRender
    private const int RoleMultimedia = 1; // eMultimedia
    private const int ClsctxAll = 0x17; // CLSCTX_ALL

    public VolumeState Read()
    {
        if (Open() is not { } endpoint) return new VolumeState(null, false);
        try
        {
            return Current(endpoint);
        }
        finally
        {
            Marshal.ReleaseComObject(endpoint);
        }
    }

    public void Apply(VolumeCommand command)
    {
        if (Open() is not { } endpoint) return;
        try
        {
            var current = Current(endpoint);
            var target = VolumeMath.Apply(command, current);
            var context = Guid.Empty;
            if (target.Level is { } level && level != current.Level)
            {
                Marshal.ThrowExceptionForHR(endpoint.SetMasterVolumeLevelScalar((float)level, ref context));
            }
            if (target.Muted != current.Muted) Marshal.ThrowExceptionForHR(endpoint.SetMute(target.Muted, ref context));
        }
        finally
        {
            Marshal.ReleaseComObject(endpoint);
        }
    }

    private static VolumeState Current(IAudioEndpointVolume endpoint)
    {
        Marshal.ThrowExceptionForHR(endpoint.GetMasterVolumeLevelScalar(out var level));
        Marshal.ThrowExceptionForHR(endpoint.GetMute(out var muted));
        return new VolumeState(level, muted);
    }

    /// <summary>null kalau tidak ada perangkat output sama sekali (mis. server atau VM tanpa audio).</summary>
    private static IAudioEndpointVolume? Open()
    {
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
        try
        {
            if (enumerator.GetDefaultAudioEndpoint(DataFlowRender, RoleMultimedia, out var device) != 0 || device is null) return null;
            try
            {
                var iid = typeof(IAudioEndpointVolume).GUID;
                return device.Activate(ref iid, ClsctxAll, 0, out var endpoint) == 0 ? (IAudioEndpointVolume)endpoint : null;
            }
            finally
            {
                Marshal.ReleaseComObject(device);
            }
        }
        finally
        {
            Marshal.ReleaseComObject(enumerator);
        }
    }

    [ComImport]
    [Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator
    {
    }

    // Urutan method mengikuti vtable di mmdeviceapi.h / endpointvolume.h; method sesudah yang dipakai boleh dihilangkan.
    [ComImport]
    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig]
        int EnumAudioEndpoints(int dataFlow, int stateMask, out nint devices);

        [PreserveSig]
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice? device);
    }

    [ComImport]
    [Guid("D666063F-1587-4E43-81F1-B948E807363F")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig]
        int Activate(ref Guid iid, int clsCtx, nint activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object endpoint);
    }

    [ComImport]
    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioEndpointVolume
    {
        [PreserveSig]
        int RegisterControlChangeNotify(nint notify);

        [PreserveSig]
        int UnregisterControlChangeNotify(nint notify);

        [PreserveSig]
        int GetChannelCount(out uint count);

        [PreserveSig]
        int SetMasterVolumeLevel(float levelDb, ref Guid eventContext);

        [PreserveSig]
        int SetMasterVolumeLevelScalar(float level, ref Guid eventContext);

        [PreserveSig]
        int GetMasterVolumeLevel(out float levelDb);

        [PreserveSig]
        int GetMasterVolumeLevelScalar(out float level);

        [PreserveSig]
        int SetChannelVolumeLevel(uint channel, float levelDb, ref Guid eventContext);

        [PreserveSig]
        int SetChannelVolumeLevelScalar(uint channel, float level, ref Guid eventContext);

        [PreserveSig]
        int GetChannelVolumeLevel(uint channel, out float levelDb);

        [PreserveSig]
        int GetChannelVolumeLevelScalar(uint channel, out float level);

        [PreserveSig]
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid eventContext);

        [PreserveSig]
        int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }
}
