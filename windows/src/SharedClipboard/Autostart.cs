using Microsoft.Win32;
using SharedClipboard.Core;

namespace SharedClipboard;

/// <summary>"Run at Windows startup" via HKCU\...\Run (no admin rights needed).</summary>
static class Autostart
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string Name = "SharedClipboard";

    public static void Apply(bool enabled)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (enabled && Environment.ProcessPath is { } exe)
                key.SetValue(Name, $"\"{exe}\" --autostart");
            else
                key.DeleteValue(Name, throwOnMissingValue: false);
        }
        catch (Exception e)
        {
            Log.Write($"autostart: {e.Message}");
        }
    }
}
