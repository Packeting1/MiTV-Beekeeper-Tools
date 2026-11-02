package com.beekeeper.slim;

import android.content.DialogInterface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 局部调光强度：开关打开后才出现调节卡片；开启时由 service.d 守护脚本在开机和被系统复位时写回，关闭则交还系统。
 *
 * <p>强度写 sysfs ldm_set_ld_level（0~0xFF，默认 0xFF，读回在 ldm_basic_info_get 的 “Local dimming strength”）。
 */
final class LocalDimming extends Part {

  private static final String D = "/sys/devices/virtual/localdimming/localdimming";
  private static final String CONF = "/data/adb/beekeeper_ldm_level";
  private static final String SCRIPT = "/data/adb/service.d/beekeeper_ldm_level.sh";
  private static final String KILL = "pkill -f beekeeper_ldm_level.sh\n";
  private static final String START = "nohup sh " + SCRIPT + " >/dev/null 2>&1 &\n";

  /** 系统在切信号、开机时会把强度复位，所以脚本常驻轮询，发现不同就写回；配置文件被删就退出。 */
  private static final String DAEMON =
      "#!/system/bin/sh\n"
          + "D="
          + D
          + "\nC="
          + CONF
          + "\nwhile [ -f $C ]; do\n"
          + "  v=$(cat $C)\n"
          + "  case $(grep 'Local dimming strength' $D/ldm_basic_info_get 2>/dev/null) in\n"
          + "    *\": $v\") ;;\n"
          + "    *) [ -w $D/ldm_set_ld_level ] && echo $v > $D/ldm_set_ld_level ;;\n"
          + "  esac\n"
          + "  sleep 5\n"
          + "done\n";

  /** 总开关和调节卡片（开关打开才显示）。 */
  final ItemView sw, item;

  /** 当前读到的强度，-1 = 本机没有该节点。 */
  private int cur = -1;

  private boolean on;
  private int want = -1;
  private boolean busy, resume;

  LocalDimming(MainActivity a) {
    super(a);
    sw = new ItemView(a, false);
    sw.setVisibility(View.GONE);
    sw.setListener(
        new ItemView.Listener() {
          public void onChange(boolean checked) {
            if (checked) apply(cur);
            else release();
          }
        });
    item = new ItemView(a, true);
    item.setVisibility(View.GONE);
    item.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            showDialog();
          }
        });
  }

  private static String hex(int v) {
    return "0x" + Integer.toHexString(v).toUpperCase();
  }

  /** 读当前强度和开关状态，更新卡片。 */
  void load() {
    new Thread(
            new Runnable() {
              public void run() {
                String out =
                    Shell.suOut(
                        "grep 'Local dimming strength' "
                            + D
                            + "/ldm_basic_info_get\n"
                            + "[ -f "
                            + CONF
                            + " ] && echo ON\n");
                int v = -1;
                boolean s = false;
                if (out != null) {
                  int i = out.indexOf(": 0x");
                  if (i >= 0) {
                    try {
                      v = Integer.parseInt(out.substring(i + 4).split("\\s")[0], 16);
                    } catch (NumberFormatException e) {
                    }
                  }
                  s = out.contains("ON");
                }
                final int fv = v;
                final boolean fs = s;
                main.post(
                    new Runnable() {
                      public void run() {
                        cur = fv;
                        on = fs;
                        update();
                      }
                    });
              }
            },
            "slim-ldm")
        .start();
  }

  private void update() {
    sw.setVisibility(cur < 0 ? View.GONE : View.VISIBLE);
    item.setVisibility(cur < 0 || !on ? View.GONE : View.VISIBLE);
    if (cur < 0) return;
    sw.setInfo(
        "局部调光强度调节（Magisk脚本）",
        "开启后可手动调节局部调光强度，开机和被系统复位时自动写回。关闭后由系统的调光档位接管。",
        null,
        null,
        ItemView.NEUTRAL);
    sw.setChecked(on);
    sw.markBase();
    item.setInfo("局部调光强度", "调节局部调光的强度（0~255，默认 255）。", null, "当前：" + cur, ItemView.GOOD);
  }

  /** 预览：只保留最新值，后台线程串行写入；先停守护脚本，否则它会在 5 秒内把值写回。again 为真时写完重新拉起守护脚本。 */
  private synchronized void preview(int v, boolean again) {
    want = v;
    resume |= again;
    if (busy) return;
    busy = true;
    new Thread(
            new Runnable() {
              public void run() {
                while (true) {
                  int v;
                  boolean back;
                  synchronized (LocalDimming.this) {
                    v = want;
                    want = -1;
                    back = resume;
                    resume = false;
                    if (v < 0) {
                      busy = false;
                      return;
                    }
                  }
                  Shell.suOut(
                      KILL
                          + "echo "
                          + hex(v)
                          + " > "
                          + D
                          + "/ldm_set_ld_level\n"
                          + (back ? START : ""));
                }
              }
            },
            "slim-ldm-preview")
        .start();
  }

  private void showDialog() {
    final int orig = cur;
    final TextView val = new TextView(a);
    val.setTextSize(15);
    val.setTextColor(Ui.TEXT);
    final SeekBar bar = new SeekBar(a);
    bar.setMax(255);
    bar.setProgress(orig);
    bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    bar.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    val.setText(orig + " / 255");
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int p, boolean user) {
            val.setText(p + " / 255");
            if (user) preview(p, false);
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        });
    LinearLayout body = new LinearLayout(a);
    body.setOrientation(LinearLayout.VERTICAL);
    body.addView(val);
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(10);
    body.addView(bar, lp);
    TextView tip = new TextView(a);
    tip.setText(
        "左右键调节，拖动时实时生效。\n\n"
            + "数值高：暗区压得更狠，对比度和黑位更好，但亮暗交界处容易出现暗部细节丢失、亮物体周围的光晕变化，有时能看到分区亮度跳变。\n"
            + "数值低：背光更均匀，对比度下降、黑色发灰，但分区造成的瑕疵会少一些。\n"
            + "数值 0：基本相当于不做局部调光。");
    tip.setTextSize(12);
    tip.setTextColor(Ui.SUB);
    tip.setPadding(0, dp(10), 0, 0);
    body.addView(tip);

    DialogInterface.OnClickListener cancel =
        new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface d, int w) {
            preview(orig, true);
          }
        };
    new Dlg.Builder(a)
        .setTitle("局部调光强度")
        .setView(body)
        .setPositiveButton(
            "保存",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                apply(bar.getProgress());
              }
            })
        .setNegativeButton("取消", cancel)
        .setOnCancelListener(
            new DialogInterface.OnCancelListener() {
              public void onCancel(DialogInterface d) {
                preview(orig, true);
              }
            })
        .show();
    bar.requestFocus();
  }

  /** 开启或保存：写守护脚本和目标值，并立即拉起。 */
  private void apply(final int v) {
    cur = v;
    on = true;
    update();
    new Thread(
            new Runnable() {
              public void run() {
                Shell.suOut(
                    "mkdir -p /data/adb/service.d\n"
                        + "cat > "
                        + SCRIPT
                        + " <<'EOF'\n"
                        + DAEMON
                        + "EOF\n"
                        + "chmod 755 "
                        + SCRIPT
                        + "\necho "
                        + hex(v)
                        + " > "
                        + CONF
                        + "\n"
                        + KILL
                        + START);
              }
            },
            "slim-ldm-apply")
        .start();
  }

  /** 关闭：删脚本和配置，把强度写回系统默认 0xFF。 */
  private void release() {
    cur = 255;
    on = false;
    update();
    new Thread(
            new Runnable() {
              public void run() {
                Shell.suOut(
                    "rm -f "
                        + CONF
                        + " "
                        + SCRIPT
                        + "\n"
                        + KILL
                        + "echo 0xFF > "
                        + D
                        + "/ldm_set_ld_level\n");
              }
            },
            "slim-ldm-release")
        .start();
  }
}
