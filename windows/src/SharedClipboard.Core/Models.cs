using System.Text.Json;

namespace SharedClipboard.Core;

/// <summary>One entry of the shared clipboard, as stored on the hub (clipd).</summary>
public sealed class ClipItem
{
    public const string KindText = "text";
    public const string KindUrl = "url";
    public const string KindFile = "file";

    public string Id { get; set; } = "";
    public string Kind { get; set; } = KindText;
    public string From { get; set; } = "";
    /// <summary>Unix time in milliseconds.</summary>
    public long Ts { get; set; }
    public string? Text { get; set; }
    public FileMeta? File { get; set; }

    public bool IsFile => Kind == KindFile && File != null;
    public bool IsUrl => Kind == KindUrl;
    public DateTimeOffset Time => DateTimeOffset.FromUnixTimeMilliseconds(Ts);
}

public sealed class FileMeta
{
    public string Name { get; set; } = "";
    public long Size { get; set; }
    public string Mime { get; set; } = "";
    public string Sha256 { get; set; } = "";
}

public sealed class Limits
{
    public long MaxTextBytes { get; set; } = 1 << 20;
    public long MaxFileBytes { get; set; } = 20 << 20;
    public long QuotaBytes { get; set; } = 80 << 20;
}

/// <summary>WebSocket message, see server/hub.go.</summary>
internal sealed class HubMessage
{
    public string Type { get; set; } = "";
    public string? Version { get; set; }
    public string? Device { get; set; }
    public ClipItem? Item { get; set; }
    public List<ClipItem>? Items { get; set; }
    public string? Id { get; set; }
    public string? Text { get; set; }
    public List<string>? Devices { get; set; }
    public Limits? Limits { get; set; }
    public string? Error { get; set; }
}

internal static class Json
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };
}

/// <summary>An error with a message that can be shown to the user as is.</summary>
public sealed class ClipException(string message, Exception? inner = null) : Exception(message, inner);

public static class Format
{
    public static string Size(long bytes) => bytes switch
    {
        >= 1 << 20 => $"{bytes / 1048576.0:0.0} МБ",
        >= 1 << 10 => $"{bytes / 1024.0:0} КБ",
        _ => $"{bytes} Б",
    };
}
