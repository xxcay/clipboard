using System.Windows;
using System.Windows.Media;
using SharedClipboard.Core;

namespace SharedClipboard;

partial class SettingsWindow : Window
{
    public SettingsWindow(AppSettings current)
    {
        InitializeComponent();
        Icon = AppIcons.WindowIcon;
        Address.Text = current.ServerUrl;
        Token.Text = current.Token;
        DeviceName.Text = current.DeviceName;
        Autostart.IsChecked = current.Autostart;
        Notifications.IsChecked = current.Notifications;
        AutoCopy.IsChecked = current.AutoCopyText;
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
