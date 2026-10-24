package com.beekeeper.slim;

import android.os.Handler;
import java.util.ArrayList;
import java.util.List;

import static com.beekeeper.slim.Catalog.*;

/** GitHub 访问（官方 + 镜像线路）、版本号比较和下载。 */
final class Net {

    private Net() { }

    static final String GH_REPO = "Packeting1/MiTV-Beekeeper-Tools";

    /** 按点分数字比较版本，忽略开头的 v 和 - 之后的后缀。 */
    static int cmpVersion(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("-")[0].split("\\.");
        String[] y = b.replaceFirst("^[vV]", "").split("-")[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Math.max(0, toInt(x[i])) : 0;
            int q = i < y.length ? Math.max(0, toInt(y[i])) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    // 官方地址放第一位，连不上再依次试镜像。镜像只做传输，
    // 更新包仍需 sha256 与同签名才能 pm install -r 成功。
    // fastgit.cc 不转发 api.github.com（返回 404），查版本时会自动跳到下一条线路。
    static final String[] GH_MIRRORS = {
        "https://fastgit.cc/", "https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/" };

    /** 所有线路都失败（网络问题），和“下载的文件不对”之类的错误区分开。 */
    static class NetFail extends Exception {
        NetFail(String m) { super(m); }
    }

    static class CancelledException extends Exception {
        CancelledException() { super("已取消"); }
    }

    /** 下载阶段可取消；开始安装后不能再取消（pm install 不能中途停）。 */
    static class CancelToken {
        volatile boolean cancelled, installing;
        volatile java.net.HttpURLConnection conn;
        synchronized boolean cancel() {
            if (installing) return false;
            cancelled = true;
            final java.net.HttpURLConnection c = conn;
            if (c != null) new Thread(new Runnable() { public void run() { try { c.disconnect(); } catch (Throwable ignore) { } } }).start();
            return true;
        }
        synchronized boolean beginInstall() {
            if (cancelled) return false;
            installing = true;
            return true;
        }
    }

    static String fetchApi(String repo, String path) throws Exception {
        Throwable last = null;
        for (String cand : withMirrors("https://api.github.com/repos/" + repo + "/" + path)) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(cand).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "MiTV-Beekeeper-Tools");
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                String s = readAll(c.getInputStream()).trim();
                if (!s.startsWith("{") && !s.startsWith("[")) throw new Exception("响应不是 JSON");
                return s;
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new NetFail(last == null ? "无法连接" : String.valueOf(last));
    }

    /** 一个 Release：版本、安装包地址和更新说明。notes 只含最近 2 个版本；notesFull 是全部，count 是版本数。 */
    static final class Release {
        String tag, url, digest = "", notes, notesFull;
        int count;
    }

    /**
     * 查 repo 的最新 Release。asset 为 null 时取第一个 .apk（可能没有，url 为 null）；
     * 否则取同名文件，找不到就抛错。cur 不为 null 且落后时，更新说明带上中间版本；
     * 列表拿不到就只用最新版的。
     */
    static Release fetchRelease(String repo, String asset, String cur) throws Exception {
        Release r = new Release();
        org.json.JSONObject j = new org.json.JSONObject(fetchApi(repo, "releases/latest"));
        r.tag = j.getString("tag_name");
        org.json.JSONArray as = j.optJSONArray("assets");
        for (int i = 0; as != null && i < as.length(); i++) {
            org.json.JSONObject a = as.getJSONObject(i);
            String name = a.optString("name");
            if (asset == null ? name.endsWith(".apk") : asset.equals(name)) {
                r.url = a.getString("browser_download_url");
                r.digest = a.optString("digest", "");
                break;
            }
        }
        if (asset != null && r.url == null) throw new Exception("最新发布里没有 " + asset);
        r.notes = notesOf(r.tag, j.optString("body", ""));
        r.notesFull = r.notes;
        r.count = 1;
        if (cur != null && cmpVersion(r.tag, cur) > 0) {
            try {
                org.json.JSONArray rs = new org.json.JSONArray(fetchApi(repo, "releases?per_page=30"));
                StringBuilder rec = new StringBuilder(), all = new StringBuilder();
                int n = 0;
                for (int i = 0; i < rs.length(); i++) {
                    org.json.JSONObject o = rs.getJSONObject(i);
                    String t = o.optString("tag_name");
                    if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
                    if (cmpVersion(t, cur) <= 0 || cmpVersion(t, r.tag) > 0) continue;
                    String e = notesOf(t, o.optString("body", ""));
                    if (n < 2) rec.append(n > 0 ? "\n\n" : "").append(e);
                    all.append(n > 0 ? "\n\n" : "").append(e);
                    n++;
                }
                // 版本落后超过 30 个时，列表拿不全最老的几版。
                if (rs.length() > 0 && cmpVersion(rs.getJSONObject(rs.length() - 1).optString("tag_name"), cur) > 0) {
                    all.append("\n\n（更早的版本说明请到 GitHub 查看）");
                }
                if (n > 0) { r.notes = rec.toString(); r.notesFull = all.toString(); r.count = n; }
            } catch (Throwable ignore) { }
        }
        return r;
    }

    private static String notesOf(String tag, String body) {
        body = body.trim();
        return "【" + tag + "】\n" + (body.length() == 0 ? "（没有更新说明）" : body);
    }

    static java.util.List<String> withMirrors(String url) {
        java.util.ArrayList<String> l = new java.util.ArrayList<String>();
        l.add(url);
        for (String m : GH_MIRRORS) l.add(m + url);
        return l;
    }

    /** 依次试每个地址，下载到 f 并校验 sha256（digest 为 "sha256:..." 时）。全部失败抛 NetFail，用户取消抛 CancelledException。 */
    static void downloadFile(final Handler main, List<String> cands, String digest, java.io.File f,
                              final Progress p, CancelToken tok) throws Throwable {
        Throwable lastErr = null;
        for (String cand : cands) {
            if (tok.cancelled) throw new CancelledException();
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(cand).openConnection();
                tok.conn = c;
                if (tok.cancelled) { c.disconnect(); throw new CancelledException(); }
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "MiTV-Beekeeper-Tools");
                if (c.getResponseCode() != 200) throw new Exception("下载失败 HTTP " + c.getResponseCode());
                final long total = c.getContentLength();
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                java.io.InputStream in = c.getInputStream();
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                long got = 0, lastUi = 0;
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (tok.cancelled) throw new CancelledException();
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        got += n;
                        final long g = got;
                        if (g - lastUi > 65536 || (total > 0 && g == total)) {
                            lastUi = g;
                            main.post(new Runnable() { public void run() {
                                if (total > 0) p.downloaded((int) (g * 100 / total), g, total);
                                else p.text.setText(String.format("正在下载……%.1f MB", g / 1048576.0));
                            } });
                        }
                    }
                } finally {
                    in.close();
                    out.close();
                }
                if (total > 0 && got != total) throw new Exception("下载不完整");
                if (digest != null && digest.startsWith("sha256:")) {
                    StringBuilder hex = new StringBuilder();
                    for (byte x : md.digest()) hex.append(String.format("%02x", x));
                    if (!digest.substring(7).equalsIgnoreCase(hex.toString())) throw new Exception("校验失败，文件可能损坏");
                }
                return;
            } catch (Throwable t) {
                if (tok.cancelled) throw new CancelledException();
                lastErr = t;
                main.post(new Runnable() { public void run() { p.waiting("下载失败，正在切换线路重试……"); } });
            }
        }
        throw new NetFail(lastErr == null ? "下载失败" : String.valueOf(lastErr));
    }

    static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }
}
