package io.github.rin.tvnosignalxiaoai;

import android.os.PowerManager;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Scoped to the TV player's existing no-signal countdown, with no system-wide hook. */
public final class NoSignalHook implements IXposedHookLoadPackage {
    private static final String TAG = "NoSignalXiaoAi";
    private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String COUNTDOWN = PLAYER + ".widget.views.SearchingView$1";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!PLAYER.equals(lp.packageName) || !PLAYER.equals(lp.processName)) return;
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
}
