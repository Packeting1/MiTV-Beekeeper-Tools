package com.beekeeper.slim;

import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 麦克风推流（MicStream 模块）已配对电脑的管理；与模块通过 Settings.Global 交换数据，取消配对没有写权限，经 root 写入。 */
final class MicPairs extends Part {

  static final String MODULE = "com.beekeeper.micstream";
  private static final String KEY_PAIRS = "beekeeper_mic_pairs";
  private static final String KEY_UNPAIR = "beekeeper_mic_unpair";

  private static final class Row {
    final String id, ip, name;
    final long time, last;
    final boolean active;

    Row(String[] f) {
      id = f[0];
      ip = f[1];
      name = f[2];
      time = Long.parseLong(f[3]);
      last = Long.parseLong(f[4]);
      active = f[5].equals("1");
    }
  }

  final ItemView item;

  MicPairs(MainActivity a) {
    super(a);
    item = new ItemView(a, true);
    item.setOnClickListener(
        new View.OnClickListener() {
          public void onClick(View v) {
            showPairs();
          }
        });
  }

  void load() {
    item.setInfo(
        "已配对的电脑", "查看已配对的电脑，可以逐台或全部取消配对。", null, "已配对 " + rows().size() + " 台", ItemView.NEUTRAL);
    syncVisible();
  }

  void syncVisible() {
    item.setVisibility(a.micGroup.box.isChecked() ? View.VISIBLE : View.GONE);
  }

  private List<Row> rows() {
    List<Row> out = new ArrayList<Row>();
    String raw = Settings.Global.getString(a.getContentResolver(), KEY_PAIRS);
    if (raw == null) return out;
    for (String line : raw.split("\n")) {
      String[] f = line.split("\t", -1);
      if (f.length != 6) continue;
      try {
        out.add(new Row(f));
      } catch (NumberFormatException e) {
        // 记录损坏，丢弃
      }
    }
    return out;
  }

  private void unpair(String id) {
    Shell.suAsync("mic-unpair", "settings put global " + KEY_UNPAIR + " '" + id + "'\n");
  }

  private final DialogInterface.OnClickListener back =
      new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface d, int w) {
          showPairs();
        }
      };

  private void showPairs() {
    List<Row> list = rows();
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
      for (final Row p : list) {
        View row = pairRow(p);
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
  private View pairRow(Row p) {
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
    if (p.active) {
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
    seen.setText(
        (p.active ? "本次连接　" : "上次连接　") + (p.last > 0 ? fmt.format(new Date(p.last)) : "无"));
    row.addView(seen);
    return row;
  }

  private void confirmRemove(final Row p) {
    new Dlg.Builder(a)
        .setTitle("取消配对")
        .setMessage("取消后，“" + p.name + "”将不能使用此电视的麦克风。")
        .setPositiveButton(
            "取消配对",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                unpair(p.id);
                Ui.toast(a, "已取消配对", false);
                afterChange();
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
                unpair("*");
                Ui.toast(a, "已取消所有配对", false);
                afterChange();
              }
            })
        .setNegativeButton("返回", back)
        .show();
  }

  /** 取消配对经 root 异步写入，稍等模块更新列表后再刷新条目。 */
  private void afterChange() {
    main.postDelayed(
        new Runnable() {
          public void run() {
            load();
          }
        },
        1500);
  }
}
