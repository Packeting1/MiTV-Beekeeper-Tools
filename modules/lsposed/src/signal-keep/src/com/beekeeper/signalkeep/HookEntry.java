package com.beekeeper.signalkeep;

import android.app.Activity;
import android.app.ActivityManager;
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
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 减少 PC 信号 (HDMI/DP) 与电视系统之间切换时的黑屏。作用域：tvplayer + com.mediatek.tvinput。
 *
 * A. 播放器不在前台 30 秒后会自己 killProcess (BaseActivity.mKillSelf)，下次进 PC 信号是冷启动。跳过它。
 * B. 按 HOME 回桌面时不拆 PC 信号：onPause 保留、媒体流静音，看门狗确认去向仍是桌面/设置才继续保留，
 *    去别的应用或息屏就补跑原始 onPause 拆除。回到 PC 时在 MtkTvInput 里允许把新 Surface 重绑到已打开的流。
 *
 * 和 TvPlayerKeep (设置键/图像参数页不黑屏) 互不依赖：设置相关的 onPause 交给它 (或原版)，本模块只处理
 * 「用户离开播放器」。
 */
public final class HookEntry extends XposedModule {

    private static final String TAG = "SignalKeep";
    private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String SETTINGS = "com.xiaomi.mitv.settings";
    private static final String TV_HOME = "com.mitv.tvhome";
    private static final String TVINPUT = "com.mediatek.tvinput";
    /** TvPlayerKeep 写的设置键标记，只读用来避开它负责的流程。 */
    private static final String SETTINGS_FLAG = "settings_window_showing";
    private static final String SETTINGS_PENDING = "tvplayerkeep_settings_pending";
    /** 设置键标记到 onPause 通常只隔几十毫秒；比 TvPlayerKeep 的 3 秒窗口短，避免把紧随其后的 HOME 当成设置流程。 */
    private static final long PENDING_WINDOW_MS = 800;
    /** Settings.System，写 0 关闭 B (回桌面保留信号)；没写或其它值都开启。A 不受影响。 */
    private static final String HOME_KEEP = "signalkeep_home";
    /** 播放器从「保留」恢复时写入的时间戳，MtkTvInput 据此允许一次把新 Surface 重绑到已打开的流。 */
    private static final String REBIND = "signalkeep_rebind";
    private static final String MUTED = "signalkeep_muted";
    private static final long REBIND_WINDOW_MS = 5000;
    /** 停在桌面超过这个时间就拆除信号：保留着 4K160 信号时 HDMI RX/scaler 中断会多占约 16% CPU。 */
    private static final long HOME_HOLD_MS = 60000;

    private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());
    private final Set<Object> kept = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));
    private final Set<Object> watching = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));
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

    /**
     * MtkTvInput 对已打开的流会忽略新的 Surface (mStreamOpened=true → 不调 doSetSurfaceToHardware)，
     * 保留信号回来时 Surface 已重建，不重绑就黑屏。播放器恢复后的短时间窗内，把标志清掉让它重绑一次。
     */
    private void installTvInput(ClassLoader cl) throws Throwable {
        Class<?> sess = cl.loadClass("com.mediatek.tvinputservice.AbstractTvInputServiceBase$AbstractTvInputSession");
        final java.lang.reflect.Field opened = sess.getDeclaredField("mStreamOpened");
        opened.setAccessible(true);
        Method onSetSurface = sess.getDeclaredMethod("onSetSurface", android.view.Surface.class);
        hook(onSetSurface).intercept(chain -> {
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

    private boolean rebindRequested() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Context ctx = (Context) at.getMethod("currentApplication").invoke(null);
            String s = Settings.System.getString(ctx.getContentResolver(), REBIND);
            if (s == null) {
                return false;
            }
            long age = System.currentTimeMillis() - Long.parseLong(s);
            return age >= 0 && age < REBIND_WINDOW_MS;
        } catch (Throwable t) {
            return false;
        }
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

        hook(Activity.class.getDeclaredMethod("onUserLeaveHint")).intercept(chain -> {
            if (chain.getThisObject().getClass().getName().equals(PLAYER + ".ExternalSourceActivity")) {
                leaveHintAt = System.currentTimeMillis();
            }
            return chain.proceed();
        });

        // 比 TvPlayerKeep 的 onPause 先执行：它的设置键标记有 3 秒窗口，窗口内按 HOME 会被它当成设置流程拆掉信号。
        hook(onPause).setPriority(XposedInterface.PRIORITY_HIGHEST).intercept(chain -> {
            Activity a = (Activity) chain.getThisObject();
            // onPause 时 AMS 还没把新的 Activity 记为顶层，查不到去向；只能先按「用户主动离开」保留，
            // 由看门狗在去向明确后决定：回桌面/设置继续保留，去别的应用或息屏就补跑拆除。
            if (!settingsSignal(a) && System.currentTimeMillis() - leaveHintAt < 1500
                    && homeKeepEnabled(a) && isAwake(a)) {
                log(Log.INFO, TAG, "onPause: user left player, keep PC signal (muted), watchdog decides");
                kept.add(a);
                setMuted(a, true);
                hideSearching(a);
                superPause.invokeSpecial(a);
                startWatchdog(a, origPause, true);
                return null;
            }
            kept.remove(a);
            return chain.proceed();
        });

        hook(onResume).intercept(chain -> {
            Activity a = (Activity) chain.getThisObject();
            if (kept.remove(a)) {
                log(Log.INFO, TAG, "onResume: playback kept, skip re-init");
                try {
                    Settings.System.putString(a.getContentResolver(), REBIND,
                            String.valueOf(System.currentTimeMillis()));
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "rebind mark failed", t);
                }
                setMuted(a, false);
                superResume.invokeSpecial(a);
                return null;
            }
            audioMute(a, false);
            return chain.proceed();
        });

        hook(onStop).intercept(chain -> {
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
        Class<?> tvView = Class.forName("android.media.tv.TvView");
        int skipped = 0;
        for (Method m : tvView.getDeclaredMethods()) {
            if (m.getName().equals("setSessionSurface") && m.getParameterTypes().length == 1) {
                hook(m).intercept(chain -> {
                    if (chain.getArgs().get(0) == null && !kept.isEmpty()) {
                        log(Log.INFO, TAG, "TvView: null surface held back while signal is kept");
                        return null;
                    }
                    return chain.proceed();
                });
                skipped++;
            }
        }

        // 播放器在后台 30 秒后会自己 killProcess (BaseActivity.mKillSelf)，下次进 PC 信号是冷启动 (多约 0.9 秒)。
        try {
            Method kill = cl.loadClass(PLAYER + ".BaseActivity").getDeclaredMethod("killBackGround");
            hook(kill).intercept(chain -> {
                log(Log.INFO, TAG, "killBackGround skipped, player stays warm");
                return null;
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "killBackGround hook failed", t);
        }

        // 后台保留信号时 PC 若休眠/断开，无信号倒计时走完会让播放器直接关机；保留期间不显示它。
        try {
            Method show = cl.loadClass(PLAYER + ".widget.views.SearchingView")
                    .getDeclaredMethod("show", android.os.Handler.class);
            hook(show).intercept(chain -> {
                if (!kept.isEmpty()) {
                    log(Log.INFO, TAG, "SearchingView.show skipped while signal is held in background");
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "SearchingView hook failed", t);
        }

        log(Log.INFO, TAG, "player hooks installed, tvview hooks=" + skipped);
    }

    /** 离开时无信号倒计时可能已经在跑，走完会 shutDown()；原版 onPause 拆除时同样会先 hide()。 */
    private void hideSearching(Activity a) {
        try {
            for (Class<?> c = a.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                java.lang.reflect.Field f;
                try {
                    f = c.getDeclaredField("mSearchingView");
                } catch (NoSuchFieldException e) {
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
            if (Settings.System.getInt(cr, SETTINGS_FLAG, 0) == 1) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            String s = Settings.System.getString(cr, SETTINGS_PENDING);
            if (s != null) {
                long age = System.currentTimeMillis() - Long.parseLong(s);
                if (age >= 0 && age < PENDING_WINDOW_MS) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return isMainEntryClass(topClass(a));
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

    private static String topPackage(Activity a) {
        try {
            ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                return tasks.get(0).topActivity.getPackageName();
            }
        } catch (Throwable ignored) {
        }
        return null;
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
        String pkg = topPackage(a);
        if (pkg == null) {
            return false;
        }
        if (TV_HOME.equals(pkg)) {
            return true;
        }
        try {
            ResolveInfo ri = a.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            return ri != null && ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    private static TvView findTvView(View v) {
        if (v instanceof TvView) {
            return (TvView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TvView t = findTvView(g.getChildAt(i));
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private void setMuted(Activity a, boolean mute) {
        audioMute(a, mute);
        try {
            TvView tv = findTvView(a.getWindow().getDecorView());
            if (tv != null) {
                tv.setStreamVolume(mute ? 0f : 1f);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "setMuted failed", t);
        }
    }

    /**
     * 只静音 PC 信号：小米音频 HAL 的 "TV-Mute" 参数 (播放器自己切源时也在用) 会释放/重建输入源的
     * audio patch，不影响应用的混音通路 (dumpsys 实测 mix 通路不变)。TvView.setStreamVolume(0) 无效
     * (HDMI In 端口增益只有 0~1 dB)，静音媒体流又会把应用的声音一起静掉。
     * 静音状态记在 Settings.System，播放器进程被杀后下次回来仍会补取消，避免电视一直没声音。
     */
    private void audioMute(Context c, boolean mute) {
        try {
            ContentResolver cr = c.getContentResolver();
            boolean muted = Settings.System.getInt(cr, MUTED, 0) == 1;
            if (mute == muted) {
                return;
            }
            Class<?> as = Class.forName("android.media.AudioSystem");
            Object r = as.getMethod("setParameters", String.class).invoke(null, mute ? "TV-Mute=1" : "TV-Mute=0");
            Settings.System.putInt(cr, MUTED, mute ? 1 : 0);
            log(Log.INFO, TAG, "TV-Mute=" + (mute ? 1 : 0) + " ret=" + r);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "audioMute failed", t);
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

    /**
     * 播放器被盖住期间每 100ms 查一次：仍在设置/桌面就继续保留，去了别的应用或息屏就补跑拆除。
     * fromPause=true 时去向可能还没更新 (顶层仍是播放器自己)，最多宽限 1.5 秒。
     */
    private void startWatchdog(final Activity a, final XposedInterface.Invoker<?, Method> origPause,
                               final boolean fromPause) {
        if (!watching.add(a)) {
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            int idle = 0;
            int grace = 15;
            boolean muted = fromPause;
            String lastTop = "";
            long homeSince;

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
                boolean awake = isAwake(a);
                boolean home = isHomeTop(a);
                boolean settingsTop = isMainEntryClass(top);
                boolean playerTop = top != null && top.startsWith(PLAYER + ".");
                boolean pending = playerTop && grace-- > 0;
                boolean active = awake && !a.isDestroyed()
                        && ((continuesSettingsFlow(top) && !playerTop) || home || pending);
                if (settingsTop) {
                    if (muted) {
                        muted = false;
                        setMuted(a, false);
                    }
                } else if (home && !muted) {
                    muted = true;
                    setMuted(a, true);
                }
                if (home) {
                    long now = SystemClock.elapsedRealtime();
                    if (homeSince == 0) {
                        homeSince = now;
                    } else if (now - homeSince >= HOME_HOLD_MS) {
                        log(Log.INFO, TAG, "watchdog: idle on home for " + (now - homeSince)
                                + " ms, release PC signal to save CPU");
                        watching.remove(a);
                        teardown(a, origPause);
                        return;
                    }
                } else {
                    homeSince = 0;
                }
                idle = active ? 0 : idle + 1;
                if (idle >= 2) {
                    log(Log.INFO, TAG, "watchdog: home/settings left (top=" + top + ", awake=" + awake
                            + "), teardown");
                    watching.remove(a);
                    teardown(a, origPause);
                    return;
                }
                h.postDelayed(this, 100);
            }
        });
    }
}
