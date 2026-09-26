namespace SharedClipboard.Core;

/// <summary>Tiny file log for troubleshooting on the user's machine.</summary>
public static class Log
{
    static readonly object Lock = new();
    public static string? Path { get; set; }

    public static void Write(string message)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {message}";
        System.Diagnostics.Debug.WriteLine(line);
        if (Path == null)
            return;
        lock (Lock)
        {
            try
            {
                var fi = new FileInfo(Path);
                if (fi.Exists && fi.Length > 1 << 20)
                    System.IO.File.Move(Path, Path + ".old", overwrite: true);
                System.IO.File.AppendAllText(Path, line + Environment.NewLine);
            }
            catch
            {
                // Logging must never break the app.
            }
        }
    }
}
