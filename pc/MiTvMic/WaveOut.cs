using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;

namespace MiTvMic
{
    sealed class WaveOut : IDisposable
    {
        public const int Mapper = -1;

        public sealed class Dev
        {
            public int Index;
            public string Name;
        }

        const int Buffers = 6;
        const uint WhdrDone = 1;

        [StructLayout(LayoutKind.Sequential)]
        struct WaveFormatEx
        {
            public ushort wFormatTag, nChannels;
            public uint nSamplesPerSec, nAvgBytesPerSec;
            public ushort nBlockAlign, wBitsPerSample, cbSize;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct WaveHdr
        {
            public IntPtr lpData;
            public uint dwBufferLength, dwBytesRecorded;
            public IntPtr dwUser;
            public uint dwFlags, dwLoops;
            public IntPtr lpNext, reserved;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
        struct WaveOutCaps
        {
            public ushort mid, pid;
            public uint ver;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
            public string name;
            public uint formats;
            public ushort channels, reserved;
            public uint support;
        }

        [DllImport("winmm.dll")] static extern int waveOutOpen(out IntPtr h, int id, ref WaveFormatEx f, IntPtr cb, IntPtr inst, int flags);
        [DllImport("winmm.dll")] static extern int waveOutClose(IntPtr h);
        [DllImport("winmm.dll")] static extern int waveOutReset(IntPtr h);
        [DllImport("winmm.dll")] static extern int waveOutPrepareHeader(IntPtr h, IntPtr hdr, int size);
        [DllImport("winmm.dll")] static extern int waveOutUnprepareHeader(IntPtr h, IntPtr hdr, int size);
        [DllImport("winmm.dll")] static extern int waveOutWrite(IntPtr h, IntPtr hdr, int size);
        [DllImport("winmm.dll")] static extern int waveOutGetNumDevs();
        [DllImport("winmm.dll", CharSet = CharSet.Auto)] static extern int waveOutGetDevCaps(IntPtr id, out WaveOutCaps c, int size);

        static readonly int HdrSize = Marshal.SizeOf(typeof(WaveHdr));
        static readonly int FlagsOffset = (int)Marshal.OffsetOf(typeof(WaveHdr), "dwFlags");

        // winmm 只列已启用的播放端，名字最多 31 个字符（被截断）
        public static List<Dev> Devices()
        {
            List<Dev> list = new List<Dev>();
            int n = waveOutGetNumDevs();
            for (int i = 0; i < n; i++)
            {
                WaveOutCaps c;
                if (waveOutGetDevCaps((IntPtr)i, out c, Marshal.SizeOf(typeof(WaveOutCaps))) != 0) continue;
                if (string.IsNullOrEmpty(c.name)) continue;
                Dev d = new Dev();
                d.Index = i;
                d.Name = c.name;
                list.Add(d);
            }
            return list;
        }

        IntPtr handle;
        readonly IntPtr[] hdrs = new IntPtr[Buffers];
        readonly IntPtr[] data = new IntPtr[Buffers];
        readonly int bufSize;
        int next;

        public WaveOut(int device, int rate, int channels, int bits)
        {
            WaveFormatEx f = new WaveFormatEx();
            f.wFormatTag = 1;
            f.nChannels = (ushort)channels;
            f.nSamplesPerSec = (uint)rate;
            f.wBitsPerSample = (ushort)bits;
            f.nBlockAlign = (ushort)(channels * bits / 8);
            f.nAvgBytesPerSec = (uint)(rate * f.nBlockAlign);
            int rc = waveOutOpen(out handle, device, ref f, IntPtr.Zero, IntPtr.Zero, 0);
            if (rc != 0) throw new IOException("waveOutOpen 失败：" + rc);
            bufSize = rate / 50 * f.nBlockAlign;
            for (int i = 0; i < Buffers; i++)
            {
                data[i] = Marshal.AllocHGlobal(bufSize);
                hdrs[i] = Marshal.AllocHGlobal(HdrSize);
                WaveHdr h = new WaveHdr();
                h.lpData = data[i];
                h.dwBufferLength = (uint)bufSize;
                Marshal.StructureToPtr(h, hdrs[i], false);
                waveOutPrepareHeader(handle, hdrs[i], HdrSize);
                // 标成「已播完」，第一轮写入才不会被当成缓冲全满
                Marshal.WriteInt32(hdrs[i], FlagsOffset, Marshal.ReadInt32(hdrs[i], FlagsOffset) | (int)WhdrDone);
            }
        }

        public void Write(byte[] b)
        {
            IntPtr hdr = hdrs[next];
            // 缓冲全满说明声卡消费慢于网络，丢掉这块以免延迟越积越大
            if ((Marshal.ReadInt32(hdr, FlagsOffset) & WhdrDone) == 0) return;
            Marshal.Copy(b, 0, data[next], Math.Min(b.Length, bufSize));
            waveOutWrite(handle, hdr, HdrSize);
            next = (next + 1) % Buffers;
        }

        public void Dispose()
        {
            if (handle == IntPtr.Zero) return;
            waveOutReset(handle);
            for (int i = 0; i < Buffers; i++)
            {
                waveOutUnprepareHeader(handle, hdrs[i], HdrSize);
                Marshal.FreeHGlobal(hdrs[i]);
                Marshal.FreeHGlobal(data[i]);
            }
            waveOutClose(handle);
            handle = IntPtr.Zero;
        }
    }
}
