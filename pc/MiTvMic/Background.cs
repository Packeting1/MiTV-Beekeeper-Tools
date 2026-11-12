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

        // 后台进程的入口，阻塞到收到停止信号
        public static int Run()
        {
            bool first;
            using (EventWaitHandle stop = new EventWaitHandle(false, EventResetMode.ManualReset, StopName))
            using (Mutex owner = new Mutex(true, MutexName, out first))
            {
                if (!first) return 0;
                MicWorker worker = new MicWorker();
                worker.Start();
                stop.WaitOne();
                worker.Stop();
                return 0;
            }
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
