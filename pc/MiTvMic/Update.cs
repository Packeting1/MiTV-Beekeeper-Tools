using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;

namespace MiTvMic
{
    // 从 GitHub Releases 检查并下载新版。和 APK 共用一个仓库，靠 tag 前缀区分，不使用 releases/latest。
    static class Update
    {
        const string Repo = "Packeting1/MiTV-Beekeeper-Tools";
        const string TagPrefix = "mitvmic-v";
        const string AssetName = "MiTvMic-Installer-x64.exe";

        // 官方地址在前，连不上再依次试镜像（与 APK 的线路一致）。fastgit.cc 不转发 api.github.com，会自动跳过
        static readonly string[] Mirrors =
        {
            "https://fastgit.cc/", "https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/"
        };

        public sealed class Info
        {
            public Version Version;
            public string Url = "";
            public string Digest = "";
        }

        public static Version Current
        {
            get { return typeof(Update).Assembly.GetName().Version; }
        }

        public static string Format(Version v)
        {
            return v.Revision > 0 ? v.ToString() : v.ToString(3);
        }

        static IEnumerable<string> WithMirrors(string url)
        {
            yield return url;
            foreach (string m in Mirrors) yield return m + url;
        }

        static HttpWebRequest Open(string url, int timeout)
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(url);
            req.UserAgent = "MiTV-Beekeeper-Tools";
            req.Accept = "application/vnd.github+json";
            req.Timeout = timeout;
            req.ReadWriteTimeout = timeout;
            return req;
        }

        static string FetchReleases()
        {
            string last = "无法连接";
            foreach (string url in WithMirrors("https://api.github.com/repos/" + Repo + "/releases?per_page=30"))
            {
                try
                {
                    using (WebResponse resp = Open(url, 8000).GetResponse())
                    using (StreamReader r = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                    {
                        string s = r.ReadToEnd().Trim();
                        if (!s.StartsWith("[")) throw new IOException("响应不是 JSON");
                        return s;
                    }
                }
                catch (Exception e)
                {
                    last = e.Message;
                }
            }
            throw new IOException(last);
        }

        static string Text(Dictionary<string, object> d, string key)
        {
            object v;
            return d.TryGetValue(key, out v) && v != null ? v.ToString() : "";
        }

        static bool Flag(Dictionary<string, object> d, string key)
        {
            object v;
            return d.TryGetValue(key, out v) && v is bool && (bool)v;
        }

        // 返回版本最高的 MiTvMic 发布；没有则返回 null。网络全部失败时抛 IOException
        public static Info Latest()
        {
            object[] list = new JavaScriptSerializer().DeserializeObject(FetchReleases()) as object[];
            Info best = null;
            if (list == null) return null;
            foreach (object o in list)
            {
                Dictionary<string, object> rel = o as Dictionary<string, object>;
                if (rel == null || Flag(rel, "draft") || Flag(rel, "prerelease")) continue;
                string tag = Text(rel, "tag_name");
                Version v;
                if (!tag.StartsWith(TagPrefix) || !Version.TryParse(tag.Substring(TagPrefix.Length), out v)) continue;
                if (best != null && v <= best.Version) continue;
                object[] assets = rel.ContainsKey("assets") ? rel["assets"] as object[] : null;
                if (assets == null) continue;
                foreach (object a in assets)
                {
                    Dictionary<string, object> asset = a as Dictionary<string, object>;
                    if (asset == null || Text(asset, "name") != AssetName) continue;
                    best = new Info();
                    best.Version = v;
                    best.Url = Text(asset, "browser_download_url");
                    best.Digest = Text(asset, "digest");
                    break;
                }
            }
            return best;
        }

        public static void Download(Info info, string dest, Action<int> progress)
        {
            string last = "下载失败";
            foreach (string url in WithMirrors(info.Url))
            {
                try
                {
                    Fetch(url, info.Digest, dest, progress);
                    return;
                }
                catch (Exception e)
                {
                    last = e.Message;
                }
            }
            throw new IOException(last);
        }

        static void Fetch(string url, string digest, string dest, Action<int> progress)
        {
            using (WebResponse resp = Open(url, 20000).GetResponse())
            using (Stream input = resp.GetResponseStream())
            using (FileStream output = new FileStream(dest, FileMode.Create, FileAccess.Write))
            using (SHA256 sha = SHA256.Create())
            {
                long total = resp.ContentLength;
                long got = 0;
                int shown = -1;
                bool isExe = false;
                byte[] buf = new byte[65536];
                int n;
                while ((n = input.Read(buf, 0, buf.Length)) > 0)
                {
                    if (got == 0 && n >= 2) isExe = buf[0] == 'M' && buf[1] == 'Z';
                    output.Write(buf, 0, n);
                    sha.TransformBlock(buf, 0, n, null, 0);
                    got += n;
                    int pct = total > 0 ? (int)(got * 100 / total) : 0;
                    if (pct != shown)
                    {
                        shown = pct;
                        progress(pct);
                    }
                }
                sha.TransformFinalBlock(buf, 0, 0);
                if (total > 0 && got != total) throw new IOException("下载不完整");
                if (!isExe) throw new IOException("下载的文件不是程序");
                if (digest.StartsWith("sha256:"))
                {
                    StringBuilder hex = new StringBuilder();
                    foreach (byte b in sha.Hash) hex.Append(b.ToString("x2"));
                    if (!string.Equals(digest.Substring(7), hex.ToString(), StringComparison.OrdinalIgnoreCase))
                        throw new IOException("校验失败，文件可能损坏");
                }
            }
        }

        public static string TempFile
        {
            get { return Path.Combine(Path.GetTempPath(), "MiTvMic.update.exe"); }
        }

        static string OldFile
        {
            get { return Env.SelfExe + ".old"; }
        }

        // 运行中的 exe 可以改名但不能覆盖：旧文件改名挪开，新文件放到原路径
        public static string Replace(string downloaded)
        {
            string self = Env.SelfExe;
            string old = OldFile;
            bool moved = false;
            try
            {
                if (File.Exists(old)) File.Delete(old);
                File.Move(self, old);
                moved = true;
                File.Copy(downloaded, self, true);
                File.Delete(downloaded);
                return null;
            }
            catch (Exception e)
            {
                if (moved)
                {
                    try { if (File.Exists(self)) File.Delete(self); File.Move(old, self); }
                    catch (Exception) { }
                }
                return "无法替换当前程序（" + e.Message + "），新版已保存在 " + downloaded;
            }
        }

        public static void Restart()
        {
            ProcessStartInfo psi = new ProcessStartInfo(Env.SelfExe, "--wait-parent " + Process.GetCurrentProcess().Id);
            psi.UseShellExecute = false;
            Process.Start(psi);
        }

        public static void CleanOld()
        {
            try { if (File.Exists(OldFile)) File.Delete(OldFile); }
            catch (Exception) { }
        }
    }
}
