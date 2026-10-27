package com.beekeeper.slim;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;

import static com.beekeeper.slim.Catalog.*;
import static com.beekeeper.slim.Net.*;
import static com.beekeeper.slim.Shell.*;

/**
 * beekeeper 电视的系统精简工具 (需要 Magisk root)。
 *
 * - 应用类：`pm disable-user` / `pm enable`，只能碰 GROUPS 里列出的可选后台应用，核心服务不在名单里。
 * - Aerial 桌面：装成系统应用 (Magisk 模块) 并设为默认桌面；系统只允许系统应用当默认桌面。
 * - 禁用系统桌面：只有Aerial已作为系统应用装好才能开启，禁用后如果默认桌面不是Aerial会自动回滚。
 * - Magisk 模块 (HGIG / LDM / Zygote64)：勾选 = `magisk --install-module`，取消 = 标记卸载 (重启时执行 uninstall.sh)。
 */
public class MainActivity extends Activity {

    final Handler main = new Handler(Looper.getMainLooper());
    final SelfUpdater self = new SelfUpdater(this);
    final AerialUpdater aerial = new AerialUpdater(this);
    final ModuleUpdater modUp = new ModuleUpdater(this);
    final LsStatus ls = new LsStatus(this);
    final ApplyRunner applier = new ApplyRunner(this);
    final Group[] GROUPS = Catalog.groups();
    final Hidden[] HIDDEN = Catalog.hidden();

    void launchHidden(final Hidden h) {
        log.setText("正在打开：" + h.title + "\n");
        new Thread(new Runnable() { public void run() {
            final String out = suOut("am start --user 0 " + h.args + " 2>&1\n");
            main.post(new Runnable() { public void run() {
                log.setText(out == null ? "无法使用 root。\n" : out);
            }});
        }}, "slim-hidden").start();
    }

    void confirmHidden(final Hidden h) {
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

    TextView log;


    /** Magisk 模块状态；null = 还没读到或没有 root。 */
    Set<String> modPresent, modRemoving, modPending;

    /** Magisk 里被停用的模块 / 实际检测到已生效的模块。 */
    Set<String> modDisabled = new HashSet<String>(), modActive = new HashSet<String>();

    /** 版本信息：模块 id -> {versionCode, version}。modVer 是已装的，modUpVer 是已暂存、重启后生效的；bundled* 是本应用自带的。 */
    java.util.Map<String, String[]> modVer = new java.util.HashMap<String, String[]>(),
        modUpVer = new java.util.HashMap<String, String[]>(),
        bundledMod = new java.util.HashMap<String, String[]>(),
        bundledApk = new java.util.HashMap<String, String[]>();

    LinearLayout updateBar;

    TextView updateText;

    String modActual(Group g) {
        String id = g.modId;
        if (modDisabled.contains(id)) return "实际：在 Magisk 里被停用，没有生效";
        boolean on = modActive.contains(id);
        if ("mitv_4kui_switch".equals(id) || "mitv_adb_keeper".equals(id) || "beekeeper_hgig_fix".equals(id)
            || "beekeeper_ldm_fix".equals(id) || "beekeeper_dv_edid".equals(id) || "zygote64_fix".equals(id)
            || "beekeeper_freesync_keep".equals(id) || FLAG_MODS.containsKey(id)) return on ? "实际：已生效" : "实际：未生效（需重启）";
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
            public void onClick(View v) { modUp.confirmUpdate(); }
        });
        updateBar.addView(updateBtn);
        updateBar.setVisibility(View.GONE);
        root.addView(updateBar);

        aerial.aerialBar = new LinearLayout(this);
        aerial.aerialBar.setOrientation(LinearLayout.HORIZONTAL);
        aerial.aerialBar.setGravity(Gravity.CENTER_VERTICAL);
        aerial.aerialBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        aerial.aerialBar.setBackgroundColor(0x33FFB300);
        aerial.aerialBarText = new TextView(this);
        aerial.aerialBarText.setTextSize(14);
        aerial.aerialBarText.setTextColor(0xFFFFD54F);
        aerial.aerialBar.addView(aerial.aerialBarText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button aerialBarBtn = new Button(this);
        aerialBarBtn.setText("查看更新");
        aerialBarBtn.setTextSize(14);
        aerialBarBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { aerial.confirmAerialUpdate(); }
        });
        aerial.aerialBar.addView(aerialBarBtn);
        aerial.aerialBar.setVisibility(View.GONE);
        root.addView(aerial.aerialBar);

        pageApps = new LinearLayout(this);
        pageApps.setOrientation(LinearLayout.VERTICAL);
        pageOpt = new LinearLayout(this);
        pageOpt.setOrientation(LinearLayout.VERTICAL);
        pageFix = new LinearLayout(this);
        pageFix.setOrientation(LinearLayout.VERTICAL);

        pageHid = new LinearLayout(this);
        pageHid.setOrientation(LinearLayout.VERTICAL);
        TextView hintHid = new TextView(this);
        hintHid.setText("以下是系统自带的页面，页面中的操作需注意。");
        hintHid.setTextSize(13);
        hintHid.setPadding(0, dp(4), 0, dp(8));
        pageHid.addView(hintHid);
        java.util.Map<LinearLayout, LinearLayout> hidRows = new java.util.HashMap<LinearLayout, LinearLayout>();
        for (final Hidden h : HIDDEN) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
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
            cell.addView(bt, blp);
            TextView d = new TextView(this);
            d.setText(h.desc + (h.warn == null ? "" : "\n⚠ " + h.warn));
            d.setTextSize(13);
            d.setTextColor(Color.LTGRAY);
            d.setPadding(dp(6), 0, dp(6), dp(2));
            cell.addView(d);
            addBoxTwoColumns(pageHid, cell, hidRows);
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
        hintFix.setText("勾选 = 启用，取消勾选 = 关闭。改动重启后生效。");
        hintFix.setTextSize(13);
        hintFix.setPadding(0, dp(4), 0, dp(8));
        pageFix.addView(hintFix);

        java.util.Map<LinearLayout, LinearLayout> openRows = new java.util.HashMap<LinearLayout, LinearLayout>();
        for (Group g : GROUPS) {
            CheckBox cb = new CheckBox(this);
            cb.setTextSize(15);
            cb.setTextColor(Color.WHITE);
            cb.setPadding(dp(6), dp(6), dp(6), dp(6));
            g.box = cb;
            addBoxTwoColumns((g.kind == KIND_DB || g.kind == KIND_APK || isOptMod(g)) ? pageOpt : g.kind == KIND_MOD ? pageFix : pageApps, cb, openRows);
        }
        LinearLayout pageAbout = new LinearLayout(this);
        pageAbout.setOrientation(LinearLayout.HORIZONTAL);
        TextView about = new TextView(this);
        String aboutHead = "小米电视团队是干嘛的？";
        android.text.SpannableString aboutText = new android.text.SpannableString(aboutHead
            + "\n项目作者：酷安@pkkka\n项目地址：github.com/" + GH_REPO
            + "\n有任何想法或建议，欢迎在项目地址的 Issue / Pull Request 中提交"
            + "\n贡献者：Rin010、酷安@伏犬");
        aboutText.setSpan(new android.text.style.RelativeSizeSpan(1.35f), 0, aboutHead.length(),
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        about.setText(aboutText);
        about.setTextSize(16);
        about.setTextColor(Color.WHITE);
        about.setPadding(dp(6), dp(16), dp(6), dp(8));
        pageAbout.addView(about, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout aboutRight = new LinearLayout(this);
        aboutRight.setOrientation(LinearLayout.VERTICAL);
        aboutRight.setGravity(Gravity.END);
        aboutRight.setPadding(dp(16), dp(12), dp(6), 0);
        pageAbout.addView(aboutRight, new LinearLayout.LayoutParams(
            dp(260), LinearLayout.LayoutParams.WRAP_CONTENT));
        self.selfStatus = new TextView(this);
        self.selfStatus.setTextSize(15);
        self.selfStatus.setTextColor(Color.LTGRAY);
        self.selfStatus.setGravity(Gravity.END);
        self.selfStatus.setPadding(dp(6), dp(4), dp(6), dp(8));
        aboutRight.addView(self.selfStatus, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        self.checkBtn = new Button(this);
        self.checkBtn.setText("检查更新");
        self.checkBtn.setTextSize(15);
        self.checkBtn.setAllCaps(false);
        self.checkBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { self.onCheckButton(); }
        });
        FrameLayout btnWrap = new FrameLayout(this);
        btnWrap.addView(self.checkBtn, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        self.btnDot = redDot();
        // Material 主题的按钮有 elevation，会盖住后加的兄弟视图；红点要比按钮更高才画得出来。
        btnWrap.setPadding(0, dp(6), dp(6), 0);
        btnWrap.setClipChildren(false);
        btnWrap.setClipToPadding(false);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP | Gravity.END);
        self.btnDot.setElevation(dp(12));
        btnWrap.addView(self.btnDot, dlp);
        aboutRight.addView(btnWrap, new LinearLayout.LayoutParams(
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
        self.aboutDot = redDot();
        self.aboutDot.setVisibility(View.GONE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        tlp.leftMargin = dp(3);
        tlp.bottomMargin = dp(10);
        aboutTab.addView(self.aboutDot, tlp);
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
            public void onClick(View v) { applier.confirmApply(); }
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
        showLoading();
        refresh();
        self.checkSelfUpdate(false);
        aerial.checkAerialUpdate();
    }

    AlertDialog loadingDlg;

    /** 首次读取模块状态要走 root，可能要几秒（还会弹 Magisk 授权），读完前挡一个加载提示。 */
    void showLoading() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(20), dp(24), dp(20));
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        row.addView(pb, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView t = new TextView(this);
        t.setText("正在加载模块状态中……");
        t.setTextSize(16);
        t.setPadding(dp(16), 0, 0, 0);
        row.addView(t);
        loadingDlg = new AlertDialog.Builder(this).setView(row).create();
        loadingDlg.show();
    }

    void hideLoading() {
        if (loadingDlg == null) return;
        try { loadingDlg.dismiss(); } catch (Exception e) { /* 窗口已不在 */ }
        loadingDlg = null;
    }

    LinearLayout pageApps, pageOpt, pageFix, pageHid;

    /** 每页的条目两个一行；奇数个时最后一行右边用占位撑住，保持左列宽度一致。 */
    void addBoxTwoColumns(LinearLayout page, View cb, java.util.Map<LinearLayout, LinearLayout> openRows) {
        LinearLayout row = openRows.get(page);
        if (row == null) {
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            page.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(cb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            openRows.put(page, row);
        } else {
            row.removeViewAt(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(8);
            row.addView(cb, lp);
            openRows.remove(page);
        }
    }

    /** 放在优化类页的 Magisk 模块。 */
    static boolean isOptMod(Group g) {
        return g.kind == KIND_MOD && ("mitv_adb_keeper".equals(g.modId) || "mitv_4kui_switch".equals(g.modId)
            || "beekeeper_dv_edid".equals(g.modId) || "beekeeper_freesync_keep".equals(g.modId)
            || FLAG_MODS.containsKey(g.modId));
    }

    View redDot() {
        View v = new View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0xFFF44336);
        v.setBackgroundDrawable(g);
        v.setVisibility(View.GONE);
        return v;
    }

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    int state(String pkg) {
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

    Group groupOfKind(int kind) {
        for (Group g : GROUPS) if (g.kind == kind) return g;
        throw new IllegalStateException();
    }

    String homePackage() {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
            return ri == null || ri.activityInfo == null ? "" : ri.activityInfo.packageName;
        } catch (Exception e) {
            return "";
        }
    }

    int dbState() {
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

    boolean noHomeReady() {
        return dbState() == DB_ACTIVE && state("com.beekeeper.homeref") != MISSING;
    }

    boolean dbInstalledAsSystem() {
        int s = dbState();
        return s == DB_SYSTEM || s == DB_ACTIVE;
    }

    /** 系统桌面开关：Aerial已装成系统应用且已是默认桌面、Aerial开关开着才能开；已经禁用时总能点，方便恢复。 */
    void updateNoHomeBox(boolean fromRefresh) {
        Group nh = groupOfKind(KIND_NOHOME);
        boolean alreadyOff = state(STOCK_HOME_PKG) == DISABLED;
        boolean allowed = noHomeReady() && groupOfKind(KIND_DB).box.isChecked();
        nh.box.setEnabled(allowed || alreadyOff);
        if (!allowed && !alreadyOff) nh.box.setChecked(false);
    }

    boolean modInstalled(Group g) {
        return modPresent != null && modPresent.contains(g.modId)
            && !(modRemoving != null && modRemoving.contains(g.modId));
    }

    void refresh() {
        render();
        new Thread(new Runnable() { public void run() {
            // 读内置模块的版本（解压、解析安装包）和 su 脚本并行，各自耗时不叠加。
            final java.util.Map<String, String[]> bm = new java.util.HashMap<String, String[]>(),
                ba = new java.util.HashMap<String, String[]>();
            Thread bundledReader = null;
            if (bundledMod.isEmpty() && bundledApk.isEmpty()) {
                bundledReader = new Thread(new Runnable() { public void run() {
                    for (Group g : GROUPS) {
                        String[] v = null;
                        if (g.kind == KIND_MOD) { v = modUp.readBundledMod(g.asset); if (v != null) bm.put(g.modId, v); }
                        else if (g.kind == KIND_APK) { v = modUp.readBundledApk(g.asset); if (v != null) ba.put(g.pkgs[0], v); }
                    }
                }}, "slim-bundled");
                bundledReader.start();
            }
            String out = suOut(
                "CD=" + getCacheDir().getAbsolutePath() + "\n"
                // LSPosed 和 Vector 的模块目录不同（zygisk_lsposed / zygisk_vector）；前者用 lspctl，后者用 cli。
                // 状态命令最慢（约 2 秒），放后台和其余检测并行
                + "LM=/data/adb/modules/zygisk_lsposed; [ -d /data/adb/modules/zygisk_vector ] && LM=/data/adb/modules/zygisk_vector\n"
                + "if [ -f \"$LM/lspctl\" ]; then CTL=lspctl; else CTL=cli; fi\n"
                + "( timeout 15 sh \"$LM/$CTL\" status > \"$CD/lsp_status\" 2>&1; echo $? > \"$CD/lsp_rc\" ) < /dev/null &\n"
                // 只用 shell 内建命令读 module.prop，避免每个模块起多个子进程
                + "pv() { code=; ver=; [ -r \"$1/module.prop\" ] || return; while IFS= read -r l || [ -n \"$l\" ]; do case $l in"
                + " versionCode=*) [ -z \"$code\" ] && code=${l#versionCode=};; version=*) [ -z \"$ver\" ] && ver=${l#version=};; esac; done < \"$1/module.prop\"; }\n"
                + "for d in /data/adb/modules/*/; do [ -d \"$d\" ] || continue; d=${d%/}; id=${d##*/}; echo \"M:$id\";"
                + " [ -e \"$d/remove\" ] && echo \"R:$id\"; [ -e \"$d/disable\" ] && echo \"D:$id\"; pv \"$d\"; echo \"V:$id|$code|$ver\"; done\n"
                + "for d in /data/adb/modules_update/*/; do [ -d \"$d\" ] || continue; d=${d%/}; id=${d##*/}; echo \"U:$id\"; pv \"$d\"; echo \"X:$id|$code|$ver\"; done\n"
                + "H=$(sha256sum /system/etc/mitv.config_product.xml 2>/dev/null | cut -d' ' -f1)\n"
                + "M=$(sha256sum /data/adb/modules/mitv_4kui_switch/system/etc/mitv.config_product.xml 2>/dev/null | cut -d' ' -f1)\n"
                + "[ -n \"$H\" ] && [ \"$H\" = \"$M\" ] && echo \"F:mitv_4kui_switch\"\n"
                + "grep -la \"[m]itv_adb_keeper/keeper.sh\" /proc/[0-9]*/cmdline >/dev/null 2>&1 && echo \"F:mitv_adb_keeper\"\n"
                + "service call TvService 82 s16 support_hdr_game_hgig 2>/dev/null | grep -q 'Y.E.S' && echo \"F:beekeeper_hgig_fix\"\n"
                + "L=$(sha256sum /vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/ldm.ini 2>/dev/null | cut -d' ' -f1)\n"
                + "[ \"$L\" = 282d2ad2991845cd79660bb13111cba1da82e8116f1519e2b395606b27eb6dcf ] && echo \"F:beekeeper_ldm_fix\"\n"
                + "E=$(sha256sum /vendor/tvconfig/bsp/common/EDID_BIN/GPro_HDMI1_FreeSync_ver6.0.bin 2>/dev/null | cut -d' ' -f1)\n"
                + "[ \"$E\" = 04d48a32abcb0fe7e8a25cac683c09b9f6dbd00dd81b8d0ab170b3d5534237fc ] && echo \"F:beekeeper_dv_edid\"\n"
                + "for z in $(pidof zygote64); do grep -qE 'zygisk_(lsposed|vector)' /proc/$z/maps 2>/dev/null && echo \"F:zygote64_fix\"; done\n"
                + "grep libmtkapp.so /proc/mounts | grep -q userdata && echo \"F:beekeeper_freesync_keep\"\n"
                + "wait\n"
                + "S=$(cat \"$CD/lsp_status\" 2>/dev/null); rc=$(cat \"$CD/lsp_rc\" 2>/dev/null); rm -f \"$CD/lsp_status\" \"$CD/lsp_rc\"\n"
                + "[ \"$rc\" = 0 ] && echo \"L:run\"\n"
                + "echo \"$S\" | grep -qE 'cannot connect|Socket file not found' && echo \"L:dead\"\n"
                // lspctl 直接报告；Vector 的 cli 不报告，改看系统进程里有没有加载 Zygisk 模块库
                + "echo \"$S\" | grep -q 'System server: connected' && echo \"L:server\"\n"
                + "for p in $(pidof system_server); do grep -qE 'zygisk_(lsposed|vector)' /proc/$p/maps 2>/dev/null && echo \"L:server\"; done\n"
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
            final boolean dbRead = ok && ls.readLsDb(lsEn, lsSc);
            final boolean fOk = ok;
            if (bundledReader != null) {
                try { bundledReader.join(); } catch (InterruptedException ignore) { }
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
                ls.lsKnown = fOk && (lsFlags[0] || lsFlags[1]);
                ls.lsRunning = lsFlags[0];
                ls.lsServer = lsFlags[2];
                ls.lsEnabled = dbRead ? lsEn : null;
                ls.lsScope = dbRead ? lsSc : null;
                render();
                modUp.updateBanner(fOk);
                hideLoading();
            }});
        }}, "slim-query").start();
    }

    String flagQuery() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : FLAG_MODS.entrySet()) {
            sb.append("service call TvService 82 s16 ").append(e.getValue())
                .append(" 2>/dev/null | grep -q 'Y.E.S' && echo \"F:").append(e.getKey()).append("\"\n");
        }
        return sb.toString();
    }

    void render() {
        java.util.LinkedHashMap<Group, String[]> ups = modUp.pendingUpdates();
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
                String av = aerial.aerialVersion();
                if (av != null && aerial.aerialInstalledOk()) t += "\n版本：" + av;
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
                if (inst) sb.append("\n").append(ls.lsActual(g.pkgs[0]));
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
        aerial.updateAerialUi();
    }

    void confirmReboot() {
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

    void showResult(String text) {
        showResult("执行结果", text);
    }

    void showResult(String title, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.setFocusable(true);
        sv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("确定", null)
            .show();
    }
}
