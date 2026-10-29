package com.beekeeper.slim;

import android.content.pm.PackageManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashSet;
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

    /** 最近一次查到的最新 Release；还没查到（或查失败）为 null。 */
    Release rel;

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
        return rel != null && v != null && aerialInstalledOk() && cmpVersion(rel.tag, v) > 0;
    }

    void updateAerialUi() {
        if (aerialBar == null) return;
        boolean up = aerialUpdatable();
        aerialBar.setVisibility(up ? View.VISIBLE : View.GONE);
        if (up) aerialBarText.setText("Aerial 有新版本：" + aerialVersion() + " → " + rel.tag);
    }

    /** 启动时自动检测；失败就静默跳过。 */
    void checkAerialUpdate() {
        if (aerialChecking) return;
        aerialChecking = true;
        new Thread(new Runnable() { public void run() {
            Release found = null;
            try {
                found = fetchRelease(AERIAL_REPO, AERIAL_ASSET, aerialVersion());
            } catch (Throwable ignore) { }
            final Release fRel = found;
            main.post(new Runnable() { public void run() {
                aerialChecking = false;
                if (fRel != null) rel = fRel;
                updateAerialUi();
            }});
        }}, "slim-aerialcheck").start();
    }

    void confirmAerialUpdate() {
        if (!aerialUpdatable()) { updateAerialUi(); return; }
        showUpdateDialog("Aerial 有新版本 " + rel.tag,
            "更新来源：github.com/" + AERIAL_REPO
                + "\n点“更新”将下载并安装，设置会保留，安装时 Aerial 会重启。"
                + "\n\nAerial " + aerialVersion() + " → " + rel.tag,
            rel,
            new Runnable() { public void run() { updateAerial(); } },
            new Runnable() { public void run() {
                showFullNotes("Aerial 完整更新日志", rel, new Runnable() { public void run() { confirmAerialUpdate(); } });
            } });
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
        final String tag = rel.tag, url = rel.url, digest = rel.digest;
        final boolean wasHome = DB_PKG.equals(homePackage());
        final Progress p = new Progress(a);
        final CancelToken tok = new CancelToken();
        final Dlg progress = showProgress("正在更新 Aerial", p.box, tok, "已取消更新");
        new Thread(new Runnable() { public void run() {
            final java.io.File f = new java.io.File(getCacheDir(), "aerial_update.apk");
            try {
                java.util.List<String> cands = withMirrors(url);
                cands.add(AERIAL_SITE_APK);
                downloadFile(main, cands, digest, f, p, tok);
                android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi == null || !DB_PKG.equals(pi.packageName)) throw new Exception("下载的不是 Aerial 安装包");
                int curCode = getPackageManager().getPackageInfo(DB_PKG, 0).versionCode;
                if (pi.versionCode <= curCode) throw new Exception("安装包版本号（" + pi.versionCode + "）不高于当前（" + curCode + "）");
                if (!sameAerialSigner(f.getAbsolutePath())) throw new Exception("安装包签名和已装的 Aerial 不一致，已取消");
                f.setReadable(true, false);
                if (!tok.beginInstall()) throw new CancelledException();
            } catch (Throwable t) {
                f.delete();
                showDownloadError(progress, t);
                return;
            }
            showInstalling(progress, p, "正在安装……");
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
            final boolean denied = noRoot(out);
            final boolean ok = out.contains("Success");
            final boolean staged = out.contains("MODULE_OK");
            final boolean noModule = out.contains("NO_MODULE");
            main.post(new Runnable() { public void run() {
                if (progress.isShowing()) progress.dismiss();
                StringBuilder m = new StringBuilder();
                if (denied) {
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
                if (!denied) m.append("\n\n").append(out.replace("MODULE_OK\n", "").replace("MODULE_FAIL\n", "")
                    .replace("NO_MODULE\n", "").trim());
                showResult(m.toString());
                refresh();
            }});
        }}, "slim-aerialupdate").start();
    }
}
