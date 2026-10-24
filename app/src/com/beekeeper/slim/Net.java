package com.beekeeper.slim;

import android.os.Handler;
import android.widget.TextView;
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

    static String fetchApi(String path) throws Exception {
        return fetchApi(GH_REPO, path);
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

    static java.util.List<String> withMirrors(String url) {
        java.util.ArrayList<String> l = new java.util.ArrayList<String>();
        l.add(url);
        for (String m : GH_MIRRORS) l.add(m + url);
        return l;
    }

    /** 依次试每个地址，下载到 f 并校验 sha256（digest 为 "sha256:..." 时）。全部失败抛 NetFail，用户取消抛 CancelledException。 */
    static void downloadFile(final Handler main, List<String> cands, String digest, java.io.File f,
                              final TextView ptxt, final android.widget.ProgressBar pbar,
                              CancelToken tok) throws Throwable {
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
                                if (total > 0) {
                                    int pct = (int) (g * 100 / total);
                                    pbar.setIndeterminate(false);
                                    pbar.setProgress(pct);
                                    ptxt.setText(String.format("正在下载……%d%%（%.1f / %.1f MB）", pct, g / 1048576.0, total / 1048576.0));
                                } else {
                                    ptxt.setText(String.format("正在下载……%.1f MB", g / 1048576.0));
                                }
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
                main.post(new Runnable() { public void run() {
                    pbar.setIndeterminate(true);
                    ptxt.setText("下载失败，正在切换线路重试……");
                } });
            }
        }
        throw new NetFail(lastErr == null ? "下载失败" : String.valueOf(lastErr));
    }

    static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }
}
