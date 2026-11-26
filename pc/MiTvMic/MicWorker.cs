using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading;

namespace MiTvMic
{
    // 推流循环，由后台进程和 `MiTvMic run` 使用。
    sealed class MicWorker
    {
        readonly ManualResetEvent stopped = new ManualResetEvent(false);
        Thread thread;
        volatile Socket current;
        string lastNote;
        string lastConnectedId;
        string lastDiscoveryId;
        DateTime nextDiscovery;

        public void Start()
        {
            thread = new Thread(Loop);
            thread.IsBackground = true;
            thread.Start();
        }

        public void Stop()
        {
            stopped.Set();
            Socket s = current;
            if (s != null)
            {
                try { s.Close(); } catch (Exception) { }
            }
            if (thread != null) thread.Join(3000);
        }

        void Loop()
        {
            Log.Write("后台进程启动");
            while (!stopped.WaitOne(0))
            {
                int pause = 2000;
                try
                {
                    pause = Cycle();
                }
                catch (Exception e)
                {
                    if (stopped.WaitOne(0)) break;
                    Note("电视连接失败（" + e.Message + "），正在重试");
                }
                if (stopped.WaitOne(pause)) break;
            }
            Log.Write("后台进程停止");
            Link.Set("后台进程已停止");
        }

        // 条件不满足则等待，满足则推流直到断开。返回下一轮前的等待毫秒数
        int Cycle()
        {
            IPAddress ip;
            if (!Config.Enabled)
            {
                Note("推流已关闭");
                return 1000;
            }
            Pairings.Item pair = Pairings.Selected();
            if (pair == null || !IPAddress.TryParse(pair.Ip, out ip))
            {
                Note("未选择电视");
                return 1000;
            }

            // 声卡不可用时不连接电视，避免占用电视的唯一连接
            string device = Config.Device;
            Card card = Cards.Probe(device);
            if (!card.Ready)
            {
                Note(card.Hint(device));
                return 5000;
            }

            if (Pairings.AutoConnect(pair.Id))
            {
                if (lastDiscoveryId == pair.Id && DateTime.UtcNow < nextDiscovery) return 1000;
                lastDiscoveryId = pair.Id;
                nextDiscovery = DateTime.UtcNow.AddSeconds(8);
                List<Protocol.Tv> found = Protocol.Scan("", new[] { pair }, true);
                if (stopped.WaitOne(0) || !Config.Enabled || !Pairings.IsSelected(pair)) return 0;
                if (found.Count > 0)
                {
                    ip = found[0].Ip;
                    Pairings.RememberAddress(pair, ip.ToString());
                }
                // 老版电视或 UDP 无响应时，以保存的地址尝试原有 TCP 握手。
            }
            Pump(ip, pair, card);
            return 2000;
        }

        // 状态变化时才写日志并更新连接状态
        void Note(string msg, string connectedId = "")
        {
            if (msg == lastNote && connectedId == lastConnectedId) return;
            lastNote = msg;
            lastConnectedId = connectedId;
            Log.Write(msg);
            Link.Set(msg, connectedId);
        }

        void Pump(IPAddress ip, Pairings.Item pair, Card card)
        {
            Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            current = s;
            try
            {
                s.NoDelay = true;
                s.ReceiveTimeout = 5000;
                Protocol.Format f = Protocol.Open(s, ip, pair.Token);
                if (f == null)
                {
                    Note("电视拒绝了该口令，请扫描已配对电视或重新配对");
                    return;
                }
                using (WaveOut w = new WaveOut(card.WaveIndex, f.Rate, f.Channels, f.Bits))
                {
                    int chunk = f.Rate / 50 * f.Channels * f.Bits / 8;
                    bool streaming = false;
                    while (!stopped.WaitOne(0) && Config.Enabled && Pairings.IsSelected(pair))
                    {
                        byte[] b = Protocol.ReadFully(s, chunk);
                        if (b == null) break;
                        if (stopped.WaitOne(0) || !Config.Enabled || !Pairings.IsSelected(pair)) break;
                        float gain = Config.Gain;
                        if (gain != 1 && f.Bits == 16) ApplyGain(b, gain);
                        w.Write(b);
                        if (!streaming)
                        {
                            streaming = true;
                            Note("已连接 " + ip + "，" + (pair.Name.Length > 0 ? pair.Name + "，" : "") + f.Rate + " Hz "
                                + f.Channels + " 声道 " + f.Bits + " 位，输出到 " + card.ShortName, pair.Id);
                        }
                    }
                    if (!stopped.WaitOne(0))
                    {
                        bool stillSelected = Config.Enabled && Pairings.IsSelected(pair);
                        Note(!stillSelected ? "连接已断开" : streaming ? "连接已断开，正在重连"
                            : "电视连接失败（未收到音频），正在重试");
                    }
                }
            }
            finally
            {
                current = null;
                s.Close();
            }
        }

        static void ApplyGain(byte[] b, float gain)
        {
            for (int i = 0; i + 1 < b.Length; i += 2)
            {
                int v = (int)((short)(b[i] | (b[i + 1] << 8)) * gain);
                if (v > short.MaxValue) v = short.MaxValue;
                else if (v < short.MinValue) v = short.MinValue;
                b[i] = (byte)v;
                b[i + 1] = (byte)(v >> 8);
            }
        }
    }
}
