using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace MiTvMic
{
    // 和电视的协议（电视端见 MicServer.java）：
    //   UDP 9777  "MITVMIC?" 扫描，回 "MITVMIC 名称"；"MITVMIC-PAIR? 电脑名" 配对，回 MITVMIC-WAIT / MITVMIC-TOKEN 口令 / MITVMIC-DENY
    //   TCP 9777  发 16 字节口令，回 "TVMC"+采样率(int)+声道(byte)+位数(byte) 后是 PCM；口令不对回 "TVNO"
    static class Protocol
    {
        public sealed class Tv
        {
            public IPAddress Ip;
            public string Name;
            public Pairings.Item Pair;
        }

        public sealed class Format
        {
            public int Rate, Channels, Bits;
        }

        // 电脑常有多块虚拟网卡，默认路由的广播不一定到电视所在网段，所以每个网卡单独发一次定向广播
        static string Proof(string token, string message)
        {
            using (HMACSHA256 mac = new HMACSHA256(Encoding.ASCII.GetBytes(token)))
                return Convert.ToBase64String(mac.ComputeHash(Encoding.ASCII.GetBytes(message)));
        }

        public static List<Tv> Scan(string extra, IList<Pairings.Item> paired, bool pairedOnly = false)
        {
            List<byte[]> requests = new List<byte[]>();
            if (!pairedOnly) requests.Add(Encoding.ASCII.GetBytes("MITVMIC?"));
            string nonce = Guid.NewGuid().ToString("N");
            foreach (Pairings.Item p in paired)
            {
                requests.Add(Encoding.ASCII.GetBytes("MITVMIC-FIND? " + nonce + " " + Proof(p.Token, "find " + nonce)));
            }
            List<Socket> socks = new List<Socket>();
            List<EndPoint> targets = new List<EndPoint>();
            Dictionary<string, Tv> found = new Dictionary<string, Tv>();
            try
            {
                foreach (NetworkInterface nic in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (nic.OperationalStatus != OperationalStatus.Up
                        || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    foreach (UnicastIPAddressInformation ua in nic.GetIPProperties().UnicastAddresses)
                    {
                        if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                        byte[] a = ua.Address.GetAddressBytes();
                        byte[] m = ua.IPv4Mask.GetAddressBytes();
                        byte[] bc = new byte[4];
                        for (int i = 0; i < 4; i++) bc[i] = (byte)(a[i] | ~m[i]);
                        Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
                        try
                        {
                            s.EnableBroadcast = true;
                            s.Bind(new IPEndPoint(ua.Address, 0));
                        }
                        catch (SocketException)
                        {
                            s.Close();
                            continue;
                        }
                        // socks 与 targets 按下标一一对应，必须成对加入
                        socks.Add(s);
                        targets.Add(new IPEndPoint(new IPAddress(bc), Env.Port));
                    }
                }
                IPAddress manual;
                if (IPAddress.TryParse(extra, out manual) && manual.AddressFamily == AddressFamily.InterNetwork)
                {
                    socks.Add(new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp));
                    targets.Add(new IPEndPoint(manual, Env.Port));
                }
                if (socks.Count == 0) return new List<Tv>();

                DateTime start = DateTime.UtcNow;
                int sent = 0;
                byte[] buf = new byte[256];
                while ((DateTime.UtcNow - start).TotalMilliseconds < 1500)
                {
                    // UDP 可能丢包，隔 700 毫秒再发一轮
                    if (sent < 2 && (DateTime.UtcNow - start).TotalMilliseconds >= sent * 700)
                    {
                        sent++;
                        for (int i = 0; i < socks.Count; i++)
                        {
                            foreach (byte[] req in requests)
                            {
                                try { socks[i].SendTo(req, targets[i]); }
                                catch (SocketException) { }
                            }
                        }
                    }
                    foreach (Socket s in socks)
                    {
                        if (!s.Poll(20000, SelectMode.SelectRead)) continue;
                        EndPoint from = new IPEndPoint(IPAddress.Any, 0);
                        int n;
                        try { n = s.ReceiveFrom(buf, ref from); }
                        catch (SocketException) { continue; }
                        string reply = Encoding.UTF8.GetString(buf, 0, n);
                        IPEndPoint source = (IPEndPoint)from;
                        if (source.Port != Env.Port) continue;
                        Tv tv = new Tv();
                        tv.Ip = source.Address;
                        string address = tv.Ip.ToString();
                        if (reply.StartsWith("MITVMIC-FOUND ", StringComparison.Ordinal))
                        {
                            string proof = reply.Substring("MITVMIC-FOUND ".Length);
                            foreach (Pairings.Item p in paired)
                            {
                                if (proof != Proof(p.Token, "found " + nonce + " " + address)) continue;
                                tv.Pair = p;
                                tv.Name = p.Name;
                                if (pairedOnly) return new List<Tv> { tv };
                                found[address] = tv;
                                break;
                            }
                        }
                        else if (!pairedOnly && reply.StartsWith("MITVMIC ", StringComparison.Ordinal) && !found.ContainsKey(address))
                        {
                            tv.Name = reply.Substring(8).Trim();
                            found[address] = tv;
                        }
                    }
                }
            }
            finally
            {
                foreach (Socket s in socks) s.Close();
            }
            return new List<Tv>(found.Values);
        }

        // 请求配对并等用户在电视上选择（最多约 60 秒）；成功返回口令，失败返回 null 并给出原因
        public static string Pair(IPAddress ip, Func<bool> cancelled, out string error)
        {
            error = "未收到电视的响应，请确认电视已开启「麦克风推流」";
            byte[] req = Encoding.UTF8.GetBytes("MITVMIC-PAIR? " + Environment.MachineName);
            byte[] buf = new byte[256];
            using (Socket s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp))
            {
                EndPoint to = new IPEndPoint(ip, Env.Port);
                for (int i = 0; i < 60 && !cancelled(); i++)
                {
                    EndPoint from = new IPEndPoint(IPAddress.Any, 0);
                    int n;
                    try
                    {
                        s.SendTo(req, to);
                        if (!s.Poll(1000000, SelectMode.SelectRead)) continue;
                        n = s.ReceiveFrom(buf, ref from);
                    }
                    catch (SocketException e)
                    {
                        error = "网络错误：" + e.Message;
                        return null;
                    }
                    string reply = Encoding.ASCII.GetString(buf, 0, n);
                    if (reply.StartsWith("MITVMIC-TOKEN ")) return reply.Substring(14).Trim();
                    if (reply == "MITVMIC-DENY")
                    {
                        error = "电视端拒绝了请求，或长时间未操作";
                        return null;
                    }
                    error = "等待电视端选择超时";
                    Thread.Sleep(1000);
                }
            }
            return null;
        }

        // 连接并握手。返回 null 表示电视不认口令（调用方负责移除配对）
        public static Format Open(Socket s, IPAddress ip, string token)
        {
            IAsyncResult ar = s.BeginConnect(ip, Env.Port, null, null);
            if (!ar.AsyncWaitHandle.WaitOne(3000)) throw new IOException("连接 " + ip + " 超时");
            s.EndConnect(ar);
            s.Send(Encoding.ASCII.GetBytes(token));
            byte[] h = ReadFully(s, 10);
            if (h == null) throw new IOException("握手失败");
            string tag = Encoding.ASCII.GetString(h, 0, 4);
            if (tag == "TVNO") return null;
            if (tag != "TVMC") throw new IOException("握手失败");
            Format r = new Format();
            r.Rate = (h[4] << 24) | (h[5] << 16) | (h[6] << 8) | h[7];
            r.Channels = h[8];
            r.Bits = h[9];
            if (r.Rate < 8000 || r.Rate > 384000 || r.Channels < 1 || r.Channels > 32
                || (r.Bits != 16 && r.Bits != 24 && r.Bits != 32))
                throw new IOException("电视返回的音频格式无效");
            return r;
        }

        public static byte[] ReadFully(Socket s, int n)
        {
            byte[] buf = new byte[n];
            int got = 0;
            while (got < n)
            {
                int r;
                try { r = s.Receive(buf, got, n - got, SocketFlags.None); }
                catch (SocketException) { return null; }
                catch (ObjectDisposedException) { return null; }
                if (r <= 0) return null;
                got += r;
            }
            return buf;
        }
    }
}
