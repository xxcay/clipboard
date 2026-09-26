using SharedClipboard.Core;
using WinForms = System.Windows.Forms;

namespace SharedClipboard;

/// <summary>The icon in the notification area (the ^ arrow next to Wi-Fi).</summary>
sealed class TrayIcon : IDisposable
{
    readonly WinForms.NotifyIcon _icon;
    readonly WinForms.ToolStripMenuItem _autostart;

    public TrayIcon(AppController app)
    {
        var menu = new WinForms.ContextMenuStrip();
        menu.Items.Add("Открыть панель", null, (_, _) => app.ShowFlyout());
        menu.Items.Add("Отправить буфер обмена", null, (_, _) => app.SendClipboard());
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add("Настройки…", null, (_, _) => app.ShowSettings());
        _autostart = new WinForms.ToolStripMenuItem("Запускать вместе с Windows") { CheckOnClick = true };
        _autostart.Click += (_, _) => app.SetAutostart(_autostart.Checked);
        menu.Items.Add(_autostart);
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add("Выход", null, (_, _) => app.Exit());
        menu.Opening += (_, _) => _autostart.Checked = app.Settings.Autostart;

        _icon = new WinForms.NotifyIcon
        {
            Icon = AppIcons.Tray(false, false),
            Text = "Общий буфер",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _icon.MouseClick += (_, e) =>
        {
            if (e.Button == WinForms.MouseButtons.Left)
                app.ToggleFlyout();
        };
        _icon.BalloonTipClicked += (_, _) => app.ShowFlyout();
    }

    public void Update(HubState state, int unread, string tooltip)
    {
        _icon.Icon = AppIcons.Tray(state == HubState.Online, unread > 0);
        _icon.Text = tooltip.Length > 127 ? tooltip[..126] + "…" : tooltip;
    }

    /// <summary>A Windows notification (toast) from the tray icon.</summary>
    public void Notify(string title, string text, bool error = false) =>
        _icon.ShowBalloonTip(5000, title, text.Length > 0 ? text : " ", error ? WinForms.ToolTipIcon.Warning : WinForms.ToolTipIcon.None);

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
    }
}
