using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using SharedClipboard.Core;

namespace SharedClipboard;

partial class SettingsWindow : Window
{
    readonly AppController _app;
    string _hotkey;

    public SettingsWindow(AppSettings current, AppController app)
    {
        _app = app;
        InitializeComponent();
        VersionText.Text = $"Версия {Updater.CurrentVersionText}";
        ShowUpdateState(null);
        app.UpdateStateChanged += OnUpdateStateChanged;
        Closed += (_, _) => app.UpdateStateChanged -= OnUpdateStateChanged;
        Icon = AppIcons.WindowIcon;
        Address.Text = current.ServerUrl;
        Token.Text = current.Token;
        DeviceName.Text = current.DeviceName;
        Autostart.IsChecked = current.Autostart;
        Notifications.IsChecked = current.Notifications;
        AutoCopy.IsChecked = current.AutoCopyText;
        _hotkey = current.Hotkey;
        // Fit small screens: the content scrolls, the title bar stays.
        Scroll.MaxHeight = Math.Max(300, SystemParameters.WorkArea.Height - 170);
        HotkeyBox.Text = Hotkey.Display(_hotkey);
        Result = current.Clone();
        Loaded += (_, _) => (string.IsNullOrEmpty(current.Token) ? Token : Address).Focus();
    }

    public AppSettings Result { get; private set; }

    AppSettings Collect()
    {
        var s = Result.Clone();
        s.ServerUrl = Address.Text.Trim();
        if (ServerAddress.TryParse(s.ServerUrl, out var b, out _))
            s.ServerUrl = b.ToString().TrimEnd('/');
        s.Token = Token.Text.Trim();
        s.DeviceName = string.IsNullOrWhiteSpace(DeviceName.Text) ? Environment.MachineName : DeviceName.Text.Trim();
        s.Autostart = Autostart.IsChecked == true;
        s.Notifications = Notifications.IsChecked == true;
        s.AutoCopyText = AutoCopy.IsChecked == true;
        s.Hotkey = _hotkey;
        return s;
    }

    void OnAddressChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
    {
        if (Token == null)
            return; // during InitializeComponent
        if (ServerAddress.TryParse(Address.Text, out var b, out var token) && token != null)
        {
            Token.Text = token;
            Address.Text = b.ToString().TrimEnd('/');
            Address.CaretIndex = Address.Text.Length;
        }
    }

    async void OnCheck(object sender, RoutedEventArgs e)
    {
        CheckButton.IsEnabled = false;
        ShowResult("Проверяю…", "Muted");
        var error = await HubClient.CheckAsync(Collect());
        ShowResult(error ?? "Всё в порядке", error == null ? "Ok" : "Danger");
        CheckButton.IsEnabled = true;
    }

    void ShowResult(string text, string brush)
    {
        CheckText.Text = text;
        CheckText.Foreground = (Brush)FindResource(brush);
        CheckIcon.Fill = (Brush)FindResource(brush);
        CheckIcon.Data = (Geometry)FindResource(brush switch
        {
            "Ok" => "IconCheck",
            "Danger" => "IconError",
            _ => "IconLink",
        });
        CheckResult.Visibility = Visibility.Visible;
    }

    // ---- Shortcut recorder ----

    void OnHotkeyFocus(object sender, System.Windows.Input.KeyboardFocusChangedEventArgs e) =>
        HotkeyBox.Text = "Нажмите сочетание…";

    void OnHotkeyBlur(object sender, System.Windows.Input.KeyboardFocusChangedEventArgs e) =>
        HotkeyBox.Text = Hotkey.Display(_hotkey);

    void OnHotkeyKey(object sender, System.Windows.Input.KeyEventArgs e)
    {
        e.Handled = true;
        var key = e.Key == System.Windows.Input.Key.System ? e.SystemKey : e.Key;
        if (key == System.Windows.Input.Key.Escape || key == System.Windows.Input.Key.Tab)
        {
            Keyboard.ClearFocus();
            return;
        }
        var text = Hotkey.Format(Keyboard.Modifiers, key);
        if (text == null)
            return; // modifiers only so far, or a plain letter
        _hotkey = text;
        HotkeyBox.Text = Hotkey.Display(text);
        HotkeyHint.Text = "Сохраните настройки, чтобы сочетание заработало.";
    }

    void OnHotkeyOff(object sender, RoutedEventArgs e)
    {
        _hotkey = "";
        HotkeyBox.Text = Hotkey.Display("");
    }

    void OnUpdateStateChanged() => ShowUpdateState(null);

    void ShowUpdateState(string? error)
    {
        var available = _app.AvailableUpdate;
        UpdateStatus.Text = error ?? (available != null ? $"Доступна сборка {available}" : "Проверяется автоматически раз в 6 часов");
        UpdateStatus.Foreground = (Brush)FindResource(error != null ? "Danger" : available != null ? "Accent" : "Muted");
        InstallButton.Visibility = available != null ? Visibility.Visible : Visibility.Collapsed;
    }

    async void OnCheckUpdate(object sender, RoutedEventArgs e)
    {
        CheckUpdateButton.IsEnabled = false;
        UpdateStatus.Text = "Проверяю…";
        var error = await _app.CheckUpdatesAsync(quiet: false);
        CheckUpdateButton.IsEnabled = true;
        if (error == null && _app.AvailableUpdate == null)
        {
            UpdateStatus.Text = "Установлена последняя версия";
            UpdateStatus.Foreground = (Brush)FindResource("Ok");
        }
        else
        {
            ShowUpdateState(error);
        }
    }

    async void OnInstallUpdate(object sender, RoutedEventArgs e)
    {
        InstallButton.IsEnabled = false;
        UpdateStatus.Text = "Загружаю обновление… Программа перезапустится сама";
        DialogResult = false;
        await _app.InstallUpdateAsync();
    }

    void OnDrag(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (e.ButtonState == System.Windows.Input.MouseButtonState.Pressed)
            DragMove();
    }

    void OnCancel(object sender, RoutedEventArgs e) => DialogResult = false;

    void OnSave(object sender, RoutedEventArgs e)
    {
        var s = Collect();
        if (!ServerAddress.TryParse(s.ServerUrl, out _, out _))
        {
            ShowResult("Неправильный адрес.", "Danger");
            return;
        }
        if (s.Token.Length == 0)
        {
            ShowResult("Введите токен.", "Danger");
            return;
        }
        Result = s;
        DialogResult = true;
    }
}
