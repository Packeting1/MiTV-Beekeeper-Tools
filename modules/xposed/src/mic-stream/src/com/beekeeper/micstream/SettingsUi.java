package com.beekeeper.micstream;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/*
 * 在快捷设置“专业功能”页末尾加「麦克风推流」开关，开启后描述显示连接状态。
 * 推流服务在 mitv.service 进程里（MicServer），这里只通过 Settings.Global 与它交换信息；配对管理在应用的「功能类」页。
 */
final class SettingsUi {

  private static final String PAGE =
      "com.xiaomi.mitv.settings.monitor.MonitorProfessionalSettingView";
  private static final String ITEM = "com.xiaomi.mitv.settings.widget.MonitorOnOffSwitcherItem";
  private static final String DESC = "推给电脑端 MiTvMic，开启后 10 分钟内无设备连接则自动关闭";

  private final XposedModule mod;
  private ContentObserver observer;
  private Object item;
  private Context context;
  private Method setDesc;

  private SettingsUi(XposedModule mod) {
    this.mod = mod;
  }

  static void install(XposedModule mod, ClassLoader cl) throws Exception {
    new SettingsUi(mod).hook(cl);
  }

  private void hook(ClassLoader cl) throws Exception {
    Class<?> page = cl.loadClass(PAGE);
    Class<?> itemClass = cl.loadClass(ITEM);
    Class<?> listener = cl.loadClass(ITEM + "$OnCheckChangedListener");
    Field contextField = page.getDeclaredField("mContext");
    Field category = page.getDeclaredField("mCategory");
    contextField.setAccessible(true);
    category.setAccessible(true);
    Method create = itemClass.getMethod("createItem", Context.class, ViewGroup.class);
    Method setTitle = itemClass.getMethod("setTitle", String.class);
    setDesc = itemClass.getMethod("setDescription", String.class);
    Method setChecked = itemClass.getMethod("setChecked", boolean.class);
    Method setListener = itemClass.getMethod("setOnCheckedListener", listener);

    mod.hook(page.getDeclaredMethod("init"))
        .intercept(chain -> {
          Object result = chain.proceed();
          try {
            Object p = chain.getThisObject();
            Context ctx = (Context) contextField.get(p);
            Object it = create.invoke(null, ctx, category.get(p));
            setTitle.invoke(it, "麦克风推流");
            setDesc.invoke(it, describe(ctx));
            setChecked.invoke(it, enabled(ctx));
            setListener.invoke(
                it,
                Proxy.newProxyInstance(
                    cl,
                    new Class<?>[] {listener},
                    (proxy, m, args) -> {
                      Settings.Global.putInt(
                          ctx.getContentResolver(), Keys.ENABLED, (Boolean) args[0] ? 1 : 0);
                      return Boolean.TRUE;
                    }));
            watch(ctx, it);
          } catch (Throwable t) {
            mod.log(Log.ERROR, HookEntry.TAG, "cannot add switch", t);
          }
          return result;
        });
  }

  private static boolean enabled(Context ctx) {
    return Settings.Global.getInt(ctx.getContentResolver(), Keys.ENABLED, 0) != 0;
  }

  private static String describe(Context ctx) {
    if (!enabled(ctx)) return DESC;
    String s = Settings.Global.getString(ctx.getContentResolver(), Keys.STATUS);
    return s == null || s.length() == 0 ? "未连接" : s;
  }

  /** 页面每次打开都会新建开关项，只刷新最近一个；观察者在进程内注册一次。 */
  private void watch(Context ctx, Object it) {
    item = it;
    context = ctx;
    if (observer != null) return;
    observer =
        new ContentObserver(new Handler(Looper.getMainLooper())) {
          @Override
          public void onChange(boolean selfChange) {
            try {
              setDesc.invoke(item, describe(context));
            } catch (Throwable t) {
              mod.log(Log.ERROR, HookEntry.TAG, "cannot update description", t);
            }
          }
        };
    ContentResolver cr = ctx.getContentResolver();
    cr.registerContentObserver(Settings.Global.getUriFor(Keys.ENABLED), false, observer);
    cr.registerContentObserver(Settings.Global.getUriFor(Keys.STATUS), false, observer);
  }
}
