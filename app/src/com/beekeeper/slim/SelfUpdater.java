package com.beekeeper.slim;

import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import static com.beekeeper.slim.Net.*;
import static com.beekeeper.slim.Shell.*;

/** 本应用自身的更新：查 Release、关于页状态、下载安装。 */
final class SelfUpdater extends Part {

    SelfUpdater(MainActivity a) {
        super(a);
    }

    String ownVersion() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "0" : v;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 有新版本时为那个 Release，否则为 null。 */
    Release rel;

    String selfMsg = "";

    boolean selfChecking;

    View aboutDot, btnDot;

    Button checkBtn;

    TextView selfStatus;

    /** 查 GitHub 最新 Release。自动检测失败就静默跳过；手动检测用提示条告知结果。不弹窗。 */
    void checkSelfUpdate(final boolean manual) {
        if (selfChecking) return;
        selfChecking = true;
        selfMsg = "正在检查更新……";
        refreshAboutUi();
        new Thread(new Runnable() { public void run() {
            Release found = null;
            String fail = null;
            boolean netFail = false;
            try {
                found = fetchRelease(GH_REPO, null, ownVersion());
            } catch (Throwable t) {
                netFail = t instanceof NetFail;
                fail = netFail ? t.getMessage() : String.valueOf(t);
            }
            final Release fRel = found;
            final String fFail = fail;
            final boolean fNetFail = netFail;
            main.post(new Runnable() { public void run() {
                selfChecking = false;
                if (fFail != null) {
                    selfMsg = manual ? "检查更新失败" : "";
                    if (manual) {
                        if (fNetFail) showNetFail("检查更新", fFail);
                        else android.widget.Toast.makeText(a, "检查更新失败：" + fFail, android.widget.Toast.LENGTH_LONG).show();
                    }
                } else if (fRel.url != null && cmpVersion(fRel.tag, ownVersion()) > 0) {
                    rel = fRel;
                    selfMsg = "";
                    if (manual) android.widget.Toast.makeText(a, "发现新版本 " + fRel.tag, android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    rel = null;
                    selfMsg = "已是最新版本";
                    if (manual) android.widget.Toast.makeText(a, selfMsg, android.widget.Toast.LENGTH_SHORT).show();
                }
                refreshAboutUi();
            }});
        }}, "slim-selfcheck").start();
    }

    void refreshAboutUi() {
        if (checkBtn == null) return;
        boolean has = rel != null;
        aboutDot.setVisibility(has ? View.VISIBLE : View.GONE);
        btnDot.setVisibility(has ? View.VISIBLE : View.GONE);
        checkBtn.setEnabled(!selfChecking);
        checkBtn.setText(has ? "更新到 " + rel.tag : "检查更新");
        StringBuilder sb = new StringBuilder("当前版本：").append(ownVersion());
        if (has) {
            sb.append("\n发现新版本：").append(rel.tag);
        } else if (selfMsg.length() > 0) {
            sb.append("\n").append(selfMsg);
        }
        selfStatus.setText(sb.toString());
    }

    void onCheckButton() {
        if (rel == null) { checkSelfUpdate(true); return; }
        final Release r = rel;
        showUpdateDialog("发现新版本 " + r.tag,
            "更新来源：github.com/" + GH_REPO
                + "\n点“更新”将下载并安装，完成后应用会自动重新打开。"
                + "\n\n当前版本 " + ownVersion() + " → " + r.tag,
            r,
            new Runnable() { public void run() { downloadAndInstall(r.url, r.digest); } },
            new Runnable() { public void run() {
                showFullNotes("完整更新日志", r, new Runnable() { public void run() { onCheckButton(); } });
            } });
    }

    void downloadAndInstall(final String url, final String digest) {
        final Progress p = new Progress(a);
        final CancelToken tok = new CancelToken();
        final Dlg progress = showProgress("正在更新", p.box, tok, "已取消更新");
        new Thread(new Runnable() { public void run() {
            final java.io.File f = new java.io.File(getCacheDir(), "selfupdate.apk");
            try {
                downloadFile(main, withMirrors(url), digest, f, p, tok);
                android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi == null || !getPackageName().equals(pi.packageName)) throw new Exception("下载的不是本应用的安装包");
                f.setReadable(true, false);
                if (!tok.beginInstall()) throw new CancelledException();
            } catch (Throwable t) {
                f.delete();
                showDownloadError(progress, t);
                return;
            }
            showInstalling(progress, p, "正在安装……完成后应用会自动重新打开。");
            String log = "/data/local/tmp/slim_selfupdate.log";
            String res = runSu("rm -f " + log + "\n"
                + "setsid sh -c \"pm install -r " + f.getAbsolutePath() + " > " + log + " 2>&1; rm -f " + f.getAbsolutePath()
                + "; am start --user 0 -n " + getPackageName() + "/.MainActivity >> " + log + " 2>&1\" >/dev/null 2>&1 &\n"
                + "sleep 1\n");
            if (noRoot(res)) {
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
}
