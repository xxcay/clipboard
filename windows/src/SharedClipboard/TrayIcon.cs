using SharedClipboard.Core;
using Drawing = System.Drawing;
using WinForms = System.Windows.Forms;

namespace SharedClipboard;

/// <summary>The icon in the notification area (the ^ arrow next to Wi-Fi).</summary>
sealed class TrayIcon : IDisposable
{
    readonly WinForms.NotifyIcon _icon;
    readonly WinForms.ToolStripMenuItem _autostart;

    public TrayIcon(AppController app)
    {
        var menu = new WinForms.ContextMenuStrip
        {
            Renderer = new MenuRenderer(),
            Font = new Drawing.Font("Segoe UI", 9.75f),
            Padding = new WinForms.Padding(4),
            ShowImageMargin = false,
            ShowCheckMargin = true,
        };
        menu.Items.Add("Открыть панель", null, (_, _) => app.ShowFlyout());
        var send = new WinForms.ToolStripMenuItem("Отправить буфер обмена", null, (_, _) => app.SendClipboard());
        menu.Items.Add(send);
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add("Настройки…", null, (_, _) => app.ShowSettings());
        _autostart = new WinForms.ToolStripMenuItem("Запускать вместе с Windows") { CheckOnClick = true };
        _autostart.Click += (_, _) => app.SetAutostart(_autostart.Checked);
        menu.Items.Add(_autostart);
        menu.Items.Add("Проверить обновления", null, async (_, _) =>
        {
            var error = await app.CheckUpdatesAsync(quiet: false);
            if (error != null)
                Notify("Общий буфер", error, error: true);
            else if (app.AvailableUpdate is { } v)
                app.ShowFlyout();
            else
                Notify("Общий буфер", $"Установлена последняя версия ({SharedClipboard.Core.Updater.CurrentVersionText})");
        });
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add("Выход", null, (_, _) => app.Exit());
        menu.Opening += (_, _) =>
        {
            _autostart.Checked = app.Settings.Autostart;
            send.ShortcutKeyDisplayString = string.IsNullOrEmpty(app.Settings.Hotkey) ? "" : app.HotkeyText;
        };
        foreach (WinForms.ToolStripItem item in menu.Items)
            item.Padding = new WinForms.Padding(4, 5, 4, 5);

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

    /// <summary>White + orange look for the tray menu.</summary>
    sealed class MenuRenderer() : WinForms.ToolStripProfessionalRenderer(new Palette())
    {
        protected override void OnRenderItemText(WinForms.ToolStripItemTextRenderEventArgs e)
        {
            e.TextColor = Drawing.Color.FromArgb(28, 25, 23);
            base.OnRenderItemText(e);
        }

        sealed class Palette : WinForms.ProfessionalColorTable
        {
            static readonly Drawing.Color White = Drawing.Color.White;
            static readonly Drawing.Color Hover = Drawing.Color.FromArgb(255, 241, 231);
            static readonly Drawing.Color Border = Drawing.Color.FromArgb(240, 232, 226);
            static readonly Drawing.Color Orange = Drawing.Color.FromArgb(255, 106, 31);

            public override Drawing.Color ToolStripDropDownBackground => White;
            public override Drawing.Color MenuBorder => Border;
            public override Drawing.Color MenuItemBorder => Hover;
            public override Drawing.Color MenuItemSelected => Hover;
            public override Drawing.Color MenuItemSelectedGradientBegin => Hover;
            public override Drawing.Color MenuItemSelectedGradientEnd => Hover;
            public override Drawing.Color ImageMarginGradientBegin => White;
            public override Drawing.Color ImageMarginGradientMiddle => White;
            public override Drawing.Color ImageMarginGradientEnd => White;
            public override Drawing.Color SeparatorDark => Border;
            public override Drawing.Color SeparatorLight => White;
            public override Drawing.Color CheckBackground => Hover;
            public override Drawing.Color CheckSelectedBackground => Hover;
            public override Drawing.Color CheckPressedBackground => Hover;
            public override Drawing.Color ButtonSelectedBorder => Orange;
        }
    }
}
