package com.beekeeper.mediabadge;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 在右上角弹出视频角标（Dolby Vision / HDR10 / HLG / HDR Vivid / Technicolor）和音频角标（Dolby / DTS 系列），停留 5 秒。
 * 系统桌面和信号源播放器自带的杜比角标会叠在一起，在它们的进程里屏蔽，改为广播给本模块弹；桌面前台时不按 HDR 类型弹。
 * 视频角标由 HDR 类型变化的回调触发；Technicolor / HDR Vivid 的类型值取自 SDK 常量，未实测；HDR10+ 与 HDR10 无法区分。
 */
public final class HookEntry extends XposedModule {

    private static final String TAG = "MediaBadge";
    private static final String SERVICE = "mitv.service";
    private static final String HOME = "com.mitv.tvhome";
    private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String[] NATIVE_CONTROLLERS = {
            "com.mitv.oneplayer.controller.BaseVideoUIController",
            "com.mitv.videoplayer.controller.BaseVideoUIController"};
    private static final String AV_MODE = "com.mediatek.twoworlds.tv.MtkTvAVModeBase";
    private static final int VIDEOINFO_HDR_TYPE = 2;
    private static final int HDR10 = 1;
    private static final int HLG = 2;
    private static final int DOLBY = 3;
    private static final int TECHNICOLOR = 4;
    private static final int HDR_VIVID = 6;
    private static final String ACTION_BADGE = "com.beekeeper.mediabadge.BADGE";
    private static final String CALLBACK = "com.mediatek.twoworlds.tv.TVCallback";
    private static final long SETTLE_MS = 400;
    private static final long SHOW_MS = 5000;

    private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());

    private Handler ui;
    private LinearLayout box;
    private String videoText;
    private String audioText;
    private final Runnable fadeTask = this::fadeOut;
    private int lastType = -1;
    private Handler worker;
    private final Runnable checkTask = this::checkNow;
    private String lastAudio = "";
    private int audioRetry;
    private final Runnable audioTask = this::checkAudio;
    private Object avMode;
    private Method getInfo;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (HOME.equals(pkg) && HOOKED.add(HOME)) {
            muteNativeDolbyLogo(param.getDefaultClassLoader());
            return;
        }
        if (PLAYER.equals(pkg) && HOOKED.add(PLAYER)) {
            mutePlayerDolbyIcon(param.getDefaultClassLoader());
            return;
        }
        if (!SERVICE.equals(pkg) || !HOOKED.add(SERVICE)) {
            return;
        }
        try {
            Class<?> c = param.getDefaultClassLoader().loadClass(AV_MODE);
            avMode = c.getConstructor().newInstance();
            getInfo = c.getMethod("getVideoInfoValue", int.class);
            ui = new Handler(Looper.getMainLooper());
            ui.post(this::listenBadge);
            ui.post(this::watchAudio);
            HandlerThread t = new HandlerThread(TAG);
            t.start();
            worker = new Handler(t.getLooper());
            // 视频信息回调（HDR 类型变化也走这里）只用来触发检查；回调线程里不能直接回调原生接口，
            // 所以在自己的线程上延迟读取，同时合并连续的事件
            Class<?> cb = param.getDefaultClassLoader().loadClass(CALLBACK);
            hook(cb.getDeclaredMethod("DO_notifyVideoInfoMessage",
                    int.class, int.class, int.class, int.class, int.class, int.class, int.class)).intercept(chain -> {
                Object r = chain.proceed();
                scheduleCheck();
                return r;
            });
            worker.post(checkTask);
            log(Log.INFO, TAG, "badge watcher started");
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "cannot start", e);
        }
    }

    // 系统桌面/第三方应用的音频角标：音频 HAL 在解码时会写出当前音频种类（audioindicator，如 DTS-HD），
    // 播放状态变化是触发信号，稍等解码器稳定后读一次；信号源播放器自己会广播，这里不重复弹
    private void watchAudio() {
        try {
            ((AudioManager) app().getSystemService(Context.AUDIO_SERVICE)).registerAudioPlaybackCallback(
                    new AudioManager.AudioPlaybackCallback() {
                        @Override
                        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                            audioRetry = 0;
                            worker.removeCallbacks(audioTask);
                            worker.postDelayed(audioTask, 1000);
                        }
                    }, worker);
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "cannot watch audio", e);
        }
    }

    private void checkAudio() {
        try {
            String v = (String) Class.forName("android.media.AudioSystem")
                    .getMethod("getParameters", String.class).invoke(null, "audioindicator");
            v = v == null ? "" : v.replace("audioindicator=", "").trim();
            if (v.isEmpty() && lastAudio.isEmpty() && audioRetry++ < 2) {
                worker.postDelayed(audioTask, 1500);
                return;
            }
            if (v.equals(lastAudio)) {
                return;
            }
            lastAudio = v;
            Context ctx = app();
            String top = v.isEmpty() ? null : topPackage(ctx);
            log(Log.INFO, TAG, "audio indicator [" + v + "], top=" + top);
            if (top == null || PLAYER.equals(top) || isHome(ctx, top)) {
                return;
            }
            final String text = audioName(v);
            ui.post(() -> present(ctx, null, text));
        } catch (Throwable e) {
            log(Log.WARN, TAG, "audio check failed", e);
        }
    }

    private static String audioName(String v) {
        String s = v.toLowerCase();
        if (s.contains("atmos")) {
            return "Dolby Atmos";
        }
        if (s.contains("dts:x")) {
            return "DTS:X";
        }
        if (s.contains("express")) {
            return "DTS Express";
        }
        if (s.contains("dts-hd")) {
            return "DTS-HD";
        }
        return v;
    }

    // 桌面和信号源播放器里被屏蔽的系统角标，改由它们广播过来在这里弹
    private void listenBadge() {
        try {
            app().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    present(c, i.getBooleanExtra("vision", false) ? label(DOLBY) : null, i.getStringExtra("audio"));
                }
            }, new IntentFilter(ACTION_BADGE));
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "cannot listen badge", e);
        }
    }

    private boolean notifyBadge(Context ctx, boolean vision, String audio) {
        try {
            ctx.sendBroadcast(new Intent(ACTION_BADGE).setPackage(SERVICE).putExtra("vision", vision).putExtra("audio", audio));
            return true;
        } catch (Throwable e) {
            log(Log.WARN, TAG, "cannot notify badge", e);
            return false;
        }
    }

    private void muteNativeDolbyLogo(ClassLoader cl) {
        for (String name : NATIVE_CONTROLLERS) {
            try {
                Class<?> c = cl.loadClass(name);
                hook(c.getDeclaredMethod("showVideoLogoIfNeeded")).intercept(chain -> {
                    return isPlainDolby(chain.getThisObject()) && notifyBadge(app(), true, null) ? null : chain.proceed();
                });
                log(Log.INFO, TAG, "native dolby logo muted: " + name);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            } catch (Throwable e) {
                log(Log.ERROR, TAG, "cannot mute native logo " + name, e);
            }
        }
    }

    // 信号源播放器的角标 showDolbyIcon(音频图标, 视觉图标)：两个图标都改由本模块弹，原方法收到 (0, 0) 会自己隐藏。
    // 音频种类按图标资源名区分，认不出时不拦，保持原样
    private void mutePlayerDolbyIcon(ClassLoader cl) {
        try {
            Class<?> c = cl.loadClass("com.xiaomi.mitv.tvplayer.ExternalSourceActivity");
            hook(c.getDeclaredMethod("showDolbyIcon", int.class, int.class)).intercept(chain -> {
                Context ctx = (Context) chain.getThisObject();
                int audioRes = (Integer) chain.getArgs().get(0);
                int visionRes = (Integer) chain.getArgs().get(1);
                String audio = audioLabel(ctx, audioRes);
                if (audioRes != 0 && audio == null || !notifyBadge(ctx, visionRes != 0, audio)) {
                    return chain.proceed();
                }
                return chain.proceed(new Object[] {0, 0});
            });
            log(Log.INFO, TAG, "player dolby icons muted");
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "cannot mute player icon", e);
        }
    }

    private static String audioLabel(Context ctx, int res) {
        if (res == 0) {
            return null;
        }
        try {
            switch (ctx.getResources().getResourceEntryName(res)) {
                case "hdmi_dolby_atmos_logo":
                    return "Dolby Atmos";
                case "hdmi_dolby_audio_logo":
                    return "Dolby Audio";
                case "dtsx_logo":
                    return "DTS:X";
                case "dts_hd_logo":
                    return "DTS-HD";
                case "dts_expres_logo":
                    return "DTS Express";
                case "dts_logo":
                    return "DTS";
                default:
                    return null;
            }
        } catch (Throwable e) {
            return null;
        }
    }

    // 原方法先判 IMAX 再判杜比，这里只拦杜比；读不到信息时不拦，保持原样
    private static boolean isPlainDolby(Object controller) {
        try {
            Field f = null;
            for (Class<?> k = controller.getClass(); f == null && k != null; k = k.getSuperclass()) {
                try {
                    f = k.getDeclaredField("mVideoProxy");
                } catch (NoSuchFieldException ignored) {
                }
            }
            f.setAccessible(true);
            Object proxy = f.get(controller);
            Object info = proxy.getClass().getMethod("getVideoInfo").invoke(proxy);
            return info.getClass().getField("isDolbyVision").getBoolean(info) && !info.getClass().getField("isIMax").getBoolean(info);
        } catch (Throwable e) {
            return false;
        }
    }

    private void scheduleCheck() {
        worker.removeCallbacks(checkTask);
        worker.postDelayed(checkTask, SETTLE_MS);
    }

    private void checkNow() {
        try {
            Context ctx = app();
            int type = (Integer) getInfo.invoke(avMode, VIDEOINFO_HDR_TYPE);
            if (type == lastType) {
                return;
            }
            String label = label(type);
            String top = label == null ? null : topPackage(ctx);
            log(Log.INFO, TAG, "hdr type " + lastType + " -> " + type + ", top=" + top);
            lastType = type;
            if (top != null && !isHome(ctx, top)) {
                ui.post(() -> present(ctx, label, null));
            }
        } catch (Throwable e) {
            log(Log.WARN, TAG, "check failed", e);
        }
    }

    // 桌面（系统桌面或当前默认桌面如 Aerial）在前台时，HDR 类型是 SignalKeep 保留的外部信号，不是桌面在播放
    private static boolean isHome(Context ctx, String pkg) {
        if (HOME.equals(pkg)) {
            return true;
        }
        try {
            ResolveInfo r = ctx.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            return r != null && r.activityInfo != null && pkg.equals(r.activityInfo.packageName);
        } catch (Throwable e) {
            return false;
        }
    }

    private static Context app() throws Throwable {
        return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
    }

    private static String topPackage(Context ctx) {
        try {
            List<ActivityManager.RunningTaskInfo> tasks =
                    ((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                return tasks.get(0).topActivity.getPackageName();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static View textBadge(Context ctx, String text) {
        float dp = ctx.getResources().getDisplayMetrics().density;
        int heightPx = Math.round(30 * dp);
        // 渐变时不建离屏图层：图层在淡出结束切换时会闪一下
        TextView v = new TextView(ctx) {
            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, heightPx * 0.5f);
        v.setGravity(Gravity.CENTER);
        v.setIncludeFontPadding(false);
        v.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(204, 0, 0, 0));
        bg.setCornerRadius(heightPx / 2f);
        v.setBackground(bg);
        return v;
    }

    private static String label(int type) {
        switch (type) {
            case HDR10:
                return "HDR10";
            case HLG:
                return "HLG";
            case DOLBY:
                return "Dolby Vision";
            case TECHNICOLOR:
                return "Technicolor";
            case HDR_VIVID:
                return "HDR Vivid";
            default:
                return null;
        }
    }

    // 最多两个药丸纵向排列（视频在上，音频在下）；已在显示时原地更新并重新计时，内容没变就不打扰
    private void present(Context ctx, String video, String audio) {
        try {
            if (video == null && audio == null) {
                return;
            }
            boolean showing = box != null;
            boolean changed = (video != null && !video.equals(videoText)) || (audio != null && !audio.equals(audioText));
            if (video != null) {
                videoText = video;
            }
            if (audio != null) {
                audioText = audio;
            }
            if (showing && !changed) {
                return;
            }
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            float dp = ctx.getResources().getDisplayMetrics().density;
            if (box == null) {
                box = new LinearLayout(ctx) {
                    @Override
                    public boolean hasOverlappingRendering() {
                        return false;
                    }
                };
                box.setOrientation(LinearLayout.VERTICAL);
                box.setAlpha(0f);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.y = Math.round(42 * dp);
                lp.x = Math.round(30 * dp);
                fill(ctx);
                wm.addView(box, lp);
            } else {
                box.animate().setListener(null).cancel();
                fill(ctx);
            }
            box.animate().alpha(1f).setDuration(250).start();
            ui.removeCallbacks(fadeTask);
            ui.postDelayed(fadeTask, SHOW_MS);
            log(Log.INFO, TAG, "badge shown: " + videoText + " / " + audioText);
        } catch (Throwable e) {
            log(Log.ERROR, TAG, "cannot show badge", e);
        }
    }

    private void fill(Context ctx) {
        float dp = ctx.getResources().getDisplayMetrics().density;
        box.removeAllViews();
        for (String t : new String[] {videoText, audioText}) {
            if (t != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(30 * dp));
                lp.gravity = Gravity.END;
                lp.topMargin = box.getChildCount() > 0 ? Math.round(8 * dp) : 0;
                box.addView(textBadge(ctx, t), lp);
            }
        }
    }

    private void fadeOut() {
        final LinearLayout b = box;
        if (b == null) {
            return;
        }
        b.animate().alpha(0f).setDuration(400).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                b.setVisibility(View.INVISIBLE);
                if (box == b) {
                    remove();
                }
            }
        }).start();
    }

    private void remove() {
        LinearLayout b = box;
        box = null;
        videoText = null;
        audioText = null;
        if (b != null) {
            try {
                ((WindowManager) b.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(b);
            } catch (Throwable ignored) {
            }
        }
    }
}
