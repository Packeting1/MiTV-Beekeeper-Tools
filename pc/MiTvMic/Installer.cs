using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32;

namespace MiTvMic
{
    // 当前用户级安装，默认位置为 %LOCALAPPDATA%\Programs\MiTvMic。
    // 后台进程是同一个 exe 的 --worker 模式，是否开机自启由界面上的勾选框控制（注册表 Run 项）。
    static class Installer
    {
        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string RunValue = "MiTvMic";
        const string ApprovedKey = @"Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run";
        const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\MiTvMic";

        static readonly string DefaultDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "MiTvMic");

        static string Dir = InstalledDirectory();

        static string Exe { get { return Path.Combine(Dir, "MiTvMic.exe"); } }

        static string InstalledDirectory()
        {
            using (RegistryKey key = Registry.CurrentUser.OpenSubKey(UninstallKey))
            {
                string dir = key == null ? null : key.GetValue("InstallLocation") as string;
                return string.IsNullOrEmpty(dir) ? DefaultDir : dir;
            }
        }

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

        static bool SameFile(string a, string b)
        {
            try
            {
                if (new FileInfo(a).Length != new FileInfo(b).Length) return false;
                using (SHA256 sha = SHA256.Create())
                using (FileStream x = File.OpenRead(a))
                using (FileStream y = File.OpenRead(b))
                {
                    byte[] hx = sha.ComputeHash(x), hy = sha.ComputeHash(y);
                    for (int i = 0; i < hx.Length; i++) if (hx[i] != hy[i]) return false;
                    return true;
                }
            }
            catch (IOException) { return false; }
            catch (UnauthorizedAccessException) { return false; }
        }

        // 只有安装目录里的副本能运行界面。其他位置的副本只能安装/更新或转交给已安装的副本。
        // 返回 false 表示当前就是已安装的副本，继续运行；返回 true 表示当前进程应当退出
        public static bool Offer()
        {
            if (IsInstalledCopy) return false;
            Version mine = Update.Current;
            Version installed = InstalledVersion();
            bool sameVersion = installed != null && installed == mine;
            if (installed != null && (installed > mine || sameVersion && SameFile(Exe, Env.SelfExe)))
            {
                Launch();
                return true;
            }

            bool fresh = installed == null;
            string directory = Dir;
            if (fresh)
            {
                directory = ChooseDirectory(directory);
                if (directory == null) return true;
            }
            else
            {
                string text = sameVersion
                    ? "检测到同版本的不同安装包，是否重新安装 MiTvMic " + Update.Format(mine) + "？\n配置和配对记录会保留。"
                    : "已安装 MiTvMic " + Update.Format(installed) + "，是否更新到 " + Update.Format(mine) + "？";
                string action = sameVersion ? "修复" : "更新";
                if (!Dialog.Ask(null, action + " MiTvMic", text, action, "取消")) return true;
            }
            try
            {
                Install(directory, fresh);
                Dir = directory;
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

        // 位置不合法时说明原因并让用户重新输入；点「取消」返回 null
        static string ChooseDirectory(string initial)
        {
            while (true)
            {
                string input = Dialog.AskInstallDirectory(initial);
                if (input == null) return null;
                try
                {
                    return CheckDirectory(input);
                }
                catch (Exception e)
                {
                    initial = input;
                    Dialog.Info(null, "安装位置无效", e.Message);
                }
            }
        }

        static string CheckDirectory(string input)
        {
            string directory = input.Trim();
            string root = Path.GetPathRoot(directory);
            if (root == null || root.Length != 3 || root[1] != ':' || root[2] != '\\')
                throw new IOException("请输入完整的安装目录，例如 D:\\Apps\\MiTvMic。");
            directory = Path.GetFullPath(directory);
            string data = Path.GetFullPath(Env.DataDir).TrimEnd('\\') + "\\";
            if ((directory.TrimEnd('\\') + "\\").StartsWith(data, StringComparison.OrdinalIgnoreCase))
                throw new IOException("请选择配置数据目录以外的安装位置。");
            if (directory.Length > root.Length) directory = directory.TrimEnd('\\');
            string exe = Path.Combine(directory, "MiTvMic.exe");
            if (File.Exists(exe) || File.Exists(exe + ".old"))
                throw new IOException("所选目录中已有 MiTvMic.exe 或 MiTvMic.exe.old，请选择其他安装位置。");
            return directory;
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

        static void Install(string directory, bool fresh)
        {
            Background.Stop(5000);
            string exe = Path.Combine(directory, "MiTvMic.exe");
            Directory.CreateDirectory(directory);
            if (fresh)
            {
                File.Copy(Env.SelfExe, exe, false);
            }
            else
            {
                Setup.KillExe(exe);
                string old = exe + ".old";
                try { if (File.Exists(old)) File.Delete(old); }
                catch (Exception) { }
                if (File.Exists(exe))
                {
                    try { File.Move(exe, old); }
                    catch (Exception) { }
                }
                File.Copy(Env.SelfExe, exe, true);
                try { File.Delete(old); }
                catch (Exception) { }
            }

            using (RegistryKey k = Registry.CurrentUser.CreateSubKey(UninstallKey))
            {
                k.SetValue("DisplayName", "MiTvMic 电视麦克风");
                k.SetValue("DisplayVersion", Update.Format(Update.Current));
                k.SetValue("Publisher", "Packeting1");
                k.SetValue("InstallLocation", directory);
                k.SetValue("DisplayIcon", exe);
                k.SetValue("UninstallString", "\"" + exe + "\" --uninstall-app");
                k.SetValue("NoModify", 1, RegistryValueKind.DWord);
                k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            }
            CreateLink(DesktopLink, exe, directory);
            CreateLink(StartMenuLink, exe, directory);
        }

        // 卸载：后台进程、自启项、快捷方式、卸载项、防火墙规则和数据目录。
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
            try { File.Delete(Exe + ".old"); }
            catch (Exception) { }

            Dialog.Info(null, "卸载完成", "MiTvMic 已卸载。");
            // 本进程退出后删除程序。目录为空时才移除，保留目录中的其他文件。
            ProcessStartInfo psi = new ProcessStartInfo("cmd.exe",
                "/d /v:off /c ping -n 3 127.0.0.1 >nul & del /q \"%MITVMIC_UNINSTALL_EXE%\" & rd \"%MITVMIC_UNINSTALL_DIR%\" 2>nul");
            psi.CreateNoWindow = true;
            psi.UseShellExecute = false;
            psi.EnvironmentVariables["MITVMIC_UNINSTALL_EXE"] = Exe;
            psi.EnvironmentVariables["MITVMIC_UNINSTALL_DIR"] = Dir;
            Process.Start(psi);
            return 0;
        }

        static void CreateLink(string path, string exe, string directory)
        {
            IShellLinkW link = (IShellLinkW)new ShellLink();
            link.SetPath(exe);
            link.SetWorkingDirectory(directory);
            link.SetDescription("MiTvMic 电视麦克风");
            link.SetIconLocation(exe, 0);
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
