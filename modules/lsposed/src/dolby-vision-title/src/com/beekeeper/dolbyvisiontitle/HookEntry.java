package com.beekeeper.dolbyvisiontitle;

import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 菜单键弹出的图像页，标题栏在杜比信号下也显示 Dolby Vision。
 *
 * 原版 PictureSettingsManagerImpl.getVideoInfoValue() 只在场景模式为 19（杜比视界游戏）时返回 7，
 * MonitorMainView.initTopTitle() 看到 7 才写 Dolby Vision；其它杜比图像模式拿到的是原生 HDR 类型 3，
 * 被当成普通 HDR。这里只在标题刷新期间把 3 换成 7，其它调用方拿到的值不变。
 */
public final class HookEntry extends XposedModule {

    private static final String TAG = "DolbyVisionTitle";
    private static final String SETTINGS = "com.xiaomi.mitv.settings";
    private static final String MAIN_VIEW = "com.xiaomi.mitv.settings.monitor.MonitorMainView";
    private static final String PICTURE_MANAGER = "mitv.impl.display.PictureSettingsManagerImpl";
    private static final int VIDEO_INFO_DOLBY = 3;
    private static final int VIDEO_INFO_DOLBY_TITLE = 7;

    private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!SETTINGS.equals(param.getPackageName()) || !HOOKED.add(SETTINGS)) {
            return;
        }
        try {
            ClassLoader cl = param.getDefaultClassLoader();
            ThreadLocal<Boolean> inTitle = new ThreadLocal<>();
            hook(cl.loadClass(MAIN_VIEW).getDeclaredMethod("initTopTitle")).intercept(chain -> {
                inTitle.set(true);
                try {
                    return chain.proceed();
                } finally {
                    inTitle.set(false);
                }
            });
            hook(cl.loadClass(PICTURE_MANAGER).getMethod("getVideoInfoValue")).intercept(chain -> {
                Object value = chain.proceed();
                return Boolean.TRUE.equals(inTitle.get()) && Integer.valueOf(VIDEO_INFO_DOLBY).equals(value)
                        ? (Object) VIDEO_INFO_DOLBY_TITLE : value;
            });
            log(Log.INFO, TAG, "title hooks installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "cannot hook title", t);
        }
    }
}