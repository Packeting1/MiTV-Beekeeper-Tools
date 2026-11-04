package com.beekeeper.volumebalanceswitch;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/*
 * 在快捷设置“声音”页末尾加「音量均衡」开关，行为与系统设置的声音页一致：
 * SoundManager.setAGCMode(0/1)，并写 Settings.System current_volume_balance_status。
 */
public final class HookEntry extends XposedModule {

  private static final String TAG = "VolumeBalanceSwitch";
  private static final String SETTINGS = "com.xiaomi.mitv.settings";
  private static final String PAGE = "com.xiaomi.mitv.settings.monitor.MonitorSoundView";
  private static final String ITEM = "com.xiaomi.mitv.settings.widget.MonitorOnOffSwitcherItem";
  private static final String MANAGER = "mitv.sound.SoundManager";
  private static final String STATUS_KEY = "current_volume_balance_status";

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
      install(param.getDefaultClassLoader());
      log(Log.INFO, TAG, "sound page hook installed");
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "cannot hook sound page", t);
    }
  }

  private void install(ClassLoader cl) throws Exception {
    Class<?> page = cl.loadClass(PAGE);
    Class<?> item = cl.loadClass(ITEM);
    Class<?> listener = cl.loadClass(ITEM + "$OnCheckChangedListener");
    Field context = field(page, "mContext");
    Field category = field(page, "mCategory");
    Method getInstance = cl.loadClass(MANAGER).getMethod("getInstance");
    Method create = item.getMethod("createItem", Context.class, ViewGroup.class);
    Method setTitle = item.getMethod("setTitle", String.class);
    Method setChecked = item.getMethod("setChecked", boolean.class);
    Method setListener = item.getMethod("setOnCheckedListener", listener);

    hook(page.getDeclaredMethod("init"))
        .intercept(chain -> {
          Object result = chain.proceed();
          try {
            Object p = chain.getThisObject();
            Object manager = getInstance.invoke(null);
            Context ctx = (Context) context.get(p);
            Method getAgc = manager.getClass().getMethod("getAGCMode");
            Method setAgc = manager.getClass().getMethod("setAGCMode", int.class);
            Object it = create.invoke(null, ctx, category.get(p));
            setTitle.invoke(it, "音量均衡");
            setChecked.invoke(it, (Integer) getAgc.invoke(manager) == 1);
            setListener.invoke(
                it,
                Proxy.newProxyInstance(
                    cl,
                    new Class<?>[] {listener},
                    (proxy, m, args) -> {
                      int on = (Boolean) args[0] ? 1 : 0;
                      setAgc.invoke(manager, on);
                      Settings.System.putInt(ctx.getContentResolver(), STATUS_KEY, on);
                      return Boolean.TRUE;
                    }));
          } catch (Throwable t) {
            log(Log.ERROR, TAG, "cannot add volume balance switch", t);
          }
          return result;
        });
  }

  private static Field field(Class<?> c, String name) throws NoSuchFieldException {
    Field f = c.getDeclaredField(name);
    f.setAccessible(true);
    return f;
  }
}
