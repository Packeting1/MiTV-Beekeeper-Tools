using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace MiTvMic
{
    enum CardState
    {
        Missing,
        Inactive,   // 驱动已安装，但播放端尚未激活（刚安装后常见，数分钟后或重启后恢复）
        Disabled,
        Ready
    }

    enum Pnp
    {
        Absent,     // 设备节点不存在（残留的注册表项）
        Dormant,    // 在，但没启动
        Active,     // DN_STARTED
        Disabled    // CM_PROB_DISABLED
    }

    sealed class Endpoint
    {
        public string Id;
        public string Name;
        public int Sound;       // 声音设置里的状态：1 启用 / 2 禁用
        public Pnp Pnp;

        public string PnpId
        {
            get { return @"SWD\MMDEVAPI\{0.0.0.00000000}." + Id; }
        }

        // IPolicyConfig 用的端点 ID
        public string RenderId
        {
            get { return "{0.0.0.00000000}." + Id; }
        }

        public bool Wide
        {
            get { return Cards.IsWide(Name); }
        }

        // 在声音设置中可见：已启用，且设备在运行或被设备管理器禁用。
        // 通过 IPolicyConfig 禁用后注册表状态仍为 1，但设备节点变为 Dormant
        public bool Shown
        {
            get { return Sound == 1 && (Pnp == Pnp.Active || Pnp == Pnp.Disabled); }
        }

        public bool Usable
        {
            get { return Sound == 1 && Pnp == Pnp.Active; }
        }
    }

    sealed class Card
    {
        public CardState State;
        public int WaveIndex = -2;      // Ready 时：要打开的 winmm 设备号
        public string WaveName = "";
        public bool WaveIsWide;
        public bool WideEnabled;        // 声音设置里还启用着 16 声道端，「禁用多余的虚拟声卡」才有意义

        public bool Ready
        {
            get { return State == CardState.Ready; }
        }

        // winmm 的名字被截成 31 个字符，去掉后面那截括号
        public string ShortName
        {
            get
            {
                int i = WaveName.IndexOf(" (", StringComparison.Ordinal);
                return i > 0 ? WaveName.Substring(0, i) : WaveName;
            }
        }
        // 声卡不可用时该怎么办（Ready 时为空）
        public string Hint(string device)
        {
            if (State == CardState.Disabled)
            {
                return "虚拟声卡已被禁用：请在「声音设置 → 更多声音设置 → 播放」中启用「" + device + "」";
            }
            if (State == CardState.Inactive)
            {
                return "虚拟声卡尚未激活：驱动安装后需等待数分钟，若长时间未激活，请重启电脑";
            }
            if (State == CardState.Missing) return "未找到虚拟声卡：请先安装 VB-CABLE 驱动";
            return "";
        }

        public string StateText
        {
            get
            {
                if (State == CardState.Ready) return "正常";
                if (State == CardState.Disabled) return "被禁用";
                if (State == CardState.Inactive) return "未激活";
                return "未找到";
            }
        }
    }

    // 虚拟声卡的状态判定：
    // - winmm 只列出声音设置中已启用的端点，被 pnputil 禁用的端点仍会列出，
    //   MMDevices 的 DeviceState 也不变，因此是否可用以设备管理器状态为准
    // - 刚安装驱动时播放端处于「未激活」：注册表状态为 1，winmm 不可见，需等待数分钟或重启
    static class Cards
    {
        const string RenderKey = @"SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render";
        const string PkeyDevice = "{a45c254e-df1c-4efd-8020-67d146a850e0}";

        [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
        static extern int CM_Locate_DevNodeW(out int devInst, string deviceId, int flags);

        [DllImport("cfgmgr32.dll")]
        static extern int CM_Get_DevNode_Status(out int status, out int problem, int devInst, int flags);

        public static Pnp PnpOf(string instanceId)
        {
            int inst;
            if (CM_Locate_DevNodeW(out inst, instanceId, 0) != 0)
            {
                // 0x1 = CM_LOCATE_DEVNODE_PHANTOM：连当前不存在的节点也找
                return CM_Locate_DevNodeW(out inst, instanceId, 1) == 0 ? Pnp.Dormant : Pnp.Absent;
            }
            int status, problem;
            if (CM_Get_DevNode_Status(out status, out problem, inst, 0) != 0) return Pnp.Dormant;
            if (problem == 22) return Pnp.Disabled;                    // CM_PROB_DISABLED
            return (status & 0x8) != 0 ? Pnp.Active : Pnp.Dormant;    // DN_STARTED
        }

        public static string PnpText(Pnp p)
        {
            if (p == Pnp.Active) return "已激活";
            if (p == Pnp.Disabled) return "已禁用";
            if (p == Pnp.Dormant) return "未激活";
            return "不存在";
        }

        static string Str(RegistryKey k, string name)
        {
            object v = k.GetValue(name);
            return v == null ? "" : v.ToString();
        }

        // 端点的显示名：优先 ,14，为空退回 ,2
        public static string NameOf(RegistryKey props)
        {
            string name = Str(props, PkeyDevice + ",14");
            return name.Length > 0 ? name : Str(props, PkeyDevice + ",2");
        }

        // DeviceState 只有低 4 位是状态，高位是内部标志
        public static int StateOf(RegistryKey dev)
        {
            object state = dev.GetValue("DeviceState");
            return state is int ? (int)state & 0xF : 0;
        }

        public static bool IsWide(string name)
        {
            return name.IndexOf("16ch", StringComparison.OrdinalIgnoreCase) >= 0;
        }

        // 注册表里已启用 / 已禁用的播放端（已移除、未插入的是残留，跳过）
        static List<Endpoint> ReadAll()
        {
            List<Endpoint> list = new List<Endpoint>();
            using (RegistryKey root = Registry.LocalMachine.OpenSubKey(RenderKey))
            {
                if (root == null) return list;
                foreach (string id in root.GetSubKeyNames())
                {
                    using (RegistryKey dev = root.OpenSubKey(id))
                    {
                        if (dev == null) continue;
                        int s = StateOf(dev);
                        if (s != 1 && s != 2) continue;
                        using (RegistryKey props = dev.OpenSubKey("Properties"))
                        {
                            if (props == null) continue;
                            string name = NameOf(props);
                            if (name.Length == 0) continue;
                            Endpoint e = new Endpoint();
                            e.Id = id;
                            e.Name = name;
                            e.Sound = s;
                            list.Add(e);
                        }
                    }
                }
            }
            return list;
        }

        static List<Endpoint> Matching(string device)
        {
            List<Endpoint> list = new List<Endpoint>();
            foreach (Endpoint e in ReadAll())
            {
                if (e.Name.IndexOf(device, StringComparison.OrdinalIgnoreCase) < 0) continue;
                e.Pnp = PnpOf(e.PnpId);
                if (e.Pnp != Pnp.Absent) list.Add(e);
            }
            return list;
        }

        // winmm 名字被截到 31 个字符，按前缀对应，多个匹配取最长的
        static Endpoint EndpointOf(List<Endpoint> eps, string waveName)
        {
            Endpoint best = null;
            foreach (Endpoint e in eps)
            {
                if (!waveName.StartsWith(e.Name, StringComparison.OrdinalIgnoreCase)) continue;
                if (best == null || e.Name.Length > best.Name.Length) best = e;
            }
            return best;
        }

        public static Card Probe(string device)
        {
            Card card = new Card();
            if (device.Equals("default", StringComparison.OrdinalIgnoreCase))
            {
                card.State = CardState.Ready;
                card.WaveIndex = WaveOut.Mapper;
                card.WaveName = "系统默认设备";
                return card;
            }

            List<Endpoint> eps = Matching(device);
            foreach (Endpoint e in eps)
            {
                if (e.Wide && e.Shown) card.WideEnabled = true;
            }

            // 候选：winmm 里有且设备管理器里在运行的；优先立体声，16 声道端垫底
            int bestRank = int.MaxValue;
            foreach (WaveOut.Dev d in WaveOut.Devices())
            {
                if (d.Name.IndexOf(device, StringComparison.OrdinalIgnoreCase) < 0) continue;
                Endpoint e = EndpointOf(eps, d.Name);
                if (e != null && !e.Usable) continue;
                bool wide = e != null ? e.Wide : IsWide(d.Name);
                bool exact = d.Name.Equals(device, StringComparison.OrdinalIgnoreCase)
                    || (e != null && e.Name.Equals(device, StringComparison.OrdinalIgnoreCase));
                int rank = exact ? 0 : wide ? 2 : 1;
                if (rank >= bestRank) continue;
                bestRank = rank;
                card.State = CardState.Ready;
                card.WaveIndex = d.Index;
                card.WaveName = d.Name;
                card.WaveIsWide = wide;
            }
            if (card.Ready) return card;

            if (eps.Count == 0)
            {
                card.State = CardState.Missing;
                return card;
            }
            // 只要有一个没激活，就还有希望（等一会儿 / 重启）；全是禁用才算被禁用
            card.State = CardState.Disabled;
            foreach (Endpoint e in eps)
            {
                if (e.Sound == 1 && e.Pnp != Pnp.Disabled) card.State = CardState.Inactive;
            }
            return card;
        }

        public static List<string> Describe()
        {
            List<string> lines = new List<string>();
            lines.Add("--- winmm 播放端 ---");
            foreach (WaveOut.Dev d in WaveOut.Devices()) lines.Add(d.Index + "  " + d.Name);
            lines.Add("--- 注册表里的播放端（设备管理器状态 / 声音设置状态）---");
            foreach (Endpoint e in ReadAll())
            {
                e.Pnp = PnpOf(e.PnpId);
                if (e.Pnp == Pnp.Absent) continue;
                lines.Add(PnpText(e.Pnp) + " / " + (e.Sound == 1 ? "已启用" : "已禁用") + "  " + e.Name);
            }
            return lines;
        }

        public static List<Endpoint> EnabledWide(string device)
        {
            List<Endpoint> list = new List<Endpoint>();
            foreach (Endpoint e in Matching(device))
            {
                if (e.Wide && e.Shown) list.Add(e);
            }
            return list;
        }
    }
}
