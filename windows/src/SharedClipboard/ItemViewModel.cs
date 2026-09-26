using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using SharedClipboard.Core;

namespace SharedClipboard;

/// <summary>One row of the flyout list.</summary>
sealed class ItemViewModel(ClipItem item, bool mine) : INotifyPropertyChanged
{
    bool _ready;
    double _progress;
    ImageSource? _thumbnail;

    public ClipItem Item { get; } = item;
    public string Id => Item.Id;
    public bool IsMine { get; } = mine;
    public bool IsFile => Item.IsFile;
    public bool IsUrl => Item.IsUrl;
    public bool IsText => !Item.IsFile && !Item.IsUrl;

    public string Title
    {
        get
        {
            if (Item.IsFile)
                return Item.File!.Name;
            var text = (Item.Text ?? "").Trim();
            return text.Length > 300 ? text[..300] + "…" : text;
        }
    }

    public string Subtitle
    {
        get
        {
            var time = Item.Time.ToLocalTime();
            var when = time.Date == DateTime.Today ? time.ToString("HH:mm") : time.ToString("dd.MM HH:mm");
            var from = IsMine ? "это устройство" : Item.From;
            var parts = new List<string> { from, when };
            if (Item.IsFile)
                parts.Add(Format.Size(Item.File!.Size));
            return string.Join(" · ", parts);
        }
    }

    /// <summary>Segoe Fluent Icons glyph.</summary>
    public string Glyph
    {
        get
        {
            if (Item.IsUrl)
                return ""; // link
            if (!Item.IsFile)
                return ""; // copy / text
            var mime = Item.File!.Mime;
            var ext = Path.GetExtension(Item.File.Name).ToLowerInvariant();
            if (mime.StartsWith("image/"))
                return ""; // photo
            if (mime.StartsWith("video/"))
                return ""; // video
            if (mime.StartsWith("audio/"))
                return ""; // music
            if (ext is ".zip" or ".rar" or ".7z")
                return ""; // zip folder
            return ""; // document
        }
    }

    public string PrimaryActionTip => Item.IsUrl ? "Открыть ссылку" : Item.IsFile ? "Открыть файл" : "Копировать";

    public bool IsReady
    {
        get => _ready;
        set
        {
            if (Set(ref _ready, value))
            {
                OnPropertyChanged(nameof(StatusText));
                OnPropertyChanged(nameof(IsLoading));
            }
        }
    }

    public double Progress
    {
        get => _progress;
        set
        {
            if (Set(ref _progress, value))
                OnPropertyChanged(nameof(StatusText));
        }
    }

    public bool IsLoading => Item.IsFile && !_ready;

    public string StatusText => IsLoading ? (Progress > 0 ? $"загрузка {Progress:P0}" : "загрузка…") : "";

    public ImageSource? Thumbnail
    {
        get => _thumbnail;
        private set
        {
            if (Set(ref _thumbnail, value))
                OnPropertyChanged(nameof(HasThumbnail));
        }
    }

    public bool HasThumbnail => _thumbnail != null;

    /// <summary>Loads a small preview for photos once the file is cached.</summary>
    public void LoadThumbnail(string path)
    {
        if (!Item.IsFile || !Item.File!.Mime.StartsWith("image/"))
            return;
        var ext = Path.GetExtension(path).ToLowerInvariant();
        if (ext is not (".jpg" or ".jpeg" or ".png" or ".gif" or ".bmp" or ".webp" or ".tif" or ".tiff" or ".ico"))
            return;
        try
        {
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad; // do not keep the file locked
            bmp.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            bmp.DecodePixelWidth = 96;
            bmp.UriSource = new Uri(path);
            bmp.EndInit();
            bmp.Freeze();
            Thumbnail = bmp;
        }
        catch (Exception e)
        {
            Log.Write($"thumbnail {Item.File.Name}: {e.Message}");
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
            return false;
        field = value;
        OnPropertyChanged(name);
        return true;
    }
}

sealed class BoolToVisibility : System.Windows.Data.IValueConverter
{
    public bool Invert { get; set; }

    public object Convert(object value, Type targetType, object parameter, System.Globalization.CultureInfo culture) =>
        (value is true) ^ Invert ? Visibility.Visible : Visibility.Collapsed;

    public object ConvertBack(object value, Type targetType, object parameter, System.Globalization.CultureInfo culture) =>
        throw new NotSupportedException();
}
