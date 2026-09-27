using Armrest.Agent.Hosting;
using Armrest.Agent.Protocol;
using Armrest.Agent.Server;

// Agent tanpa UI untuk uji end-to-end di luar Windows: server, pairing, dan fokus dari file (protocol/E2E.md).
// Tidak ada tangkapan layar di sini; fitur "screen" hanya diumumkan agent Windows sungguhan.
var profile = Profile.Current;
if (!profile.Headless)
{
    Console.Error.WriteLine("Jalankan dengan ARMREST_E2E_PAIRING_FILE (lihat protocol/E2E.md).");
    return 2;
}

using var queue = new SerialQueue("armrest-main");
var exit = new TaskCompletionSource();
Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    exit.TrySetResult();
};
AppDomain.CurrentDomain.ProcessExit += (_, _) => exit.TrySetResult();

var host = queue.Invoke(() =>
{
    var agent = new AgentHost(new AgentHostOptions
    {
        Profile = profile,
        Dispatcher = queue,
        Certificate = LocalNetwork.EphemeralCertificate(profile.IdentityName),
        HostName = profile.HostName ?? Environment.MachineName,
        Platform = AgentPlatform.Windows,
    });
    agent.Start();
    return agent;
});

await exit.Task;
queue.Invoke(host.Stop);
return 0;
