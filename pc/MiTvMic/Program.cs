using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace MiTvMic
{
    static class Program
    {
        [DllImport("kernel32.dll")]
        static extern bool AttachConsole(int pid);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern IntPtr FindWindow(string cls, string title);

        [DllImport("user32.dll")]
        static extern bool ShowWindow(IntPtr h, int cmd);

        [DllImport("user32.dll")]
        static extern bool IsIconic(IntPtr h);

        [DllImport("user32.dll")]
        static extern bool SetForegroundWindow(IntPtr h);

        [DllImport("user32.dll")]
        static extern bool BringWindowToTop(IntPtr h);

        [DllImport("user32.dll")]
        static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        static extern int GetWindowThreadProcessId(IntPtr h, out int pid);

        [DllImport("user32.dll")]
        static extern bool AttachThreadInput(int from, int to, bool attach);

        [DllImport("kernel32.dll")]
        static extern int GetCurrentThreadId();

        // 把已开着的窗口带到前台；要先挂到前台窗口的输入线程上，否则会被系统拦截
        static void FocusRunning()
        {
            IntPtr h = FindWindow(null, "MiTvMic 电视麦克风");
            if (h == IntPtr.Zero) return;
            int pid;
            int me = GetCurrentThreadId();
            int front = GetWindowThreadProcessId(GetForegroundWindow(), out pid);
            bool attached = front != 0 && front != me && AttachThreadInput(me, front, true);
            if (IsIconic(h)) ShowWindow(h, 9);
            BringWindowToTop(h);
            SetForegroundWindow(h);
            if (attached) AttachThreadInput(me, front, false);
        }

        // 更新后由旧进程拉起：等旧进程退出，单实例互斥才能被释放
        static void WaitParent(string pid)
        {
            int id;
            if (!int.TryParse(pid, out id)) return;
            try
            {
                using (System.Diagnostics.Process p = System.Diagnostics.Process.GetProcessById(id)) p.WaitForExit(15000);
            }
            catch (Exception) { }
        }

        const string Usage =
            "用法: MiTvMic [cable | disable16 | firewall | unfirewall | on | off | status | devices | defaultmic [set] | run]，不带参数打开界面";

        [STAThread]
        static int Main(string[] args)
        {
            string cmd = args.Length > 0 ? args[0].ToLowerInvariant() : "gui";
            if (cmd == "--wait-parent")
            {
                WaitParent(args.Length > 1 ? args[1] : "");
                cmd = "gui";
            }
            if (cmd != "gui" && cmd != "--worker") AttachConsole(-1);
            try
            {
                Op op;
                if (Setup.TryParse(cmd, out op))
                {
                    if (!Setup.IsAdmin()) return Report(Setup.Run(op, null));
                    return Setup.RunElevatedChild(op, args.Length > 1 ? args[1] : null);
                }

                switch (cmd)
                {
                    case "gui":
                        if (Installer.Offer()) return 0;
                        bool first;
                        using (new Mutex(true, @"Local\MiTvMic.Gui", out first))
                        {
                            if (!first)
                            {
                                FocusRunning();
                                return 0;
                            }
                            Update.CleanOld();
                            System.Windows.Application app = new System.Windows.Application();
                            app.Run(new Ui().Window);
                        }
                        return 0;
                    case "--uninstall-app":
                        return Installer.Uninstall();
                    case "--worker":
                        return Background.Run();
                    case "run":
                        new MicWorker().Start();
                        Thread.Sleep(Timeout.Infinite);
                        return 0;
                    case "on":
                    case "off":
                        Config.Set("enabled", cmd == "on" ? "1" : "0");
                        Console.WriteLine(cmd == "on" ? "已开启麦克风推流。" : "已关闭麦克风推流。");
                        return 0;
                    case "status":
                        Console.WriteLine(Status());
                        return 0;
                    case "devices":
                        foreach (string line in Cards.Describe()) Console.WriteLine(line);
                        return 0;
                    case "defaultmic":
                        if (args.Length > 1 && args[1] == "set") return Report(DefaultMic.Set(DefaultMic.Device));
                        if (args.Length > 2 && args[1] == "id")
                        {
                            DefaultMic.SetId(args[2]);
                            return 0;
                        }
                        Console.WriteLine(DefaultMic.Device + "：" + DefaultMic.State(DefaultMic.Device));
                        foreach (string line in DefaultMic.Current()) Console.WriteLine(line);
                        return 0;
                    default:
                        Console.WriteLine(Usage);
                        return 1;
                }
            }
            catch (Exception e)
            {
                // 提权后是另起进程跑的，控制台看不见，写进日志界面才能看到
                Log.Write("命令 " + cmd + " 失败：" + e.Message);
                Console.WriteLine("失败：" + e.Message);
                return 2;
            }
        }

        static int Report(Outcome o)
        {
            Console.WriteLine(o.Message);
            return o.Ok ? 0 : 1;
        }

        static string Status()
        {
            Card card = Cards.Probe(Config.Device);
            string last = Log.Last();
            return "后台进程：" + Background.StateText + "，开关：" + (Config.Enabled ? "开" : "关")
                + "，虚拟声卡 " + Config.Device + "：" + card.StateText
                + (card.Ready ? "（" + card.WaveName + "）" : "")
                + (last.Length > 0 ? "\n最后日志：" + last : "");
        }
    }
}
