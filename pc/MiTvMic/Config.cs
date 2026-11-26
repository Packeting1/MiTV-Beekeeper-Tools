using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Text;
using System.Threading;

namespace MiTvMic
{
    // 路径和常量。界面和后台进程以同一用户运行，共用同一个数据目录。
    static class Env
    {
        public const int Port = 9777;
        public const string FirewallRule = "MiTvMic discovery";

        // 按前缀匹配 VB-CABLE 的播放端（"CABLE Input"、"CABLE In 16ch"）
        public const string DefaultDevice = "CABLE In";

        // MITVMIC_DIR 仅用于调试时不动真实数据
        public static readonly string DataDir = Environment.GetEnvironmentVariable("MITVMIC_DIR")
            ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MiTvMic");

        public static string SelfExe
        {
            get { return System.Diagnostics.Process.GetCurrentProcess().MainModule.FileName; }
        }
    }

    static class Log
    {
        static readonly string LogFile = Path.Combine(Env.DataDir, "MiTvMic.log");
        static readonly object gate = new object();
        const int MaxBytes = 64 * 1024;

        public static void Write(string msg)
        {
            lock (gate)
            {
                try
                {
                    Directory.CreateDirectory(Env.DataDir);
                    FileInfo fi = new FileInfo(LogFile);
                    if (fi.Exists && fi.Length > MaxBytes) File.Delete(LogFile);
                    File.AppendAllText(LogFile, DateTime.Now.ToString("MM-dd HH:mm:ss ") + msg + "\r\n", Encoding.UTF8);
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }

        public static string Last()
        {
            try
            {
                using (FileStream f = new FileStream(LogFile, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                {
                    long from = Math.Max(0, f.Length - 4096);
                    f.Seek(from, SeekOrigin.Begin);
                    string tail = new StreamReader(f, Encoding.UTF8).ReadToEnd();
                    string[] lines = tail.Split(new[] { "\r\n" }, StringSplitOptions.RemoveEmptyEntries);
                    return lines.Length > 0 ? lines[lines.Length - 1] : "";
                }
            }
            catch (IOException) { return ""; }
            catch (UnauthorizedAccessException) { return ""; }
        }
    }

    // 后台进程当前的连接状态（一句话），经命名共享内存由后台进程写、界面读，不落盘。
    // 后台进程退出后共享内存随之消失，界面读到空串。
    static class Link
    {
        const string MapName = @"Local\MiTvMic.Link";
        const int Size = 1024;
        static MemoryMappedFile map;
        static string last;

        public static void Set(string text)
        {
            if (text == last) return;
            last = text;
            try
            {
                if (map == null) map = MemoryMappedFile.CreateOrOpen(MapName, Size);
                byte[] b = Encoding.UTF8.GetBytes(text);
                int n = Math.Min(b.Length, Size - 4);
                using (MemoryMappedViewAccessor v = map.CreateViewAccessor(0, Size))
                {
                    v.Write(0, 0);
                    v.WriteArray(4, b, 0, n);
                    v.Write(0, n);
                }
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        public static string Get()
        {
            try
            {
                using (MemoryMappedFile m = MemoryMappedFile.OpenExisting(MapName, MemoryMappedFileRights.Read))
                using (MemoryMappedViewAccessor v = m.CreateViewAccessor(0, Size, MemoryMappedFileAccess.Read))
                {
                    int n = v.ReadInt32(0);
                    if (n <= 0 || n > Size - 4) return "";
                    byte[] b = new byte[n];
                    v.ReadArray(4, b, 0, n);
                    return Encoding.UTF8.GetString(b);
                }
            }
            catch (IOException) { return ""; }
            catch (UnauthorizedAccessException) { return ""; }
        }
    }

    // config.ini：开头是 key=value（# 开头是注释），[paired] 段是已配对的电视（ip=口令|名称）。
    // 按修改时间缓存，频繁读取不会反复访问磁盘。
    static class Config
    {
        const string PairedHeader = "[paired]";
        static readonly string FilePath = Path.Combine(Env.DataDir, "config.ini");
        static readonly object gate = new object();
        static readonly Mutex fileLock = new Mutex(false, @"Local\MiTvMic.Config");
        static Dictionary<string, string> values = new Dictionary<string, string>();
        static List<KeyValuePair<string, string>> pairs = new List<KeyValuePair<string, string>>();
        static DateTime stamp;
        static DateTime checkedAt;

        static bool IsHeader(string t)
        {
            return t.StartsWith("[") && t.EndsWith("]");
        }

        static void Load()
        {
            DateTime now = DateTime.UtcNow;
            if ((now - checkedAt).TotalMilliseconds < 500) return;
            checkedAt = now;
            DateTime w = File.Exists(FilePath) ? File.GetLastWriteTimeUtc(FilePath) : DateTime.MinValue;
            if (w == stamp) return;
            if (w == DateTime.MinValue)
            {
                values = new Dictionary<string, string>();
                pairs = new List<KeyValuePair<string, string>>();
                stamp = w;
                return;
            }
            Dictionary<string, string> d = new Dictionary<string, string>();
            List<KeyValuePair<string, string>> p = new List<KeyValuePair<string, string>>();
            try
            {
                string section = "";
                foreach (string line in File.ReadAllLines(FilePath))
                {
                    string t = line.Trim();
                    if (IsHeader(t))
                    {
                        section = t.ToLowerInvariant();
                        continue;
                    }
                    int i = t.IndexOf('=');
                    if (t.StartsWith("#") || i <= 0) continue;
                    string k = t.Substring(0, i).Trim();
                    string v = t.Substring(i + 1).Trim();
                    if (section.Length == 0) d[k] = v;
                    else if (section == PairedHeader) p.Add(new KeyValuePair<string, string>(k, v));
                }
                stamp = w;
                values = d;
                pairs = p;
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }

        public static string Get(string key, string def)
        {
            lock (gate)
            {
                Load();
                string v;
                return values.TryGetValue(key, out v) && v.Length > 0 ? v : def;
            }
        }

        public static void Reload()
        {
            lock (gate)
            {
                checkedAt = DateTime.MinValue;
                Load();
            }
        }

        internal static List<KeyValuePair<string, string>> Pairs()
        {
            lock (gate)
            {
                Load();
                return new List<KeyValuePair<string, string>>(pairs);
            }
        }

        public static void Set(string key, string value)
        {
            Mutate(delegate (List<string> lines)
            {
                string section = "";
                int firstHeader = -1;
                for (int i = 0; i < lines.Count; i++)
                {
                    string t = lines[i].Trim();
                    if (IsHeader(t))
                    {
                        if (firstHeader < 0) firstHeader = i;
                        section = t;
                    }
                    else if (section.Length == 0 && t.StartsWith(key + "="))
                    {
                        lines[i] = key + "=" + value;
                        return true;
                    }
                }
                int at = firstHeader < 0 ? lines.Count : firstHeader;
                while (at > 0 && lines[at - 1].Trim().Length == 0) at--;
                lines.Insert(at, key + "=" + value);
                return true;
            });
        }

        internal static void EditPairs(Func<List<KeyValuePair<string, string>>, bool> edit)
        {
            Mutate(delegate (List<string> lines)
            {
                int s = lines.FindIndex(delegate (string l) { return l.Trim().ToLowerInvariant() == PairedHeader; });
                int e = s + 1;
                List<KeyValuePair<string, string>> entries = new List<KeyValuePair<string, string>>();
                if (s >= 0)
                {
                    while (e < lines.Count && !IsHeader(lines[e].Trim()))
                    {
                        string t = lines[e++].Trim();
                        int i = t.IndexOf('=');
                        if (!t.StartsWith("#") && i > 0)
                            entries.Add(new KeyValuePair<string, string>(t.Substring(0, i).Trim(), t.Substring(i + 1).Trim()));
                    }
                }
                if (!edit(entries)) return false;
                if (s >= 0)
                {
                    lines.RemoveRange(s, e - s);
                }
                else s = lines.Count;
                if (entries.Count > 0)
                {
                    lines.Insert(s++, PairedHeader);
                    foreach (KeyValuePair<string, string> p in entries)
                        lines.Insert(s++, p.Key + "=" + p.Value);
                }
                return true;
            });
        }

        // 先写临时文件再替换，另一个进程不会读到写一半的
        static void Mutate(Func<List<string>, bool> edit)
        {
            lock (gate)
            {
                // 持锁进程被杀时抛此异常，但锁已归当前线程所有，照常继续并在 finally 释放
                try { fileLock.WaitOne(); }
                catch (AbandonedMutexException) { }
                try
                {
                    Directory.CreateDirectory(Env.DataDir);
                    List<string> lines = new List<string>();
                    if (File.Exists(FilePath)) lines.AddRange(File.ReadAllLines(FilePath));
                    if (!edit(lines)) return;
                    string tmp = FilePath + ".tmp";
                    File.WriteAllLines(tmp, lines.ToArray(), new UTF8Encoding(false));
                    if (File.Exists(FilePath)) File.Replace(tmp, FilePath, null);
                    else File.Move(tmp, FilePath);
                    checkedAt = DateTime.MinValue;
                    stamp = DateTime.MinValue;
                }
                finally
                {
                    fileLock.ReleaseMutex();
                }
            }
        }

        public static bool Enabled
        {
            get { return Get("enabled", "1") == "1"; }
        }

        public static bool ShowTray
        {
            get { return Get("show_tray", "0") == "1"; }
        }

        public static string Host
        {
            get { return Get("host", ""); }
        }

        public static string Device
        {
            get { return Get("device", Env.DefaultDevice); }
        }

        public static float Gain
        {
            get
            {
                float g;
                return float.TryParse(Get("gain", "1"), NumberStyles.Float, CultureInfo.InvariantCulture, out g) ? g : 1;
            }
        }
    }

    static class Pairings
    {
        public sealed class Item
        {
            public string Ip, Name, Token;
        }

        public static List<Item> All()
        {
            List<Item> list = new List<Item>();
            foreach (KeyValuePair<string, string> kv in Config.Pairs())
            {
                int i = kv.Value.IndexOf('|');
                if (i <= 0) continue;
                Item p = new Item();
                p.Ip = kv.Key;
                p.Token = kv.Value.Substring(0, i);
                p.Name = kv.Value.Substring(i + 1);
                list.Add(p);
            }
            return list;
        }

        public static void Add(string ip, string name, string token)
        {
            string cleanName = name.Replace('\r', ' ').Replace('\n', ' ').Trim();
            Config.EditPairs(delegate (List<KeyValuePair<string, string>> pairs)
            {
                pairs.RemoveAll(delegate (KeyValuePair<string, string> p) { return p.Key == ip; });
                pairs.Add(new KeyValuePair<string, string>(ip, token + "|" + cleanName));
                return true;
            });
        }

        public static void Remove(string ip)
        {
            Remove(ip, null);
        }

        // 旧连接被拒绝时，只移除该口令，不影响界面刚重新配好的记录。
        public static bool Remove(string ip, string token)
        {
            bool removed = false;
            Config.EditPairs(delegate (List<KeyValuePair<string, string>> pairs)
            {
                removed = pairs.RemoveAll(delegate (KeyValuePair<string, string> p)
                {
                    return p.Key == ip && (token == null || p.Value.StartsWith(token + "|", StringComparison.Ordinal));
                }) > 0;
                return removed;
            });
            return removed;
        }

        public static string TokenOf(string ip)
        {
            foreach (Item p in All())
            {
                if (p.Ip == ip) return p.Token;
            }
            return "";
        }
    }
}
