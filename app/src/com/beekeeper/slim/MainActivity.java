package com.beekeeper.slim;

import static com.beekeeper.slim.Catalog.*;
import static com.beekeeper.slim.Net.*;
import static com.beekeeper.slim.Shell.*;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

  final Handler main = new Handler(Looper.getMainLooper());
  final GroupStatus groupStatus = new GroupStatus(this);
  final ModuleQuery moduleQuery = new ModuleQuery(this);
  final SelfUpdater self = new SelfUpdater(this);
  final AerialUpdater aerial = new AerialUpdater(this);
  final ModuleUpdater modUp = new ModuleUpdater(this);
  BaseMods baseMods;
  final LsStatus ls = new LsStatus(this);
  final ApplyRunner applier = new ApplyRunner(this);
  final Migration migration = new Migration(this);
  LocalDimming ldm;
  VoiceKey voiceKey;
  Group voiceKeyGroup, micGroup;
  MicPairs micPairs;
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

  void updateDependents() {
    groupStatus.updateDependents();
  }

  boolean fwMismatch(Group g) {
    return groupStatus.fwMismatch(g);
  }

  void confirmEnable(final Group g) {
    groupStatus.confirmEnable(g);
  }

  void confirmDisable(final Group g) {
    groupStatus.confirmDisable(g);
  }

  TextView log;

  /** Magisk 模块状态；null = 还没读到或没有 root。 */
  Set<String> modPresent, modRemoving, modPending;

  /** Magisk 里被停用的模块 / 实际检测到已生效的模块。 */
  Set<String> modDisabled = new HashSet<String>(), modActive = new HashSet<String>();

  /** 版本信息：模块 id -> {versionCode, version}。modVer 是已装的，modUpVer 是已暂存、重启后生效的；bundled* 是本应用自带的。 */
  Map<String, String[]> modVer = new HashMap<String, String[]>(),
      modUpVer = new HashMap<String, String[]>(),
      bundledMod = new HashMap<String, String[]>(),
      bundledApk = new HashMap<String, String[]>();

  LinearLayout updateBar;

  TextView updateText;

  LinearLayout[] pages;

  /** 页面序号，同时是 pages / navs 的下标。 */
  static final int P_BASE = 0,
      P_APPS = 1,
      P_OPT = 2,
      P_FUNC = 3,
      P_FIX = 4,
      P_HIDDEN = 5,
      P_ABOUT = 6;

  NavItem[] navs;

  /** -1 = 还没选。 */
  int cur = -1;

  ScrollView scroll;

  Button applyBtn;

  /** emphasis 是说明开头用醒目颜色显示的部分。 */
  LinearLayout newPage(String title, String emphasis, String hintText) {
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
      SpannableString s = new SpannableString(emphasis == null ? hintText : emphasis + hintText);
      if (emphasis != null) {
        s.setSpan(
            new ForegroundColorSpan(Ui.WARN),
            0,
            emphasis.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      h.setText(s);
      h.setTextSize(12);
      h.setTextColor(Ui.SUB);
      h.setPadding(0, dp(4), 0, dp(4));
      page.addView(h);
    }
    return page;
  }

  void addItem(LinearLayout page, View item) {
    LinearLayout.LayoutParams lp = Ui.lp();
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

  /** select 正在跑；removeAllViews 移除带焦点的页面时焦点会迁到导航项，经 onFocusChange 重进 select，那次重入直接丢弃。 */
  private boolean selecting;

  void select(int i) {
    if (selecting || i == cur) return;
    selecting = true;
    cur = i;
    for (int j = 0; j < navs.length; j++) navs[j].select(j == i);
    scroll.removeAllViews();
    scroll.addView(pages[i]);
    scroll.scrollTo(0, 0);
    selecting = false;
  }

  void updatePending() {
    int n = 0;
    for (Group g : GROUPS) if (g.box.changed() || applier.needsRepair(g)) n++;
    applyBtn.setText("应用（" + n + "）");
    applyBtn.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
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
    }
    ver.setTextSize(12);
    ver.setTextColor(Ui.SUB);
    ver.setPadding(dp(14), dp(2), 0, dp(16));
    side.addView(ver);

    String[] names = {"基础类", "应用类", "优化类", "功能类", "修复类", "隐藏项", "关于"};
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
      LinearLayout.LayoutParams nlp = Ui.lp();
      nlp.topMargin = dp(4);
      side.addView(navs[i], nlp);
    }
    self.aboutDot = navs[P_ABOUT].dot;
    side.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
    applyBtn = Ui.button(this, "应用", true);
    applyBtn.setVisibility(View.GONE);
    applyBtn.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            applier.confirmApply();
          }
        });
    side.addView(applyBtn, Ui.lp());
    Button reboot = Ui.button(this, "重启电视", false);
    reboot.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            confirmReboot();
          }
        });
    LinearLayout.LayoutParams rlp = Ui.lp();
    rlp.topMargin = dp(6);
    side.addView(reboot, rlp);

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
    baseMods = new BaseMods(this);
    baseMods.bar =
        newBar(
            new View.OnClickListener() {
              public void onClick(View v) {
                baseMods.confirmUpdate();
              }
            });
    baseMods.barText = (TextView) baseMods.bar.getChildAt(0);
    for (View bar : new View[] {updateBar, aerial.aerialBar, baseMods.bar}) {
      LinearLayout.LayoutParams blp = Ui.lp();
      blp.bottomMargin = dp(6);
      content.addView(bar, blp);
    }
    scroll = new ScrollView(this);
    scroll.setClipToPadding(false);
    content.addView(
        scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

    pages =
        new LinearLayout[] {
          newPage("基础类", null, "其它模块依赖的基础组件。"),
          newPage("应用类", null, "禁用后随时可以恢复。"),
          newPage("优化类", "Xposed 模块安装时会自动启用并勾选作用域。", ""),
          newPage("功能类", null, "为电视增加的新功能。"),
          newPage("修复类", null, null),
          newPage("隐藏项", null, "以下是系统自带的页面，页面中的操作需注意。"),
          newPage("关于", null, null),
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
      addItem(pages[P_HIDDEN], item);
    }
    log = new TextView(this);
    log.setTextSize(11);
    log.setTextColor(Ui.SUB);
    log.setPadding(dp(4), dp(10), 0, 0);
    pages[P_HIDDEN].addView(log);

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
              if (!on && g.confirmOff != null) {
                g.box.setChecked(true);
                confirmDisable(g);
                return;
              }
              if (g.kind == KIND_DB) updateNoHomeBox();
              updateDependents();
            }
          });
      g.box = item;
      // 语音键自定义的开关放在它的动作卡片上方，不跟其它模块排在一起
      if (g.pkgs.length > 0 && VoiceKey.MODULE.equals(g.pkgs[0])) {
        voiceKeyGroup = g;
        continue;
      }
      if (g.pkgs.length > 0 && MicPairs.MODULE.equals(g.pkgs[0])) {
        micGroup = g;
        continue;
      }
      addItem(
          pages[
              g.base
                  ? P_BASE
                  : g.kind == KIND_PKGS || g.kind == KIND_NOHOME ? P_APPS : g.fix ? P_FIX : P_OPT],
          item);
    }
    ldm = new LocalDimming(this);
    addItem(pages[P_OPT], ldm.sw);
    addItem(pages[P_OPT], ldm.item);
    voiceKey = new VoiceKey(this);
    addItem(pages[P_FUNC], voiceKeyGroup.box);
    addItem(pages[P_FUNC], voiceKey.item);
    micPairs = new MicPairs(this);
    addItem(pages[P_FUNC], micGroup.box);
    addItem(pages[P_FUNC], micPairs.item);

    LinearLayout about = card();
    TextView aboutText = new TextView(this);
    String aboutHead = "小米电视团队是干嘛的？";
    SpannableString aboutSpan =
        new SpannableString(
            aboutHead
                + "\n项目作者：酷安@pkkka\n项目地址：github.com/"
                + GH_REPO
                + "\n有任何想法或建议，欢迎在项目地址的 Issue / Pull Request 中提交"
                + "\n贡献者：@Rin010、酷安@伏犬、@Filey-Aaron");
    aboutSpan.setSpan(
        new RelativeSizeSpan(1.35f), 0, aboutHead.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    aboutText.setText(aboutSpan);
    aboutText.setTextSize(13);
    aboutText.setTextColor(Ui.TEXT);
    aboutText.setLineSpacing(0, 1.2f);
    about.addView(aboutText);
    addItem(pages[P_ABOUT], about);

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
    FrameLayout.LayoutParams dotLp =
        new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP | Gravity.END);
    dotLp.topMargin = -dp(6);
    dotLp.rightMargin = -dp(6);
    btnWrap.addView(self.btnDot, dotLp);
    verCard.addView(btnWrap);
    addItem(pages[P_ABOUT], verCard);

    final Diag diag = new Diag(this);
    LinearLayout diagCard = card();
    diagCard.setOrientation(LinearLayout.HORIZONTAL);
    diagCard.setGravity(Gravity.CENTER_VERTICAL);
    TextView diagText = new TextView(this);
    diagText.setText("遇到问题时，一键上传诊断信息给开发者。");
    diagText.setTextSize(13);
    diagText.setTextColor(Ui.SUB);
    diagCard.addView(
        diagText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    Button diagBtn = Ui.button(this, "上传诊断", false);
    diagBtn.setId(View.generateViewId());
    diagBtn.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            diag.start();
          }
        });
    // 与“检查更新”按钮同样的外边距，两个按钮右边缘才对得齐
    FrameLayout diagWrap = new FrameLayout(this);
    diagWrap.setPadding(0, dp(6), dp(6), 0);
    diagWrap.addView(diagBtn);
    diagCard.addView(diagWrap);
    addItem(pages[P_ABOUT], diagCard);

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
    ProgressBar pb = new ProgressBar(this);
    pb.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
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
    return groupStatus.packageState(pkg);
  }

  Group groupOfKind(int kind) {
    for (Group g : GROUPS) if (g.kind == kind) return g;
    throw new IllegalStateException();
  }

  String homePackage() {
    return groupStatus.homePackage();
  }

  int dbState() {
    return groupStatus.dbState();
  }

  boolean noHomeReady() {
    return groupStatus.noHomeReady();
  }

  void updateNoHomeBox() {
    groupStatus.updateNoHomeBox();
  }

  boolean modInstalled(Group g) {
    return groupStatus.modInstalled(g);
  }

  void refresh() {
    render();
    ldm.load();
    voiceKey.load();
    micPairs.load();
    moduleQuery.refresh();
  }

  void render() {
    groupStatus.render();
  }

  void confirmReboot() {
    new Dlg.Builder(this)
        .setTitle("重启电视")
        .setMessage("确定要现在重启电视吗？")
        .setPositiveButton("重启", rebootClick)
        .setNegativeButton("取消", null)
        .show();
  }

  void showResultReboot(String summary) {
    if (isFinishing()) return;
    new Dlg.Builder(this)
        .setTitle("应用完成")
        .setMessage(Ui.tagged(summary + "\n\n需要重启电视，改动才会生效。是否现在重启？", GROUPS))
        .setPositiveButton("重启", rebootClick)
        .setNegativeButton("稍后", null)
        .show();
  }

  /** 迁移完成：先给用户看结果，几秒后自动重启。 */
  void showResultAutoReboot(String summary) {
    if (isFinishing()) return;
    new Dlg.Builder(this)
        .setTitle("迁移完成")
        .setMessage(Ui.tagged(summary + "\n\n电视即将重启……", GROUPS))
        .setCancelable(false)
        .show();
    main.postDelayed(
        new Runnable() {
          public void run() {
            rebootNow();
          }
        },
        4000);
  }

  void rebootNow() {
    new Thread(
            new Runnable() {
              public void run() {
                final Shell.Result result = suResult("reboot\n", null);
                if (result == null || result.rc != 0)
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

  private final DialogInterface.OnClickListener rebootClick =
      new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface d, int w) {
          rebootNow();
        }
      };

  void showResult(String text) {
    showResult("执行结果", text);
  }

  void showResult(String title, String text) {
    if (isFinishing()) return;
    new Dlg.Builder(this)
        .setTitle(title)
        .setView(scrollText(Ui.tagged(text, GROUPS)))
        .setPositiveButton("确定", null)
        .show();
  }

  ScrollView scrollText(CharSequence text) {
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
