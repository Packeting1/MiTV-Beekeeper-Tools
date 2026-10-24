package com.beekeeper.slim;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    /** selfNotes 只含最近 2 个版本的说明；selfNotesFull 是全部，selfNoteCount 是版本数。 */
    String selfTag, selfUrl, selfDigest, selfNotes, selfNotesFull, selfMsg = "";

    int selfNoteCount;

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
            String fail = null;
            boolean netFail = false;
            String tag = null, url = null, digest = "", notes = "", full = "";
            int count = 0;
            try {
                org.json.JSONObject j = new org.json.JSONObject(fetchApi("releases/latest"));
                tag = j.getString("tag_name");
                org.json.JSONArray as = j.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    org.json.JSONObject a = as.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.getString("browser_download_url");
                        digest = a.optString("digest", "");
                        break;
                    }
                }
                String lb = j.optString("body", "").trim();
                notes = "【" + tag + "】\n" + (lb.length() == 0 ? "（没有更新说明）" : lb);
                full = notes;
                count = 1;
                if (cmpVersion(tag, ownVersion()) > 0) {
                    // 跨版本更新要带上中间版本的说明；列表拿不到就只显示最新版的。
                    try {
                        org.json.JSONArray rs = new org.json.JSONArray(fetchApi("releases?per_page=30"));
                        StringBuilder rec = new StringBuilder(), all = new StringBuilder();
                        int n = 0;
                        for (int i = 0; i < rs.length(); i++) {
                            org.json.JSONObject r = rs.getJSONObject(i);
                            String t = r.optString("tag_name");
                            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                            if (cmpVersion(t, ownVersion()) <= 0 || cmpVersion(t, tag) > 0) continue;
                            String b = r.optString("body", "").trim();
                            String e = "【" + t + "】\n" + (b.length() == 0 ? "（没有更新说明）" : b);
                            if (n < 2) rec.append(n > 0 ? "\n\n" : "").append(e);
                            all.append(n > 0 ? "\n\n" : "").append(e);
                            n++;
                        }
                        if (rs.length() > 0 && cmpVersion(rs.getJSONObject(rs.length() - 1).optString("tag_name"), ownVersion()) > 0) {
                            all.append("\n\n（更早的版本说明请到 GitHub 查看）");
                        }
                        if (n > 0) { notes = rec.toString(); full = all.toString(); count = n; }
                    } catch (Throwable ignore) { }
                }
            } catch (Throwable t) {
                netFail = t instanceof NetFail;
                fail = netFail ? t.getMessage() : String.valueOf(t);
            }
            final String fTag = tag, fUrl = url, fDigest = digest, fNotes = notes, fFull = full, fFail = fail;
            final boolean fNetFail = netFail;
            final int fCount = count;
            main.post(new Runnable() { public void run() {
                selfChecking = false;
                if (fFail != null) {
                    selfMsg = manual ? "检查更新失败" : "";
                    if (manual) {
                        if (fNetFail) showNetFail("检查更新", fFail);
                        else android.widget.Toast.makeText(a, "检查更新失败：" + fFail, android.widget.Toast.LENGTH_LONG).show();
                    }
                } else if (fUrl != null && cmpVersion(fTag, ownVersion()) > 0) {
                    selfTag = fTag; selfUrl = fUrl; selfDigest = fDigest; selfNotes = fNotes;
                    selfNotesFull = fFull; selfNoteCount = fCount;
                    selfMsg = "";
                    if (manual) android.widget.Toast.makeText(a, "发现新版本 " + fTag, android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    selfTag = null;
                    selfMsg = "已是最新版本";
                    if (manual) android.widget.Toast.makeText(a, selfMsg, android.widget.Toast.LENGTH_SHORT).show();
                }
                refreshAboutUi();
            }});
        }}, "slim-selfcheck").start();
    }

    void refreshAboutUi() {
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

    void onCheckButton() {
        if (selfTag == null) { checkSelfUpdate(true); return; }
        TextView tv = new TextView(a);
        String notes = selfNotes == null || selfNotes.length() == 0 ? "（该版本没有更新说明）" : selfNotes;
        tv.setText("更新来源：github.com/" + GH_REPO
            + "\n点“更新”将下载并安装，完成后应用会自动重新打开。"
            + "\n\n当前版本 " + ownVersion() + " → " + selfTag + "\n\n更新说明：\n" + notes);
        tv.setTextSize(14);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        ScrollView nsv = new ScrollView(a);
        nsv.setFocusable(true);
        nsv.addView(tv);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
            .setTitle("发现新版本 " + selfTag)
            .setView(nsv)
            .setPositiveButton("更新", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { downloadAndInstall(selfUrl, selfDigest); }
            })
            .setNegativeButton("取消", null);
        if (selfNoteCount > 2) {
            b.setNeutralButton("查看完整更新日志", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { showSelfFullNotes(); }
            });
        }
        b.show();
    }

    void showSelfFullNotes() {
        TextView tv = new TextView(a);
        tv.setText(selfNotesFull);
        tv.setTextSize(14);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        ScrollView sv = new ScrollView(a);
        sv.setFocusable(true);
        sv.addView(tv);
        new AlertDialog.Builder(a)
            .setTitle("完整更新日志（共 " + selfNoteCount + " 个版本）")
            .setView(sv)
            .setPositiveButton("返回", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { onCheckButton(); }
            })
            .show();
    }

    void downloadAndInstall(final String url, final String digest) {
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
        final AlertDialog progress = showProgress("正在更新", pbox, tok, "已取消更新");
        new Thread(new Runnable() { public void run() {
            final java.io.File f = new java.io.File(getCacheDir(), "selfupdate.apk");
            String err = null;
            boolean netFail = false, cancelled = false;
            try {
                downloadFile(main, withMirrors(url), digest, f, ptxt, pbar, tok);
                android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                if (pi == null || !getPackageName().equals(pi.packageName)) throw new Exception("下载的不是本应用的安装包");
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
}
