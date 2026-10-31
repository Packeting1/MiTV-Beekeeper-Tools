package com.beekeeper.decorativelight;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 系统设置的装饰灯页（菜单键图像页与完整设置页）新增“自定义颜色”和“网络灯控”， 并让自定义颜色在灯效被改写时继续生效。颜色经配套 Magisk 模块的灯控扩展下发， 属性
 * sys.beekeeper_rgb.native 就绪后才起作用。 作者：Rin010
 */
public final class HookEntry extends XposedModule {
  private static final String TAG = "DecorativeLight";
  private static final String SETTINGS = "com.xiaomi.mitv.settings";
  private static final String SERVICE = "mitv.service";
  private static final String HEX = "beekeeper_rgb_hex";
  private static final String ENABLED = "beekeeper_rgb_enabled";
  private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();
  private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());
  private static final Set<Class<?>> MANAGERS = ConcurrentHashMap.newKeySet();
  private static final Set<View> ADDED =
      Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

  @Override
  public void onModuleLoaded(ModuleLoadedParam param) {
    log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
  }

  @Override
  public void onPackageLoaded(PackageLoadedParam param) {
    String pkg = param.getPackageName();
    boolean settings = SETTINGS.equals(pkg);
    if (!(settings || SERVICE.equals(pkg)) || !HOOKED.add(pkg)) return;
    ClassLoader cl = param.getDefaultClassLoader();
    try {
      Method getInstance =
          cl.loadClass("mitv.display.PictureSettingsManager").getDeclaredMethod("getInstance");
      hook(getInstance)
          .intercept(chain -> {
            Object manager = chain.proceed();
            if (manager != null) hookManager(manager.getClass());
            return manager;
          });
    } catch (Throwable e) {
      fail(e);
    }
    if (!settings) return;
    try {
      Method putInt =
          Settings.Global.class.getDeclaredMethod(
              "putInt", ContentResolver.class, String.class, int.class);
      hook(putInt)
          .intercept(chain -> {
            if (!Boolean.TRUE.equals(APPLYING.get())) {
              ContentResolver cr = (ContentResolver) chain.getArg(0);
              String key = (String) chain.getArg(1);
              // 用户在原生页改灯光设置后，退出网络接管和自定义颜色。
              if (key.startsWith("atmosphere_light_")
                  && Settings.Global.getInt(cr, "beekeeper_net_enabled", 0) == 1)
                Settings.Global.putInt(cr, "beekeeper_net_enabled", 0);
              if (key.equals("atmosphere_light_preset_color")
                  || key.equals("atmosphere_light_same_color")
                  || key.equals("atmosphere_light_color_value")
                  || key.equals("atmosphere_light_switcher_pm2"))
                Settings.Global.putInt(cr, ENABLED, 0);
            }
            return chain.proceed();
          });
    } catch (Throwable e) {
      fail(e);
    }
    hookPage(
        cl,
        "com.xiaomi.mitv.settings.monitor.MonitorLightsDetailView",
        "init",
        "mCategory",
        "com.xiaomi.mitv.settings.widget.MonitorSettingItem",
        "com.xiaomi.mitv.settings.widget.MonitorOnOffSwitcherItem");
    hookPage(
        cl,
        "com.xiaomi.mitv.settings.displaysound.detail.LightsDetailView",
        "initViews",
        "mExpertDisplayCategory",
        "com.xiaomi.mitv.settings.widget.SettingItem",
        "com.xiaomi.mitv.settings.widget.OnOffSwitcherItem");
  }

  private void hookManager(Class<?> cls) {
    if (!MANAGERS.add(cls)) return;
    try {
      hook(findMethod(cls, "setColorfulLedState", int.class, int.class))
          .intercept(chain -> {
            int mode = (Integer) chain.getArg(0);
            if ((mode == 1 || mode == 2) && !Boolean.TRUE.equals(APPLYING.get()) && nativeReady()) {
              try {
                Object manager = chain.getThisObject();
                Context ctx =
                    (Context)
                        call(
                            manager
                                .getClass()
                                .getClassLoader()
                                .loadClass("mitv.impl.tv.TvContextImpl"),
                            "getAndroidContext");
                if (Settings.Global.getInt(ctx.getContentResolver(), ENABLED, 0) == 1) {
                  send(
                      manager,
                      mode,
                      RgbColor.parse(Settings.Global.getString(ctx.getContentResolver(), HEX)));
                  return null;
                }
              } catch (Throwable e) {
                fail(e);
              }
            }
            return chain.proceed();
          });
      log(Log.INFO, TAG, "manager hooked: " + cls.getName());
    } catch (Throwable e) {
      MANAGERS.remove(cls);
      fail(e);
    }
  }

  private void hookPage(
      ClassLoader cl,
      String cls,
      String method,
      String categoryField,
      String itemCls,
      String switchCls) {
    try {
      hook(findMethod(cl.loadClass(cls), method))
          .intercept(chain -> {
            Object result = chain.proceed();
            try {
              addRows((View) chain.getThisObject(), cl, categoryField, itemCls, switchCls);
              log(Log.INFO, TAG, "rows added: " + cls);
            } catch (Throwable e) {
              fail(e);
            }
            return result;
          });
    } catch (Throwable e) {
      fail(e);
    }
  }

  private void addRows(
      View page, ClassLoader cl, String categoryField, String itemCls, String switchCls)
      throws Throwable {
    if (!ADDED.add(page)) return;
    Context ctx = page.getContext();
    Object category = get(page, categoryField);
    Object manager = get(page, "mPicManager");
    hookManager(manager.getClass());
    Class<?> itemClass = cl.loadClass(itemCls);
    Handler ui = new Handler(Looper.getMainLooper());

    Object color = newRow(itemClass, ctx, category, "自定义颜色", true);
    Runnable refreshColor = () -> refresh(color, ctx);
    refreshColor.run();
    observe((View) color, ctx, refreshColor, HEX, ENABLED);
    ((View) color).setOnClickListener(v -> showDialog(ctx, manager, color));
    ui.post(() -> moveBeforeSameColor(page, (View) color));

    // 开关行控制网络接收，开启后才出现进入参数设置的行。
    Object network = newRow(cl.loadClass(switchCls), ctx, category, "网络灯控", false);
    Object settings = newRow(itemClass, ctx, category, "网络灯控设置", true);
    Runnable refreshNetwork =
        () -> {
          boolean on = NetworkSettings.get(ctx, "enabled", 0) == 1;
          try {
            call(network, "setChecked", on);
          } catch (Throwable e) {
            fail(e);
          }
          ((View) settings).setVisibility(on ? View.VISIBLE : View.GONE);
        };
    refreshNetwork.run();
    observe((View) network, ctx, refreshNetwork, "beekeeper_net_enabled");
    onChecked(network, on -> NetworkSettings.setEnabled(ctx, on));
    ((View) settings).setOnClickListener(v -> NetworkSettings.show(ctx));
    ui.post(() -> moveBeforeSameColor(page, (View) network));
    ui.post(() -> moveBeforeSameColor(page, (View) settings));
  }

  private Object newRow(
      Class<?> itemClass, Context ctx, Object category, String title, boolean arrow)
      throws Throwable {
    Object item = call(itemClass, "createItem", ctx, category);
    call(item, "setTitle", title);
    if (arrow) call(item, "setRightArrowVisibility", View.VISIBLE);
    return item;
  }

  // 开关项的监听接口是系统内部类型，用动态代理实现；返回 true 表示接受这次切换。
  private void onChecked(Object item, Consumer<Boolean> action) throws Throwable {
    for (Method m : item.getClass().getMethods()) {
      if (!m.getName().equals("setOnCheckedListener") || m.getParameterTypes().length != 1)
        continue;
      Class<?> type = m.getParameterTypes()[0];
      m.invoke(
          item,
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (proxy, method, args) -> {
                if (method.getName().equals("onCheckChanged")) {
                  action.accept((Boolean) args[0]);
                  return true;
                }
                return null;
              }));
      return;
    }
    throw new NoSuchMethodException("setOnCheckedListener");
  }

  private static void observe(View view, Context ctx, Runnable refresh, String... keys) {
    ContentObserver observer =
        new ContentObserver(new Handler(Looper.getMainLooper())) {
          @Override
          public void onChange(boolean selfChange) {
            refresh.run();
          }
        };
    boolean[] registered = {false};
    Runnable attach =
        () -> {
          if (!registered[0]) {
            for (String key : keys)
              ctx.getContentResolver()
                  .registerContentObserver(Settings.Global.getUriFor(key), false, observer);
            registered[0] = true;
          }
          refresh.run();
        };
    view.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          @Override
          public void onViewAttachedToWindow(View v) {
            attach.run();
          }

          @Override
          public void onViewDetachedFromWindow(View v) {
            if (registered[0]) ctx.getContentResolver().unregisterContentObserver(observer);
            registered[0] = false;
          }
        });
    if (view.isAttachedToWindow()) attach.run();
  }

  // createItem 把新行追加在列表末尾，这里挪到“屏幕同色”开关之前。
  private void moveBeforeSameColor(View page, View row) {
    try {
      View sameColor = (View) get(page, "mSameColorSwitcher");
      if (sameColor == null) return;
      View moving = row;
      while (moving.getParent() instanceof ViewGroup) {
        ViewGroup parent = (ViewGroup) moving.getParent();
        View anchor = sameColor;
        while (anchor.getParent() instanceof View && anchor.getParent() != parent)
          anchor = (View) anchor.getParent();
        if (anchor.getParent() == parent) {
          ViewGroup.LayoutParams params = moving.getLayoutParams();
          parent.removeView(moving);
          parent.addView(moving, parent.indexOfChild(anchor), params);
          return;
        }
        moving = parent;
      }
    } catch (Throwable e) {
      fail(e);
    }
  }

  private static boolean nativeReady() {
    return "1".equals(prop("sys.beekeeper_rgb.native"));
  }

  private static String prop(String key) {
    try {
      return (String)
          Class.forName("android.os.SystemProperties")
              .getMethod("get", String.class, String.class)
              .invoke(null, key, "0");
    } catch (Throwable e) {
      return "0";
    }
  }

  private void send(Object manager, int mode, int rgb) throws Throwable {
    Field proxyField = field(manager.getClass(), "miTvCommonInfoHidlProxy");
    Object proxy = proxyField.get(manager);
    if (proxy == null) {
      Object api =
          call(
              manager
                  .getClass()
                  .getClassLoader()
                  .loadClass("com.mitv.mtvapiclientwrapper.MiTvApiManagerProxy"),
              "getInstance");
      proxy = call(api, "getMiTvFApiSysCfg");
      proxyField.set(manager, proxy);
    }
    Object result = call(proxy, "SetColorfulLed", mode, RgbColor.encode(rgb));
    if (result instanceof Number && ((Number) result).intValue() < 0)
      throw new IllegalStateException("灯控服务拒绝自定义颜色，请检查灯控扩展是否加载");
    log(Log.INFO, TAG, "applied " + RgbColor.format(rgb) + " mode=" + mode);
  }

  private void refresh(Object item, Context ctx) {
    String hex = Settings.Global.getString(ctx.getContentResolver(), HEX);
    boolean active = Settings.Global.getInt(ctx.getContentResolver(), ENABLED, 0) == 1;
    try {
      call(item, "setValue", active && hex != null ? hex : "");
    } catch (Throwable e) {
      fail(e);
    }
  }

  private void showDialog(Context ctx, Object manager, Object row) {
    LinearLayout content = new LinearLayout(ctx);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 8), Ui.dp(ctx, 24), Ui.dp(ctx, 12));
    content.addView(Ui.text(ctx, "输入六位十六进制颜色，支持带或不带 #。", 16));
    EditText input = new EditText(ctx);
    input.setSingleLine(true);
    input.setTextSize(24);
    input.setTypeface(Typeface.MONOSPACE);
    input.setKeyListener(DigitsKeyListener.getInstance("#0123456789abcdefABCDEF"));
    input.setRawInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    input.setImeOptions(EditorInfo.IME_ACTION_DONE);
    input.setImeActionLabel("应用", EditorInfo.IME_ACTION_DONE);
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(7)});
    input.setHint("#66FFCC");
    String current = Settings.Global.getString(ctx.getContentResolver(), HEX);
    input.setText(current == null ? "#66FFCC" : current);
    input.setSelectAllOnFocus(true);
    content.addView(
        input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 58)));
    TextView preview = new TextView(ctx);
    preview.setGravity(Gravity.CENTER);
    preview.setTextSize(17);
    content.addView(
        preview,
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 54)));
    Runnable repaint =
        () -> {
          try {
            int rgb = RgbColor.parse(input.getText().toString());
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xff000000 | rgb);
            bg.setCornerRadius(Ui.dp(ctx, 8));
            preview.setBackground(bg);
            int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            preview.setTextColor(r * 299 + g * 587 + b * 114 >= 140000 ? Color.BLACK : Color.WHITE);
            preview.setText("R " + r + "    G " + g + "    B " + b);
          } catch (IllegalArgumentException e) {
            preview.setBackgroundColor(Color.TRANSPARENT);
            preview.setTextColor(Color.GRAY);
            preview.setText("请输入完整的六位颜色值");
          }
        };
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            repaint.run();
          }

          public void afterTextChanged(Editable e) {}
        });
    repaint.run();
    AlertDialog dialog =
        new AlertDialog.Builder(ctx)
            .setTitle("自定义颜色")
            .setView(content)
            .setPositiveButton("应用", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("使用预设", null)
            .create();
    dialog.setOnShowListener(ignored -> {
      dialog
          .getButton(AlertDialog.BUTTON_POSITIVE)
          .setOnClickListener(v -> {
            int rgb;
            try {
              rgb = RgbColor.parse(input.getText().toString());
            } catch (IllegalArgumentException e) {
              input.setError(e.getMessage());
              return;
            }
            if (!nativeReady()) {
              input.setError("请更新配套 Magisk 灯控模块并重启设备");
              return;
            }
            ContentResolver cr = ctx.getContentResolver();
            int mode = Settings.Global.getInt(cr, "atmosphere_light_effect", 0) + 1;
            if (mode != 1 && mode != 2) {
              input.setError("请先选择常亮或呼吸光效");
              return;
            }
            APPLYING.set(true);
            String oldHex = Settings.Global.getString(cr, HEX);
            int oldEnabled = Settings.Global.getInt(cr, ENABLED, 0);
            try {
              NetworkSettings.pause(ctx);
              send(manager, mode, rgb);
              Settings.Global.putString(cr, HEX, RgbColor.format(rgb));
              Settings.Global.putInt(cr, ENABLED, 1);
              Settings.Global.putInt(cr, "atmosphere_light_same_color", 0);
              Settings.Global.putInt(cr, "atmosphere_light_switcher", 1);
              refresh(row, ctx);
              dialog.dismiss();
            } catch (Throwable e) {
              Settings.Global.putString(cr, HEX, oldHex);
              Settings.Global.putInt(cr, ENABLED, oldEnabled);
              fail(e);
              input.setError("应用失败，请稍后重试");
            } finally {
              APPLYING.remove();
            }
          });
      // 遥控器确认键与软键盘“完成”都走这里；单行输入框的回车会以 IME_NULL + KeyEvent 到达。
      input.setOnEditorActionListener((v, actionId, event) -> {
        if (event != null) {
          int key = event.getKeyCode();
          if (key != KeyEvent.KEYCODE_ENTER && key != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
          if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
          return true;
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        return true;
      });
      dialog
          .getButton(AlertDialog.BUTTON_NEUTRAL)
          .setOnClickListener(v -> {
            APPLYING.set(true);
            try {
              NetworkSettings.pause(ctx);
              ContentResolver cr = ctx.getContentResolver();
              Settings.Global.putInt(cr, ENABLED, 0);
              int mode =
                  Settings.Global.getInt(cr, "atmosphere_light_switcher", 1) == 1
                      ? Settings.Global.getInt(cr, "atmosphere_light_effect", 0) + 1
                      : 0;
              int preset = Settings.Global.getInt(cr, "atmosphere_light_preset_color", 0) + 1;
              call(manager, "setColorfulLedState", mode, preset);
              refresh(row, ctx);
              dialog.dismiss();
            } catch (Throwable e) {
              fail(e);
              Toast.makeText(ctx, "恢复预设失败，请重新选择预设颜色", Toast.LENGTH_LONG).show();
            } finally {
              APPLYING.remove();
            }
          });
    });
    Ui.show(ctx, dialog);
    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    input.requestFocus();
  }

  private void fail(Throwable error) {
    log(Log.ERROR, TAG, "error", error);
  }

  // 反射辅助：目标是系统里的隐藏类，沿类层级查找并忽略可见性。
  private static Method findMethod(Class<?> cls, String name, Class<?>... types)
      throws NoSuchMethodException {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredMethod(name, types);
      } catch (NoSuchMethodException ignored) {
      }
    }
    throw new NoSuchMethodException(cls.getName() + "." + name);
  }

  private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      try {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
      } catch (NoSuchFieldException ignored) {
      }
    }
    throw new NoSuchFieldException(cls.getName() + "." + name);
  }

  private static Object get(Object target, String name) throws ReflectiveOperationException {
    return field(target.getClass(), name).get(target);
  }

  // target 为 Class 时调用静态方法；按名称和实参个数、类型匹配。
  private static Object call(Object target, String name, Object... args)
      throws ReflectiveOperationException {
    boolean isStatic = target instanceof Class;
    for (Class<?> c = isStatic ? (Class<?>) target : target.getClass();
        c != null;
        c = c.getSuperclass()) {
      for (Method m : c.getDeclaredMethods()) {
        if (m.getName().equals(name) && fits(m.getParameterTypes(), args)) {
          m.setAccessible(true);
          return m.invoke(isStatic ? null : target, args);
        }
      }
    }
    throw new NoSuchMethodException(name);
  }

  private static boolean fits(Class<?>[] types, Object[] args) {
    if (types.length != args.length) return false;
    for (int i = 0; i < types.length; i++) {
      Class<?> t =
          types[i] == int.class
              ? Integer.class
              : types[i] == boolean.class ? Boolean.class : types[i];
      if (args[i] == null ? types[i].isPrimitive() : !t.isInstance(args[i])) return false;
    }
    return true;
  }
}
