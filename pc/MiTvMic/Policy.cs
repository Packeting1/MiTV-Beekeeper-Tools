using System;
using System.Runtime.InteropServices;

namespace MiTvMic
{
    // 未公开的 IPolicyConfig：声音设置面板用的就是它
    static class Policy
    {
        [ComImport, Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9")]
        class PolicyConfigCom { }

        // 只用后两个方法，前面的占位，顺序不能变
        [ComImport, Guid("F8679F50-850A-41CF-9C72-430F290290C8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IPolicyConfig
        {
            [PreserveSig] int GetMixFormat([MarshalAs(UnmanagedType.LPWStr)] string dev, IntPtr fmt);
            [PreserveSig] int GetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string dev, int def, IntPtr fmt);
            [PreserveSig] int ResetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string dev);
            [PreserveSig] int SetDeviceFormat([MarshalAs(UnmanagedType.LPWStr)] string dev, IntPtr a, IntPtr b);
            [PreserveSig] int GetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)] string dev, int def, IntPtr a, IntPtr b);
            [PreserveSig] int SetProcessingPeriod([MarshalAs(UnmanagedType.LPWStr)] string dev, IntPtr a);
            [PreserveSig] int GetShareMode([MarshalAs(UnmanagedType.LPWStr)] string dev, IntPtr mode);
            [PreserveSig] int SetShareMode([MarshalAs(UnmanagedType.LPWStr)] string dev, IntPtr mode);
            [PreserveSig] int GetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string dev, IntPtr key, IntPtr value);
            [PreserveSig] int SetPropertyValue([MarshalAs(UnmanagedType.LPWStr)] string dev, IntPtr key, IntPtr value);
            [PreserveSig] int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string dev, int role);
            [PreserveSig] int SetEndpointVisibility([MarshalAs(UnmanagedType.LPWStr)] string dev, int visible);
        }

        static void Call(Func<IPolicyConfig, int> action, string what)
        {
            IPolicyConfig policy = (IPolicyConfig)new PolicyConfigCom();
            try
            {
                int hr = action(policy);
                if (hr != 0) throw new InvalidOperationException(what + " 返回 0x" + hr.ToString("X8"));
            }
            finally
            {
                Marshal.ReleaseComObject(policy);
            }
        }

        public static void SetDefault(string id, int role)
        {
            Call(delegate (IPolicyConfig p) { return p.SetDefaultEndpoint(id, role); }, "SetDefaultEndpoint(角色 " + role + ")");
        }

        // false 等于声音设置里的「禁用」：端点从播放 / 录制列表里消失，注册表 DeviceState 变 2
        public static void SetVisible(string id, bool visible)
        {
            Call(delegate (IPolicyConfig p) { return p.SetEndpointVisibility(id, visible ? 1 : 0); }, "SetEndpointVisibility");
        }
    }
}
