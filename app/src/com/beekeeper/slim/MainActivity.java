package com.beekeeper.slim;

import static com.beekeeper.slim.Catalog.*;
import static com.beekeeper.slim.Net.*;
import static com.beekeeper.slim.Shell.*;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;

/*
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
    new Thread(
            new Runnable() {
              public void run() {
                final String out = suOut("am start --user 0 " + h.args + " 2>&1\n");
                main.post(
                    new Runnable() {
                      public void run() {
                        log.setText(out == null ? "无法使用 root。\n" : out);
                      }
                    });
              }
            },
            "slim-hidden")
        .start();
  }

  void confirmHidden(final Hidden h) {
    if (h.warn == null) {
      launchHidden(h);
      return;
    }
    new Dlg.Builder(this)
        .setTitle(h.title)
        .setMessage(h.warn + "\n\n确定打开吗？")
        .setPositiveButton(
            "打开",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                launchHidden(h);
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  /** 有依赖的模块：被依赖项没勾选时锁定且不能勾选（已装的仍可取消）；它勾选期间，被依赖项也锁定。 */
  void updateDependents() {
    for (Group g : GROUPS) {
      if (g.requires == null) continue;
      for (Group r : GROUPS) {
        if (!g.requires.equals(r.modId)) continue;
        boolean unmet = !r.box.isChecked() && !modInstalled(g);
        g.box.setLocked(modPresent == null || unmet, unmet ? g.requiresMsg : null);
        if (unmet) g.box.setChecked(false);
        r.box.setLocked(
            modPresent == null || g.box.isChecked(), g.box.isChecked() ? "需先取消依赖它的模块" : null);
      }
    }
    for (Group g : GROUPS) {
      if (fwMismatch(g) && !modInstalled(g)) {
        g.box.setChecked(false);
        g.box.setLocked(true, "仅适用于 " + FIRMWARE + "，当前固件为 " + Build.VERSION.INCREMENTAL);
      }
    }
    updatePending();
  }

  /** 固件相关的模块在其它固件上：未安装的不能勾选，已安装的仍可取消。 */
  boolean fwMismatch(Group g) {
    return g.fwOnly && !FIRMWARE.equals(Build.VERSION.INCREMENTAL);
  }

  void confirmEnable(final Group g) {
    new Dlg.Builder(this)
        .setTitle(g.title)
        .setMessage(g.confirm + "\n\n确定开启吗？")
        .setPositiveButton(
            "开启",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                g.box.setChecked(true);
                updateDependents();
              }
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

  LinearLayout[] pages;

  NavItem[] navs;

  /** 当前显示的页；-1 = 还没选。 */
  int cur = -1;

  ScrollView scroll;

  Button applyBtn;

  /** 刷新是否正在跑；跑的时候又来请求就记下来，跑完再来一次。 */
  boolean refreshing, refreshAgain;

  /** 一页内容：大标题 + 一行说明，条目由调用方继续往里加。 */
  LinearLayout newPage(String title, String hintText) {
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(0, 0, 0, dp(16));
    TextView t = new TextView(this);
    t.setText(title);
    t.setTextSize(25);
    t.setTypeface(Typeface.DEFAULT_BOLD);
    t.setTextColor(Ui.TEXT);
    page.addView(t);
    if (hintText != null) {
      TextView h = new TextView(this);
      h.setText(hintText);
      h.setTextSize(12);
      h.setTextColor(Ui.SUB);
      h.setPadding(0, dp(4), 0, dp(4));
      page.addView(h);
    }
    return page;
  }

  void addItem(LinearLayout page, View item) {
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(6);
    page.addView(item, lp);
  }

  LinearLayout card() {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    c.setBackground(Ui.shape(this, Ui.CARD, Ui.LINE, 1, 10));
    c.setPadding(dp(16), dp(12), dp(16), dp(12));
    return c;
  }

  /** 顶部黄色更新提示条：第 0 个子视图是文字，右边是“查看更新”按钮。 */
  LinearLayout newBar(View.OnClickListener onClick) {
    LinearLayout bar = new LinearLayout(this);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(14), dp(3), dp(6), dp(3));
    bar.setBackground(Ui.shape(this, 0x26FFB300, 0x66FFB300, 1, 10));
    TextView text = new TextView(this);
    text.setTextSize(12);
    text.setTextColor(0xFFFFD54F);
    bar.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    Button btn = Ui.button(this, "查看更新", false);
    btn.setMinimumHeight(dp(32));
    btn.setPadding(dp(10), dp(1), dp(10), dp(1));
    btn.setOnClickListener(onClick);
    bar.addView(btn);
    bar.setVisibility(View.GONE);
    return bar;
  }

  /** 页面里第一个能聚焦的控件。 */
  static View firstFocusable(View v) {
    if (v.getVisibility() != View.VISIBLE) return null;
    if (v.isFocusable()) return v;
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) {
        View f = firstFocusable(g.getChildAt(i));
        if (f != null) return f;
      }
    }
    return null;
  }

  /** 页面里所有能聚焦的控件，按左键都回到该页对应的导航项，而不是就近乱跳。 */
  static void wireLeft(View v, int navId) {
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) wireLeft(g.getChildAt(i), navId);
    }
    if (v.isFocusable()) v.setNextFocusLeftId(navId);
  }

  void select(int i) {
    if (i == cur) return;
    cur = i;
    for (int j = 0; j < navs.length; j++) navs[j].select(j == i);
    scroll.removeAllViews();
    scroll.addView(pages[i]);
    scroll.scrollTo(0, 0);
  }

  void updatePending() {
    int n = 0;
    for (Group g : GROUPS) if (g.box.changed()) n++;
    applyBtn.setText(n > 0 ? "应用（" + n + "）" : "应用");
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
    getWindow().setBackgroundDrawable(new ColorDrawable(Ui.BG));
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.HORIZONTAL);

    // 左侧：标题、导航、操作按钮
    LinearLayout side = new LinearLayout(this);
    side.setOrientation(LinearLayout.VERTICAL);
    side.setBackgroundColor(Ui.PANEL);
    side.setPadding(dp(14), dp(22), dp(14), dp(18));
    root.addView(
        side, new LinearLayout.LayoutParams(dp(176), LinearLayout.LayoutParams.MATCH_PARENT));
    TextView title = new TextView(this);
    title.setText("系统优化");
    title.setTextSize(24);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setTextColor(Ui.TEXT);
    title.setPadding(dp(14), 0, 0, 0);
    side.addView(title);
    TextView ver = new TextView(this);
    try {
      ver.setText("v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
    } catch (Exception e) {
      // 读不到版本号就留空
    }
    ver.setTextSize(12);
    ver.setTextColor(Ui.SUB);
    ver.setPadding(dp(14), dp(2), 0, dp(16));
    side.addView(ver);

    String[] names = {"应用类", "优化类", "修复类", "隐藏项", "关于"};
    navs = new NavItem[names.length];
    for (int i = 0; i < names.length; i++) {
      final int idx = i;
      navs[i] = new NavItem(this, names[i]);
      navs[i].setOnFocusChangeListener(
          new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
              if (has) select(idx);
            }
          });
      navs[i].setOnClickListener(
          new View.OnClickListener() {
            public void onClick(View v) {
              select(idx);
              View f = firstFocusable(pages[idx]);
              if (f != null) f.requestFocus();
            }
          });
      LinearLayout.LayoutParams nlp =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      nlp.topMargin = dp(4);
      side.addView(navs[i], nlp);
    }
    self.aboutDot = navs[4].dot;
    side.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
    applyBtn = Ui.button(this, "应用", true);
    applyBtn.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            applier.confirmApply();
          }
        });
    side.addView(
        applyBtn,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    Button reboot = Ui.button(this, "重启电视", false);
    reboot.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            confirmReboot();
          }
        });
    LinearLayout.LayoutParams rlp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    rlp.topMargin = dp(6);
    side.addView(reboot, rlp);

    // 右侧：更新提示条 + 当前页
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(24), dp(16), dp(24), dp(4));
    root.addView(
        content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    updateBar =
        newBar(
            new View.OnClickListener() {
              public void onClick(View v) {
                modUp.confirmUpdate();
              }
            });
    updateText = (TextView) updateBar.getChildAt(0);
    aerial.aerialBar =
        newBar(
            new View.OnClickListener() {
              public void onClick(View v) {
                aerial.confirmAerialUpdate();
              }
            });
    aerial.aerialBarText = (TextView) aerial.aerialBar.getChildAt(0);
    for (View bar : new View[] {updateBar, aerial.aerialBar}) {
      LinearLayout.LayoutParams blp =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      blp.bottomMargin = dp(6);
      content.addView(bar, blp);
    }
    scroll = new ScrollView(this);
    scroll.setClipToPadding(false);
    content.addView(
        scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

    pages =
        new LinearLayout[] {
          newPage("应用类", "禁用后随时可以恢复。"),
          newPage("优化类", "LSPosed 模块安装后需在 LSPosed 中启用。改动重启后生效。"),
          newPage("修复类", "改动重启后生效。"),
          newPage("隐藏项", "以下是系统自带的页面，页面中的操作需注意。"),
          newPage("关于", null),
        };

    for (final Hidden h : HIDDEN) {
      ItemView item = new ItemView(this, true);
      item.setInfo(h.title, h.desc, h.warn, null, ItemView.NEUTRAL);
      item.setOnClickListener(
          new View.OnClickListener() {
            public void onClick(View v) {
              confirmHidden(h);
            }
          });
      addItem(pages[3], item);
    }
    log = new TextView(this);
    log.setTextSize(11);
    log.setTextColor(Ui.SUB);
    log.setPadding(dp(4), dp(10), 0, 0);
    pages[3].addView(log);

    for (final Group g : GROUPS) {
      ItemView item = new ItemView(this, false);
      item.setListener(
          new ItemView.Listener() {
            public void onChange(boolean on) {
              if (on && g.confirm != null) {
                g.box.setChecked(false);
                confirmEnable(g);
                return;
              }
              if (g.kind == KIND_DB) updateNoHomeBox();
              updateDependents();
            }
          });
      g.box = item;
      addItem(pages[g.kind == KIND_PKGS || g.kind == KIND_NOHOME ? 0 : g.fix ? 2 : 1], item);
    }

    LinearLayout about = card();
    TextView aboutText = new TextView(this);
    String aboutHead = "小米电视团队是干嘛的？";
    android.text.SpannableString aboutSpan =
        new android.text.SpannableString(
            aboutHead
                + "\n项目作者：酷安@pkkka\n项目地址：github.com/"
                + GH_REPO
                + "\n有任何想法或建议，欢迎在项目地址的 Issue / Pull Request 中提交"
                + "\n贡献者：@Rin010、酷安@伏犬、@Filey-Aaron");
    aboutSpan.setSpan(
        new android.text.style.RelativeSizeSpan(1.35f),
        0,
        aboutHead.length(),
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    aboutText.setText(aboutSpan);
    aboutText.setTextSize(13);
    aboutText.setTextColor(Ui.TEXT);
    aboutText.setLineSpacing(0, 1.2f);
    about.addView(aboutText);
    addItem(pages[4], about);

    LinearLayout verCard = card();
    verCard.setOrientation(LinearLayout.HORIZONTAL);
    verCard.setGravity(Gravity.CENTER_VERTICAL);
    self.selfStatus = new TextView(this);
    self.selfStatus.setTextSize(13);
    self.selfStatus.setTextColor(Ui.SUB);
    verCard.addView(
        self.selfStatus,
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    self.checkBtn = Ui.button(this, "检查更新", false);
    self.checkBtn.setId(View.generateViewId());
    self.checkBtn.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            self.onCheckButton();
          }
        });
    FrameLayout btnWrap = new FrameLayout(this);
    btnWrap.addView(
        self.checkBtn,
        new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
    self.btnDot = Ui.dot(this);
    btnWrap.setPadding(0, dp(6), dp(6), 0);
    btnWrap.setClipChildren(false);
    btnWrap.setClipToPadding(false);
    btnWrap.addView(
        self.btnDot, new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP | Gravity.END));
    verCard.addView(btnWrap);
    addItem(pages[4], verCard);

    for (int i = 0; i < pages.length; i++) {
      View first = firstFocusable(pages[i]);
      if (first != null) navs[i].setNextFocusRightId(first.getId());
      wireLeft(pages[i], navs[i].getId());
    }

    setContentView(root);
    select(0);
    navs[0].requestFocus();
    showLoading();
    refresh();
    self.checkSelfUpdate(false);
    aerial.checkAerialUpdate();
  }

  Dlg loadingDlg;

  /** 首次读取模块状态要走 root，可能要几秒（还会弹 Magisk 授权），读完前挡一个加载提示。 */
  void showLoading() {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(8), 0, dp(8));
    android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
    pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    row.addView(pb, new LinearLayout.LayoutParams(dp(40), dp(40)));
    TextView t = new TextView(this);
    t.setText("正在加载模块状态中……");
    t.setTextSize(14);
    t.setTextColor(Ui.TEXT);
    t.setPadding(dp(16), 0, 0, 0);
    row.addView(t);
    loadingDlg = new Dlg.Builder(this).setView(row).create();
    loadingDlg.show();
  }

  void hideLoading() {
    if (loadingDlg == null) return;
    try {
      loadingDlg.dismiss();
    } catch (Exception e) {
      /* 窗口已不在 */
    }
    loadingDlg = null;
  }

  int dp(int v) {
    return (int) (v * getResources().getDisplayMetrics().density);
  }

  int state(String pkg) {
    PackageManager pm = getPackageManager();
    int s;
    try {
      s = pm.getApplicationEnabledSetting(pkg);
    } catch (Exception e) {
      return MISSING;
    }
    boolean off =
        s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
    if (!off) {
      // 有些版本上被禁用的应用查不到 ApplicationInfo，这时只按启用设置判断。
      try {
        off = !pm.getApplicationInfo(pkg, 0).enabled;
      } catch (Exception e) {
        /* 按设置值 */
      }
    }
    return off ? DISABLED : ENABLED;
  }

  Group groupOfKind(int kind) {
    for (Group g : GROUPS) if (g.kind == kind) return g;
    throw new IllegalStateException();
  }

  String homePackage() {
    try {
      android.content.Intent i =
          new android.content.Intent(android.content.Intent.ACTION_MAIN)
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
  void updateNoHomeBox() {
    Group nh = groupOfKind(KIND_NOHOME);
    boolean alreadyOff = state(STOCK_HOME_PKG) == DISABLED;
    boolean allowed = noHomeReady() && groupOfKind(KIND_DB).box.isChecked();
    nh.box.setLocked(!allowed && !alreadyOff);
    if (!allowed && !alreadyOff) nh.box.setChecked(false);
    updatePending();
  }

  boolean modInstalled(Group g) {
    return modPresent != null
        && modPresent.contains(g.modId)
        && !(modRemoving != null && modRemoving.contains(g.modId));
  }

  void refresh() {
    render();
    // 查询线程共用缓存目录里的几个临时文件，同一时间只跑一个。
    if (refreshing) {
      refreshAgain = true;
      return;
    }
    refreshing = true;
    new Thread(
            new Runnable() {
              public void run() {
                // 读内置模块的版本（解压、解析安装包）和 su 脚本并行，各自耗时不叠加。
                final java.util.Map<String, String[]>
                    bm = new java.util.HashMap<String, String[]>(),
                    ba = new java.util.HashMap<String, String[]>();
                Thread bundledReader = null;
                if (bundledMod.isEmpty() && bundledApk.isEmpty()) {
                  bundledReader =
                      new Thread(
                          new Runnable() {
                            public void run() {
                              for (Group g : GROUPS) {
                                String[] v = null;
                                if (g.kind == KIND_MOD) {
                                  v = modUp.readBundledMod(g.asset);
                                  if (v != null) bm.put(g.modId, v);
                                } else if (g.kind == KIND_APK) {
                                  v = modUp.readBundledApk(g.asset);
                                  if (v != null) ba.put(g.pkgs[0], v);
                                }
                              }
                            }
                          },
                          "slim-bundled");
                  bundledReader.start();
                }
                String out =
                    suOut(
                        "CD="
                            + getCacheDir().getAbsolutePath()
                            + "\n"
                            // LSPosed 和 Vector 的模块目录不同（zygisk_lsposed / zygisk_vector）；前者用
                            // lspctl，后者用 cli。
                            // 状态命令最慢（约 2 秒），放后台和其余检测并行
                            + "LM=/data/adb/modules/zygisk_lsposed; [ -d"
                            + " /data/adb/modules/zygisk_vector ] &&"
                            + " LM=/data/adb/modules/zygisk_vector\n"
                            + "if [ -f \"$LM/lspctl\" ]; then CTL=lspctl; else CTL=cli; fi\n"
                            + "( timeout 15 sh \"$LM/$CTL\" status > \"$CD/lsp_status\" 2>&1; echo"
                            + " $? > \"$CD/lsp_rc\" ) < /dev/null &\n"
                            // 只用 shell 内建命令读 module.prop，避免每个模块起多个子进程
                            + "pv() { code=; ver=; [ -r \"$1/module.prop\" ] || return; while IFS="
                            + " read -r l || [ -n \"$l\" ]; do case $l in versionCode=*) [ -z"
                            + " \"$code\" ] && code=${l#versionCode=};; version=*) [ -z \"$ver\" ]"
                            + " && ver=${l#version=};; esac; done < \"$1/module.prop\"; }\n"
                            + "for d in /data/adb/modules/*/; do [ -d \"$d\" ] || continue;"
                            + " d=${d%/}; id=${d##*/}; echo \"M:$id\"; [ -e \"$d/remove\" ] && echo"
                            + " \"R:$id\"; [ -e \"$d/disable\" ] && echo \"D:$id\"; pv \"$d\"; echo"
                            + " \"V:$id|$code|$ver\"; done\n"
                            + "for d in /data/adb/modules_update/*/; do [ -d \"$d\" ] || continue;"
                            + " d=${d%/}; id=${d##*/}; echo \"U:$id\"; pv \"$d\"; echo"
                            + " \"X:$id|$code|$ver\"; done\n"
                            + "H=$(sha256sum /system/etc/mitv.config_product.xml 2>/dev/null | cut"
                            + " -d' ' -f1)\n"
                            + "M=$(sha256sum"
                            + " /data/adb/modules/mitv_4kui_switch/system/etc/mitv.config_product.xml"
                            + " 2>/dev/null | cut -d' ' -f1)\n"
                            + "[ -n \"$H\" ] && [ \"$H\" = \"$M\" ] && echo"
                            + " \"F:mitv_4kui_switch\"\n"
                            + "grep -la \"[m]itv_adb_keeper/keeper.sh\" /proc/[0-9]*/cmdline"
                            + " >/dev/null 2>&1 && echo \"F:mitv_adb_keeper\"\n"
                            + "service call TvService 82 s16 support_hdr_game_hgig 2>/dev/null |"
                            + " grep -q 'Y.E.S' && echo \"F:beekeeper_hgig_fix\"\n"
                            + "L=$(sha256sum"
                            + " /vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/ldm.ini"
                            + " 2>/dev/null | cut -d' ' -f1)\n"
                            + "[ \"$L\" ="
                            + " 282d2ad2991845cd79660bb13111cba1da82e8116f1519e2b395606b27eb6dcf ]"
                            + " && echo \"F:beekeeper_ldm_fix\"\n"
                            + "E=$(sha256sum"
                            + " /vendor/tvconfig/bsp/common/EDID_BIN/GPro_HDMI1_FreeSync_ver6.0.bin"
                            + " 2>/dev/null | cut -d' ' -f1)\n"
                            + "[ \"$E\" ="
                            + " 04d48a32abcb0fe7e8a25cac683c09b9f6dbd00dd81b8d0ab170b3d5534237fc ]"
                            + " && echo \"F:beekeeper_dv_edid\"\n"
                            // DP 的 Enhanced EDID 已是带音频格式的版本（仅音频 / 音频+LLDV）
                            + "case $(sha256sum"
                            + " /vendor/tvconfig/bsp/common/EDID_BIN/GPro_DP_Enhanced_ver6.0.bin"
                            + " 2>/dev/null | cut -d' ' -f1) in"
                            + " 29adce40ed1a7a733d3f9799c55451b00903c5eb806ab2008d324dc94e389903|e48a381124833ee6173d83ca82b948edc3918a31795a250c09f9a395233f67fc)"
                            + " echo \"F:beekeeper_dp_audio\";; esac\n"
                            + "for z in $(pidof zygote64); do grep -qE 'zygisk_(lsposed|vector)'"
                            + " /proc/$z/maps 2>/dev/null && echo \"F:zygote64_fix\"; done\n"
                            // 只有本模块会往这个路径 bind mount 补丁库
                            + "grep -q libmtkapp.so /proc/mounts && echo"
                            + " \"F:beekeeper_freesync_keep\"\n"
                            + "[ \"$(getprop sys.beekeeper_rgb.native)\" = 1 ] && echo"
                            + " \"F:beekeeper_decorative_light\"\n"
                            + "wait\n"
                            + "S=$(cat \"$CD/lsp_status\" 2>/dev/null); rc=$(cat \"$CD/lsp_rc\""
                            + " 2>/dev/null); rm -f \"$CD/lsp_status\" \"$CD/lsp_rc\"\n"
                            + "[ \"$rc\" = 0 ] && echo \"L:run\"\n"
                            + "echo \"$S\" | grep -qE 'cannot connect|Socket file not found' &&"
                            + " echo \"L:dead\"\n"
                            // lspctl 直接报告；Vector 的 cli 不报告，改看系统进程里有没有加载 Zygisk 模块库
                            + "echo \"$S\" | grep -q 'System server: connected' && echo"
                            + " \"L:server\"\n"
                            + "for p in $(pidof system_server); do grep -qE"
                            + " 'zygisk_(lsposed|vector)' /proc/$p/maps 2>/dev/null && echo"
                            + " \"L:server\"; done\n"
                            + "for f in modules_config.db modules_config.db-wal; do rm -f"
                            + " \"$CD/lsp_$f\"; cp \"/data/adb/lspd/config/$f\" \"$CD/lsp_$f\""
                            + " 2>/dev/null && chmod 666 \"$CD/lsp_$f\" && chown $(stat -c %u:%g"
                            + " \"$CD\") \"$CD/lsp_$f\"; done\n"
                            + flagQuery()
                            + "echo __Q_OK__\n");
                final Set<String> present = new HashSet<String>(),
                    removing = new HashSet<String>(),
                    pending = new HashSet<String>(),
                    disabled = new HashSet<String>(),
                    active = new HashSet<String>();
                boolean lsR = false, lsDead = false, lsS = false;
                boolean ok = out != null && out.contains("__Q_OK__");
                final java.util.Map<String, String[]>
                    vInst = new java.util.HashMap<String, String[]>(),
                    vUp = new java.util.HashMap<String, String[]>();
                if (ok) {
                  for (String line : out.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("V:") || line.startsWith("X:")) {
                      String[] p = line.substring(2).split("\\|", 3);
                      if (p.length == 3 && p[1].length() > 0) {
                        (line.startsWith("V:") ? vInst : vUp)
                            .put(p[0], new String[] {p[1], p[2].length() > 0 ? p[2] : p[1]});
                      }
                    } else if (line.startsWith("M:")) present.add(line.substring(2));
                    else if (line.startsWith("R:")) removing.add(line.substring(2));
                    else if (line.startsWith("U:")) {
                      present.add(line.substring(2));
                      pending.add(line.substring(2));
                    } else if (line.startsWith("D:")) disabled.add(line.substring(2));
                    else if (line.startsWith("F:")) active.add(line.substring(2));
                    else if (line.equals("L:run")) lsR = true;
                    else if (line.equals("L:dead")) lsDead = true;
                    else if (line.equals("L:server")) lsS = true;
                  }
                }
                final boolean lsRun = lsR, lsSrv = lsS, lsKnown = ok && (lsR || lsDead);
                final java.util.Map<String, Boolean> lsEn =
                    new java.util.HashMap<String, Boolean>();
                final java.util.Map<String, Set<String>> lsSc =
                    new java.util.HashMap<String, Set<String>>();
                final boolean dbRead = ok && ls.readLsDb(lsEn, lsSc);
                if (bundledReader != null) {
                  try {
                    bundledReader.join();
                  } catch (InterruptedException ignore) {
                  }
                }
                main.post(
                    new Runnable() {
                      public void run() {
                        if (!bm.isEmpty() || !ba.isEmpty()) {
                          bundledMod = bm;
                          bundledApk = ba;
                        }
                        modVer = vInst;
                        modUpVer = vUp;
                        modPresent = ok ? present : null;
                        modRemoving = ok ? removing : null;
                        modPending = ok ? pending : null;
                        modDisabled = disabled;
                        modActive = active;
                        ls.lsKnown = lsKnown;
                        ls.lsRunning = lsRun;
                        ls.lsServer = lsSrv;
                        ls.lsEnabled = dbRead ? lsEn : null;
                        ls.lsScope = dbRead ? lsSc : null;
                        render();
                        modUp.updateBanner();
                        hideLoading();
                        refreshing = false;
                        if (refreshAgain) {
                          refreshAgain = false;
                          refresh();
                        }
                      }
                    });
              }
            },
            "slim-query")
        .start();
  }

  String flagQuery() {
    StringBuilder sb = new StringBuilder();
    for (java.util.Map.Entry<String, String> e : FLAG_MODS.entrySet()) {
      sb.append("service call TvService 82 s16 ")
          .append(e.getValue())
          .append(" 2>/dev/null | grep -q 'Y.E.S' && echo \"F:")
          .append(e.getKey())
          .append("\"\n");
    }
    return sb.toString();
  }

  void render() {
    java.util.LinkedHashMap<Group, String[]> ups = modUp.pendingUpdates();
    for (Group g : GROUPS) {
      String t;
      int lv = ItemView.NEUTRAL;
      if (g.kind == KIND_DB) {
        int st = dbState();
        t =
            st == DB_ACTIVE
                ? "当前：已是默认桌面"
                : st == DB_SYSTEM
                    ? "当前：已安装，尚未设为默认桌面（请再次点击“应用”）"
                    : st == DB_PENDING
                        ? "当前：已安装，重启电视后生效"
                        : st == DB_REMOVING
                            ? "当前：已移除，重启电视后 Aerial 消失"
                            : st == DB_USERAPP ? "当前：已装成普通应用，不能设为默认桌面（勾选并应用后可改正）" : "当前：未安装";
        lv = st == DB_ACTIVE ? ItemView.GOOD : st == DB_NONE ? ItemView.NEUTRAL : ItemView.WARN;
        String av = aerial.aerialVersion();
        if (av != null && aerial.aerialInstalledOk()) t += "\n版本：" + av;
        g.box.setChecked(st == DB_ACTIVE || st == DB_SYSTEM || st == DB_PENDING);
      } else if (g.kind == KIND_NOHOME) {
        boolean off = state(STOCK_HOME_PKG) == DISABLED;
        t = off ? "当前：已禁用" : "当前：启用中";
        lv = off ? ItemView.GOOD : ItemView.NEUTRAL;
        if (!noHomeReady() && !off) {
          t += "（需要先安装 Aerial 桌面并设为默认桌面，且安装 HomeRef）";
          lv = ItemView.WARN;
        }
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
        t = inst ? "当前：已安装" + apkVer : "当前：未安装";
        if (ups.containsKey(g)) t += "\n⬆ 有新版本：" + ups.get(g)[0] + " → " + ups.get(g)[1];
        if (inst) {
          String act = ls.lsActual(g.pkgs[0]);
          t += "\n" + act;
          lv = act.startsWith("实际：已在") && !act.contains("缺") ? ItemView.GOOD : ItemView.WARN;
        }
        g.box.setChecked(inst);
      } else if (g.kind == KIND_MOD) {
        if (modPresent == null) {
          t = "当前：正在读取状态（需要 root 授权）";
        } else if (modPresent.contains(g.modId) && modRemoving.contains(g.modId)) {
          t = "当前：已关闭，重启后生效（再勾选可取消）";
          lv = ItemView.WARN;
        } else if (modPresent.contains(g.modId)) {
          boolean pend = modPending != null && modPending.contains(g.modId);
          String[] iv = modVer.get(g.modId);
          boolean off = modDisabled.contains(g.modId), on = modActive.contains(g.modId);
          String act = off ? "实际：在 Magisk 中被停用，未生效" : on ? "实际：已生效" : "实际：未生效（需重启）";
          t = pend ? "当前：已安装，重启后生效" : "当前：已安装" + (iv == null ? "" : " " + iv[1]) + "\n" + act;
          lv = pend || off || !on ? ItemView.WARN : ItemView.GOOD;
          if (ups.containsKey(g)) t += "\n⬆ 有新版本：" + ups.get(g)[0] + " → " + ups.get(g)[1];
        } else {
          t = "当前：未安装";
        }
        if (fwMismatch(g)) {
          t += "\n固件不匹配：仅适用于 " + FIRMWARE + "，当前为 " + Build.VERSION.INCREMENTAL;
          lv = ItemView.WARN;
        }
        g.box.setChecked(modInstalled(g));
        g.box.setLocked(modPresent == null);
        // 带配套 APK 的 Magisk 模块同时显示 LSPosed 启用及作用域状态。
        if (modInstalled(g) && g.pkgs.length > 0) {
          String companion =
              state(g.pkgs[0]) == MISSING
                  ? "实际：配套 APK 未安装，请重新安装模块"
                  : state(g.pkgs[0]) == DISABLED ? "实际：配套 APK 已停用" : ls.lsActual(g.pkgs[0]);
          t += "\n" + companion;
          if (!companion.startsWith("实际：已在") || companion.contains("缺")) lv = ItemView.WARN;
        }
      } else {
        int dis = 0, en = 0, miss = 0;
        for (String p : g.pkgs) {
          int s = state(p);
          if (s == DISABLED) dis++;
          else if (s == ENABLED) en++;
          else miss++;
        }
        t = dis == 0 ? "当前：启用中" : (en == 0 ? "当前：已禁用" : "当前：部分已禁用");
        if (miss > 0 && dis == 0 && en == 0) t = "当前：本机没有这些应用";
        lv = dis > 0 && en == 0 ? ItemView.GOOD : dis > 0 ? ItemView.WARN : ItemView.NEUTRAL;
        g.shown = dis > 0 && en == 0;
        g.box.setChecked(g.shown);
      }
      g.box.setInfo(g.title, g.desc, g.warn, t, lv);
      g.box.markBase();
    }
    updateNoHomeBox();
    updateDependents();
    aerial.updateAerialUi();
  }

  void confirmReboot() {
    new Dlg.Builder(this)
        .setTitle("重启电视")
        .setMessage("确定要现在重启电视吗？")
        .setPositiveButton(
            "重启",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                new Thread(
                        new Runnable() {
                          public void run() {
                            final String out = suOut("reboot\n");
                            if (out == null)
                              main.post(
                                  new Runnable() {
                                    public void run() {
                                      showResult("无法使用 root，没有重启。");
                                    }
                                  });
                          }
                        },
                        "slim-reboot")
                    .start();
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  void showResult(String text) {
    showResult("执行结果", text);
  }

  void showResult(String title, String text) {
    if (isFinishing()) return;
    new Dlg.Builder(this)
        .setTitle(title)
        .setView(scrollText(text))
        .setPositiveButton("确定", null)
        .show();
  }

  ScrollView scrollText(String text) {
    TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextSize(13);
    tv.setTextColor(Ui.TEXT);
    tv.setLineSpacing(0, 1.2f);
    ScrollView sv = new ScrollView(this);
    sv.setFocusable(true);
    sv.addView(tv);
    return sv;
  }
}
