using System;
using System.Diagnostics;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;

namespace MiTvMic
{
    // 托盘归后台进程所有，主窗口关闭后仍可控制推流。
    sealed class Tray : IDisposable
    {
        readonly EventWaitHandle stop;
        NotifyIcon icon;
        Icon artwork;
        ContextMenuStrip menu;

        Tray(EventWaitHandle stop)
        {
            this.stop = stop;
        }

        public static void Run(EventWaitHandle stop, EventWaitHandle changed)
        {
            using (ApplicationContext context = new ApplicationContext())
            using (Control dispatch = new Control())
            using (Tray tray = new Tray(stop))
            {
                dispatch.CreateControl();
                WaitOrTimerCallback close = delegate
                {
                    try { dispatch.BeginInvoke((Action)context.ExitThread); }
                    catch (InvalidOperationException) { } // 两个信号可能同时到达，此时消息循环已退出。
                };
                RegisteredWaitHandle stopWait = ThreadPool.RegisterWaitForSingleObject(stop,
                    close, null, Timeout.Infinite, true);
                RegisteredWaitHandle changeWait = ThreadPool.RegisterWaitForSingleObject(changed,
                    close, null, Timeout.Infinite, true);
                try
                {
                    tray.Show();
                    Application.Run(context);
                }
                finally
                {
                    changeWait.Unregister(null);
                    stopWait.Unregister(null);
                }
            }
        }

        void Show()
        {
            menu = new ContextMenuStrip();
            menu.Items.Add("打开 MiTvMic", null, OpenWindow);
            ToolStripItem toggle = menu.Items.Add("暂停推流", null, ToggleStreaming);
            menu.Opening += delegate { toggle.Text = Config.Enabled ? "暂停推流" : "恢复推流"; };
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("停止后台进程", null, delegate { stop.Set(); });
            artwork = Icon.ExtractAssociatedIcon(Env.SelfExe);
            icon = new NotifyIcon();
            icon.Icon = artwork ?? SystemIcons.Application;
            icon.Text = "MiTvMic 电视麦克风";
            icon.ContextMenuStrip = menu;
            icon.DoubleClick += OpenWindow;
            icon.Visible = true;
        }

        static void OpenWindow(object sender, EventArgs e)
        {
            try { Process.Start(new ProcessStartInfo(Env.SelfExe) { UseShellExecute = false }); }
            catch (Exception ex) { Log.Write("打开主界面失败：" + ex.Message); }
        }

        static void ToggleStreaming(object sender, EventArgs e)
        {
            try { Config.Set("enabled", Config.Enabled ? "0" : "1"); }
            catch (Exception ex) { Log.Write("切换推流失败：" + ex.Message); }
        }

        public void Dispose()
        {
            if (icon != null) icon.Dispose();
            if (menu != null) menu.Dispose();
            if (artwork != null) artwork.Dispose();
            icon = null;
            menu = null;
            artwork = null;
        }
    }
}
