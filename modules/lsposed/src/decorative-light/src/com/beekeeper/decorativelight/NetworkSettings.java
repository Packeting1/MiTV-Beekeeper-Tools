package com.beekeeper.decorativelight;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** 网络灯控设置页：设置写进 Settings.Global 的 beekeeper_net_*，由 root 进程里的 NetworkDaemon 读取。 */
public final class NetworkSettings {
  public static int get(Context ctx, String key, int fallback) {
    return Settings.Global.getInt(ctx.getContentResolver(), "beekeeper_net_" + key, fallback);
  }

  private static void put(Context ctx, String key, int value) {
    Settings.Global.putInt(ctx.getContentResolver(), "beekeeper_net_" + key, value);
  }

  /** 自定义颜色和刷写会占用灯控，先退出网络接管。 */
  public static void pause(Context ctx) {
    if (get(ctx, "enabled", 0) == 1) put(ctx, "enabled", 0);
  }

  public static void show(Context ctx, Runnable refresh) {
    LinearLayout form = new LinearLayout(ctx);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 4), Ui.dp(ctx, 18), Ui.dp(ctx, 8));
    form.addView(
        Ui.text(
            ctx,
            "设备 IP："
                + addresses()
                + "\nE1.31：UDP 5568（单播/组播）　DDP：UDP 4048\n"
                + "每颗灯珠使用独立的 R、G、B 三字节，按灯珠顺序映射。",
            16));
    Switch enabled = new Switch(ctx);
    enabled.setText("启用网络接收");
    enabled.setChecked(get(ctx, "enabled", 0) == 1);
    form.addView(enabled);
    CheckBox sacn = new CheckBox(ctx);
    sacn.setText("E1.31 / sACN");
    sacn.setChecked(get(ctx, "sacn", 1) == 1);
    form.addView(sacn);
    CheckBox ddp = new CheckBox(ctx);
    ddp.setText("DDP / RGB24");
    ddp.setChecked(get(ctx, "ddp", 1) == 1);
    form.addView(ddp);
    TextView count = Ui.text(ctx, "", 18);
    form.addView(count);
    Button firmware = new Button(ctx);
    firmware.setText("MCU 固件管理 / 刷写");
    firmware.setOnClickListener(v -> McuSettings.show(ctx));
    form.addView(firmware);

    LinearLayout pair = new LinearLayout(ctx);
    form.addView(pair);
    LinearLayout left = column(ctx, 0);
    LinearLayout right = column(ctx, 10);
    pair.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    pair.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    EditText universe = number(ctx, left, "sACN Universe（1–63999）", get(ctx, "universe", 1));
    EditText channel = number(ctx, right, "sACN 起始通道（RGB）", get(ctx, "channel", 1));
    form.addView(
        Ui.text(
            ctx,
            "原厂 MCU：按 1 颗配置，第一组 RGB 控制整条灯。已安装自定义 MCU：按 24 颗配置，逐颗独立控制。"
                + "DDP 从偏移 0 开始；3 秒无数据后恢复本地光效。手动调整灯光会退出网络接管。",
            15));
    TextView status = Ui.text(ctx, "", 15);
    status.setFocusable(true);
    form.addView(status);
    ScrollView scroll = new ScrollView(ctx);
    scroll.addView(form);
    AlertDialog dialog =
        new AlertDialog.Builder(ctx)
            .setTitle("网络灯控")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();

    Handler handler = new Handler(Looper.getMainLooper());
    Runnable update =
        new Runnable() {
          @Override
          public void run() {
            if (!dialog.isShowing()) return;
            count.setText(
                get(ctx, "capacity", 1) == Om2Frame.PIXELS
                    ? "灯珠数量：" + Om2Frame.PIXELS + "（自定义 MCU）"
                    : "灯珠数量：1（整条灯同色 / 原厂兼容）");
            String text =
                Settings.Global.getString(ctx.getContentResolver(), "beekeeper_net_status");
            status.setText("状态：" + (text == null ? "后台服务尚未启动" : text));
            handler.postDelayed(this, 1500);
          }
        };
    dialog.setOnShowListener(v -> {
      update.run();
      dialog
          .getButton(AlertDialog.BUTTON_POSITIVE)
          .setOnClickListener(button -> {
            int pixels = get(ctx, "capacity", 1);
            int u = parse(universe);
            int ch = parse(channel);
            if (u < 1 || u > 63999) {
              universe.setError("Universe 范围为 1–63999");
              return;
            }
            if (ch < 1 || ch + pixels * 3 - 1 > 512) {
              channel.setError("全部灯珠的 RGB 通道须位于同一个 Universe 的 1–512 范围");
              return;
            }
            if (enabled.isChecked() && !sacn.isChecked() && !ddp.isChecked()) {
              sacn.setError("至少启用一种协议");
              return;
            }
            // 先关闭接收再改参数，最后才按开关重新启用，避免守护进程读到改了一半的配置。
            put(ctx, "enabled", 0);
            put(ctx, "universe", u);
            put(ctx, "channel", ch);
            put(ctx, "sacn", sacn.isChecked() ? 1 : 0);
            put(ctx, "ddp", ddp.isChecked() ? 1 : 0);
            put(ctx, "enabled", enabled.isChecked() ? 1 : 0);
            refresh.run();
            dialog.dismiss();
          });
    });
    dialog.setOnDismissListener(v -> handler.removeCallbacks(update));
    dialog.show();
  }

  private static LinearLayout column(Context ctx, int leftPadding) {
    LinearLayout column = new LinearLayout(ctx);
    column.setOrientation(LinearLayout.VERTICAL);
    column.setPadding(Ui.dp(ctx, leftPadding), 0, 0, 0);
    return column;
  }

  private static EditText number(Context ctx, LinearLayout column, String title, int value) {
    column.addView(Ui.text(ctx, title, 16));
    EditText field = new EditText(ctx);
    field.setSingleLine();
    field.setInputType(InputType.TYPE_CLASS_NUMBER);
    field.setTextSize(20);
    field.setTextColor(Color.WHITE);
    field.setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), 0);
    field.setText(String.valueOf(value));
    field.setSelectAllOnFocus(true);
    column.addView(
        field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 48)));
    return field;
  }

  private static int parse(EditText field) {
    try {
      return Integer.parseInt(field.getText().toString());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static String addresses() {
    StringBuilder out = new StringBuilder();
    try {
      for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        for (InetAddress a : Collections.list(iface.getInetAddresses())) {
          if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress())
            continue;
          if (out.length() > 0) out.append(" / ");
          out.append(a.getHostAddress());
        }
      }
    } catch (java.net.SocketException ignored) {
    }
    return out.length() == 0 ? "未连接网络" : out.toString();
  }

  private NetworkSettings() {}
}
