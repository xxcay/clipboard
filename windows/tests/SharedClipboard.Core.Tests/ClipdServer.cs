using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace SharedClipboard.Core.Tests;

/// <summary>Runs the real hub (server/, Go) on a free local port.</summary>
public sealed class ClipdServer : IDisposable
{
    public const string Token = "test-token";
    static readonly Lazy<string> Binary = new(Build);

    readonly Process _proc;
    readonly string _dataDir;

    public ClipdServer(int maxFileMb = 20, int quotaMb = 80)
    {
        Port = FreePort();
        _dataDir = Directory.CreateTempSubdirectory("clipd-test").FullName;
        var psi = new ProcessStartInfo(Binary.Value,
            $"-listen 127.0.0.1:{Port} -data \"{_dataDir}\" -max-file-mb {maxFileMb} -quota-mb {quotaMb}")
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.Environment["CLIPD_TOKEN"] = Token;
        _proc = Process.Start(psi)!;
        _proc.BeginOutputReadLine();
        _proc.BeginErrorReadLine();
        WaitReady();
    }

    public int Port { get; }
    public string Url => $"http://127.0.0.1:{Port}";

    public AppSettings Settings(string device) => new() { ServerUrl = Url, Token = Token, DeviceName = device };

    void WaitReady()
    {
        using var http = new HttpClient();
        for (var i = 0; i < 100; i++)
        {
            try
            {
                if (http.GetAsync(Url + "/healthz").Result.StatusCode == HttpStatusCode.OK)
                    return;
            }
            catch
            {
            }
            Thread.Sleep(50);
        }
        throw new InvalidOperationException("clipd did not start");
    }

    public void Dispose()
    {
        try
        {
            _proc.Kill();
            _proc.WaitForExit();
        }
        catch
        {
        }
        Directory.Delete(_dataDir, true);
    }

    static int FreePort()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    /// <summary>Uses $CLIPD_BIN, or builds ../server with `go build`.</summary>
    static string Build()
    {
        var env = Environment.GetEnvironmentVariable("CLIPD_BIN");
        if (!string.IsNullOrEmpty(env))
            return env;
        var dir = AppContext.BaseDirectory;
        while (dir != null && !Directory.Exists(Path.Combine(dir, "server")))
            dir = Path.GetDirectoryName(dir);
        if (dir == null)
            throw new InvalidOperationException("server/ not found; set CLIPD_BIN");
        var output = Path.Combine(Path.GetTempPath(), "clipd-test-" + Guid.NewGuid().ToString("N") + (OperatingSystem.IsWindows() ? ".exe" : ""));
        var psi = new ProcessStartInfo("go", $"build -o \"{output}\" .") { WorkingDirectory = Path.Combine(dir, "server") };
        using var p = Process.Start(psi)!;
        p.WaitForExit();
        if (p.ExitCode != 0)
            throw new InvalidOperationException("go build failed");
        return output;
    }
}
