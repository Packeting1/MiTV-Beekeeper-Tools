package io.github.rin.tvnosignalxiaoai;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.tv.TvView;
import android.media.tv.TvInputInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Retain the framework TV session in audio mode so video recovery still has a callback. */
final class SessionKeeper {
    private static final String TAG = "NoSignalSession";
    private static Context context;
    private static TvView view;
    private static Object owner;
    private static Object keptActivity;
    private static boolean allowDestroy;
    private static boolean videoLost;
    private static String retainedInputId;
    private static ImageReader sink;
    private static HandlerThread sinkThread;

    static void setContext(Context value) { context = value; }
    private static Object sourceManager() {
        Class<?> tvContext = XposedHelpers.findClass("mitv.tv.TvContext", context.getClassLoader());
        return XposedHelpers.callMethod(XposedHelpers.callStaticMethod(tvContext, "getInstance"),
                "getSourceManager");
    }
    static void prepareInputWake(String inputId) {
        if (inputId == null || context == null) return;
        try {
            Object manager = sourceManager();
            int source = (Integer) XposedHelpers.callMethod(manager, "getMitvSourceFromTIFId", inputId);
            TvInputInfo info = (TvInputInfo) XposedHelpers.callMethod(manager,
                    "getTvInputInfoForMitvSource", source);
            if (info == null || !info.isPassthroughInput() || !inputId.equals(info.getId())) {
                Log.w(TAG, "Recovery source mapping unavailable: " + inputId);
                return;
            }
            // handleIntent() otherwise falls back to a saved source that can be a different port.
            Settings.Global.putInt(context.getContentResolver(), "mitv.tvplayer.hdmi.last.source", source);
            if (owner instanceof Activity && !((Activity) owner).isFinishing()) {
                Activity activity = (Activity) owner;
                Intent original = activity.getIntent();
                Intent resume = original == null ? new Intent() : new Intent(original);
                resume.putExtra("input", source);
                resume.removeExtra("autosource");
                activity.setIntent(resume);
                XposedHelpers.setIntField(owner, "mTargetSource", source);
            }
            Log.i(TAG, "Recovery wake selects source=" + source + " input=" + inputId);
        } catch (Throwable error) {
            Log.e(TAG, "Recovery source preparation failed: " + error.getClass().getSimpleName());
        }
    }
    private static boolean audioMode() {
        return context != null && "xiaoai_mode".equals(
                Settings.Global.getString(context.getContentResolver(), "mitv_power_state"));
    }
    private static void releaseSink() {
        if (sink != null) { sink.close(); sink = null; }
        if (sinkThread != null) { sinkThread.quitSafely(); sinkThread = null; }
    }
    private static Surface sinkSurface() {
        if (sink == null) {
            sinkThread = new HandlerThread("NoSignalDiscard");
            sinkThread.start();
            // PRIVATE buffers are discarded without reading pixels, saving or exporting frames.
            sink = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 2);
            sink.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader reader) {
                    try {
                        Image image = reader.acquireLatestImage();
                        if (image != null) image.close();
                    } catch (IllegalStateException ignored) { }
                }
            }, new Handler(sinkThread.getLooper()));
        }
        return sink.getSurface();
    }
    static void install(ClassLoader loader) {
        Class<?> base = XposedHelpers.findClass("com.xiaomi.mitv.tvplayer.BaseActivity", loader);
        final Class<?> session = XposedHelpers.findClass("android.media.tv.TvInputManager$Session", null);
        XposedHelpers.findAndHookMethod(base, "destoryPlayerAndSurface", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (allowDestroy || !audioMode() || ((Activity) p.thisObject).isFinishing()) return;
                if (!p.thisObject.getClass().getName().equals("com.xiaomi.mitv.tvplayer.ExternalSourceActivity")) return;
                TvView current = (TvView) XposedHelpers.getObjectField(p.thisObject, "mTvView");
                if (current == null) return;
                try { sinkSurface(); }
                catch (Throwable error) {
                    releaseSink();
                    Log.e(TAG, "Session retention unavailable: " + error.getClass().getSimpleName());
                    return;
                }
                keptActivity = p.thisObject;
                owner = p.thisObject;
                view = current;
                try {
                    int source = XposedHelpers.getIntField(owner, "mTargetSource");
                    TvInputInfo info = (TvInputInfo) XposedHelpers.callMethod(sourceManager(),
                            "getTvInputInfoForMitvSource", source);
                    retainedInputId = info == null ? null : info.getId();
                } catch (Throwable error) {
                    retainedInputId = null;
                    Log.w(TAG, "Retained input mapping unavailable: " + error.getClass().getSimpleName());
                }
                AudioManager audio = (AudioManager) XposedHelpers.getObjectField(p.thisObject, "mAudioMgr");
                if (audio != null) audio.abandonAudioFocus((AudioManager.OnAudioFocusChangeListener)
                        XposedHelpers.getObjectField(p.thisObject, "mAudioFocusChangeListener"));
                p.setResult(null);
                Log.i(TAG, "Retained input session for audio mode: " + retainedInputId);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (owner == p.thisObject && XposedHelpers.getObjectField(p.thisObject, "mTvView") == null) {
                    view = null; owner = null; keptActivity = null; videoLost = false;
                    retainedInputId = null; releaseSink();
                }
            }
        });
        XposedHelpers.findAndHookMethod(base, "createPlayerAndSurface", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (keptActivity != p.thisObject) return;
                allowDestroy = true;
                try { XposedHelpers.callMethod(p.thisObject, "destoryPlayerAndSurface"); }
                finally {
                    allowDestroy = false; keptActivity = null; releaseSink();
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable() && p.thisObject.getClass().getName()
                        .equals("com.xiaomi.mitv.tvplayer.ExternalSourceActivity")) {
                    view = (TvView) XposedHelpers.getObjectField(p.thisObject, "mTvView");
                    owner = p.thisObject;
                    videoLost = false;
                }
            }
        });
        XposedHelpers.findAndHookMethod(base, "onDestroy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (keptActivity != p.thisObject) return;
                allowDestroy = true;
                try { XposedHelpers.callMethod(p.thisObject, "destoryPlayerAndSurface"); }
                finally {
                    allowDestroy = false; keptActivity = null; view = null; owner = null;
                    videoLost = false; retainedInputId = null; releaseSink();
                }
            }
        });
        XposedHelpers.findAndHookMethod(session, "setSurface", Surface.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args[0] != null || view == null || !audioMode() || allowDestroy) return;
                Object current = XposedHelpers.getObjectField(view, "mSession");
                if (current != p.thisObject) return;
                p.args[0] = sinkSurface();
                Log.i(TAG, "Retained an offscreen signal surface");
            }
        });
        Class<?> callback = XposedHelpers.findClass("android.media.tv.TvView$MySessionCallback", null);
        XposedHelpers.findAndHookMethod(callback, "onVideoUnavailable", session, int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (view == null || XposedHelpers.getObjectField(view, "mSession") != p.args[0]) return;
                int reason = (Integer) p.args[1];
                if (reason == 2) videoLost = true;
                Log.i(TAG, "Video unavailable reason=" + reason);
            }
        });
        XposedHelpers.findAndHookMethod(callback, "onVideoAvailable", session, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (view == null || XposedHelpers.getObjectField(view, "mSession") != p.args[0]) return;
                boolean recovered = videoLost;
                videoLost = false;
                Log.i(TAG, "Video available; recovery=" + recovered);
                if (!recovered || context == null) return;
                PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (power != null && !power.isInteractive()) {
                    prepareInputWake(retainedInputId);
                    XposedHelpers.callMethod(power, "wakeUp", SystemClock.uptimeMillis(), 8,
                            "NoSignalXiaoAi:video-event");
                    Log.i(TAG, "Requested wake from video event");
                }
            }
        });
        Log.i(TAG, "Installed audio-mode session retention");
    }
}
