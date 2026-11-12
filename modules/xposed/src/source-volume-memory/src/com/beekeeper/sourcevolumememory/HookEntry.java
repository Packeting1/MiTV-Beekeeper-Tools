package com.beekeeper.sourcevolumememory;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/*
 * 信号源（tvplayer 在前台）和电视系统（其它应用、桌面）分别记忆系统音量，切换时自动恢复。
 * 前台判断靠轮询顶层任务：SignalKeep 回桌面时会保留播放器的 Activity，生命周期回调看不出实际去向。
 * 快捷设置等浮层只是临时盖在上面，不算切换。没有记录的一侧沿用当前音量并记下来。
 * 音量记录在 Settings.Global（sourcevolumememory_source / sourcevolumememory_system）。
 */
public final class HookEntry extends XposedModule {

  private static final String TAG = "SourceVolumeMemory";
  private static final String SERVICE = "mitv.service";
  private static final String SETTINGS = "com.xiaomi.mitv.settings";
  private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
  private static final String KEY_ENABLED = "sourcevolumememory_enabled";
  private static final String KEY_PREFIX = "sourcevolumememory_";
  private static final String VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
  private static final long POLL_MS = 500;

  private static final Set<String> OVERLAYS =
      new HashSet<>(Arrays.asList(SETTINGS, "com.xiaomi.mitv.smartpanel", "com.android.systemui"));

  private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());

  private Handler worker;
  private Context ctx;
  private AudioManager audio;
  private String mode;

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
            "信号源音量记忆",
            "信号源和电视系统分别记住音量，切换时自动恢复",
            KEY_ENABLED);
      } catch (Throwable t) {
        log(Log.ERROR, TAG, "cannot add settings switch", t);
      }
      return;
    }
    if (!SERVICE.equals(pkg) || !HOOKED.add(SERVICE)) {
      return;
    }
    HandlerThread t = new HandlerThread(TAG);
    t.start();
    worker = new Handler(t.getLooper());
    worker.post(this::start);
    log(Log.INFO, TAG, "started");
  }

  private void start() {
    try {
      ctx =
          (Context)
              Class.forName("android.app.ActivityThread")
                  .getMethod("currentApplication")
                  .invoke(null);
      if (ctx == null) {
        worker.postDelayed(this::start, 1000);
        return;
      }
      audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
      ctx.registerReceiver(
          new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
              onVolumeChanged(i);
            }
          },
          new IntentFilter(VOLUME_CHANGED),
          null,
          worker);
      poll();
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "cannot start", t);
    }
  }

  private void poll() {
    try {
      check();
    } catch (Throwable t) {
      log(Log.WARN, TAG, "check failed", t);
    }
    worker.postDelayed(this::poll, POLL_MS);
  }

  private void check() {
    if (!SettingsSwitch.enabled(ctx, KEY_ENABLED)) {
      mode = null;
      return;
    }
    String pkg = topPackage();
    if (pkg == null || OVERLAYS.contains(pkg)) {
      return;
    }
    String now = PLAYER.equals(pkg) ? "source" : "system";
    if (now.equals(mode)) {
      return;
    }
    mode = now;
    int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
    int saved = Settings.Global.getInt(ctx.getContentResolver(), KEY_PREFIX + now, -1);
    log(Log.INFO, TAG, "mode " + now + ", volume " + cur + ", saved " + saved);
    if (saved < 0 || saved > audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) {
      remember(now, cur);
    } else if (saved != cur) {
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0);
    }
  }

  private void onVolumeChanged(Intent i) {
    if (mode == null
        || i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC
        || !SettingsSwitch.enabled(ctx, KEY_ENABLED)) {
      return;
    }
    remember(mode, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
  }

  private void remember(String m, int volume) {
    Settings.Global.putInt(ctx.getContentResolver(), KEY_PREFIX + m, volume);
  }

  private String topPackage() {
    List<ActivityManager.RunningTaskInfo> tasks =
        ((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(1);
    if (tasks == null || tasks.isEmpty()) {
      return null;
    }
    ComponentName top = tasks.get(0).topActivity;
    return top == null ? null : top.getPackageName();
  }
}
