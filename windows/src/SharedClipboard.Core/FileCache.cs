using System.Collections.Concurrent;

namespace SharedClipboard.Core;

/// <summary>
/// Local copies of the files on the hub: &lt;root&gt;\&lt;item id&gt;\&lt;file name&gt;.
/// Files are fetched in the background as soon as they appear, so dragging
/// one out of the panel onto the desktop is instant.
/// </summary>
public sealed class FileCache(string root, HubClient hub)
{
    readonly ConcurrentDictionary<string, Task<string>> _pending = new();

    public string Root => root;

    public string PathFor(ClipItem item) => Path.Combine(root, item.Id, SafeName(item.File!.Name));

    public bool IsReady(ClipItem item)
    {
        if (!item.IsFile)
            return false;
        var fi = new FileInfo(PathFor(item));
        return fi.Exists && fi.Length == item.File!.Size;
    }

    /// <summary>Returns the local path, downloading the file once if needed.</summary>
    public Task<string> EnsureAsync(ClipItem item, IProgress<double>? progress = null)
    {
        if (IsReady(item))
            return Task.FromResult(PathFor(item));
        return _pending.GetOrAdd(item.Id, _ => DownloadAsync(item, progress));
    }

    async Task<string> DownloadAsync(ClipItem item, IProgress<double>? progress)
    {
        try
        {
            var path = PathFor(item);
            await hub.DownloadAsync(item, path, progress);
            return path;
        }
        finally
        {
            _pending.TryRemove(item.Id, out _);
        }
    }

    /// <summary>Puts a file we just uploaded into the cache without downloading it back.</summary>
    public void Adopt(ClipItem item, string sourcePath)
    {
        try
        {
            var dest = PathFor(item);
            Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
            File.Copy(sourcePath, dest, overwrite: true);
        }
        catch (Exception e)
        {
            Log.Write($"cache: adopt {item.Id}: {e.Message}");
        }
    }

    public void Remove(string id)
    {
        try
        {
            var dir = Path.Combine(root, id);
            if (Directory.Exists(dir))
                Directory.Delete(dir, recursive: true);
        }
        catch (Exception e)
        {
            // The file may be open in another app; the next cleanup retries.
            Log.Write($"cache: remove {id}: {e.Message}");
        }
    }

    /// <summary>Deletes cached files of items that are no longer on the hub.</summary>
    public void Cleanup(IEnumerable<string> keepIds)
    {
        if (!Directory.Exists(root))
            return;
        var keep = keepIds.ToHashSet();
        foreach (var dir in Directory.EnumerateDirectories(root))
        {
            var id = Path.GetFileName(dir);
            if (!keep.Contains(id) && !_pending.ContainsKey(id))
                Remove(id);
        }
    }

    public static string SafeName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars().Concat(['<', '>', ':', '"', '|', '?', '*', '\\', '/']).ToHashSet();
        var clean = new string(name.Select(c => invalid.Contains(c) || char.IsControl(c) ? '_' : c).ToArray()).Trim(' ', '.');
        return clean.Length == 0 ? "file" : clean;
    }
}
