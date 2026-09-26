using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Drawing = System.Drawing;
using WinForms = System.Windows.Forms;

namespace SharedClipboard;

/// <summary>The app icon and its tray variants (offline = grey, unread = orange dot).</summary>
static class AppIcons
{
    static readonly Uri IcoUri = new("pack://application:,,,/app.ico");
    static readonly Dictionary<(bool, bool), Drawing.Icon> TrayCache = [];

    public static ImageSource WindowIcon => BitmapFrame.Create(IcoUri);

    public static Drawing.Icon Tray(bool online, bool unread)
    {
        if (TrayCache.TryGetValue((online, unread), out var cached))
            return cached;
        var size = Math.Max(16, WinForms.SystemInformation.SmallIconSize.Width);
        using var stream = Application.GetResourceStream(IcoUri)!.Stream;
        using var baseIcon = new Drawing.Icon(stream, size, size);
        using var bmp = baseIcon.ToBitmap();
        using (var g = Drawing.Graphics.FromImage(bmp))
        {
            g.SmoothingMode = Drawing.Drawing2D.SmoothingMode.AntiAlias;
            if (!online)
            {
                // Desaturate and fade: "no connection".
                using var gray = (Drawing.Bitmap)bmp.Clone();
                var m = new Drawing.Imaging.ColorMatrix(
                [
                    [0.3f, 0.3f, 0.3f, 0, 0],
                    [0.59f, 0.59f, 0.59f, 0, 0],
                    [0.11f, 0.11f, 0.11f, 0, 0],
                    [0, 0, 0, 0.75f, 0],
                    [0, 0, 0, 0, 1],
                ]);
                using var attrs = new Drawing.Imaging.ImageAttributes();
                attrs.SetColorMatrix(m);
                g.Clear(Drawing.Color.Transparent);
                g.DrawImage(gray, new Drawing.Rectangle(0, 0, size, size), 0, 0, size, size, Drawing.GraphicsUnit.Pixel, attrs);
            }
            if (unread)
            {
                var d = size * 0.5f;
                var rect = new Drawing.RectangleF(size - d, size - d, d - 0.5f, d - 0.5f);
                using var fill = new Drawing.SolidBrush(Drawing.Color.FromArgb(249, 115, 22));
                using var ring = new Drawing.Pen(Drawing.Color.White, Math.Max(1f, size / 16f));
                g.FillEllipse(fill, rect);
                g.DrawEllipse(ring, rect);
            }
        }
        var icon = Drawing.Icon.FromHandle(bmp.GetHicon());
        TrayCache[(online, unread)] = icon;
        return icon;
    }
}
