package com.beekeeper.micstream;

import android.util.Log;
import io.github.libxposed.api.XposedModule;

/*
 * 麦克风推流整体放在这个模块里：mitv.service（系统常驻进程）里运行推流服务，快捷设置“专业功能”页里加开关和配对管理。
 * 修改后需要重启电视（或结束 xiaomi.tvservice 进程）才会重新加载服务。
 */
public final class HookEntry extends XposedModule {

  static final String TAG = "MicStream";
  private static final String SETTINGS = "com.xiaomi.mitv.settings";
  private static final String SERVICE = "mitv.service";

  private boolean settingsHooked, serviceStarted;

  @Override
  public void onModuleLoaded(ModuleLoadedParam param) {
    log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
  }

  @Override
  public void onPackageLoaded(PackageLoadedParam param) {
    String pkg = param.getPackageName();
    if (SERVICE.equals(pkg) && !serviceStarted) {
      serviceStarted = true;
      MicServer.start(this);
    } else if (SETTINGS.equals(pkg) && !settingsHooked) {
      settingsHooked = true;
      try {
        SettingsUi.install(this, param.getDefaultClassLoader());
      } catch (Throwable t) {
        log(Log.ERROR, TAG, "cannot add settings items", t);
      }
    }
  }
}
