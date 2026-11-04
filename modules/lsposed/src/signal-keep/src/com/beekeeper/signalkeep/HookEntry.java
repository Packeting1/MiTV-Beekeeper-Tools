package com.beekeeper.signalkeep;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.tv.TvView;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/*
 * 减少 PC 信号 (HDMI/DP) 与电视系统之间切换时的黑屏（tvplayer + com.mediatek.tvinput）：
 * 跳过播放器后台 30 秒自杀；按 HOME 回桌面时保留信号并静音，由看门狗决定继续保留还是补跑拆除。
 * 设置键/图像参数页的 onPause 交给 TvPlayerKeep（或原版），两者互不依赖。
 */
public final class HookEntry extends XposedModule {

  private static final String TAG = "SignalKeep";
  private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
  private static final String SETTINGS = "com.xiaomi.mitv.settings";
  private static final String TV_HOME = "com.mitv.tvhome";
  private static final String TVINPUT = "com.mediatek.tvinput";

  /** TvPlayerKeep 写的设置键标记，只读，用来避开它负责的流程。 */
  private static final String SETTINGS_FLAG = "settings_window_showing";

  private static final String SETTINGS_PENDING = "tvplayerkeep_settings_pending";

  /** 设置键标记到 onPause 通常只隔几十毫秒；取比 TvPlayerKeep 的 3 秒窗口短，免得把随后的 HOME 当成设置流程。 */
  private static final long PENDING_WINDOW_MS = 800;

  /** Settings.System，写 0 关闭回桌面保留信号；没写或其它值都开启。 */
  private static final String HOME_KEEP = "signalkeep_home";

  /** 播放器从「保留」恢复时写入的时间戳，MtkTvInput 据此允许重绑新 Surface。 */
  private static final String REBIND = "signalkeep_rebind";

  private static final long REBIND_WINDOW_MS = 5000;

  /** TV-Mute 状态；播放器进程被杀后下次回来仍据此补取消静音。 */
  private static final String MUTED = "signalkeep_muted";

  /** 停在桌面超过这个时间就拆除信号：保留着 4K160 信号时 HDMI RX/scaler 中断会多占约 16% CPU。 */
  private static final long HOME_HOLD_MS = 60000;

  /* 看门狗每 100ms 判断一次桌面，默认桌面几乎不变，短时缓存避免反复查 PackageManager。 */
  private static final long HOME_PKG_TTL_MS = 5000;

  private volatile String homePkg;
  private volatile long homePkgAt = -1_000_000L;

  private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());
  private final Set<Object> kept =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

  /** 每个 Activity 当前有效的看门狗令牌；重新 onPause 会换新令牌，让旧的看门狗退出。 */
  private final Map<Object, Object> watchers =
      Collections.synchronizedMap(new WeakHashMap<Object, Object>());

  private volatile long leaveHintAt;

  @Override
  public void onModuleLoaded(ModuleLoadedParam param) {
    log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
  }

  @Override
  public void onPackageLoaded(PackageLoadedParam param) {
    String pkg = param.getPackageName();
    if (!(PLAYER.equals(pkg) || TVINPUT.equals(pkg)) || !HOOKED.add(pkg)) {
      return;
    }
    try {
      if (PLAYER.equals(pkg)) {
        installPlayer(param.getDefaultClassLoader());
      } else {
        installTvInput(param.getDefaultClassLoader());
      }
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "install failed in " + pkg, t);
    }
  }

  /** MtkTvInput 对已打开的流 (mStreamOpened) 忽略新 Surface，保留信号回来时不重绑会黑屏；恢复后的短窗口内清掉标志。 */
  private void installTvInput(ClassLoader cl) throws Throwable {
    Class<?> sess =
        cl.loadClass(
            "com.mediatek.tvinputservice.AbstractTvInputServiceBase$AbstractTvInputSession");
    Field opened = sess.getDeclaredField("mStreamOpened");
    opened.setAccessible(true);
    Method onSetSurface = sess.getDeclaredMethod("onSetSurface", Surface.class);
    hook(onSetSurface)
        .intercept(chain -> {
          try {
            Object s = chain.getThisObject();
            if (chain.getArg(0) != null && opened.getBoolean(s) && rebindRequested()) {
              opened.setBoolean(s, false);
              log(Log.INFO, TAG, "MtkTvInput: stream was open, allow rebind to new surface");
            }
          } catch (Throwable t) {
            log(Log.ERROR, TAG, "MtkTvInput rebind check failed", t);
          }
          return chain.proceed();
        });
    log(Log.INFO, TAG, "MtkTvInput hook installed");
  }

  /** stamp 是毫秒时间戳字符串，距今不超过 windowMs。 */
  private static boolean within(String stamp, long windowMs) {
    if (stamp == null) {
      return false;
    }
    try {
      long age = System.currentTimeMillis() - Long.parseLong(stamp);
      return age >= 0 && age < windowMs;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private boolean rebindRequested() {
    try {
      Class<?> at = Class.forName("android.app.ActivityThread");
      Context ctx = (Context) at.getMethod("currentApplication").invoke(null);
      return within(Settings.System.getString(ctx.getContentResolver(), REBIND), REBIND_WINDOW_MS);
    } catch (Throwable t) {
      return false;
    }
  }

  private void installPlayer(ClassLoader cl) throws Throwable {
    Class<?> act = cl.loadClass(PLAYER + ".ExternalSourceActivity");
    Class<?> frag = cl.loadClass("android.support.v4.app.FragmentActivity");

    Method onPause = act.getDeclaredMethod("onPause");
    Method onResume = act.getDeclaredMethod("onResume");
    Method onStop = act.getDeclaredMethod("onStop");
    Method fragPause = frag.getDeclaredMethod("onPause");
    Method fragResume = frag.getDeclaredMethod("onResume");
    XposedInterface.Invoker<?, Method> superPause = getInvoker(fragPause);
    XposedInterface.Invoker<?, Method> superResume = getInvoker(fragResume);
    XposedInterface.Invoker<?, Method> origPause =
        getInvoker(onPause).setType(XposedInterface.Invoker.Type.ORIGIN);

    hook(Activity.class.getDeclaredMethod("onUserLeaveHint"))
        .intercept(chain -> {
          if (chain
              .getThisObject()
              .getClass()
              .getName()
              .equals(PLAYER + ".ExternalSourceActivity")) {
            leaveHintAt = System.currentTimeMillis();
          }
          return chain.proceed();
        });

    // 比 TvPlayerKeep 的 onPause 先执行：它的设置键标记有 3 秒窗口，窗口内按 HOME 会被它当成设置流程拆掉信号。
    hook(onPause)
        .setPriority(XposedInterface.PRIORITY_HIGHEST)
        .intercept(chain -> {
          Activity a = (Activity) chain.getThisObject();
          // onPause 时 AMS 还没把新的 Activity 记为顶层，查不到去向；只能先按「用户主动离开」保留，
          // 由看门狗在去向明确后决定：回桌面/设置继续保留，去别的应用或息屏就补跑拆除。
          if (!settingsSignal(a)
              && System.currentTimeMillis() - leaveHintAt < 1500
              && homeKeepEnabled(a)
              && isAwake(a)) {
            log(
                Log.INFO,
                TAG,
                "onPause: user left player, keep PC signal (muted), watchdog decides");
            kept.add(a);
            audioMute(a, true);
            hideSearching(a);
            superPause.invokeSpecial(a);
            startWatchdog(a, origPause, true);
            return null;
          }
          kept.remove(a);
          return chain.proceed();
        });

    hook(onResume)
        .intercept(chain -> {
          Activity a = (Activity) chain.getThisObject();
          if (kept.contains(a) && !hasSignal()) {
            log(Log.INFO, TAG, "onResume: held signal was lost, full re-init");
            teardown(a, origPause);
          }
          if (kept.remove(a)) {
            log(Log.INFO, TAG, "onResume: playback kept, skip re-init");
            try {
              Settings.System.putString(
                  a.getContentResolver(), REBIND, String.valueOf(System.currentTimeMillis()));
            } catch (Throwable t) {
              log(Log.ERROR, TAG, "rebind mark failed", t);
            }
            audioMute(a, false);
            superResume.invokeSpecial(a);
            return null;
          }
          audioMute(a, false);
          return chain.proceed();
        });

    hook(onStop)
        .intercept(chain -> {
          Activity a = (Activity) chain.getThisObject();
          if (kept.contains(a)) {
            String top = topClass(a);
            if (continuesSettingsFlow(top) || isHomeTop(a)) {
              log(Log.INFO, TAG, "onStop while kept, top=" + top + ": keep signal, watchdog on");
              startWatchdog(a, origPause, false);
            } else {
              log(Log.INFO, TAG, "onStop while kept, top=" + top + ": deferred teardown");
              teardown(a, origPause);
            }
          }
          return chain.proceed();
        });

    // 保留期间 Activity 被盖住时 TvView 会把空 Surface 交给输入服务，输入服务据此释放 HDMI。拦下这一次。
    int n = 0;
    for (Method m : TvView.class.getDeclaredMethods()) {
      if (m.getName().equals("setSessionSurface") && m.getParameterTypes().length == 1) {
        hook(m)
            .intercept(chain -> {
              if (chain.getArgs().get(0) == null && !kept.isEmpty()) {
                log(Log.INFO, TAG, "TvView: null surface held back while signal is kept");
                return null;
              }
              return chain.proceed();
            });
        n++;
      }
    }

    // BaseActivity.mKillSelf 的 killBackGround：不跳过则下次进 PC 信号是冷启动 (多约 0.9 秒)。
    try {
      Method kill = cl.loadClass(PLAYER + ".BaseActivity").getDeclaredMethod("killBackGround");
      hook(kill)
          .intercept(chain -> {
            log(Log.INFO, TAG, "killBackGround skipped, player stays warm");
            return null;
          });
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "killBackGround hook failed", t);
    }

    // 后台保留信号时 PC 若休眠/断开，无信号倒计时走完会让播放器直接关机；保留期间不显示它。
    try {
      Method show =
          cl.loadClass(PLAYER + ".widget.views.SearchingView")
              .getDeclaredMethod("show", Handler.class);
      hook(show)
          .intercept(chain -> {
            if (!kept.isEmpty()) {
              log(Log.INFO, TAG, "SearchingView.show skipped while signal is held in background");
              return null;
            }
            return chain.proceed();
          });
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "SearchingView hook failed", t);
    }

    log(Log.INFO, TAG, "player hooks installed, tvview hooks=" + n);
  }

  /** 离开时无信号倒计时可能已经在跑，走完会 shutDown()；原版 onPause 拆除时同样会先 hide()。 */
  private void hideSearching(Activity a) {
    try {
      for (Class<?> c = a.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
        Field f;
        try {
          f = c.getDeclaredField("mSearchingView");
        } catch (NoSuchFieldException ignored) {
          continue;
        }
        f.setAccessible(true);
        Object v = f.get(a);
        if (v != null) {
          v.getClass().getMethod("hide").invoke(v);
        }
        return;
      }
      log(Log.WARN, TAG, "mSearchingView not found");
    } catch (Throwable t) {
      log(Log.WARN, TAG, "hide SearchingView failed", t);
    }
  }

  private static boolean isMainEntryClass(String cls) {
    return (SETTINGS + ".entry.MainActivity").equals(cls)
        || (SETTINGS + ".entry.TranslucentMainActivity").equals(cls);
  }

  /** 读不到顶层 Activity 时按「仍在设置主入口」处理，由看门狗继续观察，不贸然拆除。 */
  private static boolean continuesSettingsFlow(String topClass) {
    return topClass == null || isMainEntryClass(topClass);
  }

  /** 设置键/图像参数页由 TvPlayerKeep (或原版) 处理，这里不接管。 */
  private static boolean settingsSignal(Activity a) {
    ContentResolver cr = a.getContentResolver();
    try {
      if (Settings.System.getInt(cr, SETTINGS_FLAG, 0) == 1
          || within(Settings.System.getString(cr, SETTINGS_PENDING), PENDING_WINDOW_MS)) {
        return true;
      }
    } catch (Throwable ignored) {
    }
    return isMainEntryClass(topClass(a));
  }

  private static ComponentName topActivity(Activity a) {
    try {
      ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
      List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
      if (tasks != null && !tasks.isEmpty()) {
        return tasks.get(0).topActivity;
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private static String topClass(Activity a) {
    ComponentName top = topActivity(a);
    return top == null ? null : top.getClassName();
  }

  private static boolean homeKeepEnabled(Activity a) {
    try {
      return Settings.System.getInt(a.getContentResolver(), HOME_KEEP, 1) != 0;
    } catch (Throwable t) {
      return true;
    }
  }

  /** 最顶层任务是不是默认桌面 (Aerial 或系统桌面)。 */
  private boolean isHomeTop(Activity a) {
    if (!homeKeepEnabled(a)) {
      return false;
    }
    ComponentName top = topActivity(a);
    if (top == null) {
      return false;
    }
    String pkg = top.getPackageName();
    if (TV_HOME.equals(pkg)) {
      return true;
    }
    long now = SystemClock.elapsedRealtime();
    if (now - homePkgAt > HOME_PKG_TTL_MS) {
      try {
        ResolveInfo ri =
            a.getPackageManager()
                .resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        homePkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
      } catch (Throwable t) {
        homePkg = null;
      }
      homePkgAt = now;
    }
    return pkg.equals(homePkg);
  }

  /**
   * 只静音 PC 信号：音频 HAL 的 "TV-Mute" 会释放/重建输入源的 audio patch，不影响应用混音 (dumpsys 实测)。
   * TvView.setStreamVolume(0) 无效 (HDMI In 增益只有 0~1 dB)，静音媒体流又会连应用声音一起静掉。
   */
  private void audioMute(Context c, boolean mute) {
    try {
      ContentResolver cr = c.getContentResolver();
      boolean muted = Settings.System.getInt(cr, MUTED, 0) == 1;
      if (mute == muted) {
        return;
      }
      Class<?> as = Class.forName("android.media.AudioSystem");
      Object r =
          as.getMethod("setParameters", String.class)
              .invoke(null, mute ? "TV-Mute=1" : "TV-Mute=0");
      Settings.System.putInt(cr, MUTED, mute ? 1 : 0);
      log(Log.INFO, TAG, "TV-Mute=" + (mute ? 1 : 0) + " ret=" + r);
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "audioMute failed", t);
    }
  }

  /**
   * 播放器每次 onSourceSignalStable 都会写 sys.inputsource.have_signal。保留期间 Aerial 视频壁纸 (4K)
   * 会用硬件解码器抢占主视频通道并上报 WEAK_SIGNAL，被抢占的会话只能拆掉重来。
   */
  private static boolean hasSignal() {
    try {
      Class<?> sp = Class.forName("android.os.SystemProperties");
      return !"false"
          .equals(sp.getMethod("get", String.class).invoke(null, "sys.inputsource.have_signal"));
    } catch (Throwable t) {
      return true;
    }
  }

  private void teardown(Activity a, XposedInterface.Invoker<?, Method> origPause) {
    if (!kept.remove(a)) {
      return;
    }
    audioMute(a, false);
    try {
      origPause.invoke(a);
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "deferred teardown failed", t);
    }
  }

  private static boolean isAwake(Activity a) {
    try {
      return ((PowerManager) a.getSystemService(Context.POWER_SERVICE)).isInteractive();
    } catch (Throwable ignored) {
      return true;
    }
  }

  /** 播放器被盖住期间每 100ms 查一次：仍在设置/桌面就继续保留，去了别的应用或息屏就补跑拆除。 fromPause 时顶层可能仍是播放器自己，最多宽限 1.5 秒。 */
  private void startWatchdog(
      Activity a, XposedInterface.Invoker<?, Method> origPause, boolean fromPause) {
    Object token = new Object();
    // onPause 总是接管旧的看门狗 (HOME→PC→HOME 间隔很短时旧的还没退出)；onStop 只在没有时才补一个。
    if (fromPause) {
      watchers.put(a, token);
    } else if (watchers.putIfAbsent(a, token) != null) {
      return;
    }
    Handler h = new Handler(Looper.getMainLooper());
    h.post(
        new Runnable() {
          int idle = 0;
          int grace = 15;
          boolean muted = fromPause;
          String lastTop = "";
          long homeSince;

          @Override
          public void run() {
            if (!kept.contains(a) || watchers.get(a) != token) {
              watchers.remove(a, token);
              return;
            }
            if (!hasSignal()) {
              log(Log.INFO, TAG, "watchdog: held signal was lost, release it");
              watchers.remove(a, token);
              teardown(a, origPause);
              return;
            }
            String top = topClass(a);
            if (top != null && !top.equals(lastTop)) {
              log(Log.INFO, TAG, "watchdog top=" + top);
              lastTop = top;
            }
            boolean awake = isAwake(a);
            boolean home = isHomeTop(a);
            boolean settingsTop = isMainEntryClass(top);
            boolean playerTop = top != null && top.startsWith(PLAYER + ".");
            boolean pending = playerTop && grace-- > 0;
            boolean active =
                awake
                    && !a.isDestroyed()
                    && ((continuesSettingsFlow(top) && !playerTop) || home || pending);
            if (settingsTop) {
              if (muted) {
                muted = false;
                audioMute(a, false);
              }
            } else if (home && !muted) {
              muted = true;
              audioMute(a, true);
            }
            if (home) {
              long now = SystemClock.elapsedRealtime();
              if (homeSince == 0) {
                homeSince = now;
              } else if (now - homeSince >= HOME_HOLD_MS) {
                log(
                    Log.INFO,
                    TAG,
                    "watchdog: idle on home for "
                        + (now - homeSince)
                        + " ms, release PC signal to save CPU");
                watchers.remove(a, token);
                teardown(a, origPause);
                return;
              }
            } else {
              homeSince = 0;
            }
            idle = active ? 0 : idle + 1;
            if (idle >= 2) {
              log(
                  Log.INFO,
                  TAG,
                  "watchdog: home/settings left (top=" + top + ", awake=" + awake + "), teardown");
              watchers.remove(a, token);
              teardown(a, origPause);
              return;
            }
            h.postDelayed(this, 100);
          }
        });
  }
}
