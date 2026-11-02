package com.beekeeper.slim;

import static com.beekeeper.slim.Catalog.*;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 遥控器语音键自定义（配合 VoiceKeyMap 模块）：开关打开后出现一张设置卡片，点进去在弹窗里分别选单击 / 双击 / 长按的动作。
 *
 * <p>配置写在 Settings.Global voicekeymap，如 click=sleep,long=source,double=app:com.pkg，模块在
 * system_server 里读取。 语音助手没有禁用时，小爱由蓝牙服务直接拉起，模块拦不住语音键，所以此时开关锁定。
 */
final class VoiceKey extends Part {

  private static final String KEY = "voicekeymap";
  private static final String MODULE = "com.beekeeper.voicekeymap";
  static final String[] ASSISTANT = {"com.xiaomi.voicecontrol", "com.xiaomi.wakeupservice"};
  private static final String[] KEYS = {"click", "double", "long"};
  private static final String[] NAMES = {"单击", "双击", "长按"};

  /** 可直接切换的信号源：播放器 input 序号 -> 名称。 */
  private static final String[] SRC_IDS = {"23", "24", "29"};

  private static final String[] SRC_NAMES = {"HDMI 1", "HDMI 2", "DP"};

  private interface Pick {
    void on(int index);
  }

  /** 总开关和动作设置卡片（开关打开才显示）。 */
  final ItemView sw, item;

  /** 各手势当前的动作，空串 = 无动作。 */
  private final String[] act = {"", "", ""};

  /** 开关状态单独记：三个动作都选“无动作”时开关和卡片也要留着，否则焦点所在的卡片消失，界面会跳回第一页。 */
  boolean on;

  private final Back back = new Back();

  VoiceKey(MainActivity a) {
    super(a);
    sw = new ItemView(a, false);
    sw.setListener(
        new ItemView.Listener() {
          public void onChange(boolean checked) {
            on = checked;
            for (int i = 0; i < 3; i++) act[i] = "";
            if (checked) act[0] = "sleep";
            save();
            update();
          }
        });
    item = new ItemView(a, true);
    item.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            showMain();
          }
        });
  }

  /** 读当前配置并刷新界面。 */
  void load() {
    String v = Settings.Global.getString(a.getContentResolver(), KEY);
    for (int i = 0; i < 3; i++) act[i] = "";
    if (v != null) {
      for (String p : v.split(",")) {
        int e = p.indexOf('=');
        for (int i = 0; e > 0 && i < 3; i++) {
          if (KEYS[i].equals(p.substring(0, e))) act[i] = p.substring(e + 1);
        }
      }
    }
    for (String s : act) on |= s.length() > 0;
    update();
  }

  private void update() {
    String lock = null;
    if (state(MODULE) == MISSING) lock = "请先安装 VoiceKeyMap 模块";
    else if (state(ASSISTANT[0]) == ENABLED || state(ASSISTANT[1]) == ENABLED)
      lock = "请先在「应用类」禁用「语音助手」";
    sw.setInfo(
        "语音键自定义（LSPosed模块）",
        "自定义遥控器语音键的单击、双击、长按动作。需先禁用“语音助手”。",
        null,
        lock == null ? null : "当前不可用：" + lock,
        lock == null ? ItemView.NEUTRAL : ItemView.WARN);
    sw.setLocked(lock != null && !on, lock);
    sw.setChecked(on);
    sw.markBase();
    item.setVisibility(on ? View.VISIBLE : View.GONE);
    a.updateDependents();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      sb.append(i > 0 ? "\n" : "").append(NAMES[i]).append("：").append(label(act[i]));
    }
    item.setInfo("语音键动作", "设置单击、双击、长按各自执行的动作。", null, sb.toString(), ItemView.NEUTRAL);
  }

  private String label(String v) {
    if (v.length() == 0) return "无动作";
    if (v.equals("sleep")) return "息屏（音响模式）";
    if (v.equals("source")) return "循环切换已接入的信号源";
    if (v.startsWith("source:")) {
      for (int i = 0; i < SRC_IDS.length; i++) {
        if (v.equals("source:" + SRC_IDS[i])) return "切换到信号源：" + SRC_NAMES[i];
      }
    }
    if (v.startsWith("app:")) {
      String pkg = v.substring(4);
      try {
        return "启动应用："
            + getPackageManager().getApplicationInfo(pkg, 0).loadLabel(getPackageManager());
      } catch (Exception e) {
        return "启动应用：" + pkg + "（未安装）";
      }
    }
    return v;
  }

  private void save() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      if (act[i].length() > 0)
        sb.append(sb.length() > 0 ? "," : "").append(KEYS[i]).append('=').append(act[i]);
    }
    final String cmd =
        sb.length() == 0
            ? "settings delete global " + KEY + "\n"
            : "settings put global " + KEY + " '" + sb + "'\n";
    new Thread(
            new Runnable() {
              public void run() {
                Shell.suOut(cmd);
              }
            },
            "slim-voicekey")
        .start();
  }

  private void set(int i, String v) {
    act[i] = v;
    save();
    update();
    showMain();
  }

  /** 三个手势各一行，点哪个就选哪个的动作；选完回到这里。 */
  private void showMain() {
    String[] labels = new String[3];
    for (int i = 0; i < 3; i++) labels[i] = NAMES[i] + "：" + label(act[i]);
    choose(
        "语音键动作",
        labels,
        new Pick() {
          public void on(int k) {
            pickAction(k);
          }
        },
        false);
  }

  /** 子弹窗取消或按返回键，都回到主弹窗。 */
  private final class Back
      implements DialogInterface.OnClickListener, DialogInterface.OnCancelListener {
    public void onClick(DialogInterface d, int w) {
      showMain();
    }

    public void onCancel(DialogInterface d) {
      showMain();
    }
  }

  private void pickAction(final int i) {
    choose(
        NAMES[i] + "动作",
        new String[] {"无动作", "息屏（音响模式）", "循环切换已接入的信号源", "切换到指定信号源…", "启动指定应用…"},
        new Pick() {
          public void on(int k) {
            if (k == 0) set(i, "");
            else if (k == 1) set(i, "sleep");
            else if (k == 2) set(i, "source");
            else if (k == 3) pickSource(i);
            else pickApp(i);
          }
        },
        true);
  }

  private void pickSource(final int i) {
    choose(
        "选择信号源",
        SRC_NAMES,
        new Pick() {
          public void on(int k) {
            set(i, "source:" + SRC_IDS[k]);
          }
        },
        true);
  }

  private void pickApp(final int i) {
    final Apps apps = new Apps();
    choose(
        "选择要启动的应用",
        apps.labels,
        new Pick() {
          public void on(int k) {
            set(i, "app:" + apps.pkgs.get(k));
          }
        },
        true);
  }

  /** 可启动的应用（桌面或电视桌面有入口），按名称排序。 */
  private final class Apps {
    final List<String> pkgs = new ArrayList<String>();
    final String[] labels;

    Apps() {
      final Map<String, String> m = new LinkedHashMap<String, String>();
      for (String cat :
          new String[] {Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
        for (ResolveInfo ri :
            getPackageManager()
                .queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
          String pkg = ri.activityInfo.packageName;
          if (!m.containsKey(pkg)) m.put(pkg, String.valueOf(ri.loadLabel(getPackageManager())));
        }
      }
      List<Map.Entry<String, String>> list = new ArrayList<Map.Entry<String, String>>(m.entrySet());
      Collections.sort(
          list,
          new Comparator<Map.Entry<String, String>>() {
            public int compare(Map.Entry<String, String> x, Map.Entry<String, String> y) {
              return x.getValue().compareToIgnoreCase(y.getValue());
            }
          });
      labels = new String[list.size()];
      for (int i = 0; i < labels.length; i++) {
        pkgs.add(list.get(i).getKey());
        labels[i] = list.get(i).getValue();
      }
    }
  }

  /** 单选列表弹窗：选中即关闭并回调。sub 为真是子弹窗（有“取消”），否则是主弹窗（只有“完成”）。 */
  private void choose(String title, String[] labels, final Pick pick, boolean sub) {
    LinearLayout list = new LinearLayout(a);
    list.setOrientation(LinearLayout.VERTICAL);
    final Dlg[] d = new Dlg[1];
    for (int i = 0; i < labels.length; i++) {
      final int k = i;
      Button b = Ui.button(a, labels[i], false);
      b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
      b.setOnClickListener(
          new View.OnClickListener() {
            public void onClick(View v) {
              d[0].dismiss();
              pick.on(k);
            }
          });
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(6);
      list.addView(b, lp);
    }
    ScrollView sv = new ScrollView(a);
    sv.addView(list);
    Dlg.Builder b = new Dlg.Builder(a).setTitle(title).setView(sv);
    if (sub) b.setNegativeButton("取消", back).setOnCancelListener(back);
    else b.setPositiveButton("完成", null);
    d[0] = b.show();
    if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
  }
}
