using System.Windows;
using SharedClipboard.Core;

namespace SharedClipboard;

partial class App : Application
{
    const string InstanceName = @"Local\SharedClipboard.Instance";
    const string ShowEventName = @"Local\SharedClipboard.Show";

    Mutex? _instance;
    AppController? _controller;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        AppDomain.CurrentDomain.UnhandledException += (_, a) =>
            CrashReport.Show("Программа аварийно завершилась", a.ExceptionObject as Exception);

        // One copy per user. Starting it again just opens the panel.
        _instance = new Mutex(true, InstanceName, out var first);
        if (!first && e.Args.Contains("--updated"))
        {
            // The old version is still closing: wait for it.
            try
            {
                first = _instance.WaitOne(TimeSpan.FromSeconds(10));
            }
            catch (AbandonedMutexException)
            {
                first = true;
            }
        }
        if (!first)
        {
            try
            {
                EventWaitHandle.OpenExisting(ShowEventName).Set();
            }
            catch
            {
            }
            Shutdown();
            return;
        }
        var show = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        new Thread(() =>
        {
            while (show.WaitOne())
                Dispatcher.BeginInvoke(() => _controller?.ShowFlyout());
        })
        { IsBackground = true, Name = "show-signal" }.Start();

        DispatcherUnhandledException += (_, a) =>
        {
            Log.Write("unhandled: " + a.Exception);
            a.Handled = true;
        };

        try
        {
            _controller = new AppController(Dispatcher);
            _controller.Start(showPanel: !e.Args.Contains("--autostart"), updated: e.Args.Contains("--updated"));
        }
        catch (Exception ex)
        {
            CrashReport.Show("Программа не смогла запуститься", ex);
            Shutdown(1);
        }
    }

    /// <summary>Starts the freshly installed version and closes this one.</summary>
    public void Restart(string exe)
    {
        _controller?.Dispose();
        try
        {
            _instance?.ReleaseMutex();
        }
        catch
        {
        }
        _instance?.Dispose();
        _instance = null;
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(exe, "--updated") { UseShellExecute = false });
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _controller?.Dispose();
        _instance?.Dispose();
        base.OnExit(e);
    }
}

/// <summary>
/// Last-resort error report: written to %LOCALAPPDATA%\SharedClipboard\crash.txt
/// and shown in a message box, so the app never just silently disappears.
/// </summary>
static class CrashReport
{
    public static readonly string Path = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "SharedClipboard", "crash.txt");

    public static void Show(string title, Exception? ex)
    {
        var text = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {title}{Environment.NewLine}{ex}{Environment.NewLine}";
        try
        {
            System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
            System.IO.File.AppendAllText(Path, text + Environment.NewLine);
        }
        catch
        {
        }
        Log.Write(text);
        try
        {
            MessageBox.Show($"{title}:{Environment.NewLine}{Environment.NewLine}{ex?.Message}{Environment.NewLine}{Environment.NewLine}" +
                $"Подробности сохранены в файл:{Environment.NewLine}{Path}",
                "Общий буфер", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        catch
        {
        }
    }
}
