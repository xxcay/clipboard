using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using SharedClipboard.Core;

namespace SharedClipboard;

/// <summary>
/// The panel that pops up above the tray icon: the shared clipboard items,
/// drag files out to the desktop, drop files in (or Ctrl+V) to send them.
/// </summary>
partial class FlyoutWindow : Window
{
    readonly AppController _app;
    readonly ICollectionView _view;
    readonly DispatcherTimer _toastTimer = new() { Interval = TimeSpan.FromSeconds(2.6) };
    Point? _dragStart;
    ListBoxItem? _pressedItem;
    bool _dragging;
    string _filter = "all";

    public FlyoutWindow(AppController app)
    {
        _app = app;
        InitializeComponent();
        DataContext = app;
        _view = CollectionViewSource.GetDefaultView(app.Items);
        _view.Filter = o => Matches((ItemViewModel)o);
        _toastTimer.Tick += (_, _) => HideToast();
        app.Items.CollectionChanged += (_, _) => UpdateCounts();
        UpdateCounts();
    }

    /// <summary>When the flyout last hid itself; used to make the tray click a toggle.</summary>
    public DateTime HiddenAt { get; private set; }

    /// <summary>True while a modal dialog (Save as…) is open, so the panel does not hide.</summary>
    public bool KeepOpen { get; set; }

    public void ShowAtTray()
    {
        Theme.Apply();
        var area = SystemParameters.WorkArea;
        Left = area.Right - Width + 6;
        Top = area.Bottom - Height + 6;
        Show();
        Activate();
        List.Focus();
    }

    // ---- Toast ----

    public void ShowStatus(string text, bool error = false)
    {
        ShowToast(text, error ? "IconError" : "IconCheck", error ? "Danger" : "Ok");
        _toastTimer.Stop();
        _toastTimer.Start();
    }

    /// <summary>Progress messages stay until replaced.</summary>
    public void ShowProgress(string text)
    {
        _toastTimer.Stop();
        ShowToast(text, "IconUpload", "Accent");
    }

    public void ClearProgress()
    {
        _toastTimer.Stop();
        _toastTimer.Start();
    }

    void ShowToast(string text, string icon, string color)
    {
        ToastText.Text = text;
        ToastIcon.Data = (Geometry)FindResource(icon);
        ToastIcon.Fill = (Brush)FindResource(color);
        if (Toast.Visibility != Visibility.Visible)
        {
            Toast.Visibility = Visibility.Visible;
            Toast.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(160)));
        }
    }

    void HideToast()
    {
        _toastTimer.Stop();
        var fade = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(220));
        fade.Completed += (_, _) =>
        {
            if (!_toastTimer.IsEnabled && Toast.Opacity == 0)
                Toast.Visibility = Visibility.Collapsed;
        };
        Toast.BeginAnimation(OpacityProperty, fade);
    }

    // ---- Connection banner: only when something is wrong ----

    public void UpdateConnection(HubState state, string? error)
    {
        string? text = state switch
        {
            HubState.Online => null,
            HubState.AuthFailed => "Неверный токен. Нажмите, чтобы открыть настройки",
            HubState.NotConfigured => "Не настроено. Нажмите, чтобы указать адрес роутера и токен",
            HubState.Offline => "Нет связи с роутером — переподключаюсь…",
            // While reconnecting keep the previous message, so the banner does not blink.
            _ => Banner.Visibility == Visibility.Visible ? BannerText.Text : null,
        };
        Banner.Visibility = text == null ? Visibility.Collapsed : Visibility.Visible;
        if (text != null)
            BannerText.Text = text;
    }

    void OnBannerClick(object sender, MouseButtonEventArgs e) => _app.ShowSettings();

    public void UpdateAvailable(int? version)
    {
        UpdateBanner.Visibility = version == null ? Visibility.Collapsed : Visibility.Visible;
        if (version != null)
            UpdateText.Text = $"Доступна новая версия (сборка {version})";
    }

    void OnUpdateClick(object sender, MouseButtonEventArgs e) => _ = _app.InstallUpdateAsync();

    // ---- Filters and counts ----

    bool Matches(ItemViewModel vm) => _filter switch
    {
        "files" => vm.IsFile,
        "links" => vm.IsUrl,
        "text" => vm.IsText,
        _ => true,
    };

    void OnFilter(object sender, RoutedEventArgs e)
    {
        _filter = sender == ChipFiles ? "files" : sender == ChipLinks ? "links" : sender == ChipText ? "text" : "all";
        _view?.Refresh();
        UpdateCounts();
    }

    void UpdateCounts()
    {
        if (_view == null)
            return;
        var items = _app.Items;
        var files = items.Count(i => i.IsFile);
        CountText.Text = items.Count == 0
            ? "ПК · ноутбук · телефон"
            : files > 0
                ? $"{Plural(items.Count, "запись", "записи", "записей")} · {Plural(files, "файл", "файла", "файлов")}"
                : Plural(items.Count, "запись", "записи", "записей");

        var empty = _view.IsEmpty;
        EmptyState.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
        (EmptyTitle.Text, EmptyHint.Text) = (_filter, items.Count) switch
        {
            (_, 0) => ("Здесь пока пусто", "Скопируйте что-нибудь и нажмите Ctrl+V или перетащите файлы в это окно"),
            ("files", _) => ("Файлов нет", "Перетащите файлы сюда, чтобы отправить их на другие устройства"),
            ("links", _) => ("Ссылок нет", "Скопируйте ссылку и нажмите Ctrl+V"),
            _ => ("Текста нет", "Скопируйте текст и нажмите Ctrl+V"),
        };
    }

    static string Plural(int n, string one, string few, string many)
    {
        var mod100 = n % 100;
        var mod10 = n % 10;
        var word = mod100 is >= 11 and <= 14 ? many : mod10 == 1 ? one : mod10 is >= 2 and <= 4 ? few : many;
        return $"{n} {word}";
    }

    void OnDeactivated(object? sender, EventArgs e)
    {
        if (_dragging || KeepOpen)
            return;
        HiddenAt = DateTime.UtcNow;
        Hide();
    }

    IReadOnlyList<ItemViewModel> Selected() => List.SelectedItems.Cast<ItemViewModel>().ToList();

    static ItemViewModel? ItemOf(object sender) => (sender as FrameworkElement)?.DataContext as ItemViewModel;

    void OnKeyDown(object sender, KeyEventArgs e)
    {
        var ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
        switch (e.Key)
        {
            case Key.Escape:
                HiddenAt = DateTime.UtcNow;
                Hide();
                break;
            case Key.V when ctrl:
                _app.SendClipboard();
                break;
            case Key.C when ctrl && List.SelectedItems.Count > 0:
                _app.Copy(Selected());
                break;
            case Key.A when ctrl:
                List.SelectAll();
                break;
            case Key.Delete when List.SelectedItems.Count > 0:
                foreach (var vm in Selected())
                    _app.Delete(vm);
                break;
            case Key.Enter when List.SelectedItem is ItemViewModel vm:
                _app.PrimaryAction(vm);
                break;
            default:
                return;
        }
        e.Handled = true;
    }

    void OnSendClipboard(object sender, RoutedEventArgs e) => _app.SendClipboard();

    void OnSettings(object sender, RoutedEventArgs e) => _app.ShowSettings();

    void OnCopy(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is { } vm)
            _app.Copy(List.SelectedItems.Contains(vm) ? Selected() : [vm]);
    }

    void OnOpen(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is { } vm)
            _app.PrimaryAction(vm);
    }

    void OnShowInFolder(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is { } vm)
            _app.ShowInFolder(vm);
    }

    void OnSaveAs(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is { } vm)
            _app.SaveAs(vm);
    }

    void OnDelete(object sender, RoutedEventArgs e)
    {
        if (ItemOf(sender) is not { } vm)
            return;
        var targets = List.SelectedItems.Contains(vm) ? Selected() : [vm];
        foreach (var t in targets)
            _app.Delete(t);
    }

    void OnListDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (ContainerAt(e.OriginalSource) is { DataContext: ItemViewModel vm })
            _app.PrimaryAction(vm);
    }

    // ---- Drag files out (to the desktop, a folder, an e-mail…) ----

    static ListBoxItem? ContainerAt(object source)
    {
        var d = source as DependencyObject;
        while (d != null && d is not ListBoxItem)
        {
            if (d is Button)
                return null; // clicks on the row buttons are not drags
            d = d is Visual or System.Windows.Media.Media3D.Visual3D ? VisualTreeHelper.GetParent(d) : LogicalTreeHelper.GetParent(d);
        }
        return d as ListBoxItem;
    }

    void OnListMouseDown(object sender, MouseButtonEventArgs e)
    {
        _pressedItem = ContainerAt(e.OriginalSource);
        _dragStart = _pressedItem != null ? e.GetPosition(this) : null;
        // Keep a multi-selection when pressing on one of its items, so the
        // whole selection can be dragged; plain click still selects one on mouse up.
        if (_pressedItem is { IsSelected: true } && List.SelectedItems.Count > 1 && Keyboard.Modifiers == ModifierKeys.None)
            e.Handled = true;
    }

    void OnListMouseUp(object sender, MouseButtonEventArgs e)
    {
        if (_pressedItem != null && _dragStart != null && Keyboard.Modifiers == ModifierKeys.None && List.SelectedItems.Count > 1)
        {
            List.SelectedItems.Clear();
            _pressedItem.IsSelected = true;
        }
        _dragStart = null;
        _pressedItem = null;
    }

    void OnListMouseMove(object sender, MouseEventArgs e)
    {
        if (e.LeftButton != MouseButtonState.Pressed || _dragStart is not { } start || _pressedItem == null)
            return;
        var delta = e.GetPosition(this) - start;
        if (Math.Abs(delta.X) < SystemParameters.MinimumHorizontalDragDistance &&
            Math.Abs(delta.Y) < SystemParameters.MinimumVerticalDragDistance)
            return;

        var pressed = (ItemViewModel)_pressedItem.DataContext;
        var items = _pressedItem.IsSelected ? Selected() : [pressed];
        _dragStart = null;
        _pressedItem = null;

        var data = _app.BuildDragData(items);
        if (data == null)
            return;
        _dragging = true;
        try
        {
            DragDrop.DoDragDrop(List, data, DragDropEffects.Copy);
        }
        finally
        {
            _dragging = false;
        }
    }

    // ---- Drop files in to send them ----

    bool CanAccept(DragEventArgs e) =>
        !_dragging && (e.Data.GetDataPresent(DataFormats.FileDrop) || e.Data.GetDataPresent(DataFormats.UnicodeText));

    void OnDragOver(object sender, DragEventArgs e)
    {
        e.Effects = CanAccept(e) ? DragDropEffects.Copy : DragDropEffects.None;
        DropOverlay.Visibility = e.Effects == DragDropEffects.Copy ? Visibility.Visible : Visibility.Collapsed;
        e.Handled = true;
    }

    void OnDragLeave(object sender, DragEventArgs e) => DropOverlay.Visibility = Visibility.Collapsed;

    void OnDrop(object sender, DragEventArgs e)
    {
        DropOverlay.Visibility = Visibility.Collapsed;
        if (!CanAccept(e))
            return;
        if (e.Data.GetData(DataFormats.FileDrop) is string[] files && files.Length > 0)
            _app.UploadFiles(files);
        else if (e.Data.GetData(DataFormats.UnicodeText) is string text)
            _app.SendText(text);
        e.Handled = true;
    }
}
