using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace MiTvMic
{
    enum MicState
    {
        Unavailable,
        NotDefault,
        IsDefault
    }

    // 把 CABLE Output 设为默认麦克风（IPolicyConfig，无需管理员权限）
    static class DefaultMic
    {
        const string CaptureKey = @"SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture";

        const int DataFlowCapture = 1;

        public const string Device = "CABLE Output";

        // 控制台、多媒体、通信三个角色
        static readonly int[] Roles = { 0, 1, 2 };

        [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
        class MMDeviceEnumeratorCom { }

        [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDeviceEnumerator
        {
            [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
            [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
        }

        [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDevice
        {
            [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, out IntPtr iface);
            [PreserveSig] int OpenPropertyStore(int access, out IntPtr props);
            [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        }

        // 已启用且在运行的录音端，返回 COM 用的端点 ID
        public static string FindId(string name)
        {
            using (RegistryKey root = Registry.LocalMachine.OpenSubKey(CaptureKey))
            {
                if (root == null) return null;
                foreach (string guid in root.GetSubKeyNames())
                {
                    using (RegistryKey dev = root.OpenSubKey(guid))
                    {
                        if (dev == null) continue;
                        if (Cards.StateOf(dev) != 1) continue;
                        using (RegistryKey props = dev.OpenSubKey("Properties"))
                        {
                            if (props == null) continue;
                            if (!Cards.NameOf(props).Equals(name, StringComparison.OrdinalIgnoreCase)) continue;
                            string id = "{0.0.1.00000000}." + guid;
                            if (Cards.PnpOf(@"SWD\MMDEVAPI\" + id) != Pnp.Active) continue;
                            return id;
                        }
                    }
                }
            }
            return null;
        }

        static string CurrentId(IMMDeviceEnumerator en, int role)
        {
            IMMDevice d;
            if (en.GetDefaultAudioEndpoint(DataFlowCapture, role, out d) != 0 || d == null) return "";
            try
            {
                string id;
                return d.GetId(out id) == 0 && id != null ? id : "";
            }
            finally
            {
                Marshal.ReleaseComObject(d);
            }
        }

        public static MicState State(string name)
        {
            try
            {
                string id = FindId(name);
                if (id == null) return MicState.Unavailable;
                IMMDeviceEnumerator en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
                try
                {
                    foreach (int role in Roles)
                    {
                        if (!CurrentId(en, role).Equals(id, StringComparison.OrdinalIgnoreCase)) return MicState.NotDefault;
                    }
                    return MicState.IsDefault;
                }
                finally
                {
                    Marshal.ReleaseComObject(en);
                }
            }
            catch (Exception e)
            {
                Log.Write("读取默认麦克风失败: " + e.Message);
                return MicState.Unavailable;
            }
        }

        public static List<string> Current()
        {
            List<string> list = new List<string>();
            IMMDeviceEnumerator en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            try
            {
                foreach (int role in Roles) list.Add("角色 " + role + ": " + CurrentId(en, role));
            }
            finally
            {
                Marshal.ReleaseComObject(en);
            }
            return list;
        }

        public static void SetId(string id)
        {
            foreach (int role in Roles) Policy.SetDefault(id, role);
        }

        public static Outcome Set(string name)
        {
            string id = FindId(name);
            if (id == null) return Outcome.Fail("未找到可用的录音设备「" + name + "」。");
            try
            {
                SetId(id);
            }
            catch (Exception e)
            {
                return Outcome.Fail("设置默认麦克风失败：" + e.Message);
            }
            Log.Write("已将「" + name + "」设为默认麦克风");
            return Outcome.Good("已将「" + name + "」设为默认麦克风。");
        }
    }
}
