using System;
using System.Diagnostics;
using System.Threading;

namespace MiTvMic
{
    // 后台推流进程（MiTvMic.exe --worker）：无窗口，登录后自启，界面仅用于查看和控制。
    static class Background
    {
        const string MutexName = @"Local\MiTvMic.Worker";
        const string StopName = @"Local\MiTvMic.Worker.Stop";
        const string TrayChangedName = @"Local\MiTvMic.Worker.TrayChanged";

        public static bool Running
        {
            get
            {
                Mutex m;
                if (!Mutex.TryOpenExisting(MutexName, out m)) return false;
                m.Dispose();
                return true;
            }
        }

        public static string StateText
        {
            get { return Running ? "运行中" : "已停止"; }
        }

        // 后台进程等待设置变更或停止信号，仅在显示托盘时运行消息循环。
        public static int Run()
        {
            bool first;
            using (EventWaitHandle stop = new EventWaitHandle(false, EventResetMode.ManualReset, StopName))
            using (Mutex owner = new Mutex(true, MutexName, out first))
            using (EventWaitHandle trayChanged = new EventWaitHandle(false, EventResetMode.AutoReset, TrayChangedName))
            {
                if (!first) return 0;
                MicWorker worker = new MicWorker();
                worker.Start();
                try
                {
                    WaitHandle[] signals = { stop, trayChanged };
                    while (!stop.WaitOne(0))
                    {
                        Config.Reload();
                        if (Config.ShowTray) Tray.Run(stop, trayChanged);
                        else WaitHandle.WaitAny(signals);
                    }
                }
                catch (Exception ex)
                {
                    Log.Write("托盘消息循环失败：" + ex.Message);
                    stop.WaitOne();
                }
                finally { worker.Stop(); }
                return 0;
            }
        }

        public static void RefreshTray()
        {
            EventWaitHandle changed;
            if (!EventWaitHandle.TryOpenExisting(TrayChangedName, out changed)) return;
            using (changed) changed.Set();
        }

        public static void Start()
        {
            if (Running) return;
            ProcessStartInfo psi = new ProcessStartInfo(Env.SelfExe, "--worker");
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            Process.Start(psi);
        }

        // 通知后台进程退出并等它真正结束；超时返回 false
        public static bool Stop(int waitMs)
        {
            EventWaitHandle stop;
            if (EventWaitHandle.TryOpenExisting(StopName, out stop))
            {
                stop.Set();
                stop.Dispose();
            }
            DateTime end = DateTime.Now.AddMilliseconds(waitMs);
            while (Running)
            {
                if (DateTime.Now > end) return false;
                Thread.Sleep(100);
            }
            return true;
        }
    }
}
