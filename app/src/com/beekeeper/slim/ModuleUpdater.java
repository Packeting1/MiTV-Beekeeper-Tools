package com.beekeeper.slim;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.View;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static com.beekeeper.slim.Catalog.*;
import static com.beekeeper.slim.Net.*;
import static com.beekeeper.slim.Shell.*;

/** 内置 Magisk / LSPosed 模块的版本比较与更新。 */
final class ModuleUpdater extends Part {

    ModuleUpdater(MainActivity a) {
        super(a);
    }

    String[] readBundledMod(String asset) {
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

    String[] readBundledApk(String asset) {
        try {
            String path = copyAsset(asset);
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
    java.util.LinkedHashMap<Group, String[]> pendingUpdates() {
        java.util.LinkedHashMap<Group, String[]> res = new java.util.LinkedHashMap<Group, String[]>();
        for (Group g : a.GROUPS) {
            if (g.kind == KIND_MOD) {
                if (a.modPresent == null || a.modRemoving == null) continue;
                if (!a.modPresent.contains(g.modId) || a.modRemoving.contains(g.modId)) continue;
                String[] nb = a.bundledMod.get(g.modId);
                String[] cur = a.modUpVer.containsKey(g.modId) ? a.modUpVer.get(g.modId) : a.modVer.get(g.modId);
                if (nb == null || cur == null) continue;
                int c = toInt(cur[0]), n = toInt(nb[0]);
                if (c >= 0 && n >= 0 && c < n) res.put(g, new String[] { cur[1], nb[1] });
            } else if (g.kind == KIND_APK) {
                String[] nb = a.bundledApk.get(g.pkgs[0]);
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

    void updateBanner() {
        if (a.updateBar == null) return;
        java.util.LinkedHashMap<Group, String[]> ups = pendingUpdates();
        if (ups.isEmpty()) {
            a.updateBar.setVisibility(View.GONE);
            return;
        }
        a.updateText.setText("发现 " + ups.size() + " 个模块/应用有更新");
        a.updateBar.setVisibility(View.VISIBLE);
    }

    void confirmUpdate() {
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
        new AlertDialog.Builder(a)
            .setTitle("发现更新")
            .setMessage(msg.toString())
            .setPositiveButton("全部更新", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { runUpdate(new ArrayList<Group>(ups.keySet())); }
            })
            .setNegativeButton("以后再说", null)
            .show();
    }

    void runUpdate(final List<Group> list) {
        final AlertDialog progress = new AlertDialog.Builder(a)
            .setTitle("正在更新")
            .setMessage("请在弹窗里允许 root……")
            .show();
        new Thread(new Runnable() { public void run() {
            StringBuilder script = new StringBuilder();
            String err = null;
            boolean mod = false;
            for (Group g : list) {
                try {
                    String f = copyAsset(g.asset);
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
            final String out = (err == null ? "" : err) + runSu(script.toString());
            main.post(new Runnable() { public void run() {
                if (progress.isShowing()) progress.dismiss();
                showResult(out);
                refresh();
            }});
        }}, "slim-update").start();
    }
}
