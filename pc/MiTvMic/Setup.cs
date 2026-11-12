using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Security.Principal;
using System.Text;

namespace MiTvMic
{
    // 需要管理员权限的操作：非管理员就 runas 重开自己执行，结果经文件传回。
    enum Op
    {
        Driver,
        DisableWide,
        Firewall,
        Unfirewall
    }

    sealed class Outcome
    {
        public bool Ok;
        public string Message;

        public static Outcome Good(string msg)
        {
            Outcome o = new Outcome();
            o.Ok = true;
            o.Message = msg;
            return o;
        }

        public static Outcome Fail(string msg)
        {
            Outcome o = new Outcome();
            o.Ok = false;
            o.Message = msg;
            return o;
        }
    }

    static class Setup
    {
        const int ErrorCancelled = 1223;
        const string DriverResource = "VBCABLE_Driver_Pack.zip";
        const string Declined = "declined";

        static readonly string DriverDir = Path.Combine(Env.DataDir, "VBCABLE");

        public static string CommandOf(Op op)
        {
            if (op == Op.Driver) return "cable";
            if (op == Op.Firewall) return "firewall";
            if (op == Op.Unfirewall) return "unfirewall";
            return "disable16";
        }

        public static bool TryParse(string cmd, out Op op)
        {
            foreach (Op o in new[] { Op.Driver, Op.DisableWide, Op.Firewall, Op.Unfirewall })
            {
                if (CommandOf(o) != cmd) continue;
                op = o;
                return true;
            }
            op = Op.Driver;
            return false;
        }

        static string Working(Op op)
        {
            if (op == Op.Driver) return "正在安装 VB-CABLE 驱动，请稍候…";
            if (op == Op.DisableWide) return "正在禁用多余的虚拟声卡，请稍候…";
            if (op == Op.Firewall) return "正在添加防火墙规则，请稍候…";
            return "正在删除防火墙规则，请稍候…";
        }

        public static bool IsAdmin()
        {
            return new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);
        }

        static void Say(Action<string> status, string text)
        {
            if (status == null) return;
            try { status(text); }
            catch (Exception) { }
        }

        public static bool FirewallRuleExists()
        {
            int code;
            Exec("netsh.exe", "advfirewall firewall show rule name=\"" + Env.FirewallRule + "\"", out code);
            return code == 0;
        }

        // 规则存在，并且是为当前这份 exe 添加的
        public static bool FirewallReady()
        {
            return ReadMarker() == Env.SelfExe.ToLowerInvariant() && FirewallRuleExists();
        }

        public static bool FirewallDeclined()
        {
            return ReadMarker() == Declined;
        }

        public static void DeclineFirewall()
        {
            WriteMarker(Declined);
        }

        static string ReadMarker()
        {
            return Config.Get("firewall", "").ToLowerInvariant();
        }

        static void WriteMarker(string text)
        {
            try { Config.Set("firewall", text); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        public static Outcome Run(Op op, Action<string> status)
        {
            Outcome o = RunCore(op, status);
            if (o.Ok && op == Op.Firewall) WriteMarker(Env.SelfExe);
            if (o.Ok && op == Op.Unfirewall) WriteMarker("");
            return o;
        }

        static Outcome RunCore(Op op, Action<string> status)
        {
            // 声音设置里的启用/禁用普通用户就能做，不行再提权
            if (op == Op.DisableWide && !IsAdmin())
            {
                Outcome direct = Execute(op);
                if (direct.Ok) return direct;
            }
            if (IsAdmin())
            {
                Say(status, Working(op));
                return Execute(op);
            }

            string resultFile = Path.Combine(Path.GetTempPath(), "MiTvMic-" + Guid.NewGuid().ToString("N") + ".tmp");
            ProcessStartInfo psi = new ProcessStartInfo(Env.SelfExe, CommandOf(op) + " \"" + resultFile + "\"");
            psi.Verb = "runas";
            psi.UseShellExecute = true;
            Say(status, "正在请求管理员权限，请在系统弹出的对话框中选择「是」。");
            Process p;
            try
            {
                p = Process.Start(psi);
            }
            catch (Win32Exception e)
            {
                return Outcome.Fail(e.NativeErrorCode == ErrorCancelled
                    ? "已取消：未授予管理员权限。" : "请求管理员权限失败：" + e.Message);
            }
            // runas 在用户点了「是」才返回，说明子进程已经在干活了
            Say(status, Working(op));
            p.WaitForExit();

            try
            {
                if (File.Exists(resultFile))
                {
                    string[] lines = File.ReadAllLines(resultFile, Encoding.UTF8);
                    File.Delete(resultFile);
                    if (lines.Length >= 2) return lines[0] == "OK" ? Outcome.Good(lines[1]) : Outcome.Fail(lines[1]);
                }
            }
            catch (IOException) { }
            return Outcome.Fail("未获取到执行结果（返回码 " + p.ExitCode + "），详情见日志。");
        }

        public static int RunElevatedChild(Op op, string resultFile)
        {
            Outcome o = Execute(op);
            try
            {
                if (resultFile != null)
                    File.WriteAllLines(resultFile, new[] { o.Ok ? "OK" : "FAIL", o.Message }, new UTF8Encoding(false));
            }
            catch (Exception e)
            {
                Log.Write("写结果文件失败：" + e.Message);
            }
            Console.WriteLine(o.Message);
            return o.Ok ? 0 : 1;
        }

        static Outcome Execute(Op op)
        {
            Outcome o;
            try
            {
                if (op == Op.Driver) o = InstallDriver();
                else if (op == Op.Firewall) o = AddFirewall();
                else if (op == Op.Unfirewall) o = RemoveFirewall();
                else o = DisableWide();
            }
            catch (Exception e)
            {
                o = Outcome.Fail("失败：" + e.Message);
            }
            Log.Write((o.Ok ? "" : "[失败] ") + CommandOf(op) + "：" + o.Message);
            return o;
        }

        static string Exec(string file, string args, out int code)
        {
            ProcessStartInfo psi = new ProcessStartInfo(file, args);
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;
            try
            {
                Encoding oem = Encoding.GetEncoding(CultureInfo.CurrentCulture.TextInfo.OEMCodePage);
                psi.StandardOutputEncoding = oem;
                psi.StandardErrorEncoding = oem;
            }
            catch (Exception) { }
            using (Process p = Process.Start(psi))
            {
                string o = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
                p.WaitForExit();
                code = p.ExitCode;
                return o.Replace("\r", " ").Replace("\n", " ").Trim();
            }
        }

        // 结束指定路径的其他 MiTvMic 进程（不含自己）
        public static void KillExe(string exe)
        {
            int me = Process.GetCurrentProcess().Id;
            foreach (Process p in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(exe)))
            {
                try
                {
                    if (p.Id == me) continue;
                    if (!string.Equals(p.MainModule.FileName, exe, StringComparison.OrdinalIgnoreCase)) continue;
                    p.Kill();
                    p.WaitForExit(5000);
                }
                catch (Exception) { }
            }
        }

        static void DeleteFirewallRule()
        {
            int code;
            Exec("netsh.exe", "advfirewall firewall delete rule name=\"" + Env.FirewallRule + "\"", out code);
        }

        static Outcome AddFirewall()
        {
            DeleteFirewallRule();
            int code;
            string o = Exec("netsh.exe", "advfirewall firewall add rule name=\"" + Env.FirewallRule
                + "\" dir=in action=allow protocol=UDP program=\"" + Env.SelfExe + "\" profile=private,domain", out code);
            if (code != 0) return Outcome.Fail("添加防火墙规则失败：" + o);
            return Outcome.Good("已添加防火墙规则。");
        }

        static Outcome RemoveFirewall()
        {
            DeleteFirewallRule();
            return Outcome.Good("已删除防火墙规则。");
        }

        static string ExtractDriver()
        {
            string setup = Path.Combine(DriverDir,
                Environment.Is64BitOperatingSystem ? "VBCABLE_Setup_x64.exe" : "VBCABLE_Setup.exe");
            if (File.Exists(setup)) return setup;
            using (Stream s = typeof(Setup).Assembly.GetManifestResourceStream(DriverResource))
            {
                if (s == null) return null;
                try { Directory.Delete(DriverDir, true); }
                catch (Exception) { }
                Directory.CreateDirectory(DriverDir);
                string zip = Path.Combine(DriverDir, "pack.zip");
                using (FileStream f = File.Create(zip)) s.CopyTo(f);
                ZipFile.ExtractToDirectory(zip, DriverDir);
                File.Delete(zip);
            }
            return File.Exists(setup) ? setup : null;
        }

        static Outcome InstallDriver()
        {
            Card card = Cards.Probe(Config.Device);
            if (card.State != CardState.Missing)
            {
                return Outcome.Good("虚拟声卡驱动已安装（状态：" + card.StateText + "），无需重复安装。");
            }
            string setup = ExtractDriver();
            if (setup == null)
            {
                return Outcome.Fail("程序未内置驱动包，请到 https://vb-audio.com/Cable/ 手动安装。");
            }
            // "-i -h" 是官方安装程序的静默参数（安装 + 不显示界面）
            ProcessStartInfo psi = new ProcessStartInfo(setup, "-i -h");
            psi.WorkingDirectory = DriverDir;
            psi.UseShellExecute = true;
            using (Process p = Process.Start(psi))
            {
                p.WaitForExit();
                if (p.ExitCode != 0)
                {
                    return Outcome.Fail("驱动安装程序返回 " + p.ExitCode + "，可到 " + DriverDir + " 手动运行。");
                }
            }
            return Outcome.Good("驱动已安装。新安装的播放端需数分钟后才会激活，若长时间未激活，请重启电脑。");
        }

        static Outcome DisableWide()
        {
            int count = 0;
            foreach (Endpoint e in Cards.EnabledWide(Config.Device))
            {
                Policy.SetVisible(e.RenderId, false);
                count++;
            }
            if (count == 0) return Outcome.Good("声音设置中没有已启用的 16 声道播放端，无需禁用。");
            return Outcome.Good("已在声音设置中禁用 " + count + " 个 16 声道播放端。");
        }
    }
}
