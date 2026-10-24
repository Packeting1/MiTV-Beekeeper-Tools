package com.beekeeper.homeref;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 默认桌面换成第三方桌面 (如当贝桌面) 后，把系统里写死 com.mitv.tvhome 的几处引用指向当前默认桌面。
 * 默认桌面仍是系统桌面时，这里不改任何东西。
 *
 * - mitv.service：TvService 靠 mitv.action.resume.activity 的 package/component 判断“现在是不是桌面”
 *   (4K UI 页面类型、回桌面清理后台)，只认 com.mitv.tvhome.MainActivityUserMode。
 * - tvplayer / settings：信号源列表和设置里的“主页”入口发 com.mitv.tvhome.HOME_PAGE，只有系统桌面能接，
 *   改成标准的 MAIN + HOME 交给默认桌面。
 */
public final class HookEntry extends XposedModule {

    private static final String TAG = "HomeRef";
    private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String SETTINGS = "com.xiaomi.mitv.settings";
    private static final String TVSERVICE = "mitv.service";
    private static final String STOCK_HOME = "com.mitv.tvhome";
    private static final String STOCK_HOME_ACTIVITY = "com.mitv.tvhome.MainActivityUserMode";
    private static final String HOME_PAGE_ACTION = "com.mitv.tvhome.HOME_PAGE";

    private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!(PLAYER.equals(pkg) || SETTINGS.equals(pkg) || TVSERVICE.equals(pkg)) || !HOOKED.add(pkg)) {
            return;
        }
        try {
            if (TVSERVICE.equals(pkg)) {
                installHomeAlias(param.getDefaultClassLoader());
            } else {
                installHomeRedirect();
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install failed in " + pkg, t);
        }
    }

    /** 当前默认桌面的包名；解析失败返回 null。 */
    private static String defaultHomePackage(Context c) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = c.getPackageManager().resolveActivity(i, 0);
            return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 在 TvService 读 extras 之前，把默认桌面的 package/component 改成系统桌面的，让它按桌面处理。
     */
    private void installHomeAlias(ClassLoader cl) throws Throwable {
        Class<?> rcv = cl.loadClass("mitv.service.TvService$17");
        final Method onReceive = rcv.getDeclaredMethod("onReceive", Context.class, android.content.Intent.class);
        hook(onReceive).intercept(chain -> {
            try {
                android.content.Intent it = (android.content.Intent) chain.getArgs().get(1);
                Context ctx = (Context) chain.getArgs().get(0);
                String p = it == null ? null : it.getStringExtra("package");
                if (p != null && !p.equals(STOCK_HOME) && p.equals(defaultHomePackage(ctx))) {
                    String comp = it.getStringExtra("component");
                    log(Log.INFO, TAG, "home alias: " + p + "/" + comp + " -> " + STOCK_HOME);
                    it.putExtra("package", STOCK_HOME);
                    it.putExtra("component", STOCK_HOME_ACTIVITY);
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "home alias failed", t);
            }
            return chain.proceed();
        });
        log(Log.INFO, TAG, "tvservice home alias hook installed");
    }

    private void installHomeRedirect() {
        int n = 0;
        for (Method m : android.app.Instrumentation.class.getDeclaredMethods()) {
            if (!m.getName().equals("execStartActivity")) {
                continue;
            }
            hook(m).intercept(chain -> {
                try {
                    Context ctx = null;
                    android.content.Intent it = null;
                    for (Object o : chain.getArgs()) {
                        if (ctx == null && o instanceof Context) {
                            ctx = (Context) o;
                        }
                        if (it == null && o instanceof android.content.Intent) {
                            it = (android.content.Intent) o;
                        }
                    }
                    if (ctx != null && it != null && HOME_PAGE_ACTION.equals(it.getAction())) {
                        String home = defaultHomePackage(ctx);
                        if (home != null && !home.equals(STOCK_HOME)) {
                            log(Log.INFO, TAG, "home redirect: HOME_PAGE -> default home " + home);
                            it.setAction(android.content.Intent.ACTION_MAIN);
                            it.addCategory(android.content.Intent.CATEGORY_HOME);
                            it.setPackage(null);
                        }
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "home redirect failed", t);
                }
                return chain.proceed();
            });
            n++;
        }
        log(Log.INFO, TAG, "home redirect hooks installed: " + n);
    }
}
