package com.beekeeper.sourcevolumememory;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** 在快捷设置“更多设置”页末尾加一个开关项，状态存在 Settings.Global（默认开）。 */
final class SettingsSwitch {

  private static final String PAGE =
      "com.xiaomi.mitv.settings.monitor.MonitorProfessionalSettingView";
  private static final String ITEM = "com.xiaomi.mitv.settings.widget.MonitorOnOffSwitcherItem";

  private SettingsSwitch() {}

  static boolean enabled(Context ctx, String key) {
    try {
      return Settings.Global.getInt(ctx.getContentResolver(), key, 1) != 0;
    } catch (Throwable t) {
      return true;
    }
  }

  static void install(
      XposedModule module, ClassLoader cl, String tag, String title, String desc, String key)
      throws Exception {
    Class<?> page = cl.loadClass(PAGE);
    Class<?> item = cl.loadClass(ITEM);
    Class<?> listener = cl.loadClass(ITEM + "$OnCheckChangedListener");
    Field context = page.getDeclaredField("mContext");
    Field category = page.getDeclaredField("mCategory");
    context.setAccessible(true);
    category.setAccessible(true);
    Method create = item.getMethod("createItem", Context.class, ViewGroup.class);
    Method setTitle = item.getMethod("setTitle", String.class);
    Method setDesc = item.getMethod("setDescription", String.class);
    Method setChecked = item.getMethod("setChecked", boolean.class);
    Method setListener = item.getMethod("setOnCheckedListener", listener);

    module
        .hook(page.getDeclaredMethod("init"))
        .intercept(chain -> {
          Object result = chain.proceed();
          try {
            Object p = chain.getThisObject();
            Context ctx = (Context) context.get(p);
            Object it = create.invoke(null, ctx, category.get(p));
            setTitle.invoke(it, title);
            setDesc.invoke(it, desc);
            setChecked.invoke(it, enabled(ctx, key));
            setListener.invoke(
                it,
                Proxy.newProxyInstance(
                    cl,
                    new Class<?>[] {listener},
                    (proxy, m, args) -> {
                      Settings.Global.putInt(
                          ctx.getContentResolver(), key, (Boolean) args[0] ? 1 : 0);
                      return Boolean.TRUE;
                    }));
          } catch (Throwable t) {
            module.log(Log.ERROR, tag, "cannot add switch", t);
          }
          return result;
        });
  }
}
