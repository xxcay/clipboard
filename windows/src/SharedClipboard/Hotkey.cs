using System.Runtime.InteropServices;
using System.Windows.Input;
using System.Windows.Interop;
using SharedClipboard.Core;

namespace SharedClipboard;

/// <summary>A system-wide shortcut (RegisterHotKey) that works in any app.</summary>
sealed class Hotkey : IDisposable
{
    const int WM_HOTKEY = 0x0312;
    const int Id = 0x5C1B;
    const uint MOD_ALT = 1, MOD_CONTROL = 2, MOD_SHIFT = 4, MOD_WIN = 8, MOD_NOREPEAT = 0x4000;

    readonly HwndSource _window;
    readonly Action _pressed;
    bool _registered;

    public Hotkey(Action pressed)
    {
        _pressed = pressed;
        // A message-only window just to receive WM_HOTKEY.
        _window = new HwndSource(new HwndSourceParameters("SharedClipboard.Hotkey") { ParentWindow = new IntPtr(-3), WindowStyle = 0 });
        _window.AddHook(WndProc);
    }

    /// <summary>Registers <paramref name="text"/> ("Ctrl+Alt+C"); returns false if another app already uses it.</summary>
    public bool Set(string? text)
    {
        Clear();
        if (!TryParse(text, out var mods, out var key))
            return true; // off
        var vk = (uint)KeyInterop.VirtualKeyFromKey(key);
        _registered = RegisterHotKey(_window.Handle, Id, mods | MOD_NOREPEAT, vk);
        if (!_registered)
            Log.Write($"hotkey {text}: busy (error {Marshal.GetLastWin32Error()})");
        return _registered;
    }

    public void Clear()
    {
        if (_registered)
            UnregisterHotKey(_window.Handle, Id);
        _registered = false;
    }

    IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_HOTKEY && wParam.ToInt32() == Id)
        {
            handled = true;
            _pressed();
        }
        return IntPtr.Zero;
    }

    public void Dispose()
    {
        Clear();
        _window.Dispose();
    }

    public static bool TryParse(string? text, out uint mods, out Key key)
    {
        mods = 0;
        key = Key.None;
        if (string.IsNullOrWhiteSpace(text))
            return false;
        foreach (var part in text.Split('+', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            switch (part.ToLowerInvariant())
            {
                case "ctrl": mods |= MOD_CONTROL; break;
                case "alt": mods |= MOD_ALT; break;
                case "shift": mods |= MOD_SHIFT; break;
                case "win": mods |= MOD_WIN; break;
                default:
                    if (!Enum.TryParse(part, true, out key))
                        return false;
                    break;
            }
        }
        return key != Key.None;
    }

    /// <summary>Text for a key press in the settings recorder, or null if it is not a usable shortcut.</summary>
    public static string? Format(ModifierKeys mods, Key key)
    {
        if (key is Key.LeftCtrl or Key.RightCtrl or Key.LeftAlt or Key.RightAlt or Key.LeftShift or Key.RightShift
            or Key.LWin or Key.RWin or Key.None or Key.System)
            return null;
        var isF = key >= Key.F1 && key <= Key.F24;
        // Plain letters would break typing everywhere: need Ctrl, Alt or Win (F-keys may go alone).
        if (!isF && (mods & (ModifierKeys.Control | ModifierKeys.Alt | ModifierKeys.Windows)) == 0)
            return null;
        var parts = new List<string>();
        if (mods.HasFlag(ModifierKeys.Control)) parts.Add("Ctrl");
        if (mods.HasFlag(ModifierKeys.Alt)) parts.Add("Alt");
        if (mods.HasFlag(ModifierKeys.Shift)) parts.Add("Shift");
        if (mods.HasFlag(ModifierKeys.Windows)) parts.Add("Win");
        parts.Add(key.ToString());
        return string.Join("+", parts);
    }

    /// <summary>"Ctrl+Alt+D1" → "Ctrl + Alt + 1" for display.</summary>
    public static string Display(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
            return "Выключено";
        return string.Join(" + ", text.Split('+').Select(p => p.Length == 2 && p[0] == 'D' && char.IsDigit(p[1]) ? p[1..] : p switch
        {
            "OemPlus" => "=", "OemMinus" => "-", "OemComma" => ",", "OemPeriod" => ".", _ => p,
        }));
    }

    [DllImport("user32.dll", SetLastError = true)]
    static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    static extern bool UnregisterHotKey(IntPtr hWnd, int id);
}
