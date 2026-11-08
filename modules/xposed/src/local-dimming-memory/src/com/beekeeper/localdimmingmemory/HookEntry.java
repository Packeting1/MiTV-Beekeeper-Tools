package com.beekeeper.localdimmingmemory;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/*
 * 按视频格式（SDR / HDR10 / HLG / 杜比视界）分别记忆精密光控档位。
 * 格式变化时恢复该格式上次的档位，没有记录则沿用当前档位；用户手动改档位时更新当前格式的记录。
 * 格式判断与媒体角标相同：视频信息回调只作触发，稳定后读 MtkTvAVModeBase 的 HDR 类型。
 * 档位记录在 Settings.Global（localdimmingmemory_<格式>，-1 为无记录）。
 */
public final class HookEntry extends XposedModule {

  private static final String TAG = "LocalDimmingMemory";
  private static final String SERVICE = "mitv.service";
  private static final String SETTINGS = "com.xiaomi.mitv.settings";
  private static final String AV_MODE = "com.mediatek.twoworlds.tv.MtkTvAVModeBase";
  private static final String CALLBACK = "com.mediatek.twoworlds.tv.TVCallback";
  private static final String PICTURE = "mitv.display.PictureSettingsManager";
  private static final String KEY_ENABLED = "localdimmingmemory_enabled";
  private static final String KEY_PREFIX = "localdimmingmemory_";
  private static final String KEY_LEVEL = "picture_local_dimming";
  private static final int VIDEOINFO_HDR_TYPE = 2;
  private static final int MAX_LEVEL = 3;
  private static final long SETTLE_MS = 400;

  private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());

  private volatile Handler worker;
  private final Runnable checkTask = this::checkNow;
  private Object avMode;
  private Method getInfo;
  private Object picture;
  private Method setLevel;
  private String current;

  @Override
  public void onModuleLoaded(ModuleLoadedParam param) {
    log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
  }

  @Override
  public void onPackageLoaded(PackageLoadedParam param) {
    String pkg = param.getPackageName();
    if (SETTINGS.equals(pkg) && HOOKED.add(SETTINGS)) {
      try {
        SettingsSwitch.install(
            this,
            param.getDefaultClassLoader(),
            TAG,
            "精密光控记忆",
            "按 SDR、HDR10、HLG、杜比视界分别记住精密光控档位",
            KEY_ENABLED);
      } catch (Throwable t) {
        log(Log.ERROR, TAG, "cannot add settings switch", t);
      }
      return;
    }
    if (!SERVICE.equals(pkg) || !HOOKED.add(SERVICE)) {
      return;
    }
    try {
      ClassLoader cl = param.getDefaultClassLoader();
      Class<?> c = cl.loadClass(AV_MODE);
      avMode = c.getConstructor().newInstance();
      getInfo = c.getMethod("getVideoInfoValue", int.class);
      Class<?> p = cl.loadClass(PICTURE);
      picture = p.getMethod("getInstance").invoke(null);
      setLevel = p.getMethod("setLocalDimming", int.class);
      hook(cl.loadClass(CALLBACK)
              .getDeclaredMethod(
                  "DO_notifyVideoInfoMessage",
                  int.class,
                  int.class,
                  int.class,
                  int.class,
                  int.class,
                  int.class,
                  int.class))
          .intercept(chain -> {
            Object r = chain.proceed();
            scheduleCheck();
            return r;
          });
      HandlerThread t = new HandlerThread(TAG);
      t.start();
      worker = new Handler(t.getLooper());
      worker.post(this::start);
      log(Log.INFO, TAG, "started");
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "cannot start", t);
    }
  }

  private void start() {
    try {
      Context ctx = app();
      if (ctx == null) {
        worker.postDelayed(this::start, 1000);
        return;
      }
      ctx.getContentResolver()
          .registerContentObserver(
              Settings.Global.getUriFor(KEY_LEVEL),
              false,
              new ContentObserver(worker) {
                @Override
                public void onChange(boolean selfChange) {
                  onLevelChanged();
                }
              });
      checkNow();
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "cannot register observer", t);
    }
  }

  private void scheduleCheck() {
    Handler w = worker;
    if (w != null) {
      w.removeCallbacks(checkTask);
      w.postDelayed(checkTask, SETTLE_MS);
    }
  }

  private void checkNow() {
    try {
      Context ctx = app();
      if (ctx == null) {
        scheduleCheck();
        return;
      }
      if (!SettingsSwitch.enabled(ctx, KEY_ENABLED)) {
        current = null;
        return;
      }
      String format = format();
      if (format.equals(current)) {
        return;
      }
      current = format;
      int level = level(ctx);
      int saved = Settings.Global.getInt(ctx.getContentResolver(), KEY_PREFIX + format, -1);
      log(Log.INFO, TAG, "format " + format + ", level " + level + ", saved " + saved);
      if (level < 0) {
        return;
      }
      if (saved < 0 || saved > MAX_LEVEL) {
        remember(ctx, format, level);
      } else if (saved != level) {
        log(Log.INFO, TAG, "apply " + saved + " -> " + setLevel.invoke(picture, saved));
      }
    } catch (Throwable t) {
      log(Log.WARN, TAG, "check failed", t);
    }
  }

  // 格式刚变化、还没轮到 checkNow 时系统可能先重置档位，这种变化不能记到旧格式上
  private void onLevelChanged() {
    try {
      Context ctx = app();
      if (ctx == null || !SettingsSwitch.enabled(ctx, KEY_ENABLED)) {
        return;
      }
      if (!format().equals(current)) {
        checkNow();
        return;
      }
      int level = level(ctx);
      if (level >= 0) {
        remember(ctx, current, level);
      }
    } catch (Throwable t) {
      log(Log.WARN, TAG, "record failed", t);
    }
  }

  private static void remember(Context ctx, String format, int level) {
    Settings.Global.putInt(ctx.getContentResolver(), KEY_PREFIX + format, level);
  }

  private String format() throws Throwable {
    switch ((Integer) getInfo.invoke(avMode, VIDEOINFO_HDR_TYPE)) {
      case 1:
      case 4:
      case 6:
        return "hdr10";
      case 2:
        return "hlg";
      case 3:
        return "dv";
      default:
        return "sdr";
    }
  }

  private static int level(Context ctx) {
    int v = Settings.Global.getInt(ctx.getContentResolver(), KEY_LEVEL, -1);
    return v > MAX_LEVEL ? -1 : v;
  }

  private static Context app() throws Throwable {
    return (Context)
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
  }
}
