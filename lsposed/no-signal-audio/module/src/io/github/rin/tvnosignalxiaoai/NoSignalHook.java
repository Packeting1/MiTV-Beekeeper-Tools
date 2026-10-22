package io.github.rin.tvnosignalxiaoai;

import android.app.Application;
import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Countdown audio mode and external-input recovery wake, within the TV player process. */
public final class NoSignalHook implements IXposedHookLoadPackage {
    private static final String TAG = "NoSignalXiaoAi";
    private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String COUNTDOWN = PLAYER + ".widget.views.SearchingView$1";
    private static TvInputManager inputManager;
    private static TvInputManager.TvInputCallback inputCallback;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!PLAYER.equals(lp.packageName) || !PLAYER.equals(lp.processName)) return;
        XposedHelpers.findAndHookMethod(PLAYER + ".TVPlayerApp", lp.classLoader,
                "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                try {
                    watchInputs((Application) param.thisObject);
                } catch (Throwable error) {
                    Log.e(TAG, "Input recovery watcher unavailable: " + error.getClass().getSimpleName());
                }
            }
        });
        // Fail closed on incompatible player builds: the original behavior is retained.
        Class<?> countdown = XposedHelpers.findClass(COUNTDOWN, lp.classLoader);
        countdown.getDeclaredMethod("run");
        XposedHelpers.findAndHookMethod(PowerManager.class, "goToSleep",
                long.class, int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (((Integer) param.args[1]).intValue() != 9) return;
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if (COUNTDOWN.equals(frame.getClassName()) && "run".equals(frame.getMethodName())) {
                        param.args[1] = Integer.valueOf(8);
                        Log.i(TAG, "No-signal countdown: sleep reason 9 -> 8 (XiaoAi)");
                        XposedBridge.log(TAG + ": countdown redirected to XiaoAi");
                        return;
                    }
                }
            }
        });
        Log.i(TAG, "Installed countdown hook in " + lp.processName);
        XposedBridge.log(TAG + ": installed for " + lp.processName);
    }

    private static void watchInputs(final Context context) {
        if (inputCallback != null) return;
        final TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        final PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (manager == null || power == null) throw new IllegalStateException("Missing system service");
        final Handler handler = new Handler(Looper.getMainLooper());
        final InputRecoveryTracker recovery = new InputRecoveryTracker();
        final Runnable wake = new Runnable() {
            @Override public void run() {
                if (power.isInteractive()) return;
                try {
                    XposedHelpers.callMethod(power, "wakeUp", SystemClock.uptimeMillis(), 8,
                            "NoSignalXiaoAi:input-restored");
                    Log.i(TAG, "Input restored: requested screen wake");
                } catch (Throwable error) {
                    Log.e(TAG, "Input wake failed: " + error.getClass().getSimpleName());
                }
            }
        };
        TvInputManager.TvInputCallback callback = new TvInputManager.TvInputCallback() {
            private void update(String id, int state) {
                TvInputInfo info = manager.getTvInputInfo(id);
                if (info == null || !info.isPassthroughInput()) return;
                if (recovery.update(id, state)) {
                    // Hardware can report DP and HDMI recovery together. Request one wake.
                    handler.removeCallbacks(wake);
                    handler.postDelayed(wake, 250L);
                    Log.i(TAG, "Input recovery detected");
                }
            }
            @Override public void onInputStateChanged(String id, int state) { update(id, state); }
            @Override public void onInputAdded(String id) { update(id, manager.getInputState(id)); }
            @Override public void onInputRemoved(String id) { recovery.removed(id); }
        };
        // Seed current states without treating already-connected inputs as recovery.
        for (TvInputInfo info : manager.getTvInputList()) {
            if (info.isPassthroughInput()) recovery.update(info.getId(), manager.getInputState(info.getId()));
        }
        manager.registerCallback(callback, handler);
        inputManager = manager;
        inputCallback = callback;
        Log.i(TAG, "Watching recovery of all external inputs");
    }
}
