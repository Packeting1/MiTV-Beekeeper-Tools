package com.beekeeper.slim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * beekeeper 电视的系统精简工具 (需要 Magisk root)。
 *
 * - 应用类：`pm disable-user` / `pm enable`，只能碰 GROUPS 里列出的可选后台应用，核心服务不在名单里。
 * - Aerial 桌面：装成系统应用 (Magisk 模块) 并设为默认桌面；系统只允许系统应用当默认桌面。
 * - 禁用系统桌面：只有Aerial已作为系统应用装好才能开启，禁用后如果默认桌面不是Aerial会自动回滚。
 * - Magisk 模块 (HGIG / LDM / Zygote64)：勾选 = `magisk --install-module`，取消 = 标记卸载 (重启时执行 uninstall.sh)。
 */
public class MainActivity extends Activity {

    private static final String DB_PKG = "app.orchard.tv";
    private static final String DB_COMP = DB_PKG + "/app.orchard.tv.MainActivity";
    private static final String DB_MODULE = "beekeeper_aerial";
    private static final String OLD_DB_MODULE = "beekeeper_dangbei";
    private static final String STOCK_HOME_PKG = "com.mitv.tvhome";
    private static final String STOCK_HOME_COMP = STOCK_HOME_PKG + "/.MainActivityUserMode";

    private static final int KIND_PKGS = 0, KIND_DB = 1, KIND_NOHOME = 2, KIND_MOD = 3, KIND_APK = 4;

    private static final class Group {
        final String title, desc, warn;
        final String[] pkgs;
        final int kind;
        String modId, asset;
        CheckBox box;
        Group(String title, String desc, String warn, String... pkgs) {
            this(KIND_PKGS, title, desc, warn, pkgs);
        }
        Group(int kind, String title, String desc, String warn, String... pkgs) {
            this.kind = kind; this.title = title; this.desc = desc; this.warn = warn; this.pkgs = pkgs;
        }
        Group mod(String id, String asset) {
            this.modId = id; this.asset = asset;
            return this;
        }
    }

    private final Group[] GROUPS = new Group[] {
        new Group("语音助手 (占内存约 245MB)",
            "语音控制、语音唤醒。禁用后遥控器语音键和小爱同学不可用。",
            null,
            "com.xiaomi.voicecontrol", "com.xiaomi.wakeupservice"),
        new Group("统计与诊断 (占内存约 270MB)",
            "数据统计、MiSight 诊断、质量上报。对日常使用没有影响。",
            null,
            "com.miui.tv.analytics", "com.miui.misightservice", "com.xiaomi.tvqs"),
        new Group("智能家居 (占内存约 90MB)",
            "小米智能家居 TV 版。不用智能家居联动可以禁用。",
            null,
            "com.xiaomi.smarthome.tv"),
        new Group("消息推送 (占内存约 87MB)",
            "电视推送服务。禁用后收不到系统推送消息。",
            null,
            "com.xiaomi.mitv.tvpush.tvpushservice"),
        new Group("屏保 (占内存约 92MB)",
            "超级屏保。禁用后没有待机屏保。",
            null,
            "com.xiaomi.mitv.hyper.screensaver"),
        new Group("应用商店 (占内存约 109MB)",
            "小米应用商店。禁用后不能从商店安装和更新应用。",
            null,
            "com.xiaomi.mitv.appstore"),
        new Group("视频头条",
            "短视频信息流应用。禁用后桌面和系统入口里的视频头条不可用。",
            null,
            "com.duokan.videodaily"),
        new Group("小米互联 (占内存约 420MB)",
            "手机互联、MiPlay、HyperConnect。小米手机投屏和多设备协同依赖它们。",
            "风险：不确定其它功能是否依赖",
            "com.xiaomi.mi_connect_service", "com.xiaomi.miplay", "com.xiaomi.hyper_connect.tv"),
        new Group(KIND_DB, "Aerial 桌面 1.20",
            "安装 Aerial 并设为默认桌面。取消勾选会恢复系统桌面，重启后 Aerial 消失。",
            null,
            DB_PKG),
        new Group(KIND_NOHOME, "禁用系统桌面 (占内存约 390MB)",
            "禁用小米系统桌面。仅已安装“Aerial 桌面”和 HomeRef，且 Aerial 为默认桌面后才能禁用此项",
            "风险：部分系统入口（如信号源列表的“主页”）可能没反应",
            STOCK_HOME_PKG),
        new Group(KIND_APK, "TvPlayerKeep（LSPosed模块）",
            "修复 PC 信号进设置黑屏。",
            null, "com.beekeeper.tvplayerkeep").mod("com.beekeeper.tvplayerkeep", "apk_tvplayerkeep.apk"),
        new Group(KIND_APK, "CastPip（LSPosed模块）",
            "投屏小窗叠加 PC 信号，并支持混音。",
            null, "com.beekeeper.castpip").mod("com.beekeeper.castpip", "apk_castpip.apk"),
        new Group(KIND_APK, "HomeRef（LSPosed模块）",
            "把系统桌面的引用替换为当前的默认桌面。",
            null, "com.beekeeper.homeref").mod("com.beekeeper.homeref", "apk_homeref.apk"),
        new Group(KIND_MOD, "ADB 常开（Magisk模块）",
            "开机后自动保持 ADB 开关和 5555 端口开启。",
            null).mod("mitv_adb_keeper", "mod_adbkeeper.zip"),
        new Group(KIND_MOD, "4K 界面开关（Magisk模块）",
            "在系统设置里显示 4K 界面开关。开关位于“设置>通用设置>UI显示模式>极清模式”",
            null).mod("mitv_4kui_switch", "mod_4kui.zip"),
        new Group(KIND_MOD, "HGIG 修复（Magisk模块）",
            "修复 3.0.109 的 HDR 色调映射里 HGIG 档位错位。",
            null).mod("beekeeper_hgig_fix", "mod_hgig.zip"),
        new Group(KIND_MOD, "LDM 局部调光修复（Magisk模块）",
            "调整 3.0.109 的局部调光参数，减轻 HDR/杜比视界过曝。取消后恢复原样。",
            null).mod("beekeeper_ldm_fix", "mod_ldm.zip"),
        new Group(KIND_MOD, "Zygote64 修复（Magisk模块）",
            "让 LSPosed 模块开机后稳定生效。",
            "风险：关闭后 LSPosed 的模块可能不生效").mod("zygote64_fix", "mod_zygote.zip"),
        new Group(KIND_MOD, "HDR10+（Magisk模块）",
            "上报HDR10+能力支持，实际效果未知。",
            null).mod("beekeeper_hdr10plus", "mod_hdr10plus.zip"),
        new Group(KIND_MOD, "开启IMAX支持（Magisk模块）",
            "在图像模式中显示IMAX。",
            null).mod("beekeeper_imax", "mod_imax.zip"),
        new Group(KIND_MOD, "AISR 强度调节（Magisk模块）",
            "“图像参数>清晰度”中显示超分辨率强度调节。",
            null).mod("beekeeper_aisr_level", "mod_aisr.zip"),
        new Group(KIND_MOD, "音量均衡（Magisk模块）",
            "在声音设置里显示“音量均衡”。",
            null).mod("beekeeper_volume_balance", "mod_volbalance.zip"),
        new Group(KIND_MOD, "Windows 杜比视界 EDID（Magisk模块）",
            "修改 HDMI/DP 的 EDID，让 Windows 能识别杜比视界。卸载后恢复原文件。",
            null).mod("beekeeper_dv_edid", "mod_dvedid.zip"),
    };

    private static final class Hidden {
        final String title, desc, warn, args;
        Hidden(String title, String desc, String warn, String args) {
            this.title = title; this.desc = desc; this.warn = warn; this.args = args;
        }
    }

    private static final String SET_MAIN = "com.xiaomi.mitv.settings/com.xiaomi.mitv.settings.entry.MainActivity";
    private static String viaSettings(String name) {
        return "-n " + SET_MAIN + " --es activity_name " + name;
    }

    /** 设置菜单里没有入口、但能直接打开的页面。每一项都在 3.0.109 上用 am start 实测过能打开。 */
    private final Hidden[] HIDDEN = new Hidden[] {
        new Hidden("调试信息", "设备和系统的调试信息页。", null, viaSettings("DebugInfoActivity")),
        new Hidden("调试页", "设置里的调试页面。", "风险：里面有调试开关，别乱改", viaSettings("DebugActivity")),
        new Hidden("开机视频", "开机视频设置页。", null, viaSettings("BootVideoActivity")),
        new Hidden("色彩校准", "图像色彩校准页。", null, viaSettings("ColorCalibrationActivity")),
        new Hidden("息屏样式", "息屏显示的图案选择页。", null, viaSettings("AodStyle")),
        new Hidden("环绕声测试", "环绕音箱声道测试页。", null, viaSettings("surroundTest")),
        new Hidden("MTK 工厂菜单", "联发科工厂/设计菜单，能改面板、画质等底层参数。",
            "风险：很高。改错可能让画面异常，没有把握不要动",
            "-n mediatek.factorymenu.ui/mediatek.tvsetting.factory.ui.designmenu.DesignMenuActivity"),
    };

    private void launchHidden(final Hidden h) {
        log.setText("正在打开：" + h.title + "\n");
        new Thread(new Runnable() { public void run() {
            final String out = suOut("am start --user 0 " + h.args + " 2>&1\n");
            main.post(new Runnable() { public void run() {
                log.setText(out == null ? "无法使用 root。\n" : out);
            }});
        }}, "slim-hidden").start();
    }

    private void confirmHidden(final Hidden h) {
        if (h.warn == null) { launchHidden(h); return; }
        new AlertDialog.Builder(this)
            .setTitle(h.title)
            .setMessage(h.warn + "\n\n确定打开吗？")
            .setPositiveButton("打开", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { launchHidden(h); }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private TextView log;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Magisk 模块状态；null = 还没读到或没有 root。 */
    private Set<String> modPresent, modRemoving, modPending;
    /** Magisk 里被停用的模块 / 实际检测到已生效的模块。 */
    private Set<String> modDisabled = new HashSet<String>(), modActive = new HashSet<String>();
    /** 版本信息：模块 id -> {versionCode, version}。modVer 是已装的，modUpVer 是已暂存、重启后生效的；bundled* 是本应用自带的。 */
    private java.util.Map<String, String[]> modVer = new java.util.HashMap<String, String[]>(),
        modUpVer = new java.util.HashMap<String, String[]>(),
        bundledMod = new java.util.HashMap<String, String[]>(),
        bundledApk = new java.util.HashMap<String, String[]>();
    private LinearLayout updateBar;
    private TextView updateText;

    private static final String GH_REPO = "Packeting1/MiTV-Beekeeper-Tools";

    private String ownVersion() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "0" : v;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 按点分数字比较版本，忽略开头的 v 和 - 之后的后缀。 */
    private static int cmpVersion(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("-")[0].split("\\.");
        String[] y = b.replaceFirst("^[vV]", "").split("-")[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Math.max(0, toInt(x[i])) : 0;
            int q = i < y.length ? Math.max(0, toInt(y[i])) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private String selfTag, selfUrl, selfDigest, selfNotes, selfMsg = "";
    private boolean selfChecking;
    private View aboutDot, btnDot;
    private Button checkBtn;
    private TextView selfStatus;

    /** 查 GitHub 最新 Release。自动检测失败就静默跳过；手动检测用提示条告知结果。不弹窗。 */
    private void checkSelfUpdate(final boolean manual) {
        if (selfChecking) return;
        selfChecking = true;
        selfMsg = "正在检查更新……";
        refreshAboutUi();
        new Thread(new Runnable() { public void run() {
            String fail = null;
            String tag = null, url = null, digest = "", notes = "";
            try {
                org.json.JSONObject j = null;
                Throwable last = null;
                for (String cand : withMirrors("https://api.github.com/repos/" + GH_REPO + "/releases/latest")) {
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(cand).openConnection();
                        c.setConnectTimeout(6000);
                        c.setReadTimeout(8000);
                        c.setRequestProperty("Accept", "application/vnd.github+json");
                        c.setRequestProperty("User-Agent", "MiTV-Beekeeper-Tools");
                        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                        j = new org.json.JSONObject(readAll(c.getInputStream()));
                        tag = j.getString("tag_name");
                        break;
                    } catch (Throwable t) {
                        last = t;
                        j = null;
                    }
                }
                if (j == null) throw last != null ? last : new Exception("无法连接");
                org.json.JSONArray as = j.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    org.json.JSONObject a = as.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.getString("browser_download_url");
                        digest = a.optString("digest", "");
                        break;
                    }
                }
                notes = j.optString("body", "").trim();
                if (notes.length() > 3000) notes = notes.substring(0, 3000) + "…";
            } catch (Throwable t) {
                fail = String.valueOf(t);
            }
            final String fTag = tag, fUrl = url, fDigest = digest, fNotes = notes, fFail = fail;
            main.post(new Runnable() { public void run() {
                selfChecking = false;
                if (fFail != null) {
                    selfMsg = manual ? "检查更新失败：" + fFail : "";
                    if (manual) android.widget.Toast.makeText(MainActivity.this, selfMsg, android.widget.Toast.LENGTH_LONG).show();
                } else if (fUrl != null && cmpVersion(fTag, ownVersion()) > 0) {
                    selfTag = fTag; selfUrl = fUrl; selfDigest = fDigest; selfNotes = fNotes;
                    selfMsg = "";
                    if (manual) android.widget.Toast.makeText(MainActivity.this, "发现新版本 " + fTag, android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    selfTag = null;
                    selfMsg = "已是最新版本";
                    if (manual) android.widget.Toast.makeText(MainActivity.this, selfMsg, android.widget.Toast.LENGTH_SHORT).show();
                }
                refreshAboutUi();
            }});
        }}, "slim-selfcheck").start();
    }

    // 官方地址放第一位，连不上再依次试镜像（ghproxy.com 已停止服务）。镜像只做传输，
    // 更新包仍需 sha256 与同签名才能 pm install -r 成功。
    private static final String[] GH_MIRRORS = {
        "https://gh-proxy.com/", "https://ghfast.top/", "https://ghproxy.net/" };

    private static java.util.List<String> withMirrors(String url) {
        java.util.ArrayList<String> l = new java.util.ArrayList<String>();
        l.add(url);
        for (String m : GH_MIRRORS) l.add(m + url);
        return l;
    }

    private void refreshAboutUi() {
        if (checkBtn == null) return;
        boolean has = selfTag != null;
        aboutDot.setVisibility(has ? View.VISIBLE : View.GONE);
        btnDot.setVisibility(has ? View.VISIBLE : View.GONE);
        checkBtn.setEnabled(!selfChecking);
        checkBtn.setText(has ? "更新到 " + selfTag : "检查更新");
        StringBuilder sb = new StringBuilder("当前版本：").append(ownVersion());
        if (has) {
            sb.append("\n发现新版本：").append(selfTag);
        } else if (selfMsg.length() > 0) {
            sb.append("\n").append(selfMsg);
        }
        selfStatus.setText(sb.toString());
    }

    private void onCheckButton() {
        if (selfTag == null) { checkSelfUpdate(true); return; }
        TextView tv = new TextView(this);
        String notes = selfNotes == null || selfNotes.length() == 0 ? "（该版本没有更新说明）" : selfNotes;
        tv.setText("当前版本 " + ownVersion() + " → " + selfTag + "\n\n更新说明：\n" + notes
            + "\n\n点“更新”将下载并安装，完成后应用会自动重新打开。");
        tv.setTextSize(14);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        ScrollView nsv = new ScrollView(this);
        nsv.setFocusable(true);
        nsv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle("发现新版本 " + selfTag)
            .setView(nsv)
            .setPositiveButton("更新", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { downloadAndInstall(selfUrl, selfDigest); }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void downloadAndInstall(final String url, final String digest) {
        final TextView ptxt = new TextView(this);
        ptxt.setText("正在下载……0%");
        ptxt.setTextSize(14);
        final android.widget.ProgressBar pbar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbar.setMax(100);
        pbar.setIndeterminate(true);
        LinearLayout pbox = new LinearLayout(this);
        pbox.setOrientation(LinearLayout.VERTICAL);
        pbox.setPadding(dp(24), dp(16), dp(24), dp(8));
        pbox.addView(ptxt);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(10);
        pbox.addView(pbar, plp);
        final AlertDialog progress = new AlertDialog.Builder(this)
            .setTitle("正在更新")
            .setView(pbox)
            .setCancelable(false)
            .show();
        new Thread(new Runnable() { public void run() {
            final java.io.File f = new java.io.File(getCacheDir(), "selfupdate.apk");
            String err = null;
            try {
                Throwable lastErr = null;
                boolean got200 = false;
                for (String cand : withMirrors(url)) {
                    try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(cand).openConnection();
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
                        got200 = true;
                        break;
                    } catch (Throwable t) {
                        lastErr = t;
                        main.post(new Runnable() { public void run() {
                            pbar.setIndeterminate(true);
                            ptxt.setText("下载失败，正在切换线路重试……");
                        } });
                    }
                }
                if (!got200) throw lastErr != null ? lastErr : new Exception("下载失败");
                android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi == null || !getPackageName().equals(pi.packageName)) throw new Exception("下载的不是本应用的安装包");
                f.setReadable(true, false);
            } catch (Throwable t) {
                err = String.valueOf(t);
            }
            if (err != null) {
                f.delete();
                final String e = err;
                main.post(new Runnable() { public void run() {
                    if (progress.isShowing()) progress.dismiss();
                    showResult("更新失败：" + e);
                } });
                return;
            }
            main.post(new Runnable() { public void run() {
                pbar.setIndeterminate(true);
                ptxt.setText("正在安装……完成后应用会自动重新打开。");
            } });
            String log = "/data/local/tmp/slim_selfupdate.log";
            String res = runSu("rm -f " + log + "\n"
                + "setsid sh -c \"pm install -r " + f.getAbsolutePath() + " > " + log + " 2>&1; rm -f " + f.getAbsolutePath()
                + "; am start --user 0 -n " + getPackageName() + "/.MainActivity >> " + log + " 2>&1\" >/dev/null 2>&1 &\n"
                + "sleep 1\n");
            if (res.contains("无法使用 root") || res.contains("可能没有授予 root")) {
                f.delete();
                final String r = res;
                main.post(new Runnable() { public void run() {
                    if (progress.isShowing()) progress.dismiss();
                    showResult("没有 root 权限，无法安装。\n" + r);
                } });
                return;
            }
            // 安装成功时本应用会被系统结束；只有失败才会走到后面。
            for (int i = 0; i < 30; i++) {
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                String s = suOut("cat " + log + " 2>/dev/null\n");
                if (s != null && s.contains("Failure")) {
                    f.delete();
                    final String m = s;
                    main.post(new Runnable() { public void run() {
                        if (progress.isShowing()) progress.dismiss();
                        showResult("安装失败：\n" + m);
                    } });
                    return;
                }
            }
            main.post(new Runnable() { public void run() {
                if (progress.isShowing()) progress.dismiss();
                showResult("安装没有结束，请稍后重新打开应用确认版本。");
            } });
        }}, "slim-selfupdate").start();
    }

    private static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private String[] readBundledMod(String asset) {
        try {
            java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(getAssets().open(asset));
            try {
                java.util.zip.ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (!"module.prop".equals(e.getName())) continue;
                    BufferedReader r = new BufferedReader(new InputStreamReader(z, "UTF-8"));
                    String code = null, name = null, l;
                    while ((l = r.readLine()) != null) {
                        if (l.startsWith("versionCode=")) code = l.substring(12).trim();
                        else if (l.startsWith("version=")) name = l.substring(8).trim();
                    }
                    return code == null ? null : new String[] { code, name == null ? code : name };
                }
            } finally {
                z.close();
            }
        } catch (Exception e) {
            // 读不到就当没有版本信息，不提示更新
        }
        return null;
    }

    private String[] readBundledApk(String asset) {
        try {
            String path = copyAsset(asset, "ver_" + asset);
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(path, 0);
                if (pi == null) return null;
                return new String[] { String.valueOf(pi.versionCode),
                    pi.versionName == null ? String.valueOf(pi.versionCode) : pi.versionName };
            } finally {
                new java.io.File(path).delete();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 有新版本的项目：Group -> {当前版本, 新版本}。Aerial 桌面不参与。 */
    private java.util.LinkedHashMap<Group, String[]> pendingUpdates() {
        java.util.LinkedHashMap<Group, String[]> res = new java.util.LinkedHashMap<Group, String[]>();
        for (Group g : GROUPS) {
            if (g.kind == KIND_MOD) {
                if (modPresent == null || modRemoving == null) continue;
                if (!modPresent.contains(g.modId) || modRemoving.contains(g.modId)) continue;
                String[] nb = bundledMod.get(g.modId);
                String[] cur = modUpVer.containsKey(g.modId) ? modUpVer.get(g.modId) : modVer.get(g.modId);
                if (nb == null || cur == null) continue;
                int c = toInt(cur[0]), n = toInt(nb[0]);
                if (c >= 0 && n >= 0 && c < n) res.put(g, new String[] { cur[1], nb[1] });
            } else if (g.kind == KIND_APK) {
                String[] nb = bundledApk.get(g.pkgs[0]);
                if (nb == null) continue;
                try {
                    android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(g.pkgs[0], 0);
                    int n = toInt(nb[0]);
                    if (n >= 0 && pi.versionCode < n) {
                        res.put(g, new String[] { pi.versionName == null ? "旧版（无版本号）" : pi.versionName, nb[1] });
                    }
                } catch (Exception e) {
                    // 没装就不算更新
                }
            }
        }
        return res;
    }

    private void updateBanner(boolean prompt) {
        if (updateBar == null) return;
        java.util.LinkedHashMap<Group, String[]> ups = pendingUpdates();
        if (ups.isEmpty()) {
            updateBar.setVisibility(View.GONE);
            return;
        }
        updateText.setText("发现 " + ups.size() + " 个模块/应用有更新");
        updateBar.setVisibility(View.VISIBLE);
    }

    private void confirmUpdate() {
        final java.util.LinkedHashMap<Group, String[]> ups = pendingUpdates();
        if (ups.isEmpty()) {
            showResult("没有可用的更新。");
            return;
        }
        StringBuilder msg = new StringBuilder();
        boolean hasMod = false;
        for (java.util.Map.Entry<Group, String[]> e : ups.entrySet()) {
            String t = e.getKey().title;
            msg.append(t.replace("（Magisk模块）", "")).append("：").append(e.getValue()[0])
                .append(" → ").append(e.getValue()[1]).append("\n");
            if (e.getKey().kind == KIND_MOD) hasMod = true;
        }
        if (hasMod) msg.append("\n模块更新后需要重启电视才生效。");
        new AlertDialog.Builder(this)
            .setTitle("发现更新")
            .setMessage(msg.toString())
            .setPositiveButton("全部更新", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { runUpdate(new ArrayList<Group>(ups.keySet())); }
            })
            .setNegativeButton("以后再说", null)
            .show();
    }

    private void runUpdate(final List<Group> list) {
        final AlertDialog progress = new AlertDialog.Builder(this)
            .setTitle("正在更新")
            .setMessage("请在弹窗里允许 root……")
            .show();
        new Thread(new Runnable() { public void run() {
            StringBuilder script = new StringBuilder();
            String err = null;
            boolean mod = false;
            for (Group g : list) {
                try {
                    String f = copyAsset(g.asset, g.asset);
                    script.append("echo \"== 更新 ").append(g.kind == KIND_APK ? g.title : g.modId).append("\"\n");
                    if (g.kind == KIND_APK) {
                        script.append("pm install -r ").append(f).append(" 2>&1\n");
                    } else {
                        mod = true;
                        script.append("magisk --install-module ").append(f).append(" 2>&1\n");
                    }
                    script.append("rm -f ").append(f).append("\n");
                } catch (Exception e) {
                    err = (err == null ? "" : err) + "无法读取内置文件 " + g.asset + "：" + e + "\n";
                }
            }
            if (mod) script.append("echo 模块更新需要重启电视后生效\n");
            script.append("echo __DONE__\n");
            final String out = (err == null ? "" : err) + runSu(script.toString());
            main.post(new Runnable() { public void run() {
                if (progress.isShowing()) progress.dismiss();
                showResult(out);
                refresh();
            }});
        }}, "slim-update").start();
    }
    /** LSPosed 状态；lsKnown=false 表示还没读到。lsEnabled/lsScope 为 null = 读不到配置库。 */
    private boolean lsKnown, lsRunning, lsServer;
    private java.util.Map<String, Boolean> lsEnabled;
    private java.util.Map<String, Set<String>> lsScope;

    private static final java.util.Map<String, String[]> LS_SCOPES = new java.util.HashMap<String, String[]>();
    static {
        LS_SCOPES.put("com.beekeeper.tvplayerkeep", new String[] {
            "com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.smartpanel", "com.xiaomi.mitv.settings" });
        LS_SCOPES.put("com.beekeeper.castpip", new String[] {
            "com.xiaomi.mitv.smartshare", "com.xiaomi.mitv.tvplayer", "mitv.service" });
        LS_SCOPES.put("com.beekeeper.homeref", new String[] {
            "com.xiaomi.mitv.settings", "com.xiaomi.mitv.tvplayer", "mitv.service" });
    }

    /** 读 su 复制到本应用缓存目录的 LSPosed 配置库副本；读完就删。 */
    private boolean readLsDb(java.util.Map<String, Boolean> en, java.util.Map<String, Set<String>> sc) {
        java.io.File db = new java.io.File(getCacheDir(), "lsp_modules_config.db");
        android.database.sqlite.SQLiteDatabase d = null;
        try {
            if (!db.exists()) return false;
            d = android.database.sqlite.SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                    | android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            android.database.Cursor c = d.rawQuery("select module_pkg_name, enabled from modules_state where user_id=0", null);
            while (c.moveToNext()) en.put(c.getString(0), c.getInt(1) != 0);
            c.close();
            c = d.rawQuery("select module_pkg_name, app_pkg_name from scope where user_id=0", null);
            while (c.moveToNext()) {
                Set<String> s = sc.get(c.getString(0));
                if (s == null) { s = new HashSet<String>(); sc.put(c.getString(0), s); }
                s.add(c.getString(1));
            }
            c.close();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (d != null) d.close();
            new java.io.File(getCacheDir(), "lsp_modules_config.db").delete();
            new java.io.File(getCacheDir(), "lsp_modules_config.db-wal").delete();
            new java.io.File(getCacheDir(), "lsp_modules_config.db-shm").delete();
        }
    }

    private String lsActual(String pkg) {
        if (!lsKnown) return "实际：LSPosed 没有运行，模块不会生效";
        if (!lsRunning) return "实际：LSPosed 没有运行，模块不会生效";
        if (!lsServer) return "实际：LSPosed 已运行，但还没注入系统，重启后再看";
        if (lsEnabled == null) return "实际：读不到 LSPosed 的配置";
        Boolean e = lsEnabled.get(pkg);
        if (e == null) return "实际：LSPosed 还没登记此模块，重启后再看";
        if (!e) return "实际：LSPosed 里没有启用，请到 LSPosed 里启用";
        String[] need = LS_SCOPES.get(pkg);
        if (need == null) return "实际：已在 LSPosed 启用";
        Set<String> have = lsScope == null ? null : lsScope.get(pkg);
        StringBuilder miss = new StringBuilder();
        for (String a : need) {
            if (have == null || !have.contains(a)) {
                if (miss.length() > 0) miss.append("、");
                miss.append(a.startsWith("com.xiaomi.mitv.") ? a.substring(16) : a);
            }
        }
        if (miss.length() > 0) return "实际：已在 LSPosed 启用，但作用域缺 " + miss;
        return "实际：已在 LSPosed 启用，作用域齐全";
    }

    /** 只改 mitv.config.xml 一个开关的模块：模块 id -> 开关 key。 */
    private static final java.util.Map<String, String> FLAG_MODS = new java.util.LinkedHashMap<String, String>();
    static {
        FLAG_MODS.put("beekeeper_hdr10plus", "support_hdr10_plus");
        FLAG_MODS.put("beekeeper_imax", "support_display_IMAX");
        FLAG_MODS.put("beekeeper_aisr_level", "support_display_AISR_level");
        FLAG_MODS.put("beekeeper_volume_balance", "support_volume_balance");
    }

    private String modActual(Group g) {
        String id = g.modId;
        if (modDisabled.contains(id)) return "实际：在 Magisk 里被停用，没有生效";
        boolean on = modActive.contains(id);
        if ("mitv_4kui_switch".equals(id)) return on ? "实际：配置已生效" : "实际：配置没有生效";
        if ("mitv_adb_keeper".equals(id)) return on ? "实际：已生效（守护进程运行中）" : "实际：没有生效（守护进程没有运行）";
        if ("beekeeper_hgig_fix".equals(id)) return on ? "实际：已生效（HGIG 标志已开启）" : "实际：没有生效（系统里 HGIG 标志没开启）";
        if ("beekeeper_ldm_fix".equals(id)) return on ? "实际：配置已生效" : "实际：配置没有生效";
        if ("beekeeper_dv_edid".equals(id)) return on ? "实际：EDID 已改写" : "实际：EDID 没有改写（需重启）";
        if (FLAG_MODS.containsKey(id)) return on ? "实际：已生效" : "实际：未生效（需重启）";
        if ("zygote64_fix".equals(id)) return on ? "实际：已生效（zygote64 已被注入）" : "实际：没有生效（zygote64 没有被注入）";
        return null;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!"beekeeper".equals(Build.PRODUCT)) {
            TextView t = new TextView(this);
            t.setText("此工具只适用于 beekeeper 机型");
            setContentView(t);
            return;
        }
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(12), pad, dp(12));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("系统优化");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        PowerIcon power = new PowerIcon(this);
        power.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmReboot(); }
        });
        header.addView(power, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(header);

        updateBar = new LinearLayout(this);
        updateBar.setOrientation(LinearLayout.HORIZONTAL);
        updateBar.setGravity(Gravity.CENTER_VERTICAL);
        updateBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        updateBar.setBackgroundColor(0x33FFB300);
        updateText = new TextView(this);
        updateText.setTextSize(14);
        updateText.setTextColor(0xFFFFD54F);
        updateBar.addView(updateText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button updateBtn = new Button(this);
        updateBtn.setText("查看更新");
        updateBtn.setTextSize(14);
        updateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmUpdate(); }
        });
        updateBar.addView(updateBtn);
        updateBar.setVisibility(View.GONE);
        root.addView(updateBar);

        pageApps = new LinearLayout(this);
        pageApps.setOrientation(LinearLayout.VERTICAL);
        pageOpt = new LinearLayout(this);
        pageOpt.setOrientation(LinearLayout.VERTICAL);
        pageFix = new LinearLayout(this);
        pageFix.setOrientation(LinearLayout.VERTICAL);

        pageHid = new LinearLayout(this);
        pageHid.setOrientation(LinearLayout.VERTICAL);
        TextView hintHid = new TextView(this);
        hintHid.setText("这些页面在设置菜单里看不到，但能直接打开。点按钮打开，按遥控器返回键退出。\n"
            + "打开的是系统自带的页面，不会改动本应用的任何设置；页面里的操作要自己小心。");
        hintHid.setTextSize(13);
        hintHid.setPadding(0, dp(4), 0, dp(8));
        pageHid.addView(hintHid);
        for (final Hidden h : HIDDEN) {
            Button bt = new Button(this);
            bt.setText(h.title);
            bt.setTextSize(15);
            bt.setAllCaps(false);
            bt.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmHidden(h); }
            });
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(6);
            pageHid.addView(bt, blp);
            TextView d = new TextView(this);
            d.setText(h.desc + (h.warn == null ? "" : "\n⚠ " + h.warn));
            d.setTextSize(13);
            d.setTextColor(Color.LTGRAY);
            d.setPadding(dp(6), 0, dp(6), dp(2));
            pageHid.addView(d);
        }
        TextView hintApps = new TextView(this);
        hintApps.setText("勾选 = 禁用该组应用，取消勾选 = 恢复。禁用只是停用，随时可以恢复。");
        hintApps.setTextSize(13);
        hintApps.setPadding(0, dp(4), 0, dp(8));
        pageApps.addView(hintApps);
        TextView hintOpt = new TextView(this);
        hintOpt.setText("勾选 = 启用，取消勾选 = 关闭。LSPosed 模块安装后需要在 LSPosed 里启用。改动重启后生效。");
        hintOpt.setTextSize(13);
        hintOpt.setPadding(0, dp(4), 0, dp(8));
        pageOpt.addView(hintOpt);
        TextView hintFix = new TextView(this);
        hintFix.setText("勾选 = 启用，取消勾选 = 关闭。改动需要重启电视后生效。");
        hintFix.setTextSize(13);
        hintFix.setPadding(0, dp(4), 0, dp(8));
        pageFix.addView(hintFix);

        for (Group g : GROUPS) {
            CheckBox cb = new CheckBox(this);
            cb.setTextSize(15);
            cb.setTextColor(Color.WHITE);
            cb.setPadding(dp(6), dp(6), dp(6), dp(6));
            g.box = cb;
            ((g.kind == KIND_DB || g.kind == KIND_APK || isOptMod(g)) ? pageOpt : g.kind == KIND_MOD ? pageFix : pageApps).addView(cb);
        }
        LinearLayout pageAbout = new LinearLayout(this);
        pageAbout.setOrientation(LinearLayout.VERTICAL);
        TextView about = new TextView(this);
        about.setText("小米电视团队是干嘛的？\n作者：酷安@pkkka\n项目地址：github.com/" + GH_REPO);
        about.setTextSize(16);
        about.setTextColor(Color.WHITE);
        about.setPadding(dp(6), dp(16), dp(6), dp(8));
        pageAbout.addView(about);
        selfStatus = new TextView(this);
        selfStatus.setTextSize(15);
        selfStatus.setTextColor(Color.LTGRAY);
        selfStatus.setPadding(dp(6), dp(4), dp(6), dp(8));
        pageAbout.addView(selfStatus);
        checkBtn = new Button(this);
        checkBtn.setText("检查更新");
        checkBtn.setTextSize(15);
        checkBtn.setAllCaps(false);
        checkBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onCheckButton(); }
        });
        FrameLayout btnWrap = new FrameLayout(this);
        btnWrap.addView(checkBtn, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        btnDot = redDot();
        // Material 主题的按钮有 elevation，会盖住后加的兄弟视图；红点要比按钮更高才画得出来。
        btnWrap.setPadding(0, dp(6), dp(6), 0);
        btnWrap.setClipChildren(false);
        btnWrap.setClipToPadding(false);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP | Gravity.END);
        btnDot.setElevation(dp(12));
        btnWrap.addView(btnDot, dlp);
        pageAbout.addView(btnWrap, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final TabHost host = new TabHost(this, null);
        LinearLayout hostBody = new LinearLayout(this);
        hostBody.setOrientation(LinearLayout.VERTICAL);
        TabWidget widget = new TabWidget(this);
        widget.setId(android.R.id.tabs);
        FrameLayout content = new FrameLayout(this);
        content.setId(android.R.id.tabcontent);
        hostBody.addView(widget, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        hostBody.addView(content, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        host.addView(hostBody, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        host.setup();
        host.addTab(host.newTabSpec("apps").setIndicator("应用类").setContent(new TabHost.TabContentFactory() {
            public View createTabContent(String tag) { return pageApps; }
        }));
        host.addTab(host.newTabSpec("opt").setIndicator("优化类").setContent(new TabHost.TabContentFactory() {
            public View createTabContent(String tag) { return pageOpt; }
        }));
        host.addTab(host.newTabSpec("fix").setIndicator("修复类").setContent(new TabHost.TabContentFactory() {
            public View createTabContent(String tag) { return pageFix; }
        }));
        host.addTab(host.newTabSpec("hid").setIndicator("隐藏项").setContent(new TabHost.TabContentFactory() {
            public View createTabContent(String tag) { return pageHid; }
        }));
        LinearLayout aboutTab = new LinearLayout(this);
        aboutTab.setOrientation(LinearLayout.HORIZONTAL);
        aboutTab.setGravity(Gravity.CENTER);
        TextView aboutTitle = new TextView(this);
        aboutTitle.setId(android.R.id.title);
        aboutTitle.setText("关于");
        aboutTitle.setTextColor(Color.WHITE);
        aboutTab.addView(aboutTitle);
        aboutDot = redDot();
        aboutDot.setVisibility(View.GONE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        tlp.leftMargin = dp(3);
        tlp.bottomMargin = dp(10);
        aboutTab.addView(aboutDot, tlp);
        host.addTab(host.newTabSpec("about").setIndicator(aboutTab).setContent(new TabHost.TabContentFactory() {
            public View createTabContent(String tag) { return pageAbout; }
        }));
        // 自定义指示器不带系统的选中/焦点背景，照着前面几个标签复制一份，遥控器高亮才会显示。
        View firstTab = widget.getChildTabViewAt(0);
        View lastTab = widget.getChildTabViewAt(widget.getTabCount() - 1);
        if (firstTab.getBackground() != null && firstTab.getBackground().getConstantState() != null) {
            lastTab.setBackgroundDrawable(firstTab.getBackground().getConstantState().newDrawable());
        }
        lastTab.setFocusable(true);
        for (int i = 0; i < widget.getTabCount(); i++) {
            View tv = widget.getChildTabViewAt(i).findViewById(android.R.id.title);
            if (tv instanceof TextView) ((TextView) tv).setTextSize(16);
            widget.getChildTabViewAt(i).setPadding(dp(16), dp(6), dp(16), dp(6));
        }
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hostLp.topMargin = dp(6);
        root.addView(host, hostLp);
        groupOfKind(KIND_DB).box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                updateNoHomeBox(false);
            }
        });

        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(4));
        Button apply = new Button(this);
        apply.setText("应用");
        apply.setTextSize(15);
        apply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmApply(); }
        });
        bar.addView(apply);
        root.addView(bar);
        host.setOnTabChangedListener(new TabHost.OnTabChangeListener() {
            public void onTabChanged(String tag) {
                bar.setVisibility("about".equals(tag) || "hid".equals(tag) ? View.GONE : View.VISIBLE);
            }
        });

        log = new TextView(this);
        log.setTextSize(12);
        log.setTextColor(Color.LTGRAY);
        root.addView(log);

        setContentView(sv);
        refresh();
        checkSelfUpdate(false);
    }

    private LinearLayout pageApps, pageOpt, pageFix, pageHid;
    /** 放在优化类页的 Magisk 模块。 */
    private static boolean isOptMod(Group g) {
        return g.kind == KIND_MOD && ("mitv_adb_keeper".equals(g.modId) || "mitv_4kui_switch".equals(g.modId)
            || FLAG_MODS.containsKey(g.modId));
    }

    private View redDot() {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0xFFF44336);
        v.setBackgroundDrawable(g);
        v.setVisibility(View.GONE);
        return v;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static final int MISSING = -1, ENABLED = 0, DISABLED = 1;

    private int state(String pkg) {
        try {
            int s = getPackageManager().getApplicationEnabledSetting(pkg);
            getPackageManager().getApplicationInfo(pkg, 0);
            boolean off = s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
            if (!off) {
                off = !getPackageManager().getApplicationInfo(pkg, 0).enabled;
            }
            return off ? DISABLED : ENABLED;
        } catch (Exception e) {
            // A disabled app is still found with MATCH_DISABLED_COMPONENTS-less lookups only on some
            // builds; treat a failed ApplicationInfo lookup after a DISABLED setting as disabled.
            try {
                int s = getPackageManager().getApplicationEnabledSetting(pkg);
                if (s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) return DISABLED;
                return ENABLED;
            } catch (Exception e2) {
                return MISSING;
            }
        }
    }

    private Group groupOfKind(int kind) {
        for (Group g : GROUPS) if (g.kind == kind) return g;
        throw new IllegalStateException();
    }

    private String homePackage() {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
            return ri == null || ri.activityInfo == null ? "" : ri.activityInfo.packageName;
        } catch (Exception e) {
            return "";
        }
    }

    private static final int DB_NONE = 0, DB_USERAPP = 1, DB_PENDING = 2, DB_SYSTEM = 3, DB_ACTIVE = 4, DB_REMOVING = 5;

    private int dbState() {
        boolean pending = modPresent != null && modPresent.contains(DB_MODULE);
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(DB_PKG, 0);
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            // 模块目录已被移除但Aerial还在系统分区里：重启后才会消失。
            if (sys && modPresent != null && !pending) return DB_REMOVING;
            if (sys) return DB_PKG.equals(homePackage()) ? DB_ACTIVE : DB_SYSTEM;
            return pending ? DB_PENDING : DB_USERAPP;
        } catch (Exception e) {
            return pending ? DB_PENDING : DB_NONE;
        }
    }

    private boolean noHomeReady() {
        return dbState() == DB_ACTIVE && state("com.beekeeper.homeref") != MISSING;
    }

    private boolean dbInstalledAsSystem() {
        int s = dbState();
        return s == DB_SYSTEM || s == DB_ACTIVE;
    }

    /** 系统桌面开关：Aerial已装成系统应用且已是默认桌面、Aerial开关开着才能开；已经禁用时总能点，方便恢复。 */
    private void updateNoHomeBox(boolean fromRefresh) {
        Group nh = groupOfKind(KIND_NOHOME);
        boolean alreadyOff = state(STOCK_HOME_PKG) == DISABLED;
        boolean allowed = noHomeReady() && groupOfKind(KIND_DB).box.isChecked();
        nh.box.setEnabled(allowed || alreadyOff);
        if (!allowed && !alreadyOff) nh.box.setChecked(false);
    }

    private boolean modInstalled(Group g) {
        return modPresent != null && modPresent.contains(g.modId)
            && !(modRemoving != null && modRemoving.contains(g.modId));
    }

    private void refresh() {
        render();
        new Thread(new Runnable() { public void run() {
            String out = suOut(
                "for d in /data/adb/modules/*/; do [ -d \"$d\" ] || continue; id=$(basename \"$d\"); echo \"M:$id\";"
                + " [ -e \"$d/remove\" ] && echo \"R:$id\"; done\n"
                + "for d in /data/adb/modules_update/*/; do [ -d \"$d\" ] && echo \"U:$(basename \"$d\")\"; done\n"
                + "for d in /data/adb/modules/*/; do [ -d \"$d\" ] && echo \"V:$(basename \"$d\")|$(grep -m1 '^versionCode=' \"$d/module.prop\" 2>/dev/null | cut -d= -f2)|$(grep -m1 '^version=' \"$d/module.prop\" 2>/dev/null | cut -d= -f2-)\"; done\n"
                + "for d in /data/adb/modules_update/*/; do [ -d \"$d\" ] && echo \"X:$(basename \"$d\")|$(grep -m1 '^versionCode=' \"$d/module.prop\" 2>/dev/null | cut -d= -f2)|$(grep -m1 '^version=' \"$d/module.prop\" 2>/dev/null | cut -d= -f2-)\"; done\n"
                + "CD=" + getCacheDir().getAbsolutePath() + "\n"
                + "for d in /data/adb/modules/*/; do [ -e \"$d/disable\" ] && echo \"D:$(basename \"$d\")\"; done\n"
                + "H=$(sha256sum /system/etc/mitv.config_product.xml 2>/dev/null | cut -d' ' -f1)\n"
                + "M=$(sha256sum /data/adb/modules/mitv_4kui_switch/system/etc/mitv.config_product.xml 2>/dev/null | cut -d' ' -f1)\n"
                + "[ -n \"$H\" ] && [ \"$H\" = \"$M\" ] && echo \"F:mitv_4kui_switch\"\n"
                + "grep -la \"[m]itv_adb_keeper/keeper.sh\" /proc/[0-9]*/cmdline >/dev/null 2>&1 && echo \"F:mitv_adb_keeper\"\n"
                + "service call TvService 82 s16 support_hdr_game_hgig 2>/dev/null | grep -q 'Y.E.S' && echo \"F:beekeeper_hgig_fix\"\n"
                + "L=$(sha256sum /vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/ldm.ini 2>/dev/null | cut -d' ' -f1)\n"
                + "[ \"$L\" = 282d2ad2991845cd79660bb13111cba1da82e8116f1519e2b395606b27eb6dcf ] && echo \"F:beekeeper_ldm_fix\"\n"
                + "E=$(sha256sum /vendor/tvconfig/bsp/common/EDID_BIN/GPro_HDMI1_FreeSync_ver6.0.bin 2>/dev/null | cut -d' ' -f1)\n"
                + "[ \"$E\" = 04d48a32abcb0fe7e8a25cac683c09b9f6dbd00dd81b8d0ab170b3d5534237fc ] && echo \"F:beekeeper_dv_edid\"\n"
                + "for z in $(pidof zygote64); do grep -q zygisk_lsposed /proc/$z/maps 2>/dev/null && echo \"F:zygote64_fix\"; done\n"
                + "S=$(timeout 15 sh /data/adb/modules/zygisk_lsposed/lspctl status 2>&1); rc=$?\n"
                + "[ $rc = 0 ] && echo \"L:run\"\n"
                + "echo \"$S\" | grep -q 'cannot connect' && echo \"L:dead\"\n"
                + "echo \"$S\" | grep -q 'System server: connected' && echo \"L:server\"\n"
                + "for f in modules_config.db modules_config.db-wal; do rm -f \"$CD/lsp_$f\"; cp \"/data/adb/lspd/config/$f\" \"$CD/lsp_$f\" 2>/dev/null && chmod 666 \"$CD/lsp_$f\" && chown $(stat -c %u:%g \"$CD\") \"$CD/lsp_$f\"; done\n"
                + flagQuery()
                + "echo __Q_OK__\n");
            final Set<String> present = new HashSet<String>(), removing = new HashSet<String>(),
                pending = new HashSet<String>(), disabled = new HashSet<String>(), active = new HashSet<String>();
            boolean lsR = false, lsDead = false, lsS = false;
            boolean ok = out != null && out.contains("__Q_OK__");
            final java.util.Map<String, String[]> vInst = new java.util.HashMap<String, String[]>(),
                vUp = new java.util.HashMap<String, String[]>();
            if (ok) {
                for (String line : out.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("V:") || line.startsWith("X:")) {
                        String[] p = line.substring(2).split("\\|", 3);
                        if (p.length == 3 && p[1].length() > 0) {
                            (line.startsWith("V:") ? vInst : vUp).put(p[0], new String[] { p[1], p[2].length() > 0 ? p[2] : p[1] });
                        }
                    } else if (line.startsWith("M:")) present.add(line.substring(2));
                    else if (line.startsWith("R:")) removing.add(line.substring(2));
                    else if (line.startsWith("U:")) { present.add(line.substring(2)); pending.add(line.substring(2)); }
                    else if (line.startsWith("D:")) disabled.add(line.substring(2));
                    else if (line.startsWith("F:")) active.add(line.substring(2));
                    else if (line.equals("L:run")) lsR = true;
                    else if (line.equals("L:dead")) lsDead = true;
                    else if (line.equals("L:server")) lsS = true;
                }
            }
            final boolean[] lsFlags = new boolean[] { lsR, lsDead, lsS };
            final java.util.Map<String, Boolean> lsEn = new java.util.HashMap<String, Boolean>();
            final java.util.Map<String, Set<String>> lsSc = new java.util.HashMap<String, Set<String>>();
            final boolean dbRead = ok && readLsDb(lsEn, lsSc);
            final boolean fOk = ok;
            final java.util.Map<String, String[]> bm = new java.util.HashMap<String, String[]>(),
                ba = new java.util.HashMap<String, String[]>();
            if (bundledMod.isEmpty() && bundledApk.isEmpty()) {
                for (Group g : GROUPS) {
                    String[] v = null;
                    if (g.kind == KIND_MOD) { v = readBundledMod(g.asset); if (v != null) bm.put(g.modId, v); }
                    else if (g.kind == KIND_APK) { v = readBundledApk(g.asset); if (v != null) ba.put(g.pkgs[0], v); }
                }
            }
            main.post(new Runnable() { public void run() {
                if (!bm.isEmpty() || !ba.isEmpty()) { bundledMod = bm; bundledApk = ba; }
                modVer = vInst;
                modUpVer = vUp;
                modPresent = fOk ? present : null;
                modRemoving = fOk ? removing : null;
                modPending = fOk ? pending : null;
                modDisabled = disabled;
                modActive = active;
                lsKnown = fOk && (lsFlags[0] || lsFlags[1]);
                lsRunning = lsFlags[0];
                lsServer = lsFlags[2];
                lsEnabled = dbRead ? lsEn : null;
                lsScope = dbRead ? lsSc : null;
                render();
                updateBanner(fOk);
            }});
        }}, "slim-query").start();
    }

    private String flagQuery() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : FLAG_MODS.entrySet()) {
            sb.append("service call TvService 82 s16 ").append(e.getValue())
                .append(" 2>/dev/null | grep -q 'Y.E.S' && echo \"F:").append(e.getKey()).append("\"\n");
        }
        return sb.toString();
    }

    private void render() {
        java.util.LinkedHashMap<Group, String[]> ups = pendingUpdates();
        for (Group g : GROUPS) {
            StringBuilder sb = new StringBuilder(g.title).append("\n").append(g.desc);
            if (g.warn != null) sb.append("\n⚠ ").append(g.warn);
            if (g.kind == KIND_DB) {
                int st = dbState();
                String t = st == DB_ACTIVE ? "当前：已是默认桌面"
                    : st == DB_SYSTEM ? "当前：已安装，还没设为默认桌面（再点一次“应用”）"
                    : st == DB_PENDING ? "当前：已安装，重启电视后生效"
                    : st == DB_REMOVING ? "当前：已移除，重启电视后 Aerial 消失"
                    : st == DB_USERAPP ? "当前：已装成普通应用，不能设为默认桌面（勾选并应用后可改正）"
                    : "当前：未安装";
                sb.append("\n").append(t);
                g.box.setText(sb.toString());
                g.box.setChecked(st == DB_ACTIVE || st == DB_SYSTEM || st == DB_PENDING);
            } else if (g.kind == KIND_NOHOME) {
                boolean off = state(STOCK_HOME_PKG) == DISABLED;
                sb.append("\n").append(off ? "当前：已禁用" : "当前：启用中");
                if (!noHomeReady() && !off) sb.append("（需要先安装 Aerial 桌面并设为默认桌面，且安装 HomeRef）");
                g.box.setText(sb.toString());
                g.box.setChecked(off);
            } else if (g.kind == KIND_APK) {
                boolean inst = state(g.pkgs[0]) != MISSING;
                String apkVer = "";
                if (inst) {
                    try {
                        android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(g.pkgs[0], 0);
                        apkVer = pi.versionName != null ? " " + pi.versionName : " （没有版本号的旧版）";
                    } catch (Exception e) {
                        // 读不到版本就不显示
                    }
                }
                sb.append("\n").append(inst ? "当前：已安装" + apkVer : "当前：未安装");
                if (ups.containsKey(g)) sb.append("\n⬆ 有新版本：").append(ups.get(g)[0]).append(" → ").append(ups.get(g)[1]);
                if (inst) sb.append("\n").append(lsActual(g.pkgs[0]));
                g.box.setText(sb.toString());
                g.box.setChecked(inst);
            } else if (g.kind == KIND_MOD) {
                String t;
                if (modPresent == null) {
                    t = "当前：正在读取状态（需要 root 授权）";
                } else if (modPresent.contains(g.modId) && modRemoving.contains(g.modId)) {
                    t = "当前：已关闭，重启后生效（再勾选可取消）";
                } else if (modPresent.contains(g.modId)) {
                    boolean pend = modPending != null && modPending.contains(g.modId);
                    String act = pend ? null : modActual(g);
                    String[] iv = modVer.get(g.modId);
                    String vs = iv == null ? "" : " " + iv[1];
                    t = pend ? "当前：已安装，重启后生效" : act == null ? "当前：已安装" + vs : "当前：已安装" + vs + "\n" + act;
                    if (ups.containsKey(g)) t += "\n⬆ 有新版本：" + ups.get(g)[0] + " → " + ups.get(g)[1];
                } else {
                    t = "当前：未安装";
                }
                sb.append("\n").append(t);
                g.box.setText(sb.toString());
                g.box.setChecked(modInstalled(g));
                g.box.setEnabled(modPresent != null);
            } else {
                int dis = 0, en = 0, miss = 0;
                for (String p : g.pkgs) {
                    int s = state(p);
                    if (s == DISABLED) dis++; else if (s == ENABLED) en++; else miss++;
                }
                String st = dis == 0 ? "当前：启用中" : (en == 0 ? "当前：已禁用" : "当前：部分已禁用");
                if (miss > 0 && dis == 0 && en == 0) st = "当前：本机没有这些应用";
                sb.append("\n").append(st);
                g.box.setText(sb.toString());
                g.box.setChecked(dis > 0 && en == 0);
            }
        }
        updateNoHomeBox(true);
    }

    private void confirmApply() {
        final List<String> toDisable = new ArrayList<String>();
        final List<String> toEnable = new ArrayList<String>();
        for (Group g : GROUPS) {
            if (g.kind != KIND_PKGS) continue;
            for (String p : g.pkgs) {
                int s = state(p);
                if (s == MISSING) continue;
                if (g.box.isChecked() && s == ENABLED) toDisable.add(p);
                if (!g.box.isChecked() && s == DISABLED) toEnable.add(p);
            }
        }
        boolean dbWant = groupOfKind(KIND_DB).box.isChecked();
        int dbSt = dbState();
        final boolean dbOn = dbWant && dbSt != DB_ACTIVE && dbSt != DB_PENDING;
        final boolean dbOff = !dbWant && (dbSt == DB_ACTIVE || dbSt == DB_SYSTEM || dbSt == DB_PENDING);
        boolean nhWant = dbWant && groupOfKind(KIND_NOHOME).box.isChecked();
        boolean nhCur = state(STOCK_HOME_PKG) == DISABLED;
        final boolean nhOn = nhWant && !nhCur && noHomeReady();
        final boolean nhOff = !nhWant && nhCur;
        final List<Group> modOn = new ArrayList<Group>();
        final List<Group> modOff = new ArrayList<Group>();
        for (Group g : GROUPS) {
            if (g.kind == KIND_APK) {
                boolean inst = state(g.pkgs[0]) != MISSING;
                if (g.box.isChecked() && !inst) modOn.add(g);
                if (!g.box.isChecked() && inst) modOff.add(g);
                continue;
            }
            if (g.kind != KIND_MOD || modPresent == null) continue;
            boolean want = g.box.isChecked(), cur = modInstalled(g);
            boolean flagged = modPresent.contains(g.modId) && modRemoving.contains(g.modId);
            if (want && (!cur || flagged)) modOn.add(g);
            if (!want && cur) modOff.add(g);
        }
        if (toDisable.isEmpty() && toEnable.isEmpty() && !dbOn && !dbOff && !nhOn && !nhOff
            && modOn.isEmpty() && modOff.isEmpty()) {
            showResult("没有需要修改的项目。");
            return;
        }
        StringBuilder msg = new StringBuilder();
        if (!toDisable.isEmpty()) msg.append("将禁用 ").append(toDisable.size()).append(" 个应用\n");
        if (!toEnable.isEmpty()) msg.append("将恢复 ").append(toEnable.size()).append(" 个应用\n");
        if (dbOn) msg.append("将安装 Aerial 桌面并设为默认桌面（需重启电视）\n");
        if (dbOff) msg.append("将恢复系统桌面，并移除 Aerial 桌面（重启后 Aerial 消失）\n");
        if (nhOn) msg.append("将禁用系统桌面（有风险）\n");
        if (nhOff) msg.append("将重新启用系统桌面\n");
        for (Group g : modOn) msg.append(g.kind == KIND_APK ? "将安装：" : "将启用：").append(g.title).append("\n");
        for (Group g : modOff) msg.append(g.kind == KIND_APK ? "将卸载：" : "将关闭：").append(g.title).append("\n");
        msg.append("\n确定继续吗？");
        new AlertDialog.Builder(this)
            .setTitle("确认")
            .setMessage(msg.toString())
            .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    runApply(toDisable, toEnable, dbOn, dbOff, nhOn, nhOff, modOn, modOff);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String copyAsset(String asset, String outName) throws Exception {
        java.io.File f = new java.io.File(getCacheDir(), outName);
        java.io.InputStream in = getAssets().open(asset);
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
        f.setReadable(true, false);
        return f.getAbsolutePath();
    }

    private void runApply(final List<String> toDisable, final List<String> toEnable,
                          final boolean dbOn, final boolean dbOff,
                          final boolean nhOn, final boolean nhOff,
                          final List<Group> modOn, final List<Group> modOff) {
        final AlertDialog progress = new AlertDialog.Builder(this)
            .setTitle("正在执行")
            .setMessage("请在弹窗里允许 root……")
            .show();
        new Thread(new Runnable() { public void run() {
            final StringBuilder script = new StringBuilder();
            String err = null;
            for (String p : toDisable) {
                script.append("pm disable-user --user 0 ").append(p).append(" 2>&1\n");
                script.append("am force-stop ").append(p).append(" 2>&1\n");
            }
            for (String p : toEnable) {
                script.append("pm enable ").append(p).append(" 2>&1\n");
            }
            if (dbOn) {
                try {
                    String apk = copyAsset("aerial.apk", "aerial.apk");
                    // 系统补丁 (PreferredActivityHelper) 拒绝把非系统应用设为默认桌面，所以要让Aerial成为系统应用：
                    // 写一个 Magisk 模块把它放进 /system/app，重启后生效；模块的 service.sh 每次开机再设默认桌面。
                    script.append("rm -rf /data/adb/modules/").append(OLD_DB_MODULE).append("\n");
                    script.append("PKG=").append(DB_PKG).append("\n");
                    script.append("MD=/data/adb/modules/").append(DB_MODULE).append("\n");
                    script.append("if pm list packages -s | grep -q \"package:$PKG\\$\"; then\n");
                    script.append("  cmd package set-home-activity --user 0 ").append(DB_COMP).append(" 2>&1\n");
                    script.append("  echo 已设为默认桌面\n");
                    script.append("else\n");
                    script.append("  rm -rf $MD /data/local/tmp/dbx\n");
                    script.append("  mkdir -p $MD/system/app/Aerial/lib/arm64 /data/local/tmp/dbx\n");
                    script.append("  cp ").append(apk).append(" $MD/system/app/Aerial/Aerial.apk\n");
                    script.append("  unzip -o ").append(apk).append(" -d /data/local/tmp/dbx >/dev/null 2>&1\n");
                    script.append("  cp /data/local/tmp/dbx/lib/arm64-v8a/*.so $MD/system/app/Aerial/lib/arm64/\n");
                    script.append("  rm -rf /data/local/tmp/dbx\n");
                    script.append("  printf 'id=beekeeper_aerial\\nname=Aerial Launcher\\nversion=v1.1.1\\nversionCode=111\\n"
                        + "author=酷安@pkkka\\ndescription=Installs Aerial 1.20 as a system app and sets it as the default home.\\n' > $MD/module.prop\n");
                    script.append("  cat > $MD/service.sh <<'SVCEOF'\n");
                    script.append("#!/system/bin/sh\n");
                    script.append("until [ \"$(getprop sys.boot_completed)\" = 1 ]; do sleep 3; done\n");
                    script.append("sleep 10\n");
                    script.append("P=").append(DB_PKG).append("\n");
                    script.append("for p in WRITE_SECURE_SETTINGS ACCESS_FINE_LOCATION; do pm grant $P android.permission.$p; done\n");
                    script.append("cmd package set-home-activity --user 0 ").append(DB_COMP).append("\n");
                    script.append("SVCEOF\n");
                    script.append("  chmod -R a+rX $MD; chmod 755 $MD/service.sh\n");
                    script.append("  pm uninstall $PKG 2>&1\n");
                    script.append("  echo 请重启电视；重启后自动设为默认桌面\n");
                    script.append("fi\n");
                    script.append("rm -f ").append(apk).append("\n");
                } catch (Exception e) {
                    err = "无法读取内置的 Aerial 安装包：" + e + "\n";
                }
            }
            if (nhOn && err == null) {
                // 先确认Aerial是系统应用；禁用后再确认默认桌面确实落到Aerial，否则立刻恢复系统桌面。
                script.append("if pm list packages -s | grep -q \"package:").append(DB_PKG).append("\\$\"; then\n");
                script.append("  pm disable-user --user 0 ").append(STOCK_HOME_PKG).append(" 2>&1\n");
                script.append("  H=$(cmd package resolve-activity --brief -a android.intent.action.MAIN"
                    + " -c android.intent.category.HOME | tail -1)\n");
                script.append("  case \"$H\" in\n");
                script.append("    ").append(DB_PKG).append("/*) am force-stop ").append(STOCK_HOME_PKG)
                    .append("; echo 已禁用系统桌面;;\n");
                script.append("    *) pm enable ").append(STOCK_HOME_PKG)
                    .append("; echo \"默认桌面不是 Aerial（$H），已自动恢复系统桌面\";;\n");
                script.append("  esac\n");
                script.append("else echo Aerial 桌面还不是系统应用，已跳过禁用系统桌面; fi\n");
            }
            if (nhOff || dbOff) {
                script.append("pm enable ").append(STOCK_HOME_PKG).append(" 2>&1\n");
            }
            if (dbOff) {
                script.append("cmd package set-home-activity ").append(STOCK_HOME_COMP).append(" 2>&1\n");
                script.append("rm -rf /data/adb/modules/").append(DB_MODULE).append("\n");
                script.append("echo 已恢复系统桌面，重启后 Aerial 消失\n");
            }
            for (Group g : modOn) {
                if (g.kind == KIND_APK) {
                    try {
                        String apk = copyAsset(g.asset, g.asset);
                        script.append("echo \"== 安装 ").append(g.title).append("\"\n");
                        script.append("pm install -r ").append(apk).append(" 2>&1\n");
                        script.append("rm -f ").append(apk).append("\n");
                    } catch (Exception e) {
                        err = (err == null ? "" : err) + "无法读取内置安装包 " + g.asset + "：" + e + "\n";
                    }
                    continue;
                }
                try {
                    String zip = copyAsset(g.asset, g.asset);
                    script.append("echo \"== 安装 ").append(g.modId).append("\"\n");
                    script.append("if [ -d /data/adb/modules/").append(g.modId).append(" ]; then\n");
                    script.append("  rm -f /data/adb/modules/").append(g.modId).append("/remove\n");
                    script.append("  echo 已恢复启用\n");
                    script.append("else magisk --install-module ").append(zip).append(" 2>&1; fi\n");
                    script.append("rm -f ").append(zip).append("\n");
                } catch (Exception e) {
                    err = (err == null ? "" : err) + "无法读取内置模块 " + g.asset + "：" + e + "\n";
                }
            }
            for (Group g : modOff) {
                if (g.kind == KIND_APK) {
                    script.append("echo \"== 卸载 ").append(g.title).append("\"\n");
                    script.append("pm uninstall ").append(g.pkgs[0]).append(" 2>&1\n");
                    continue;
                }
                script.append("echo \"== 卸载 ").append(g.modId).append("\"\n");
                script.append("if [ -d /data/adb/modules/").append(g.modId).append(" ]; then\n");
                script.append("  touch /data/adb/modules/").append(g.modId).append("/remove\n");
                script.append("  echo 已关闭，重启后生效\n");
                script.append("else rm -rf /data/adb/modules_update/").append(g.modId).append("; echo 已移除; fi\n");
            }
            boolean modReboot = false;
            for (Group g : modOn) if (g.kind == KIND_MOD) modReboot = true;
            for (Group g : modOff) if (g.kind == KIND_MOD) modReboot = true;
            final boolean needReboot = dbOn || dbOff || modReboot;
            if (needReboot) script.append("echo 有改动需要重启电视后生效\n");
            script.append("echo __DONE__\n");
            final String out = (err == null ? "" : err) + runSu(script.toString());
            main.post(new Runnable() { public void run() {
                if (progress.isShowing()) progress.dismiss();
                showResult(out);
                refresh();
            }});
        }}, "slim-apply").start();
    }

    /** 右上角的电源图标：画出来的，不依赖字体或资源文件。 */
    private static final class PowerIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        PowerIcon(Context c) {
            super(c);
            setFocusable(true);
            setClickable(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.28f;
            float cx = w / 2, cy = h / 2 + r * 0.1f;
            if (isFocused() || isPressed()) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x44FFFFFF);
                canvas.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, paint);
                paint.setStyle(Paint.Style.STROKE);
            }
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(Math.max(2f, r * 0.22f));
            canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -60, 300, false, paint);
            canvas.drawLine(cx, cy - r * 1.15f, cx, cy - r * 0.15f, paint);
        }
        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
            .setTitle("重启电视")
            .setMessage("确定要现在重启电视吗？")
            .setPositiveButton("重启", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    new Thread(new Runnable() { public void run() {
                        final String out = suOut("reboot\n");
                        if (out == null) main.post(new Runnable() { public void run() {
                            showResult("无法使用 root，没有重启。");
                        }});
                    }}, "slim-reboot").start();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showResult(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.setFocusable(true);
        sv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle("执行结果")
            .setView(sv)
            .setPositiveButton("确定", null)
            .show();
    }

    /** 跑 su 脚本并原样返回输出；没有 root 或出错返回 null。 */
    private String suOut(String script) {
        try {
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            OutputStream os = p.getOutputStream();
            os.write(script.getBytes("UTF-8"));
            os.write("exit\n".getBytes("UTF-8"));
            os.flush();
            os.close();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String runSu(String script) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            OutputStream os = p.getOutputStream();
            os.write(script.getBytes("UTF-8"));
            os.write("exit\n".getBytes("UTF-8"));
            os.flush();
            os.close();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.equals("__DONE__")) continue;
                sb.append(line).append("\n");
            }
            int rc = p.waitFor();
            if (rc != 0 && sb.length() == 0) sb.append("su 返回 ").append(rc).append("，可能没有授予 root。\n");
            else sb.append("完成。\n");
        } catch (Exception e) {
            sb.append("无法使用 root：").append(e).append("\n");
        }
        return sb.toString();
    }
}
