package com.beekeeper.voicekeymap;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/*
 * 把遥控器的语音键（小爱键）改成可自定义的单击 / 双击 / 长按动作。作用域：system（system_server）。
 *
 * 蓝牙语音遥控器的语音键发的是 evdev KEY_F5（扫描码 63），键值表把它映射成 KEYCODE_UNKNOWN(0)，
 * 到 MitvPhoneWindowManager.interceptKeyBeforeQueueing 时仍是 keycode 0。小爱本身是蓝牙服务直接发意图拉起的，
 * 不经过按键层，所以语音助手没有禁用时本模块拦不住它（应用端会锁定开关）。
 *
 * 配置存在 Settings.Global voicekeymap，形如 click=sleep,long=source,double=app:com.pkg；
 * 动作值：sleep、source（循环）、source:<序号 23/24/29>、app:<包名>，都在 system_server 里直接做。
 */
public final class HookEntry extends XposedModule {

  private static final String TAG = "VoiceKeyMap";
  private static final String KEY_SETTING = "voicekeymap";
  private static final String PWM = "com.android.server.policy.MitvPhoneWindowManager";
  private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
  private static final int SCAN_CODE = 63;
  private static final long LONG_MS = 700;
  private static final long DOUBLE_MS = 350;

  /** 可切换的信号源：播放器 input 序号、TvInput 的输入 id、显示名（按遥控器上的顺序循环）。 */
  private static final int[] SOURCES = {23, 24, 29};

  private static final String[] INPUTS = {
    "com.mediatek.tvinput/.hdmi.HDMIInputService/HW5",
    "com.mediatek.tvinput/.hdmi.HDMIInputService/HW6",
    "com.mediatek.tvinput/.dp.DpInputService/HW9"
  };
  private static final String[] SOURCE_NAMES = {"HDMI 1", "HDMI 2", "DP"};

  private static volatile boolean hooked;

  private Handler worker;
  private Handler ui;
  private Method gotoXiaoAiMode;

  // 手势状态，只在 worker 线程里读写
  private boolean held, longFired, tapPending, second;
  private Context ctx;

  @Override
  public void onModuleLoaded(ModuleLoadedParam param) {
    log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
  }

  @Override
  public void onSystemServerStarting(SystemServerStartingParam param) {
    if (hooked) {
      return;
    }
    try {
      Class<?> pwm = param.getClassLoader().loadClass(PWM);
      gotoXiaoAiMode = pwm.getMethod("gotoXiaoAIMode", Context.class);
      HandlerThread t = new HandlerThread(TAG);
      t.start();
      worker = new Handler(t.getLooper());
      ui = new Handler(Looper.getMainLooper());
      hook(pwm.getMethod(
              "interceptKeyBeforeQueueing",
              KeyEvent.class,
              boolean.class,
              int.class,
              Context.class))
          .intercept(chain -> {
            KeyEvent e = (KeyEvent) chain.getArg(0);
            Context c = (Context) chain.getArg(3);
            if (e.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN
                && e.getScanCode() == SCAN_CODE
                && !config(c).isEmpty()) {
              boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
              if (!down || e.getRepeatCount() == 0) {
                worker.post(() -> onKey(c, down));
              }
              return Boolean.TRUE;
            }
            return chain.proceed();
          });
      hooked = true;
      log(Log.INFO, TAG, "voice key hook installed");
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "cannot hook window manager", t);
    }
  }

  // ---- 手势识别（system_server） ----

  private void onKey(Context c, boolean down) {
    ctx = c;
    if (down) {
      if (held) {
        return;
      }
      held = true;
      longFired = false;
      if (tapPending) {
        tapPending = false;
        second = true;
        worker.removeCallbacks(singleTask);
      }
      worker.postDelayed(longTask, LONG_MS);
      return;
    }
    if (!held) {
      return;
    }
    held = false;
    worker.removeCallbacks(longTask);
    if (longFired) {
      return;
    }
    if (second) {
      second = false;
      run("double");
    } else if (action("double").isEmpty()) {
      run("click");
    } else {
      tapPending = true;
      worker.postDelayed(singleTask, DOUBLE_MS);
    }
  }

  private final Runnable longTask =
      () -> {
        longFired = true;
        second = false;
        run("long");
      };

  private final Runnable singleTask =
      () -> {
        tapPending = false;
        run("click");
      };

  private static Map<String, String> config(Context c) {
    Map<String, String> m = new HashMap<>();
    String s = Settings.Global.getString(c.getContentResolver(), KEY_SETTING);
    if (s != null) {
      for (String kv : s.split(",")) {
        int i = kv.indexOf('=');
        if (i > 0 && i < kv.length() - 1) {
          m.put(kv.substring(0, i), kv.substring(i + 1));
        }
      }
    }
    return m;
  }

  private String action(String gesture) {
    String a = config(ctx).get(gesture);
    return a == null ? "" : a;
  }

  private void run(String gesture) {
    String a = action(gesture);
    log(Log.INFO, TAG, gesture + " -> " + a);
    try {
      if (a.equals("sleep")) {
        gotoXiaoAiMode.invoke(null, ctx);
      } else if (a.equals("source")) {
        cycleSource();
      } else if (a.startsWith("source:")) {
        int id = Integer.parseInt(a.substring(7));
        for (int i = 0; i < SOURCES.length; i++) {
          if (SOURCES[i] == id) {
            switchTo(i);
          }
        }
      } else if (a.startsWith("app:")) {
        launchApp(a.substring(4));
      }
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "action failed: " + a, t);
    }
  }

  // ---- 动作 ----

  private void toast(String text) {
    ui.post(() -> Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show());
  }

  /** 在已接入的信号源之间循环；current_source_for_tvplayer 是播放器记录的当前信号源。 */
  private void cycleSource() {
    TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
    int cur = Settings.System.getInt(ctx.getContentResolver(), "current_source_for_tvplayer", -1);
    int curIdx = -1;
    for (int i = 0; i < SOURCES.length; i++) {
      if (SOURCES[i] == cur) {
        curIdx = i;
      }
    }
    int target = -1;
    for (int k = 1; k <= SOURCES.length && target < 0; k++) {
      int i = (curIdx + k) % SOURCES.length;
      if (i != curIdx && tim.getInputState(INPUTS[i]) == TvInputManager.INPUT_STATE_CONNECTED) {
        target = i;
      }
    }
    if (target < 0) {
      toast("无已接入的信号源可以切换");
      return;
    }
    switchTo(target);
  }

  private void switchTo(int idx) {
    ctx.startActivity(
        new Intent()
            .setClassName(PLAYER, PLAYER + ".ExternalSourceActivity")
            .putExtra("input", SOURCES[idx])
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    toast("信号源：" + SOURCE_NAMES[idx]);
  }

  private void launchApp(String pkg) {
    PackageManager pm = ctx.getPackageManager();
    Intent it = pm.getLaunchIntentForPackage(pkg);
    if (it == null) {
      it = pm.getLeanbackLaunchIntentForPackage(pkg);
    }
    if (it == null) {
      toast("应用未安装：" + pkg);
      return;
    }
    ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }
}
