using System.Collections.Specialized;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Media.Imaging;

namespace SharedClipboard;

/// <summary>What is currently on the Windows clipboard.</summary>
abstract record ClipboardContent
{
    public sealed record Files(IReadOnlyList<string> Paths) : ClipboardContent;
    public sealed record Text(string Value) : ClipboardContent;
    /// <summary>A picture (e.g. a screenshot) saved to a temporary PNG file.</summary>
    public sealed record Image(string Path) : ClipboardContent;
    public sealed record Empty : ClipboardContent;
}

/// <summary>
/// Windows clipboard access. Another app may hold the clipboard open for a
/// moment, so every call is retried a few times.
/// </summary>
static class ClipboardService
{
    public static ClipboardContent Read(string tempDir)
    {
        return Retry(() =>
        {
            if (Clipboard.ContainsFileDropList())
            {
                var files = Clipboard.GetFileDropList().Cast<string>().ToList();
                if (files.Count > 0)
                    return new ClipboardContent.Files(files);
            }
            if (Clipboard.ContainsImage() || Clipboard.ContainsData("PNG"))
            {
                var path = SaveImage(tempDir);
                if (path != null)
                    return new ClipboardContent.Image(path);
            }
            if (Clipboard.ContainsText())
            {
                var text = Clipboard.GetText();
                if (!string.IsNullOrWhiteSpace(text))
                    return new ClipboardContent.Text(text);
            }
            return (ClipboardContent)new ClipboardContent.Empty();
        });
    }

    public static void SetText(string text) => Retry(() =>
    {
        Clipboard.SetDataObject(new DataObject(DataFormats.UnicodeText, text), copy: true);
        return true;
    });

    /// <summary>Puts files on the clipboard; Ctrl+V in Explorer then pastes them.</summary>
    public static void SetFiles(IEnumerable<string> paths) => Retry(() =>
    {
        var list = new StringCollection();
        list.AddRange(paths.ToArray());
        Clipboard.SetFileDropList(list);
        return true;
    });

    static string? SaveImage(string tempDir)
    {
        Directory.CreateDirectory(tempDir);
        var path = Path.Combine(tempDir, $"Скриншот {DateTime.Now:yyyy-MM-dd HH.mm.ss}.png");

        // Browsers, the Snipping Tool and Office put a ready PNG on the
        // clipboard; it keeps transparency, so prefer it over the bitmap.
        if (Clipboard.GetData("PNG") is MemoryStream png)
        {
            File.WriteAllBytes(path, png.ToArray());
            return path;
        }
        var bmp = Clipboard.GetImage();
        if (bmp == null)
            return null;
        var enc = new PngBitmapEncoder();
        enc.Frames.Add(BitmapFrame.Create(bmp));
        using var fs = File.Create(path);
        enc.Save(fs);
        return path;
    }

    static T Retry<T>(Func<T> action)
    {
        for (var i = 0; ; i++)
        {
            try
            {
                return action();
            }
            catch (COMException) when (i < 9)
            {
                Thread.Sleep(50);
            }
            catch (ExternalException) when (i < 9)
            {
                Thread.Sleep(50);
            }
        }
    }
}
