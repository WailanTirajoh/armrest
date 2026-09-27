using System.Collections.Concurrent;

namespace Armrest.Agent.Server;

/// <summary>Antrean tempat semua state agent diubah, satu aksi sekali jalan. Di app: thread UI (WPF Dispatcher).</summary>
public interface IDispatcher
{
    void Post(Action action);
}

/// <summary>Thread khusus yang menjalankan aksi berurutan. Dipakai mode tanpa UI dan test.</summary>
public sealed class SerialQueue : IDispatcher, IDisposable
{
    private readonly BlockingCollection<Action> actions = [];
    private readonly Thread thread;

    public SerialQueue(string name)
    {
        thread = new Thread(Run) { IsBackground = true, Name = name };
        thread.Start();
    }

    /// <summary>Error yang lolos dari sebuah aksi; antrean tetap berjalan.</summary>
    public event Action<Exception>? UnhandledException;

    public void Post(Action action)
    {
        try
        {
            actions.Add(action);
        }
        catch (InvalidOperationException)
        {
            // Antrean sudah ditutup.
        }
    }

    /// <summary>Jalankan di antrean dan tunggu hasilnya. Jangan dipanggil dari antrean itu sendiri.</summary>
    public T Invoke<T>(Func<T> func)
    {
        if (Environment.CurrentManagedThreadId == thread.ManagedThreadId) return func();
        var done = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        Post(() =>
        {
            try
            {
                done.SetResult(func());
            }
            catch (Exception e)
            {
                done.SetException(e);
            }
        });
        return done.Task.GetAwaiter().GetResult();
    }

    public void Invoke(Action action) => Invoke(() =>
    {
        action();
        return true;
    });

    public void Dispose() => actions.CompleteAdding();

    private void Run()
    {
        foreach (var action in actions.GetConsumingEnumerable())
        {
            try
            {
                action();
            }
            catch (Exception e)
            {
                UnhandledException?.Invoke(e);
            }
        }
    }
}
