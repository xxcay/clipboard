using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Threading;
using Microsoft.Win32;
using SharedClipboard.Core;

namespace SharedClipboard;

/// <summary>Ties together the hub connection, the file cache, the tray icon and the panel.</summary>
sealed class AppController(Dispatcher ui) : IDisposable
{
    static readonly string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SharedClipboard");
    static readonly string SettingsPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "SharedClipboard", "settings.json");
    static readonly string TempDir = Path.Combine(DataDir, "outgoing");

    readonly HubClient _hub = new();
    FileCache _cache = null!;
    TrayIcon _tray = null!;
    FlyoutWindow _flyout = null!;
    SettingsWindow? _settingsWindow;
    int _unread;

    public ObservableCollection<ItemViewModel> Items { get; } = [];
    public AppSettings Settings { get; private set; } = new();

    public void Start(bool showPanel)
    {
        Directory.CreateDirectory(DataDir);
        Log.Path = Path.Combine(DataDir, "log.txt");
        Log.Write("start");
        Settings = AppSettings.Load(SettingsPath);
        Theme.Apply();
        CleanTemp();

        _cache = new FileCache(Path.Combine(DataDir, "cache"), _hub);
        _flyout = new FlyoutWindow(this);
        _tray = new TrayIcon(this);

        _hub.StateChanged += _ => ui.BeginInvoke(UpdateStatus);
        _hub.DevicesChanged += _ => ui.BeginInvoke(UpdateStatus);
        _hub.Synced += items => ui.BeginInvoke(() => OnSynced(items));
        _hub.ItemAdded += item => ui.BeginInvoke(() => OnItemAdded(item));
        _hub.ItemRemoved += id => ui.BeginInvoke(() => OnItemRemoved(id));

        if (Settings.Autostart)
            Autostart.Apply(true); // keeps the path right if the .exe was moved

        if (Settings.IsConfigured)
        {
            _hub.Configure(Settings);
            if (showPanel)
                ShowFlyout();
        }
        else
        {
            ShowSettings();
        }
        UpdateStatus();
    }

    public void Dispose()
    {
        _hub.Dispose();
        _tray?.Dispose();
    }

    public void Exit()
    {
        Dispose();
        Application.Current.Shutdown();
    }

    // ---- Hub events (on the UI thread) ----

    bool IsMine(ClipItem item) => item.From == Settings.DeviceName.Trim();

    void OnSynced(IReadOnlyList<ClipItem> items)
    {
        Items.Clear();
        foreach (var item in items.Reverse())
        {
            var vm = new ItemViewModel(item, IsMine(item));
            Items.Add(vm);
            Prefetch(vm);
        }
        _cache.Cleanup(items.Select(i => i.Id));
    }

    void OnItemAdded(ClipItem item)
    {
        var vm = new ItemViewModel(item, IsMine(item));
        Items.Insert(0, vm);
        if (vm.IsMine)
        {
            // Our own upload: the file is put into the cache when the upload
            // finishes, no need to download it back.
            if (vm.IsFile)
                ui.BeginInvoke(async () =>
                {
                    await Task.Delay(TimeSpan.FromSeconds(5));
                    Prefetch(vm);
                });
            Prefetch(vm, onlyIfCached: true);
            return;
        }

        Prefetch(vm);
        if (Settings.AutoCopyText && !vm.IsFile && item.Text != null)
        {
            try
            {
                ClipboardService.SetText(item.Text);
            }
            catch (Exception e)
            {
                Log.Write($"auto copy: {e.Message}");
            }
        }
        if (!_flyout.IsVisible)
        {
            _unread++;
            if (Settings.Notifications)
                _tray.Notify($"От {item.From}", Describe(item));
        }
        UpdateStatus();
    }

    void OnItemRemoved(string id)
    {
        var vm = Items.FirstOrDefault(i => i.Id == id);
        if (vm != null)
            Items.Remove(vm);
        _cache.Remove(id);
    }

    static string Describe(ClipItem item)
    {
        if (item.IsFile)
            return $"{item.File!.Name} ({Format.Size(item.File.Size)})";
        var text = (item.Text ?? "").Trim().ReplaceLineEndings(" ");
        return text.Length > 120 ? text[..120] + "…" : text;
    }

    async void Prefetch(ItemViewModel vm, bool onlyIfCached = false)
    {
        if (!vm.IsFile || vm.IsReady)
            return;
        if (_cache.IsReady(vm.Item))
        {
            vm.IsReady = true;
            vm.LoadThumbnail(_cache.PathFor(vm.Item));
            return;
        }
        if (onlyIfCached)
            return;
        try
        {
            var path = await _cache.EnsureAsync(vm.Item, new Progress<double>(p => vm.Progress = p));
            vm.IsReady = true;
            vm.LoadThumbnail(path);
        }
        catch (Exception e)
        {
            vm.Progress = 0;
            Log.Write($"prefetch {vm.Item.File!.Name}: {e.Message}");
        }
    }

    void UpdateStatus()
    {
        var state = Settings.IsConfigured ? _hub.State : HubState.NotConfigured;
        _flyout.UpdateConnection(state, _hub.LastError);
        var tip = state switch
        {
            HubState.Online or HubState.Connecting => "Общий буфер",
            HubState.AuthFailed => "Общий буфер — неверный токен",
            HubState.NotConfigured => "Общий буфер — не настроено",
            _ => "Общий буфер — нет связи с роутером",
        };
        if (_unread > 0)
            tip += $"\nНовых: {_unread}";
        _tray.Update(state, _unread, tip);
    }

    // ---- Panel ----

    public void ToggleFlyout()
    {
        if (_flyout.IsVisible)
        {
            _flyout.Hide();
            return;
        }
        // A click on the tray icon first deactivates (and hides) the panel;
        // don't reopen it right away, so the click works as a toggle.
        if ((DateTime.UtcNow - _flyout.HiddenAt).TotalMilliseconds < 400)
            return;
        ShowFlyout();
    }

    public void ShowFlyout()
    {
        if (_settingsWindow != null)
        {
            _settingsWindow.Activate();
            return;
        }
        _unread = 0;
        UpdateStatus();
        _flyout.ShowAtTray();
    }

    public void ShowSettings()
    {
        if (_settingsWindow != null)
        {
            _settingsWindow.Activate();
            return;
        }
        _flyout.Hide();
        _settingsWindow = new SettingsWindow(Settings);
        try
        {
            if (_settingsWindow.ShowDialog() != true)
                return;
            Settings = _settingsWindow.Result;
        }
        finally
        {
            _settingsWindow = null;
        }
        try
        {
            Settings.Save(SettingsPath);
        }
        catch (Exception e)
        {
            Log.Write($"save settings: {e.Message}");
        }
        Autostart.Apply(Settings.Autostart);
        Items.Clear();
        _hub.Configure(Settings);
        UpdateStatus();
        ShowFlyout();
    }

    public void SetAutostart(bool enabled)
    {
        Settings.Autostart = enabled;
        Autostart.Apply(enabled);
        try
        {
            Settings.Save(SettingsPath);
        }
        catch (Exception e)
        {
            Log.Write($"save settings: {e.Message}");
        }
    }

    /// <summary>Shows a message in the panel, or as a notification when the panel is hidden.</summary>
    void Status(string text, bool error = false)
    {
        if (_flyout.IsVisible)
            _flyout.ShowStatus(text, error);
        else
            _tray.Notify(error ? "Общий буфер — ошибка" : "Общий буфер", text, error);
    }

    // ---- Sending ----

    public void SendClipboard()
    {
        ClipboardContent content;
        try
        {
            content = ClipboardService.Read(TempDir);
        }
        catch (Exception e)
        {
            Log.Write($"read clipboard: {e}");
            Status("Не удалось прочитать буфер обмена", true);
            return;
        }
        switch (content)
        {
            case ClipboardContent.Files f:
                UploadFiles(f.Paths);
                break;
            case ClipboardContent.Image img:
                _ = UploadFilesAsync([img.Path], deleteAfter: true);
                break;
            case ClipboardContent.Text t:
                SendText(t.Value);
                break;
            default:
                Status("Буфер обмена пуст");
                break;
        }
    }

    public async void SendText(string text)
    {
        try
        {
            await _hub.SendTextAsync(text);
            Status("Текст отправлен");
        }
        catch (ClipException e)
        {
            Status(e.Message, true);
        }
        catch (Exception e)
        {
            Log.Write($"send text: {e}");
            Status("Не удалось отправить текст", true);
        }
    }

    public void UploadFiles(IEnumerable<string> paths) => _ = UploadFilesAsync(paths);

    async Task UploadFilesAsync(IEnumerable<string> paths, bool deleteAfter = false)
    {
        var sent = new List<string>();
        foreach (var path in paths)
        {
            var name = Path.GetFileName(path.TrimEnd('\\', '/'));
            if (Directory.Exists(path))
            {
                Status($"«{name}» — это папка. Папки пока не отправляются: запакуйте её в zip.", true);
                continue;
            }
            try
            {
                var progress = new Progress<double>(p =>
                {
                    if (_flyout.IsVisible)
                        _flyout.ShowProgress($"Отправка {name}… {p:P0}");
                });
                var item = await _hub.UploadFileAsync(path, progress: progress);
                _cache.Adopt(item, path);
                if (Items.FirstOrDefault(i => i.Id == item.Id) is { } vm)
                    Prefetch(vm, onlyIfCached: true);
                sent.Add(name);
            }
            catch (ClipException e)
            {
                Status(e.Message, true);
            }
            catch (Exception e)
            {
                Log.Write($"upload {path}: {e}");
                Status($"{name}: не удалось отправить", true);
            }
            finally
            {
                if (deleteAfter)
                    TryDelete(path);
            }
        }
        _flyout.ClearProgress();
        if (sent.Count == 1)
            Status($"Отправлено: {sent[0]}");
        else if (sent.Count > 1)
            Status($"Отправлено файлов: {sent.Count}");
    }

    // ---- Item actions ----

    async Task<List<string>?> EnsureFiles(IEnumerable<ItemViewModel> items)
    {
        var paths = new List<string>();
        foreach (var vm in items)
        {
            try
            {
                if (!_cache.IsReady(vm.Item))
                    _flyout.ShowProgress($"Загрузка {vm.Item.File!.Name}…");
                paths.Add(await _cache.EnsureAsync(vm.Item, new Progress<double>(p => vm.Progress = p)));
                vm.IsReady = true;
            }
            catch (ClipException e)
            {
                Status(e.Message, true);
                return null;
            }
            catch (Exception e)
            {
                Log.Write($"download {vm.Item.File!.Name}: {e}");
                Status($"{vm.Item.File!.Name}: не удалось загрузить", true);
                return null;
            }
        }
        _flyout.ClearProgress();
        return paths;
    }

    public async void Copy(IReadOnlyList<ItemViewModel> items)
    {
        try
        {
            var files = items.Where(i => i.IsFile).ToList();
            if (files.Count > 0)
            {
                var paths = await EnsureFiles(files);
                if (paths == null)
                    return;
                ClipboardService.SetFiles(paths);
                Status(paths.Count == 1 ? "Файл скопирован — вставьте Ctrl+V в папке" : $"Скопировано файлов: {paths.Count}");
                return;
            }
            ClipboardService.SetText(string.Join(Environment.NewLine, items.Select(i => i.Item.Text)));
            Status("Скопировано");
        }
        catch (Exception e)
        {
            Log.Write($"copy: {e}");
            Status("Не удалось скопировать", true);
        }
    }

    public async void PrimaryAction(ItemViewModel vm)
    {
        if (vm.IsText)
        {
            Copy([vm]);
            return;
        }
        if (vm.IsUrl)
        {
            Open(vm.Item.Text!);
            return;
        }
        if (await EnsureFiles([vm]) is [var path])
            Open(path);
    }

    public async void ShowInFolder(ItemViewModel vm)
    {
        if (await EnsureFiles([vm]) is [var path])
            Process.Start("explorer.exe", $"/select,\"{path}\"");
    }

    public async void SaveAs(ItemViewModel vm)
    {
        if (await EnsureFiles([vm]) is not [var path])
            return;
        var ext = Path.GetExtension(vm.Item.File!.Name);
        var dialog = new SaveFileDialog
        {
            FileName = vm.Item.File.Name,
            InitialDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads"),
            Filter = ext.Length > 0 ? $"*{ext}|*{ext}|Все файлы|*.*" : "Все файлы|*.*",
        };
        _flyout.KeepOpen = true;
        try
        {
            if (dialog.ShowDialog() == true)
            {
                File.Copy(path, dialog.FileName, overwrite: true);
                Status($"Сохранено: {Path.GetFileName(dialog.FileName)}");
            }
        }
        catch (Exception e)
        {
            Status($"Не удалось сохранить: {e.Message}", true);
        }
        finally
        {
            _flyout.KeepOpen = false;
        }
    }

    public async void Delete(ItemViewModel vm)
    {
        try
        {
            await _hub.DeleteAsync(vm.Id);
        }
        catch (ClipException e)
        {
            Status(e.Message, true);
        }
        catch (Exception e)
        {
            Log.Write($"delete: {e}");
            Status("Не удалось удалить", true);
        }
    }

    /// <summary>Data for dragging items out of the panel: files and/or text.</summary>
    public DataObject? BuildDragData(IReadOnlyList<ItemViewModel> items)
    {
        var files = new StringCollection();
        var texts = new List<string>();
        var waiting = 0;
        foreach (var vm in items)
        {
            if (vm.IsFile)
            {
                if (_cache.IsReady(vm.Item))
                    files.Add(_cache.PathFor(vm.Item));
                else
                {
                    waiting++;
                    Prefetch(vm);
                }
            }
            else if (vm.Item.Text != null)
            {
                texts.Add(vm.Item.Text);
            }
        }
        if (waiting > 0)
            Status("Файл ещё загружается — попробуйте через секунду");
        if (files.Count == 0 && texts.Count == 0)
            return null;
        var data = new DataObject();
        if (files.Count > 0)
            data.SetFileDropList(files);
        if (texts.Count > 0)
            data.SetText(string.Join(Environment.NewLine, texts));
        return data;
    }

    void Open(string target)
    {
        try
        {
            Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
        }
        catch (Exception e)
        {
            Status($"Не удалось открыть: {e.Message}", true);
        }
    }

    static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch
        {
        }
    }

    static void CleanTemp()
    {
        try
        {
            if (Directory.Exists(TempDir))
                Directory.Delete(TempDir, recursive: true);
        }
        catch
        {
        }
    }
}
