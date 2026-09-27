using System;
using CursorController.Agent.Protocol;
using CursorController.Agent.Streaming;

namespace CursorController.Agent.Windows.Screen;

/// <summary>Tangkapan GDI + encoder Media Foundation.</summary>
internal sealed class WindowsScreenBackend : IScreenBackend
{
    public IScreenSource CreateSource() => new GdiScreenSource();

    public IVideoEncoder CreateEncoder(PixelSize size, Action<EncodedFrame> output) => new MediaFoundationEncoder(size, output);
}
