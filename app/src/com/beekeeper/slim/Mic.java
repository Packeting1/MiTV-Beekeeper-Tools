package com.beekeeper.slim;

import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 麦克风推流的开关和电脑配对入口，实际推流在 MicService。 */
final class Mic extends Part {

  private static final String SCRIPT = "/data/adb/service.d/beekeeper_mic.sh";

  /** 系统在电视待机时会强制停止所有第三方应用，被强制停止的应用收不到任何广播，只能靠应用之外的 root 脚本拉起；开机时 Magisk 也会运行它。 脚本文件被删或应用被卸载就退出。 */
  private static final String DAEMON =
      "#!/system/bin/sh\n"
          + "while [ -f \"$0\" ] && [ -d /data/data/com.beekeeper.slim ]; do\n"
          + "  grep -q ':"
          + Integer.toHexString(MicService.PORT).toUpperCase()
          + " [0-9A-F:]* 0A ' /proc/net/tcp6 /proc/net/tcp"
          + " || am start-foreground-service -n com.beekeeper.slim/.MicService >/dev/null 2>&1\n"
          + "  sleep 5\n"
          + "done\n";

  final ItemView sw, item;

  Mic(MainActivity a) {
    super(a);
    sw = new ItemView(a, false);
    sw.setListener(
        new ItemView.Listener() {
          public void onChange(boolean checked) {
            toggle(checked);
          }
        });
    item = new ItemView(a, true);
    item.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            showPairs();
          }
        });
  }

  void load() {
    boolean on = MicService.enabled(a);
    sw.setInfo("麦克风推流", "把电视麦克风通过局域网推给电脑端 MiTvMic，在电脑上使用电视麦克风。", null, null, ItemView.NEUTRAL);
    sw.setChecked(on);
    sw.markBase();
    item.setInfo(
        "已配对的电脑",
        "查看已配对的电脑，可以逐台或全部取消配对。",
        null,
        "已配对 " + MicService.pairs(a).size() + " 台　电视地址：" + ip() + ":" + MicService.PORT,
        ItemView.NEUTRAL);
    item.setVisibility(on ? View.VISIBLE : View.GONE);
  }

  private void toggle(final boolean on) {
    MicService.setEnabled(a, on);
    load();
    if (!on) {
      Shell.suAsync("slim-mic-off", "rm -f " + SCRIPT + "\npkill -f beekeeper_mic.sh\n");
      MicService.stop(a);
      return;
    }
    new Thread(
            new Runnable() {
              public void run() {
                // 后台启动（如开机）时系统默认会让没有前台界面的应用录到静音，显式放行；悬浮窗用于弹出配对询问
                Shell.suOut(
                    "pm grant "
                        + getPackageName()
                        + " android.permission.RECORD_AUDIO\nappops set "
                        + getPackageName()
                        + " RECORD_AUDIO allow\nappops set "
                        + getPackageName()
                        + " SYSTEM_ALERT_WINDOW allow\n"
                        + "mkdir -p /data/adb/service.d\n"
                        + "cat > "
                        + SCRIPT
                        + " <<'EOF'\n"
                        + DAEMON
                        + "EOF\n"
                        + "chmod 755 "
                        + SCRIPT
                        + "\npkill -f beekeeper_mic.sh\n"
                        + "nohup sh "
                        + SCRIPT
                        + " >/dev/null 2>&1 &\n");
                main.post(
                    new Runnable() {
                      public void run() {
                        MicService.start(a);
                      }
                    });
              }
            },
            "slim-mic")
        .start();
  }

  private final DialogInterface.OnClickListener back =
      new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface d, int w) {
          showPairs();
        }
      };

  private void showPairs() {
    List<MicService.Pair> list = MicService.pairs(a);
    Dlg.Builder b = new Dlg.Builder(a).setTitle("已配对的电脑").setPositiveButton("关闭", null);
    final Dlg[] self = new Dlg[1];
    if (list.isEmpty()) {
      b.setMessage("没有已配对的电脑。\n\n在电脑端 MiTvMic 里选中这台电视并点“配对”，电视会弹窗询问是否允许。");
    } else {
      LinearLayout col = new LinearLayout(a);
      col.setOrientation(LinearLayout.VERTICAL);
      TextView tip = text(12, Ui.SUB, false);
      tip.setText("点击电脑可取消它的配对。");
      tip.setPadding(0, 0, 0, Ui.dp(a, 8));
      col.addView(tip);
      String active = MicService.activeToken();
      for (final MicService.Pair p : list) {
        View row = pairRow(p, p.token.equals(active));
        row.setOnClickListener(
            new View.OnClickListener() {
              public void onClick(View v) {
                self[0].dismiss();
                confirmRemove(p);
              }
            });
        LinearLayout.LayoutParams lp = Ui.lp();
        lp.bottomMargin = Ui.dp(a, 6);
        col.addView(row, lp);
      }
      ScrollView sv = new ScrollView(a);
      sv.addView(col);
      b.setView(sv);
      b.setNeutralButton(
          "全部取消配对",
          new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
              confirmClear();
            }
          });
    }
    self[0] = b.show();
  }

  private TextView text(int sp, int color, boolean bold) {
    TextView t = new TextView(a);
    t.setTextSize(sp);
    t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
    t.setPadding(0, Ui.dp(a, 1), 0, 0);
    return t;
  }

  /** 一台已配对电脑：名称（正在连接时带“正在使用”标签）、IP、配对时间、连接时间。 */
  private View pairRow(MicService.Pair p, boolean active) {
    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
    LinearLayout row = new LinearLayout(a);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setFocusable(true);
    row.setClickable(true);
    row.setPadding(Ui.dp(a, 14), Ui.dp(a, 9), Ui.dp(a, 14), Ui.dp(a, 9));
    row.setBackground(Ui.focusBg(a, Ui.CARD, Ui.LINE, Ui.CARD_FOCUS, Ui.ACCENT, 8));
    Ui.noDefaultHighlight(row);

    LinearLayout head = new LinearLayout(a);
    head.setGravity(Gravity.CENTER_VERTICAL);
    TextView name = text(15, Ui.TEXT, true);
    name.setText(p.name);
    head.addView(name);
    if (active) {
      TextView chip = text(10, Ui.GOOD, true);
      chip.setText("正在使用");
      chip.setPadding(Ui.dp(a, 5), Ui.dp(a, 1), Ui.dp(a, 5), Ui.dp(a, 1));
      GradientDrawable bg = new GradientDrawable();
      bg.setStroke(Ui.dp(a, 1), Ui.GOOD);
      bg.setCornerRadius(Ui.dp(a, 4));
      chip.setBackground(bg);
      LinearLayout.LayoutParams clp =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      clp.leftMargin = Ui.dp(a, 8);
      head.addView(chip, clp);
    }
    row.addView(head);

    TextView ip = text(12, Ui.SUB, false);
    ip.setText(p.ip);
    row.addView(ip);
    TextView paired = text(11, Ui.SUB, false);
    paired.setText("配对时间　" + fmt.format(new Date(p.time)));
    row.addView(paired);
    TextView seen = text(11, Ui.SUB, false);
    seen.setText((active ? "本次连接　" : "上次连接　") + (p.last > 0 ? fmt.format(new Date(p.last)) : "无"));
    row.addView(seen);
    return row;
  }

  private void confirmRemove(final MicService.Pair p) {
    new Dlg.Builder(a)
        .setTitle("取消配对")
        .setMessage("取消后，“" + p.name + "”将不能使用此电视的麦克风。")
        .setPositiveButton(
            "取消配对",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                MicService.removePair(a, p.token);
                load();
                Ui.toast(a, "已取消配对", false);
                showPairs();
              }
            })
        .setNegativeButton("返回", back)
        .show();
  }

  private void confirmClear() {
    new Dlg.Builder(a)
        .setTitle("全部取消配对")
        .setMessage("取消后，所有已配对的电脑都无法再使用电视麦克风，需要在电视弹出的询问框里重新允许。")
        .setPositiveButton(
            "全部取消",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                MicService.clearPairs(a);
                load();
                Ui.toast(a, "已取消所有配对", false);
              }
            })
        .setNegativeButton("返回", back)
        .show();
  }

  private static String ip() {
    try {
      for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (n.isLoopback() || !n.isUp()) continue;
        for (InetAddress ad : Collections.list(n.getInetAddresses())) {
          if (ad instanceof Inet4Address) return ad.getHostAddress();
        }
      }
    } catch (SocketException e) {
      // 取不到就显示未知
    }
    return "未知";
  }
}
