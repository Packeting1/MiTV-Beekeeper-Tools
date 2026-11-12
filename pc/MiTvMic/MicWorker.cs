using System;
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
            string host = Config.Host;
            IPAddress ip;
            string token = Pairings.TokenOf(host);
            if (!Config.Enabled)
            {
                Note("推流已关闭");
                return 1000;
            }
            if (token.Length == 0 || !IPAddress.TryParse(host, out ip))
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

            Pump(ip, token, card);
            return 2000;
        }

        // 状态变化时才写日志并更新连接状态
        void Note(string msg)
        {
            if (msg == lastNote) return;
            lastNote = msg;
            Log.Write(msg);
            Link.Set(msg);
        }

        void Pump(IPAddress ip, string token, Card card)
        {
            Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            current = s;
            try
            {
                s.NoDelay = true;
                s.ReceiveTimeout = 5000;
                Protocol.Format f = Protocol.Open(s, ip, token);
                if (f == null)
                {
                    Pairings.Remove(ip.ToString());
                    Note("电视拒绝了该口令，已移除配对，请重新配对");
                    return;
                }
                string name = "";
                foreach (Pairings.Item p in Pairings.All())
                {
                    if (p.Ip == ip.ToString()) name = p.Name;
                }
                Note("已连接 " + (name.Length > 0 ? name + " " : "") + ip + "，" + f.Rate + " Hz "
                    + f.Channels + " 声道 " + f.Bits + " 位，输出到 " + card.ShortName);
                using (WaveOut w = new WaveOut(card.WaveIndex, f.Rate, f.Channels, f.Bits))
                {
                    int chunk = f.Rate / 50 * f.Channels * f.Bits / 8;
                    string host = ip.ToString();
                    while (!stopped.WaitOne(0) && Config.Enabled && Config.Host == host)
                    {
                        byte[] b = Protocol.ReadFully(s, chunk);
                        if (b == null) break;
                        float gain = Config.Gain;
                        if (gain != 1 && f.Bits == 16) ApplyGain(b, gain);
                        w.Write(b);
                    }
                }
                if (!stopped.WaitOne(0)) Note("连接已断开，正在重连");
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
