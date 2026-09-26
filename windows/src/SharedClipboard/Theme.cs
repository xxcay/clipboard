using System.Windows;
using System.Windows.Media;
using Microsoft.Win32;

namespace SharedClipboard;

/// <summary>Follows the Windows light/dark app setting.</summary>
static class Theme
{
    public static bool IsDark()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("AppsUseLightTheme") is int v && v == 0;
        }
        catch
        {
            return false;
        }
    }

    public static void Apply()
    {
        var dark = IsDark();
        Set("Bg", dark ? "#FF202226" : "#FFF9F9FB");
        Set("Fg", dark ? "#FFE8EAED" : "#FF1B1D21");
        Set("Muted", dark ? "#FF9AA0A8" : "#FF6B7280");
        Set("Line", dark ? "#FF34373E" : "#FFE3E5E9");
        Set("Hover", dark ? "#1AFFFFFF" : "#0F000000");
        Set("Selected", dark ? "#3360A5FA" : "#1A2563EB");
        Set("Accent", dark ? "#FF60A5FA" : "#FF2563EB");
        Set("AccentFg", dark ? "#FF0B1220" : "#FFFFFFFF");
        Set("Danger", dark ? "#FFF87171" : "#FFDC2626");
        Set("Ok", dark ? "#FF4ADE80" : "#FF16A34A");
        Set("Input", dark ? "#FF2A2D33" : "#FFFFFFFF");
    }

    static void Set(string key, string color)
    {
        var brush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(color));
        brush.Freeze();
        Application.Current.Resources[key] = brush;
    }
}
