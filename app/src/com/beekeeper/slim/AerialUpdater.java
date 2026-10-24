package com.beekeeper.slim;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.beekeeper.slim.Catalog.*;
import static com.beekeeper.slim.Net.*;
import static com.beekeeper.slim.Shell.*;

/** Aerial 桌面的更新：查 Release、更新提示条、下载安装并同步到 Magisk 模块。 */
final class AerialUpdater extends Part {

    AerialUpdater(MainActivity a) {
        super(a);
    }

    // ---- Aerial 更新：查 github.com/pengchujin/aerial-tv 的最新 Release，用户点了才下载安装 ----
    static final String AERIAL_REPO = "pengchujin/aerial-tv";

    static final String AERIAL_ASSET = "aerial-standard.apk";

    static final String AERIAL_SITE_APK = "https://aerial.qust.me/download/aerial-standard.apk";

    /** aerialNotes 只含最近 2 个版本的说明；aerialNotesFull 是全部，aerialNoteCount 是版本数。 */
    String aerialTag, aerialUrl, aerialDigest, aerialNotes, aerialNotesFull;

    int aerialNoteCount;

    boolean aerialChecking;

    TextView aerialBarText;

    LinearLayout aerialBar;

    String aerialVersion() {
        try {
            return getPackageManager().getPackageInfo(DB_PKG, 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    boolean aerialInstalledOk() {
        int st = dbState();
        return st == DB_SYSTEM || st == DB_ACTIVE || st == DB_USERAPP;
    }

    boolean aerialUpdatable() {
        String v = aerialVersion();
        return aerialTag != null && v != null && aerialInstalledOk() && cmpVersion(aerialTag, v) > 0;
    }

    void updateAerialUi() {
        if (aerialBar == null) return;
        boolean up = aerialUpdatable();
        aerialBar.setVisibility(up ? View.VISIBLE : View.GONE);
        if (up) aerialBarText.setText("Aerial 有新版本：" + aerialVersion() + " → " + aerialTag);
    }

    /** 启动时自动检测；失败就静默跳过。 */
    void checkAerialUpdate() {
        if (aerialChecking) return;
        aerialChecking = true;
        new Thread(new Runnable() { public void run() {
            String fail = null, tag = null, url = null, digest = "", notes = "", full = "";
            int count = 0;
            try {
                org.json.JSONObject j = new org.json.JSONObject(fetchApi(AERIAL_REPO, "releases/latest"));
                tag = j.getString("tag_name");
                org.json.JSONArray as = j.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    org.json.JSONObject a = as.getJSONObject(i);
                    if (AERIAL_ASSET.equals(a.optString("name"))) {
                        url = a.getString("browser_download_url");
                        digest = a.optString("digest", "");
                        break;
                    }
                }
                if (url == null) throw new Exception("最新发布里没有 " + AERIAL_ASSET);
                String lb = j.optString("body", "").trim();
                notes = "【" + tag + "】\n" + (lb.length() == 0 ? "（没有更新说明）" : lb);
                full = notes;
                count = 1;
                String cur = aerialVersion();
                if (cur != null && cmpVersion(tag, cur) > 0) {
                    try {
                        org.json.JSONArray rs = new org.json.JSONArray(fetchApi(AERIAL_REPO, "releases?per_page=30"));
                        StringBuilder rec = new StringBuilder(), all = new StringBuilder();
                        int n = 0;
                        for (int i = 0; i < rs.length(); i++) {
                            org.json.JSONObject r = rs.getJSONObject(i);
                            String t = r.optString("tag_name");
                            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                            if (cmpVersion(t, cur) <= 0 || cmpVersion(t, tag) > 0) continue;
                            String b = r.optString("body", "").trim();
                            String e = "【" + t + "】\n" + (b.length() == 0 ? "（没有更新说明）" : b);
                            if (n < 2) rec.append(n > 0 ? "\n\n" : "").append(e);
                            all.append(n > 0 ? "\n\n" : "").append(e);
                            n++;
                        }
                        // 版本落后超过 30 个时，列表拿不全最老的几版。
                        if (rs.length() > 0 && cmpVersion(rs.getJSONObject(rs.length() - 1).optString("tag_name"), cur) > 0) {
                            all.append("\n\n（更早的版本说明请到 GitHub 查看）");
                        }
                        if (n > 0) { notes = rec.toString(); full = all.toString(); count = n; }
                    } catch (Throwable ignore) { }
                }
            } catch (Throwable t) {
                fail = String.valueOf(t);
            }
            final String fTag = tag, fUrl = url, fDigest = digest, fNotes = notes, fFull = full, fFail = fail;
            final int fCount = count;
            main.post(new Runnable() { public void run() {
                aerialChecking = false;
                if (fFail == null) {
                    aerialTag = fTag; aerialUrl = fUrl; aerialDigest = fDigest;
                    aerialNotes = fNotes; aerialNotesFull = fFull; aerialNoteCount = fCount;
                }
                updateAerialUi();
            }});
        }}, "slim-aerialcheck").start();
    }

    void confirmAerialUpdate() {
        if (!aerialUpdatable()) { updateAerialUi(); return; }
        TextView tv = new TextView(a);
        String notes = aerialNotes == null || aerialNotes.length() == 0 ? "（该版本没有更新说明）" : aerialNotes;
        tv.setText("更新来源：github.com/" + AERIAL_REPO
            + "\n点“更新”将下载并安装，设置会保留，安装时 Aerial 会重启。"
            + "\n\nAerial " + aerialVersion() + " → " + aerialTag + "\n\n更新说明：\n" + notes);
        tv.setTextSize(14);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        ScrollView nsv = new ScrollView(a);
        nsv.setFocusable(true);
        nsv.addView(tv);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
            .setTitle("Aerial 有新版本 " + aerialTag)
            .setView(nsv)
            .setPositiveButton("更新", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { updateAerial(); }
            })
            .setNegativeButton("取消", null);
        if (aerialNoteCount > 2) {
            b.setNeutralButton("查看完整更新日志", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { showAerialFullNotes(); }
            });
        }
        b.show();
    }

    void showAerialFullNotes() {
        TextView tv = new TextView(a);
        tv.setText(aerialNotesFull);
        tv.setTextSize(14);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        ScrollView sv = new ScrollView(a);
        sv.setFocusable(true);
        sv.addView(tv);
        new AlertDialog.Builder(a)
            .setTitle("Aerial 完整更新日志（共 " + aerialNoteCount + " 个版本）")
            .setView(sv)
            .setPositiveButton("返回", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { confirmAerialUpdate(); }
            })
            .show();
    }

    /** 新安装包的签名要和已装的 Aerial 一致；读不到签名时交给 pm install 判断。 */
    boolean sameAerialSigner(String apkPath) {
        try {
            PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo a = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES);
            android.content.pm.PackageInfo i = pm.getPackageInfo(DB_PKG, PackageManager.GET_SIGNATURES);
            if (a == null || a.signatures == null || a.signatures.length == 0
                || i.signatures == null || i.signatures.length == 0) return true;
            Set<String> x = new HashSet<String>(), y = new HashSet<String>();
            for (android.content.pm.Signature s : a.signatures) x.add(s.toCharsString());
            for (android.content.pm.Signature s : i.signatures) y.add(s.toCharsString());
            return x.equals(y);
        } catch (Exception e) {
            return true;
        }
    }

    void updateAerial() {
        final String tag = aerialTag, url = aerialUrl, digest = aerialDigest;
        final boolean wasHome = DB_PKG.equals(homePackage());
        final TextView ptxt = new TextView(a);
        ptxt.setText("正在下载……0%");
        ptxt.setTextSize(14);
        final android.widget.ProgressBar pbar = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pbar.setMax(100);
        pbar.setIndeterminate(true);
        LinearLayout pbox = new LinearLayout(a);
        pbox.setOrientation(LinearLayout.VERTICAL);
        pbox.setPadding(dp(24), dp(16), dp(24), dp(8));
        pbox.addView(ptxt);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(10);
        pbox.addView(pbar, plp);
        final CancelToken tok = new CancelToken();
        final AlertDialog progress = showProgress("正在更新 Aerial", pbox, tok, "已取消更新");
        new Thread(new Runnable() { public void run() {
            final java.io.File f = new java.io.File(getCacheDir(), "aerial_update.apk");
            String err = null;
            boolean netFail = false, cancelled = false;
            try {
                java.util.List<String> cands = withMirrors(url);
                cands.add(AERIAL_SITE_APK);
                downloadFile(main, cands, digest, f, ptxt, pbar, tok);
                android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi == null || !DB_PKG.equals(pi.packageName)) throw new Exception("下载的不是 Aerial 安装包");
                int curCode = getPackageManager().getPackageInfo(DB_PKG, 0).versionCode;
                if (pi.versionCode <= curCode) throw new Exception("安装包版本号（" + pi.versionCode + "）不高于当前（" + curCode + "）");
                if (!sameAerialSigner(f.getAbsolutePath())) throw new Exception("安装包签名和已装的 Aerial 不一致，已取消");
                f.setReadable(true, false);
                if (!tok.beginInstall()) throw new CancelledException();
            } catch (Throwable t) {
                cancelled = t instanceof CancelledException;
                netFail = t instanceof NetFail;
                err = netFail ? t.getMessage() : String.valueOf(t);
            }
            if (err != null) {
                f.delete();
                final String e = err;
                final boolean nf = netFail, cc = cancelled;
                main.post(new Runnable() { public void run() {
                    if (progress.isShowing()) progress.dismiss();
                    if (cc) return;
                    if (nf) showNetFail("下载", e);
                    else showResult("更新失败：" + e);
                } });
                return;
            }
            main.post(new Runnable() { public void run() {
                progress.setCancelable(false);
                progress.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                pbar.setIndeterminate(true);
                ptxt.setText("正在安装……");
            } });
            String ver = tag.replaceFirst("^[vV]", "");
            String md = "/data/adb/modules/" + DB_MODULE;
            StringBuilder s = new StringBuilder();
            s.append("PKG=").append(DB_PKG).append("\n");
            s.append("APK=").append(f.getAbsolutePath()).append("\n");
            s.append("D=").append(md).append("/system/app/Aerial\n");
            s.append("X=/data/local/tmp/aerial_x\n");
            // 先把 Magisk 模块里的安装包换成新版，重启后仍是新版；再直接安装，立即生效。
            s.append("if [ -d $D ]; then\n");
            s.append("  rm -rf $X; mkdir -p $X\n");
            s.append("  unzip -o $APK 'lib/arm64-v8a/*' -d $X >/dev/null 2>&1\n");
            s.append("  if ls $X/lib/arm64-v8a/*.so >/dev/null 2>&1 && cp $APK $D/Aerial.apk.new && mv -f $D/Aerial.apk.new $D/Aerial.apk; then\n");
            s.append("    mkdir -p $D/lib/arm64; rm -f $D/lib/arm64/*.so\n");
            s.append("    cp $X/lib/arm64-v8a/*.so $D/lib/arm64/\n");
            s.append("    chmod -R a+rX ").append(md).append("\n");
            s.append("    sed -i \"s/Aerial [0-9][0-9.]* as a system app/Aerial ").append(ver).append(" as a system app/\" ")
                .append(md).append("/module.prop\n");
            s.append("    echo MODULE_OK\n");
            s.append("  else rm -f $D/Aerial.apk.new; echo MODULE_FAIL; fi\n");
            s.append("  rm -rf $X\n");
            s.append("else echo NO_MODULE; fi\n");
            s.append("pm install -r $APK 2>&1\n");
            s.append("for p in WRITE_SECURE_SETTINGS ACCESS_FINE_LOCATION; do pm grant $PKG android.permission.$p >/dev/null 2>&1; done\n");
            if (wasHome) s.append("cmd package set-home-activity --user 0 ").append(DB_COMP).append(" 2>&1\n");
            s.append("rm -f $APK\n");
            final String out = runSu(s.toString());
            f.delete();
            final boolean noRoot = out.contains("无法使用 root") || out.contains("可能没有授予 root");
            final boolean ok = out.contains("Success");
            final boolean staged = out.contains("MODULE_OK");
            final boolean noModule = out.contains("NO_MODULE");
            main.post(new Runnable() { public void run() {
                if (progress.isShowing()) progress.dismiss();
                StringBuilder m = new StringBuilder();
                if (noRoot) {
                    m.append("没有 root 权限，无法安装。");
                } else if (ok) {
                    m.append("Aerial 已更新到 ").append(tag).append("。");
                    if (noModule) m.append("\n没有找到 Aerial 模块，只更新了已装的应用。");
                    else if (!staged) m.append("\n⚠ 模块里的安装包没能同步，重启后可能回到旧版。");
                } else if (staged) {
                    m.append("已更新模块里的安装包，但现在没能直接安装。重启电视后生效。");
                } else {
                    m.append("更新失败。");
                }
                if (!noRoot) m.append("\n\n").append(out.replace("MODULE_OK\n", "").replace("MODULE_FAIL\n", "")
                    .replace("NO_MODULE\n", "").trim());
                showResult(m.toString());
                refresh();
            }});
        }}, "slim-aerialupdate").start();
    }
}
