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

        // One copy per user. Starting it again just opens the panel.
        _instance = new Mutex(true, InstanceName, out var first);
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
        AppDomain.CurrentDomain.UnhandledException += (_, a) => Log.Write("fatal: " + a.ExceptionObject);

        _controller = new AppController(Dispatcher);
        _controller.Start(showPanel: !e.Args.Contains("--autostart"));
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _controller?.Dispose();
        _instance?.Dispose();
        base.OnExit(e);
    }
}
