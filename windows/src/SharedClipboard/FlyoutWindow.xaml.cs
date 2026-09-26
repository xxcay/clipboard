using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using SharedClipboard.Core;

namespace SharedClipboard;

/// <summary>
/// The panel that pops up above the tray icon: the shared clipboard items,
/// drag files out to the desktop, drop files in (or Ctrl+V) to send them.
/// </summary>
partial class FlyoutWindow : Window
{
    const string DefaultFooter = "Перетащите файлы сюда или нажмите Ctrl+V";

    readonly AppController _app;
    readonly DispatcherTimer _footerTimer = new() { Interval = TimeSpan.FromSeconds(4) };
    Point? _dragStart;
    ListBoxItem? _pressedItem;
    bool _dragging;

    public FlyoutWindow(AppController app)
    {
        _app = app;
        InitializeComponent();
        DataContext = app;
        FooterText.Text = DefaultFooter;
        _footerTimer.Tick += (_, _) =>
        {
            _footerTimer.Stop();
            FooterText.Text = DefaultFooter;
            FooterText.Foreground = (Brush)FindResource("Muted");
        };
        app.Items.CollectionChanged += (_, _) => UpdateEmpty();
        UpdateEmpty();
    }

    /// <summary>When the flyout last hid itself; used to make the tray click a toggle.</summary>
    public DateTime HiddenAt { get; private set; }

    /// <summary>True while a modal dialog (Save as…) is open, so the panel does not hide.</summary>
    public bool KeepOpen { get; set; }

    public void ShowAtTray()
    {
        Theme.Apply();
        var area = SystemParameters.WorkArea;
        Left = area.Right - Width;
        Top = area.Bottom - Height;
        Show();
        Activate();
        List.Focus();
    }

    public void ShowStatus(string text, bool error = false)
    {
        FooterText.Text = text;
        FooterText.Foreground = (Brush)FindResource(error ? "Danger" : "Fg");
        _footerTimer.Stop();
        _footerTimer.Start();
    }

    /// <summary>Progress messages stay until replaced.</summary>
    public void ShowProgress(string text)
    {
        _footerTimer.Stop();
        FooterText.Text = text;
        FooterText.Foreground = (Brush)FindResource("Fg");
    }

    public void ClearProgress() => _footerTimer.Start();

    public void UpdateHeader(HubState state, IReadOnlyList<string> devices, string? error)
    {
        var (color, text) = state switch
        {
            HubState.Online => ("Ok", devices.Count > 0 ? "Онлайн: " + string.Join(", ", devices) : "Онлайн"),
            HubState.Connecting => ("Muted", "Подключение…"),
            HubState.AuthFailed => ("Danger", "Неверный токен — откройте настройки"),
            HubState.NotConfigured => ("Danger", "Не настроено — откройте настройки"),
            _ => ("Danger", error ?? "Нет связи с роутером"),
        };
        StatusDot.Fill = (Brush)FindResource(color);
        StatusText.Text = text;
    }

    void UpdateEmpty() => EmptyText.Visibility = _app.Items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

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
