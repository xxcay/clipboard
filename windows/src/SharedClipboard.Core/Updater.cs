using System.Net.Http.Json;
using System.Reflection;

namespace SharedClipboard.Core;

public sealed class ReleaseInfo
{
    public int Version { get; set; }
    public string? Commit { get; set; }
}

/// <summary>
/// Self-update from the "windows-latest" GitHub release: version.json holds the
/// build number, next to the two .exe builds.
/// </summary>
public sealed class Updater(HttpClient http, string baseUrl = Updater.DefaultBase)
{
    public const string DefaultBase = "https://github.com/xxcay/clipboard/releases/download/windows-latest/";

    /// <summary>Build number of the running app (the third part of 1.0.N).</summary>
    public static int CurrentVersion => Assembly.GetEntryAssembly()?.GetName().Version?.Build ?? 0;

    public static string CurrentVersionText
    {
        get
        {
            var v = Assembly.GetEntryAssembly()?.GetName().Version;
            return v == null ? "?" : $"{v.Major}.{v.Minor}.{v.Build}";
        }
    }

    /// <summary>The full build carries the .NET runtime (tens of MB); the small one needs it installed.</summary>
    public static string AssetFor(long exeSize) => exeSize > 20 << 20 ? "SharedClipboard.exe" : "SharedClipboard-small.exe";

    public async Task<ReleaseInfo> LatestAsync(CancellationToken ct = default)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, baseUrl + "version.json");
        req.Headers.CacheControl = new() { NoCache = true };
        using var resp = await http.SendAsync(req, ct);
        resp.EnsureSuccessStatusCode();
        return await resp.Content.ReadFromJsonAsync<ReleaseInfo>(Json.Options, ct)
               ?? throw new ClipException("Пустой ответ о версии");
    }

    /// <summary>Downloads an asset to <paramref name="dest"/> and checks it is a Windows program.</summary>
    public async Task DownloadAsync(string asset, string dest, IProgress<double>? progress = null, CancellationToken ct = default)
    {
        using var resp = await http.GetAsync(baseUrl + asset, HttpCompletionOption.ResponseHeadersRead, ct);
        resp.EnsureSuccessStatusCode();
        var total = resp.Content.Headers.ContentLength ?? -1;
        await using (var src = await resp.Content.ReadAsStreamAsync(ct))
        await using (var dst = File.Create(dest))
        {
            var buf = new byte[81920];
            long done = 0;
            int n;
            while ((n = await src.ReadAsync(buf, ct)) > 0)
            {
                await dst.WriteAsync(buf.AsMemory(0, n), ct);
                done += n;
                if (total > 0)
                    progress?.Report((double)done / total);
            }
        }
        var head = new byte[2];
        await using (var f = File.OpenRead(dest))
            _ = await f.ReadAsync(head, ct);
        if (new FileInfo(dest).Length < 100_000 || head[0] != 'M' || head[1] != 'Z')
        {
            File.Delete(dest);
            throw new ClipException("Скачанный файл повреждён");
        }
    }

    /// <summary>
    /// Swaps the running exe for the new one: a running .exe cannot be
    /// overwritten on Windows, but it can be renamed.
    /// </summary>
    public static void Swap(string exe, string newExe)
    {
        var old = exe + ".old";
        if (File.Exists(old))
            File.Delete(old);
        File.Move(exe, old);
        try
        {
            File.Move(newExe, exe);
        }
        catch
        {
            File.Move(old, exe);
            throw;
        }
    }

    /// <summary>Removes the previous version left after an update.</summary>
    public static void CleanUp(string exe)
    {
        try
        {
            if (File.Exists(exe + ".old"))
                File.Delete(exe + ".old");
            if (File.Exists(exe + ".new"))
                File.Delete(exe + ".new");
        }
        catch
        {
            // Still locked right after the restart; next start cleans up.
        }
    }
}
