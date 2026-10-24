package com.beekeeper.nosignalaudiomode;

import android.app.Application;
import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 无信号倒计时结束后进入音响模式（而不是待机），倒计时页新增“息屏”按钮；
 * 任意外部信号源恢复时自动亮屏。只作用于电视播放器进程。
 */
public final class HookEntry extends XposedModule {
    private static final String TAG = "NoSignalAudioMode";
    private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String COUNTDOWN = PLAYER + ".widget.views.SearchingView$1";
    private static TvInputManager.TvInputCallback inputCallback;
    private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());

    // PowerManager 的隐藏方法，在 install() 里解析。
    static Method wakeUp;
    static Method goToSleep;

    private String processName;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "module loaded in " + processName);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!PLAYER.equals(param.getPackageName()) || !PLAYER.equals(processName)) return;
        if (!HOOKED.add(PLAYER)) return;
        try {
            install(param.getDefaultClassLoader());
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "install failed", error);
        }
    }

    private void install(ClassLoader loader) throws Throwable {
        wakeUp = PowerManager.class.getMethod("wakeUp", long.class, int.class, String.class);
        goToSleep = PowerManager.class.getDeclaredMethod("goToSleep", long.class, int.class, int.class);
        // 先确认倒计时入口存在，再装任何 hook（不是这版播放器就整体不装）。
        loader.loadClass(COUNTDOWN).getDeclaredMethod("run");
        Method onCreate = loader.loadClass(PLAYER + ".TVPlayerApp").getDeclaredMethod("onCreate");
        hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Application app = (Application) chain.getThisObject();
                SessionKeeper.setContext(app);
                watchInputs(app);
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Input recovery watcher unavailable: " + error.getClass().getSimpleName());
            }
            return result;
        });
        try { SessionKeeper.install(this, loader); }
        catch (Throwable error) { log(Log.ERROR, TAG, "Session test unavailable: " + error.getClass().getSimpleName()); }
        try { CountdownControls.install(this, loader); }
        catch (Throwable error) { log(Log.ERROR, TAG, "Countdown controls unavailable: " + error.getClass().getSimpleName()); }
        hook(goToSleep).intercept(chain -> {
            if (((Integer) chain.getArg(1)).intValue() == 9) {
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if (COUNTDOWN.equals(frame.getClassName()) && "run".equals(frame.getMethodName())) {
                        log(Log.INFO, TAG, "No-signal countdown: sleep reason 9 -> 8 (XiaoAi)");
                        return chain.proceed(new Object[] { chain.getArg(0), Integer.valueOf(8), chain.getArg(2) });
                    }
                }
            }
            return chain.proceed();
        });
        log(Log.INFO, TAG, "Installed countdown hook in " + processName);
    }

    private void watchInputs(final Context context) {
        if (inputCallback != null) return;
        final TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        final PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (manager == null || power == null) throw new IllegalStateException("Missing system service");
        final Handler handler = new Handler(Looper.getMainLooper());
        final InputRecoveryTracker recovery = new InputRecoveryTracker();
        final String[] recoveredInput = new String[1];
        final Runnable wake = new Runnable() {
            @Override public void run() {
                String inputId = recoveredInput[0];
                recoveredInput[0] = null;
                if (power.isInteractive()) return;
                // 普通待机（电源键息屏等）不处理，只在音响模式下按信号恢复亮屏。
                if (!SessionKeeper.audioMode()) return;
                try {
                    SessionKeeper.prepareInputWake(inputId);
                    wakeUp.invoke(power, Long.valueOf(SystemClock.uptimeMillis()), Integer.valueOf(8),
                            "NoSignalXiaoAi:input-restored");
                    log(Log.INFO, TAG, "Input restored: requested screen wake");
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "Input wake failed: " + error.getClass().getSimpleName());
                }
            }
        };
        TvInputManager.TvInputCallback callback = new TvInputManager.TvInputCallback() {
            private void update(String id, int state) {
                TvInputInfo info = manager.getTvInputInfo(id);
                if (info == null || !info.isPassthroughInput()) return;
                if (recovery.update(id, state)) {
                    // 硬件可能同时上报 DP 和 HDMI 恢复，只请求一次亮屏。
                    recoveredInput[0] = id;
                    handler.removeCallbacks(wake);
                    handler.postDelayed(wake, 250L);
                    log(Log.INFO, TAG, "Input recovery detected: " + id);
                }
            }
            @Override public void onInputStateChanged(String id, int state) { update(id, state); }
            @Override public void onInputAdded(String id) { update(id, manager.getInputState(id)); }
            @Override public void onInputRemoved(String id) { recovery.removed(id); }
        };
        // 先记录当前状态，已连接的输入不算作“恢复”。
        for (TvInputInfo info : manager.getTvInputList()) {
            if (info.isPassthroughInput()) recovery.update(info.getId(), manager.getInputState(info.getId()));
        }
        manager.registerCallback(callback, handler);
        inputCallback = callback;
        log(Log.INFO, TAG, "Watching recovery of all external inputs");
    }
}
