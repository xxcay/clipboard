using System.Text.Json;

namespace SharedClipboard.Core;

public sealed class AppSettings
{
    public const int DefaultPort = 8765;

    public string ServerUrl { get; set; } = $"http://clip.lan:{DefaultPort}";
    public string Token { get; set; } = "";
    public string DeviceName { get; set; } = Environment.MachineName;
    public bool Autostart { get; set; } = true;
    public bool Notifications { get; set; } = true;
    /// <summary>Put incoming text and links into the local clipboard right away.</summary>
    public bool AutoCopyText { get; set; }

    public bool IsConfigured => !string.IsNullOrWhiteSpace(Token) && ServerAddress.TryParse(ServerUrl, out _, out _);

    public AppSettings Clone() => (AppSettings)MemberwiseClone();

    public static AppSettings Load(string path)
    {
        try
        {
            if (System.IO.File.Exists(path))
                return JsonSerializer.Deserialize<AppSettings>(System.IO.File.ReadAllText(path), Json.Options) ?? new();
        }
        catch (Exception e)
        {
            Log.Write($"settings: {e.Message}");
        }
        return new();
    }

    public void Save(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp = path + ".tmp";
        System.IO.File.WriteAllText(tmp, JsonSerializer.Serialize(this, new JsonSerializerOptions(Json.Options) { WriteIndented = true }));
        System.IO.File.Move(tmp, path, overwrite: true);
    }
}

public static class ServerAddress
{
    /// <summary>
    /// Accepts "clip.lan", "192.168.8.1:8765", "http://clip.lan:8765" or the
    /// quick link printed by the router installer ("http://…:8765/#token=…").
    /// A missing port means 8765: port 80 is the GL.iNet admin page.
    /// </summary>
    public static bool TryParse(string? input, out Uri baseUri, out string? token)
    {
        baseUri = null!;
        token = null;
        var s = input?.Trim() ?? "";
        if (s.Length == 0)
            return false;
        if (!s.Contains("://"))
            s = "http://" + s;
        if (!Uri.TryCreate(s, UriKind.Absolute, out var u) || (u.Scheme != "http" && u.Scheme != "https") || u.Host.Length == 0)
            return false;

        var frag = u.Fragment.TrimStart('#');
        foreach (var part in frag.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var kv = part.Split('=', 2);
            if (kv.Length == 2 && kv[0] == "token" && kv[1].Length > 0)
                token = Uri.UnescapeDataString(kv[1]);
        }

        var rest = s[(s.IndexOf("://", StringComparison.Ordinal) + 3)..];
        var end = rest.IndexOfAny(['/', '?', '#']);
        var authority = end < 0 ? rest : rest[..end];
        var hasPort = authority.LastIndexOf(':') > authority.LastIndexOf(']');
        var b = new UriBuilder(u.Scheme, u.Host, hasPort ? u.Port : AppSettings.DefaultPort, "/");
        baseUri = b.Uri;
        return true;
    }
}
