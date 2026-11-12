using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using Microsoft.Win32;

namespace MiTvMic
{
    // 当前用户级安装：复制到 %LOCALAPPDATA%\Programs\MiTvMic，创建桌面和开始菜单快捷方式，注册卸载项。
    // 后台进程是同一个 exe 的 --worker 模式，是否开机自启由界面上的勾选框控制（注册表 Run 项）。
    static class Installer
    {
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string RunValue = "MiTvMic";
        const string ApprovedKey = @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";
        const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\MiTvMic";

        static readonly string Dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "MiTvMic");

        static readonly string Exe = Path.Combine(Dir, "MiTvMic.exe");

        static string DesktopLink
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "MiTvMic.lnk"); }
        }

        static string StartMenuLink
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "MiTvMic.lnk"); }
        }

        static bool IsInstalledCopy
        {
            get { return string.Equals(Env.SelfExe, Exe, StringComparison.OrdinalIgnoreCase); }
        }

        static Version InstalledVersion()
        {
            try
            {
                if (!File.Exists(Exe)) return null;
                FileVersionInfo fv = FileVersionInfo.GetVersionInfo(Exe);
                return new Version(fv.FileMajorPart, fv.FileMinorPart, fv.FileBuildPart, fv.FilePrivatePart);
            }
            catch (Exception)
            {
                return null;
            }
        }

        // 只有安装目录里的副本能运行界面。其他位置的副本只能安装/更新或转交给已安装的副本。
        // 返回 false 表示当前就是已安装的副本，继续运行；返回 true 表示当前进程应当退出
        public static bool Offer()
        {
            if (IsInstalledCopy) return false;
            Version mine = Update.Current;
            Version installed = InstalledVersion();
            if (installed != null && installed >= mine)
            {
                Launch();
                return true;
            }

            bool fresh = installed == null;
            string text = fresh
                ? "MiTvMic 需要安装后才能使用。\n安装位置：" + Dir + "\n安装后会创建桌面和开始菜单快捷方式。"
                : "已安装 MiTvMic " + Update.Format(installed) + "，是否更新到 " + Update.Format(mine) + "？";
            if (!Dialog.Ask(null, fresh ? "安装 MiTvMic" : "更新 MiTvMic", text, fresh ? "安装" : "更新", "取消"))
                return true;
            try
            {
                Install();
            }
            catch (Exception e)
            {
                Log.Write("安装失败：" + e.Message);
                Dialog.Info(null, "安装失败", e.Message);
                return true;
            }
            Launch();
            return true;
        }

        // 勾选 = Run 项存在，且没有被任务管理器或系统设置禁用（StartupApproved 首字节为奇数表示已禁用）
        public static bool AutostartEnabled()
        {
            using (RegistryKey run = Registry.CurrentUser.OpenSubKey(RunKey))
            {
                if (run == null || run.GetValue(RunValue) == null) return false;
            }
            using (RegistryKey approved = Registry.CurrentUser.OpenSubKey(ApprovedKey))
            {
                byte[] flags = approved == null ? null : approved.GetValue(RunValue) as byte[];
                return flags == null || flags.Length == 0 || (flags[0] & 1) == 0;
            }
        }

        public static void SetAutostart(bool on)
        {
            using (RegistryKey run = Registry.CurrentUser.CreateSubKey(RunKey))
            using (RegistryKey approved = Registry.CurrentUser.CreateSubKey(ApprovedKey))
            {
                if (on) run.SetValue(RunValue, "\"" + Exe + "\" --worker");
                else run.DeleteValue(RunValue, false);
                approved.DeleteValue(RunValue, false);
            }
        }

        static void Launch()
        {
            ProcessStartInfo psi = new ProcessStartInfo(Exe, "--wait-parent " + Process.GetCurrentProcess().Id);
            psi.UseShellExecute = false;
            try
            {
                Process.Start(psi);
            }
            catch (Exception e)
            {
                Log.Write("启动已安装的程序失败：" + e.Message);
            }
        }

        static void Install()
        {
            Background.Stop(5000);
            Setup.KillExe(Exe);
            Directory.CreateDirectory(Dir);
            string old = Exe + ".old";
            try { if (File.Exists(old)) File.Delete(old); }
            catch (Exception) { }
            if (File.Exists(Exe))
            {
                try { File.Move(Exe, old); }
                catch (Exception) { }
            }
            File.Copy(Env.SelfExe, Exe, true);
            try { File.Delete(old); }
            catch (Exception) { }

            CreateLink(DesktopLink);
            CreateLink(StartMenuLink);
            using (RegistryKey k = Registry.CurrentUser.CreateSubKey(UninstallKey))
            {
                k.SetValue("DisplayName", "MiTvMic 电视麦克风");
                k.SetValue("DisplayVersion", Update.Format(Update.Current));
                k.SetValue("Publisher", "Packeting1");
                k.SetValue("InstallLocation", Dir);
                k.SetValue("DisplayIcon", Exe);
                k.SetValue("UninstallString", "\"" + Exe + "\" --uninstall-app");
                k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            }
        }

        // 卸载：后台进程、自启项、快捷方式、卸载项、防火墙规则和数据目录。程序目录在本进程退出后由 cmd 删除
        public static int Uninstall()
        {
            bool firewall = Setup.FirewallRuleExists();
            string text = "将删除程序、快捷方式、开机自启项和配置数据"
                + (firewall ? "，并删除防火墙规则（需要管理员权限）。" : "。")
                + "\n虚拟声卡驱动 VB-CABLE 不会被卸载。";
            if (!Dialog.Ask(null, "卸载 MiTvMic", text, "卸载", "取消")) return 0;

            if (firewall)
            {
                Outcome o = Setup.Run(Op.Unfirewall, null);
                if (!o.Ok)
                {
                    Dialog.Info(null, "删除防火墙规则失败", o.Message);
                    return 1;
                }
            }
            Background.Stop(5000);
            Setup.KillExe(Exe);
            try { SetAutostart(false); }
            catch (Exception) { }
            try { if (Directory.Exists(Env.DataDir)) Directory.Delete(Env.DataDir, true); }
            catch (Exception e) { Log.Write("删除数据目录失败：" + e.Message); }
            foreach (string link in new[] { DesktopLink, StartMenuLink })
            {
                try { if (File.Exists(link)) File.Delete(link); }
                catch (Exception) { }
            }
            try { Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false); }
            catch (Exception) { }
            try { File.Delete(Update.TempFile); }
            catch (Exception) { }

            Dialog.Info(null, "卸载完成", "MiTvMic 已卸载。");
            // 本进程退出后才能删掉自己所在的目录
            ProcessStartInfo psi = new ProcessStartInfo("cmd.exe",
                "/c ping -n 3 127.0.0.1 >nul & rd /s /q \"" + Dir + "\"");
            psi.CreateNoWindow = true;
            psi.UseShellExecute = false;
            Process.Start(psi);
            return 0;
        }

        static void CreateLink(string path)
        {
            IShellLinkW link = (IShellLinkW)new ShellLink();
            link.SetPath(Exe);
            link.SetWorkingDirectory(Dir);
            link.SetDescription("MiTvMic 电视麦克风");
            link.SetIconLocation(Exe, 0);
            ((IPersistFile)link).Save(path, true);
        }

        [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
        class ShellLink { }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
        interface IShellLinkW
        {
            void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder file, int max, IntPtr data, int flags);
            void GetIDList(out IntPtr idList);
            void SetIDList(IntPtr idList);
            void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder name, int max);
            void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string name);
            void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder dir, int max);
            void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string dir);
            void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder args, int max);
            void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string args);
            void GetHotkey(out short hotkey);
            void SetHotkey(short hotkey);
            void GetShowCmd(out int cmd);
            void SetShowCmd(int cmd);
            void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int max, out int index);
            void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path, int index);
            void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string path, int reserved);
            void Resolve(IntPtr hwnd, int flags);
            void SetPath([MarshalAs(UnmanagedType.LPWStr)] string path);
        }
    }
}
