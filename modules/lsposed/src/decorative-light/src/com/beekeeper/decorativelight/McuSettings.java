package com.beekeeper.decorativelight;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.UUID;

/**
 * MCU 固件管理页。设置进程只负责界面和授权流程，真正的校验与刷写由 Magisk 的 root 进程执行， 双方通过 Settings.Global 的 beekeeper_mcu_* 通信。
 */
public final class McuSettings {
  private static final String WARNING =
      "警告：此 MCU 负责背光（局部调光）和装饰灯。刷写失败或中途断电可能造成背光异常（表现为黑屏）或灯光异常；"
          + "目前没有经过验证的纯软件救砖办法。原版镜像不是完整 MCU 闪存备份，也不能保证故障后可刷回。\n\n"
          + "刷写期间不要断电、拔线、重启设备或更新模块。页面关闭后后台仍会继续刷写。请确认能承担上述风险。";

  private static final class Session {
    String token = "";
    long armed;
    boolean started;
  }

  public static void show(Context ctx) {
    new AlertDialog.Builder(ctx)
        .setTitle("MCU 固件管理")
        .setItems(
            new String[] {"刷写 24 灯珠自定义固件", "刷回原版固件（单灯控制）"},
            (d, index) -> confirm(ctx, index == 0 ? "install" : "restore"))
        .setNegativeButton("关闭", null)
        .show();
  }

  private static void confirm(Context ctx, String action) {
    LinearLayout content = new LinearLayout(ctx);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 8), Ui.dp(ctx, 18), Ui.dp(ctx, 8));
    TextView warning = Ui.text(ctx, WARNING, 19);
    warning.setTextColor(Color.rgb(255, 110, 80));
    content.addView(warning);
    content.addView(
        Ui.text(
            ctx,
            "仅支持 beekeeper / otfcn1n\n系统："
                + McuPolicy.SYSTEM
                + "（Android 14）\n"
                + "还会强制校验完整系统指纹、灯控库、厂商原版镜像、生成的自定义镜像及实时 MCU v1.07。版本不匹配禁止刷写。\n"
                + "开始时会以红灯闪烁提示，随后可能暂时黑屏。",
            16));
    CheckBox consent = new CheckBox(ctx);
    consent.setText("我已阅读并接受刷写风险");
    content.addView(consent);
    TextView status = Ui.text(ctx, "正在请求后台校验…", 16);
    content.addView(status);
    ScrollView scroll = new ScrollView(ctx);
    scroll.addView(content);
    AlertDialog dialog =
        new AlertDialog.Builder(ctx)
            .setTitle(action.equals("install") ? "刷写自定义 MCU 固件" : "刷回原版 MCU 固件")
            .setView(scroll)
            .setPositiveButton("请稍候", null)
            .setNegativeButton("取消", null)
            .create();

    Handler handler = new Handler(Looper.getMainLooper());
    String id = UUID.randomUUID().toString();
    Session s = new Session();
    Runnable update =
        new Runnable() {
          @Override
          public void run() {
            if (!dialog.isShowing()) return;
            String[] ticket = get(ctx, "ticket").split(":");
            s.token = "";
            s.armed = 0;
            if (ticket.length == 3 && ticket[0].equals(id)) {
              s.token = ticket[1];
              try {
                s.armed = Long.parseLong(ticket[2]);
              } catch (NumberFormatException ignored) {
              }
            }
            long now = SystemClock.elapsedRealtime();
            int remaining = (int) Math.max(0, (10000 - (now - s.armed) + 999) / 1000);
            Button start = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            start.setEnabled(
                !s.started
                    && !s.token.isEmpty()
                    && McuPolicy.ready(s.armed, now)
                    && consent.isChecked());
            start.setText(
                s.started
                    ? "正在刷写"
                    : s.armed == 0
                        ? "等待校验"
                        : remaining > 0
                            ? "开始刷写（" + remaining + " 秒）"
                            : now - s.armed > 300000 ? "已过期，请重新打开" : "开始刷写");
            String state = get(ctx, "status");
            status.setText("后台：" + (state.isEmpty() ? "服务未响应，请检查 Magisk 模块" : state));
            handler.postDelayed(this, 250);
          }
        };
    dialog.setOnShowListener(d -> {
      Button start = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      start.setEnabled(false);
      request(ctx, "prepare:" + action + ":" + id);
      update.run();
      start.setOnClickListener(v -> {
        if (s.started
            || !consent.isChecked()
            || s.token.isEmpty()
            || !McuPolicy.ready(s.armed, SystemClock.elapsedRealtime())) return;
        request(ctx, "start:" + s.token);
        s.started = true;
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        start.setEnabled(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("关闭页面（后台继续）");
      });
    });
    dialog.setOnDismissListener(d -> {
      handler.removeCallbacks(update);
      if (!s.started && !s.token.isEmpty()) request(ctx, "cancel:" + s.token);
    });
    dialog.show();
  }

  private static String get(Context ctx, String key) {
    String value = Settings.Global.getString(ctx.getContentResolver(), "beekeeper_mcu_" + key);
    return value == null ? "" : value;
  }

  private static void request(Context ctx, String value) {
    Settings.Global.putString(ctx.getContentResolver(), "beekeeper_mcu_request", value);
  }

  private McuSettings() {}
}
