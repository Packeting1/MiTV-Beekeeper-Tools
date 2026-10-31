package com.beekeeper.castpip;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvView;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/*
 * 投屏小窗叠在 PC 信号 (HDMI) 上：投屏页盖住播放器时不拆 HDMI，进小窗后 PC 画面和投屏画面同时可见，
 * PC 声音和投屏声音混合输出。
 *
 * - tvplayer：投屏页启动前 smartshare 写一个时间戳标记，播放器 onPause 看到标记 (或最顶层是投屏页) 就不拆除；
 *   投屏页把 SurfaceView 销毁过 (onStop) 的话，回来时走原版拆除再重建。
 * - smartshare：打标记、小窗时不静音投屏、投屏页恢复后自动进小窗。
 * - mitv.service：前台包是播放器时把被它拆掉的投屏音频通道补回来。
 *
 * 黑屏修复 (系统设置) 是独立模块 TvPlayerKeep，两个模块各用各的标记和状态，可以单独启用。
 */
public final class HookEntry extends XposedModule {

  private static final String TAG = "CastPip";
  private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
  private static final String SMARTSHARE = "com.xiaomi.mitv.smartshare";
  private static final String TVSERVICE = "mitv.service";
  private static final String BASE_PIP_CAST = SMARTSHARE + ".dlna.dmr.ui.BasePipCastActivity";
  private static final String[] CAST_PLAYER_ACTIVITIES = {
    SMARTSHARE + ".mairplay.AirPlayerActivity",
    SMARTSHARE + ".dlna.dmr.ui.MultiPlayerActivity",
    SMARTSHARE + ".dlna.dmr.ui.DMRImageActivity",
    SMARTSHARE + ".dlna.dmr.ui.DMRMusicPlayerActivity",
    SMARTSHARE + ".wfd.WfdPlayerActivity"
  };
  private static final String[] AUTO_PIP_ACTIVITIES = {
    SMARTSHARE + ".mairplay.AirPlayerActivity", SMARTSHARE + ".dlna.dmr.ui.MultiPlayerActivity"
  };
  private static final String PENDING = "castpip_launch_pending";
  private static final long PENDING_WINDOW_MS = 3000;

  private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());
  private final Set<Object> kept =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

  /** 被投屏页完全盖住并 onStop 过的播放器：SurfaceView 已销毁，输入服务不会把新 Surface 交给硬件。 */
  private final Set<Object> surfaceLost =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

  private final Set<Object> autoPipDone =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));
  private final Set<Object> watching =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

  @Override
  public void onModuleLoaded(ModuleLoadedParam param) {
    log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
  }

  @Override
  public void onPackageLoaded(PackageLoadedParam param) {
    String pkg = param.getPackageName();
    if (!(PLAYER.equals(pkg) || SMARTSHARE.equals(pkg) || TVSERVICE.equals(pkg))
        || !HOOKED.add(pkg)) {
      return;
    }
    try {
      if (TVSERVICE.equals(pkg)) {
        installTvService(param.getDefaultClassLoader());
      } else if (PLAYER.equals(pkg)) {
        installPlayer(param.getDefaultClassLoader());
      } else {
        installCast(param.getDefaultClassLoader());
      }
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "install failed in " + pkg, t);
    }
  }

  /**
   * TvService：前台包变成播放器时它会开 mitv_source (信号源声音) 并拆掉投屏的音频通道。 投屏进行中 (sys.cast.state != 0)
   * 时在原逻辑之后把投屏音频通道补回来，信号源声音和投屏声音混合输出。
   */
  private void installTvService(ClassLoader cl) throws Throwable {
    Class<?> svc = cl.loadClass("mitv.service.TvService");
    final Method actions = svc.getDeclaredMethod("doActionsForPkg", String.class);
    final Field patchF = svc.getDeclaredField("mMtkAudioPatchManager");
    final Field inShareF = svc.getDeclaredField("mCurrentInSmartShare");
    patchF.setAccessible(true);
    inShareF.setAccessible(true);
    final Method sysGet =
        Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
    hook(actions)
        .intercept(chain -> {
          Object r = chain.proceed();
          try {
            Object pkgArg = chain.getArgs().get(0);
            if (PLAYER.equals(pkgArg)) {
              String st = (String) sysGet.invoke(null, "sys.cast.state", "0");
              Object mgr = patchF.get(chain.getThisObject());
              if (!"0".equals(st) && mgr != null) {
                mgr.getClass().getMethod("createAudioPatch").invoke(mgr);
                inShareF.setBoolean(chain.getThisObject(), true);
                log(
                    Log.INFO,
                    TAG,
                    "tvservice: cast audio patch kept with player foreground, state=" + st);
              }
            }
          } catch (Throwable t) {
            log(Log.ERROR, TAG, "tvservice audio hook failed", t);
          }
          return r;
        });
    log(Log.INFO, TAG, "tvservice hook installed");
  }

  /**
   * smartshare 进程：(1) 投屏页启动前写时间戳标记，让 PC 信号的播放器暂停时不拆 HDMI； (2) 进小窗时不静音投屏； (3) 投屏页恢复后，若系统开启了小窗投屏
   * (sys_cast_pip_disable != 1) 且正在全屏播放 (sys_cast_state == 1)， 调用 BasePipCastActivity.m(float)
   * 自动进画中画，与遥控器菜单键双击走的是同一个入口。
   */
  private void installCast(ClassLoader cl) throws Throwable {
    int n = 0;
    for (Method m : Instrumentation.class.getDeclaredMethods()) {
      if (!m.getName().equals("execStartActivity")) {
        continue;
      }
      hook(m)
          .intercept(chain -> {
            try {
              for (Object o : chain.getArgs()) {
                if (o instanceof Intent) {
                  Intent it = (Intent) o;
                  String cls = it.getComponent() != null ? it.getComponent().getClassName() : null;
                  if (isCastPlayerClass(cls)) {
                    markPending();
                  }
                  break;
                }
              }
            } catch (Throwable t) {
              log(Log.ERROR, TAG, "cast launch marker failed", t);
            }
            return chain.proceed();
          });
      n++;
    }

    // 进入小窗时 AirPlayService 会把投屏静音 (J0(session, inPip))。要和 PC 声音混合，就不让它静音。
    try {
      Class<?> svc = cl.loadClass(SMARTSHARE + ".mairplay.AirPlayService");
      Method mute = svc.getDeclaredMethod("J0", String.class, boolean.class);
      hook(mute)
          .intercept(chain -> {
            if (Boolean.TRUE.equals(chain.getArgs().get(1))) {
              log(Log.INFO, TAG, "cast audio: mute on pip skipped");
              return null;
            }
            return chain.proceed();
          });
    } catch (Throwable t) {
      log(Log.WARN, TAG, "cast audio mute hook failed", t);
    }

    final Class<?> base = cl.loadClass(BASE_PIP_CAST);
    final Method pipEntry = base.getDeclaredMethod("m", float.class);
    pipEntry.setAccessible(true);
    final Method onResume = base.getDeclaredMethod("onResume");
    hook(onResume)
        .intercept(chain -> {
          Object r = chain.proceed();
          try {
            Activity a = (Activity) chain.getThisObject();
            String name = a.getClass().getName();
            for (String s : AUTO_PIP_ACTIVITIES) {
              if (s.equals(name)) {
                scheduleAutoPip(a, pipEntry);
                break;
              }
            }
          } catch (Throwable t) {
            log(Log.ERROR, TAG, "auto pip schedule failed", t);
          }
          return r;
        });
    log(Log.INFO, TAG, "cast hooks installed: launch=" + n + " autoPip=" + pipEntry.getName());
  }

  private void scheduleAutoPip(final Activity a, final Method pipEntry) {
    if (!autoPipDone.add(a)) {
      return;
    }
    final Handler h = new Handler(Looper.getMainLooper());
    h.postDelayed(
        new Runnable() {
          int tries = 0;

          @Override
          public void run() {
            try {
              if (a.isFinishing() || a.isDestroyed() || a.isInPictureInPictureMode()) {
                return;
              }
              ContentResolver cr = a.getContentResolver();
              if (Settings.Global.getInt(cr, "sys_cast_pip_disable", 0) == 1) {
                return;
              }
              if ("1".equals(Settings.Global.getString(cr, "sys_cast_state"))) {
                log(
                    Log.INFO,
                    TAG,
                    "auto pip: entering for " + a.getClass().getSimpleName() + " try=" + tries);
                pipEntry.invoke(a, -1f);
                return;
              }
            } catch (Throwable t) {
              log(Log.ERROR, TAG, "auto pip failed", t);
            }
            if (++tries < 8) {
              h.postDelayed(this, 600);
            }
          }
        },
        150);
  }

  /** 小窗进得比播放器 onStop 早时没有 surfaceLost 记录，但画面同样已不可用，按投屏状态兜底。 */
  private static boolean castActive(Activity a) {
    try {
      String s = Settings.Global.getString(a.getContentResolver(), "sys_cast_state");
      return s != null && !s.equals("0");
    } catch (Throwable t) {
      return false;
    }
  }

  private static boolean isCastPlayerClass(String cls) {
    if (cls == null) {
      return false;
    }
    for (String s : CAST_PLAYER_ACTIVITIES) {
      if (s.equals(cls)) {
        return true;
      }
    }
    return false;
  }

  private void markPending() {
    try {
      Class<?> at = Class.forName("android.app.ActivityThread");
      Context ctx = (Context) at.getMethod("currentApplication").invoke(null);
      Settings.System.putString(
          ctx.getContentResolver(), PENDING, String.valueOf(System.currentTimeMillis()));
      log(Log.INFO, TAG, "smartshare: cast launch marked");
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "smartshare: mark failed", t);
    }
  }

  private boolean castSignal(Activity a) {
    ContentResolver cr = a.getContentResolver();
    boolean fresh = false;
    long age = -1;
    try {
      String s = Settings.System.getString(cr, PENDING);
      if (s != null) {
        age = System.currentTimeMillis() - Long.parseLong(s);
        fresh = age >= 0 && age < PENDING_WINDOW_MS;
      }
    } catch (Throwable ignored) {
    }
    String top = topClass(a);
    boolean topIsCast = isCastPlayerClass(top);
    boolean showing = fresh || topIsCast;
    log(
        Log.INFO,
        TAG,
        "onPause signals pendingAgeMs=" + age + " top=" + top + " -> keep=" + showing);
    return showing;
  }

  private void installPlayer(ClassLoader cl) throws Throwable {
    Class<?> act = cl.loadClass(PLAYER + ".ExternalSourceActivity");
    Class<?> frag = cl.loadClass("android.support.v4.app.FragmentActivity");

    final Method onPause = act.getDeclaredMethod("onPause");
    final Method onResume = act.getDeclaredMethod("onResume");
    final Method onStop = act.getDeclaredMethod("onStop");
    final Method fragPause = frag.getDeclaredMethod("onPause");
    final Method fragResume = frag.getDeclaredMethod("onResume");
    final XposedInterface.Invoker<?, Method> superPause = getInvoker(fragPause);
    final XposedInterface.Invoker<?, Method> superResume = getInvoker(fragResume);
    final XposedInterface.Invoker<?, Method> origPause =
        getInvoker(onPause).setType(XposedInterface.Invoker.Type.ORIGIN);

    hook(onPause)
        .intercept(chain -> {
          Activity a = (Activity) chain.getThisObject();
          surfaceLost.remove(a);
          if (castSignal(a)) {
            kept.add(a);
            superPause.invokeSpecial(a);
            return null;
          }
          kept.remove(a);
          return chain.proceed();
        });

    hook(onResume)
        .intercept(chain -> {
          Activity a = (Activity) chain.getThisObject();
          if (kept.contains(a) && (surfaceLost.remove(a) || castActive(a))) {
            // 输入服务对已打开的流忽略新 Surface (streamOpened=true)，视频仍绑在已销毁的窗口上，表现为黑屏。
            // 走原版「拆除再重建」，让硬件重新绑定新 Surface。
            log(Log.INFO, TAG, "onResume: surface was destroyed under cast, full re-init");
            teardown(a, origPause);
            return chain.proceed();
          }
          if (kept.remove(a)) {
            log(Log.INFO, TAG, "onResume: playback kept, skip re-init");
            superResume.invokeSpecial(a);
            return null;
          }
          return chain.proceed();
        });

    hook(onStop)
        .intercept(chain -> {
          Activity a = (Activity) chain.getThisObject();
          if (kept.contains(a)) {
            String top = topClass(a);
            if (top == null || isCastPlayerClass(top)) {
              log(Log.INFO, TAG, "onStop while kept, top=" + top + ": keep HDMI, watchdog on");
              if (isCastPlayerClass(top)) {
                surfaceLost.add(a);
              }
              startWatchdog(a, origPause);
            } else {
              log(Log.INFO, TAG, "onStop while kept, top=" + top + ": deferred teardown");
              teardown(a, origPause);
            }
          }
          return chain.proceed();
        });

    // 投屏页是不透明的独立 Activity，会销毁 SurfaceView；TvView 随后把空 Surface 交给
    // 输入服务，输入服务才会释放 HDMI。保留期间拦下这一次空 Surface。
    int n = 0;
    for (Method m : TvView.class.getDeclaredMethods()) {
      if (m.getName().equals("setSessionSurface") && m.getParameterTypes().length == 1) {
        hook(m)
            .intercept(chain -> {
              if (chain.getArgs().get(0) == null && !kept.isEmpty()) {
                log(Log.INFO, TAG, "TvView: null surface held back while cast active");
                return null;
              }
              return chain.proceed();
            });
        n++;
      }
    }

    // 投屏时播放器一旦 onStop 就 finishAndRemoveTask 并杀进程，小窗下面没有播放器任务，只能回到桌面。
    // 保留期间 (投屏页盖住播放器) 跳过这一步；其余情况仍走原逻辑。
    try {
      final Method checkCast = act.getDeclaredMethod("checkScreenCast");
      hook(checkCast)
          .intercept(chain -> {
            if (kept.contains(chain.getThisObject())) {
              log(Log.INFO, TAG, "checkScreenCast skipped: player kept for cast");
              return null;
            }
            return chain.proceed();
          });
    } catch (NoSuchMethodException e) {
      log(Log.WARN, TAG, "checkScreenCast not found");
    }

    // 4K 且刷新率 >120Hz 的 HDMI 输入会让播放器广播 EXIT_CURRENT_PIP，把正在进行的投屏小窗关掉。
    try {
      final Method mutex = act.getDeclaredMethod("isMiraCastMutualExclusion");
      hook(mutex)
          .intercept(chain -> {
            log(Log.INFO, TAG, "isMiraCastMutualExclusion -> false (cast kept)");
            return Boolean.FALSE;
          });
    } catch (NoSuchMethodException e) {
      log(Log.WARN, TAG, "isMiraCastMutualExclusion not found");
    }
    // 中间件 SourceManagerImpl 里还有一份更严格的同名判断 (DLG / EDID 版本 / 高帧率 VRR / 4K>120Hz)，
    // 命中时同样会发 EXIT_CURRENT_PIP。
    try {
      Class<?> sm = cl.loadClass("mitv.impl.tv.SourceManagerImpl");
      final Method mutex2 = sm.getDeclaredMethod("isMiraCastMutualExclusion");
      hook(mutex2)
          .intercept(chain -> {
            log(Log.INFO, TAG, "SourceManagerImpl.isMiraCastMutualExclusion -> false (cast kept)");
            return Boolean.FALSE;
          });
    } catch (Throwable e) {
      log(Log.WARN, TAG, "SourceManagerImpl.isMiraCastMutualExclusion hook failed", e);
    }

    log(Log.INFO, TAG, "player hooks installed, tvview hooks=" + n);
  }

  private static String topClass(Activity a) {
    try {
      ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
      List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
      if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
        return tasks.get(0).topActivity.getClassName();
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private void teardown(Activity a, XposedInterface.Invoker<?, Method> origPause) {
    if (!kept.remove(a)) {
      return;
    }
    try {
      origPause.invoke(a);
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "deferred teardown failed", t);
    }
  }

  /** 播放器被完全盖住后，每秒查一次：投屏页已离开前台（回桌面等）或息屏，就补跑拆除。 */
  private void startWatchdog(final Activity a, final XposedInterface.Invoker<?, Method> origPause) {
    if (!watching.add(a)) {
      return;
    }
    final Handler h = new Handler(Looper.getMainLooper());
    h.post(
        new Runnable() {
          int idle = 0;
          String lastTop = "";

          @Override
          public void run() {
            if (!kept.contains(a)) {
              watching.remove(a);
              return;
            }
            String top = topClass(a);
            if (top != null && !top.equals(lastTop)) {
              log(Log.INFO, TAG, "watchdog top=" + top);
              lastTop = top;
            }
            boolean awake = true;
            try {
              awake = ((PowerManager) a.getSystemService(Context.POWER_SERVICE)).isInteractive();
            } catch (Throwable ignored) {
            }
            boolean active = awake && !a.isDestroyed() && (top == null || isCastPlayerClass(top));
            idle = active ? 0 : idle + 1;
            if (idle >= 2) {
              log(
                  Log.INFO,
                  TAG,
                  "watchdog: cast page left (top=" + top + ", awake=" + awake + "), teardown");
              watching.remove(a);
              teardown(a, origPause);
              return;
            }
            h.postDelayed(this, 1000);
          }
        });
  }
}
