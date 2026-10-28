package com.beekeeper.tvplayerkeep;

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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 小米电视播放器 (com.xiaomi.mitv.tvplayer) 在系统设置打开时不拆 HDMI。
 *
 * OS3 的完整设置是一个 Activity，播放器 onPause 会无条件拆掉播放环境(setSurface(null)+释放硬件)，
 * 所以 PC 信号进设置会黑屏。遥控器设置键由 com.xiaomi.mitv.smartpanel 发起，启动时设置窗口的
 * settings_window_showing 还没置位，因此再加两个更早的信号：smartpanel 启动前写入的时间戳标记，
 * 以及当前最顶层任务是否属于设置。命中任何一个就跳过 onPause/onResume 的函数体，只调用
 * FragmentActivity 的父类实现。播放器之后若被完全盖住 (onStop)，补跑原始 onPause 做拆除。
 *
 * 投屏小窗和桌面引用替换是各自独立的模块 (CastPip / HomeRef)，这里不含任何投屏或桌面逻辑。
 */
public final class HookEntry extends XposedModule {

    private static final String TAG = "TvPlayerKeep";
    private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String PANEL = "com.xiaomi.mitv.smartpanel";
    private static final String SETTINGS = "com.xiaomi.mitv.settings";
    private static final String FLAG = "settings_window_showing";
    private static final String PENDING = "tvplayerkeep_settings_pending";
    private static final long PENDING_WINDOW_MS = 3000;
    private static final int PICTURE_FLAGS = Intent.FLAG_ACTIVITY_SINGLE_TOP
            | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK;

    private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());
    private final Set<Object> kept = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));
    private final Set<Object> watching = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!(PLAYER.equals(pkg) || PANEL.equals(pkg) || SETTINGS.equals(pkg)) || !HOOKED.add(pkg)) {
            return;
        }
        try {
            if (PLAYER.equals(pkg)) {
                installPlayer(param.getDefaultClassLoader());
            } else {
                if (PANEL.equals(pkg)) {
                    installPanel(param.getDefaultClassLoader());
                }
                installLaunchMarker(SETTINGS.equals(pkg));
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install failed in " + pkg, t);
        }
    }

    private void installPanel(ClassLoader cl) throws Throwable {
        Set<String> names = new HashSet<>(Arrays.asList(
                "getSettingsIntent", "launchSettings", "launchSettingsNew", "launchSettingsOffice"));
        String[] classes = {"com.xiaomi.mitv.smartpanel.Utils",
                "com.xiaomi.mitv.smartpanel.event.MessageLaunchEvent"};
        int n = 0;
        for (String cn : classes) {
            Class<?> c;
            try {
                c = cl.loadClass(cn);
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!names.contains(m.getName())) {
                    continue;
                }
                hook(m).intercept(chain -> {
                    markPending();
                    return chain.proceed();
                });
                n++;
            }
        }
        log(Log.INFO, TAG, "panel hooks installed: " + n);
    }

    /**
     * 设置进程自己发起的设置主入口 (图像参数) 不经过 smartpanel，播放器暂停时没有任何前置信号。
     * 只在目标是设置主入口 Activity 或设置的 action 时打标记，其他二级菜单保持原样。
     */
    private void installLaunchMarker(final boolean fromSettingsProcess) throws Throwable {
        int n = 0;
        for (Method m : Instrumentation.class.getDeclaredMethods()) {
            if (!m.getName().equals("execStartActivity")) {
                continue;
            }
            hook(m).intercept(chain -> {
                try {
                    for (Object o : chain.getArgs()) {
                        if (o instanceof Intent) {
                            Intent it = (Intent) o;
                            String cls = it.getComponent() != null ? it.getComponent().getClassName() : null;
                            String action = it.getAction();
                            // 图像参数从半透明设置页再拉起的是不透明的 MainActivity (SINGLE_TOP|CLEAR_TOP|NEW_TASK)，
                            // 它盖住了 HDMI 画面；改成同一页面的半透明版本，画面才看得见。
                            if (fromSettingsProcess && (SETTINGS + ".entry.MainActivity").equals(cls)
                                    && (it.getFlags() & PICTURE_FLAGS) == PICTURE_FLAGS) {
                                it.setClassName(SETTINGS, SETTINGS + ".entry.TranslucentMainActivity");
                                cls = SETTINGS + ".entry.TranslucentMainActivity";
                                log(Log.INFO, TAG, "picture page: MainActivity -> TranslucentMainActivity");
                            }
                            if (isMainEntryClass(cls)
                                    || (action != null && action.startsWith("com.xiaomi.mitv.settings."))) {
                                markPending();
                            }
                            break;
                        }
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "launch marker failed", t);
                }
                return chain.proceed();
            });
            n++;
        }
        log(Log.INFO, TAG, "launch marker hooks installed: " + n + " settingsProcess=" + fromSettingsProcess);
    }

    private void markPending() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Context ctx = (Context) at.getMethod("currentApplication").invoke(null);
            Settings.System.putString(ctx.getContentResolver(), PENDING,
                    String.valueOf(System.currentTimeMillis()));
            log(Log.INFO, TAG, "panel: settings launch marked");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "panel: mark failed", t);
        }
    }

    private boolean settingsSignal(Activity a) {
        ContentResolver cr = a.getContentResolver();
        boolean flag = false;
        boolean fresh = false;
        long age = -1;
        try {
            flag = Settings.System.getInt(cr, FLAG, 0) == 1;
        } catch (Throwable ignored) {
        }
        try {
            String s = Settings.System.getString(cr, PENDING);
            if (s != null) {
                age = System.currentTimeMillis() - Long.parseLong(s);
                fresh = age >= 0 && age < PENDING_WINDOW_MS;
            }
        } catch (Throwable ignored) {
        }
        String top = topClass(a);
        boolean showing = flag || fresh || isMainEntryClass(top);
        log(Log.INFO, TAG, "onPause signals flag=" + flag + " pendingAgeMs=" + age
                + " top=" + top + " -> keep=" + showing);
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

        hook(onPause).intercept(chain -> {
            Activity a = (Activity) chain.getThisObject();
            if (settingsSignal(a)) {
                kept.add(a);
                superPause.invokeSpecial(a);
                return null;
            }
            kept.remove(a);
            return chain.proceed();
        });

        hook(onResume).intercept(chain -> {
            Activity a = (Activity) chain.getThisObject();
            if (kept.remove(a)) {
                log(Log.INFO, TAG, "onResume: playback kept, skip re-init");
                superResume.invokeSpecial(a);
                return null;
            }
            return chain.proceed();
        });

        hook(onStop).intercept(chain -> {
            Activity a = (Activity) chain.getThisObject();
            if (kept.contains(a)) {
                String top = topClass(a);
                if (continuesSettingsFlow(top)) {
                    log(Log.INFO, TAG, "onStop while kept, top=" + top + ": keep HDMI, watchdog on");
                    startWatchdog(a, origPause);
                } else {
                    log(Log.INFO, TAG, "onStop while kept, top=" + top + ": deferred teardown");
                    teardown(a, origPause);
                }
            }
            return chain.proceed();
        });

        // 二级设置页是不透明的独立 Activity，会销毁 SurfaceView；TvView 随后把空 Surface 交给
        // 输入服务，输入服务才会释放 HDMI。保留期间拦下这一次空 Surface。
        int n = 0;
        for (Method m : TvView.class.getDeclaredMethods()) {
            if (m.getName().equals("setSessionSurface") && m.getParameterTypes().length == 1) {
                hook(m).intercept(chain -> {
                    if (chain.getArgs().get(0) == null && !kept.isEmpty()) {
                        log(Log.INFO, TAG, "TvView: null surface held back while settings active");
                        return null;
                    }
                    return chain.proceed();
                });
                n++;
            }
        }

        log(Log.INFO, TAG, "player hooks installed, tvview hooks=" + n);
    }

    private static boolean isMainEntryClass(String cls) {
        return (SETTINGS + ".entry.MainActivity").equals(cls)
                || (SETTINGS + ".entry.TranslucentMainActivity").equals(cls);
    }

    /** 读不到顶层 Activity 时按「仍在设置主入口」处理，由看门狗继续观察，不贸然拆除。 */
    private static boolean continuesSettingsFlow(String topClass) {
        return topClass == null || isMainEntryClass(topClass);
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

    /** 播放器被完全盖住后，每秒查一次：设置已离开前台（回桌面等）或息屏，就补跑拆除。 */
    private void startWatchdog(final Activity a, final XposedInterface.Invoker<?, Method> origPause) {
        if (!watching.add(a)) {
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
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
                boolean active = awake && !a.isDestroyed() && continuesSettingsFlow(top);
                idle = active ? 0 : idle + 1;
                if (idle >= 2) {
                    log(Log.INFO, TAG, "watchdog: settings left (top=" + top + ", awake=" + awake
                            + "), teardown");
                    watching.remove(a);
                    teardown(a, origPause);
                    return;
                }
                h.postDelayed(this, 1000);
            }
        });
    }
}
