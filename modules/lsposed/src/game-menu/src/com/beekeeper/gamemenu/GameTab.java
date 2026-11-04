package com.beekeeper.gamemenu;

import android.R;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 快捷设置（MonitorMainView）左侧新增“游戏”标签。右侧页面另建一个“更多设置”页（MonitorProfessionalSettingView）实例： 保留 320Hz
 * 竞技模式、FreeSync（EDID 切换逻辑复杂，原样复用），去掉其它项，加预置场景、准星、暗区增强、灰阶响应时间；
 * 原“更多设置”页里的这几项随之移除。多档项点击后在右侧展开选项列表（原版第三页）。
 */
final class GameTab {

  private static final String MON = "com.xiaomi.mitv.settings.monitor.";
  private static final String WIDGET = "com.xiaomi.mitv.settings.widget.";
  private static final String UTILS = "com.xiaomi.mitv.settings.util.Utils";
  private static final String PICTURE = "mitv.display.PictureSettingsManager";
  private static final String FRONT_SIGHT_KEY = "front_sight_index";
  private static final int INDEX = 6;
  private static final int THIRD_ITEM_WIDTH = 0x7f0702e8;
  private static final int VALUE_ID = 0x7f0a034f;
  private static final String[] PRESET_NAMES = {"个人设定", "FPS", "RPG", "RTS", "MOBA", "SPT"};
  private static final int[][] PRESET_VALUES = {
    {4, 24, 25, 26, 27, 28},
    {15, 29, 30, 31, 32, 33}
  };

  private static final ThreadLocal<Boolean> BUILDING = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> PM2_OVERRIDE = new ThreadLocal<>();

  private final XposedModule module;
  private final ClassLoader cl;
  private final int buttonId = View.generateViewId();

  private Class<?> mainClass;
  private Class<?> pageClass;
  private Class<?> itemClass;
  private Field fContext;
  private Field fLastFocused;
  private Field fRight;
  private Field fMap;
  private Field fDisplay;
  private Field fCategory;
  private Field[] fMoved;
  private Method getHolder;
  private Method createCategory;
  private Field fThirdView;
  private Field fThirdHolder;
  private Field fThirdCategory;
  private Field fWhiteLine;
  private Field fShowThird;
  private Class<?> radioClass;
  private Class<?> radioListener;
  private Object picture;

  private Object owner;
  private View button;
  private Object page;
  private Object lastItem;

  GameTab(XposedModule module, ClassLoader cl) {
    this.module = module;
    this.cl = cl;
  }

  private void log(int level, String msg, Throwable t) {
    module.log(level, HookEntry.TAG, msg, t);
  }

  void install() throws Exception {
    mainClass = cl.loadClass(MON + "MonitorMainView");
    pageClass = cl.loadClass(MON + "MonitorProfessionalSettingView");
    itemClass = cl.loadClass(WIDGET + "MonitorSettingItem");
    fContext = field(mainClass, "mContext");
    fLastFocused = field(mainClass, "mLastFocused");
    fRight = field(mainClass, "mRightView");
    fMap = field(mainClass, "mIndexToButtonMap");
    fDisplay = field(mainClass, "mDisplayButton");
    fCategory = field(pageClass, "mCategory");
    fMoved =
        new Field[] {
          field(pageClass, "m320HzItem"),
          field(pageClass, "mFreeSyncItem"),
          field(pageClass, "mFPSCounterItem"),
          field(pageClass, "mTimerItem"),
          field(pageClass, "mStopwatchItem")
        };
    getHolder = fCategory.getType().getMethod("getContentHolder");
    createCategory = fCategory.getType().getMethod("createNewCategory", ViewGroup.class);
    fThirdView = field(pageClass, "mThirdPageView");
    fThirdHolder = field(pageClass, "mThirdContentHolder");
    fThirdCategory = field(pageClass, "mThirdPageCategory");
    fWhiteLine = field(pageClass, "mWhiteLine");
    fShowThird = field(pageClass, "mIsShowThirdPage");
    radioClass = cl.loadClass(WIDGET + "MonitorRadioItem");
    radioListener = cl.loadClass(WIDGET + "MonitorRadioItem$OnRadioItemClickListener");

    module
        .hook(mainClass.getDeclaredMethod("createMapping"))
        .intercept(chain -> {
          Object result = chain.proceed();
          try {
            addButton(chain.getThisObject());
          } catch (Throwable t) {
            log(Log.ERROR, "add game tab failed", t);
          }
          return result;
        });

    module
        .hook(mainClass.getDeclaredMethod("loadView", int.class))
        .intercept(chain -> {
          if ((Integer) chain.getArg(0) != buttonId) {
            return chain.proceed();
          }
          try {
            loadPage(chain.getThisObject());
          } catch (Throwable t) {
            log(Log.ERROR, "load game page failed", t);
          }
          return null;
        });

    module
        .hook(mainClass.getDeclaredMethod("comToRight"))
        .intercept(chain -> {
          Object result = chain.proceed();
          try {
            Object main = chain.getThisObject();
            if (fLastFocused.getInt(main) == INDEX && page != null) {
              pageClass.getMethod("enterSecondPage").invoke(page);
              button.setSelected(true);
            }
          } catch (Throwable t) {
            log(Log.ERROR, "enter game page failed", t);
          }
          return result;
        });

    module
        .hook(pageClass.getDeclaredMethod("init"))
        .setPriority(XposedInterface.PRIORITY_HIGHEST)
        .intercept(chain -> {
          Object result = chain.proceed();
          try {
            Object p = chain.getThisObject();
            if (Boolean.TRUE.equals(BUILDING.get())) {
              addHiddenItems(p);
            }
            prune(p);
          } catch (Throwable t) {
            log(Log.ERROR, "prune page failed", t);
          }
          return result;
        });

    Method pm2 = cl.loadClass(UTILS).getDeclaredMethod("isSupportPm2Monitor");
    module
        .hook(pm2)
        .intercept(chain -> {
          Boolean forced = PM2_OVERRIDE.get();
          return forced != null ? forced : chain.proceed();
        });

    module
        .hook(pageClass.getDeclaredMethod("backToRight"))
        .intercept(chain -> {
          fillValueViews(chain.getThisObject());
          Object result = chain.proceed();
          if (chain.getThisObject() == page) {
            try {
              // restoreUnSelected 不会清除 transform() 设的选中态，要用 restore()
              ViewGroup holder = holder(page);
              for (int i = 0; i < holder.getChildCount(); i++) {
                View c = holder.getChildAt(i);
                if (c.getClass() == itemClass) {
                  itemClass.getMethod("restore").invoke(c);
                }
              }
              if (lastItem != null) {
                ((View) lastItem).requestFocus();
              }
            } catch (Throwable t) {
              log(Log.WARN, "cannot restore menu items", t);
            }
            lastItem = null;
          }
          return result;
        });
    // 原版在第 0（或第 2）项上取焦点，移除两项后可能越界或落到别的项，统一改成第一个可见可聚焦项
    module
        .hook(pageClass.getMethod("enterSecondPage"))
        .intercept(chain -> {
          Object result = null;
          try {
            result = chain.proceed();
          } catch (Throwable t) {
            log(Log.ERROR, "enterSecondPage failed", t);
          }
          focusFirst(chain.getThisObject());
          return result;
        });
    log(Log.INFO, "settings hooks installed", null);
  }

  /** 原版 restore() 会读每个项的 mValue，没调过 setValue* 的项为空会 NPE。 */
  private void fillValueViews(Object p) {
    try {
      Field value = field(itemClass, "mValue");
      for (Field f : pageClass.getDeclaredFields()) {
        if (f.getType() != itemClass) {
          continue;
        }
        f.setAccessible(true);
        View item = (View) f.get(p);
        if (item != null && value.get(item) == null) {
          value.set(item, item.findViewById(VALUE_ID));
          log(Log.INFO, "filled mValue of " + f.getName(), null);
        }
      }
    } catch (Throwable t) {
      log(Log.WARN, "cannot fill value views", t);
    }
  }

  private static Field field(Class<?> c, String name) throws NoSuchFieldException {
    Field f = c.getDeclaredField(name);
    f.setAccessible(true);
    return f;
  }

  private ViewGroup holder(Object page) throws Exception {
    return (ViewGroup) getHolder.invoke(fCategory.get(page));
  }

  private List<View> moved(Object p) throws Exception {
    List<View> list = new ArrayList<>();
    for (Field f : fMoved) {
      View v = (View) f.get(p);
      if (v != null) {
        list.add(v);
      }
    }
    return list;
  }

  private void prune(Object p) throws Exception {
    ViewGroup holder = holder(p);
    List<View> keep = moved(p);
    if (Boolean.TRUE.equals(BUILDING.get())) {
      for (int i = holder.getChildCount() - 1; i >= 0; i--) {
        if (!keep.contains(holder.getChildAt(i))) {
          holder.removeViewAt(i);
        }
      }
    } else {
      for (View v : keep) {
        holder.removeView(v);
      }
    }
  }

  private void addHiddenItems(Object p) {
    String[][] hidden = {
      {"mFPSCounterItem", "initFPSCounterItem"},
      {"mTimerItem", "initTimerItem"},
      {"mStopwatchItem", "initStopWatchCounterItem"}
    };
    for (String[] h : hidden) {
      try {
        Field f = field(pageClass, h[0]);
        if (f.get(p) != null) {
          continue;
        }
        Method init = pageClass.getDeclaredMethod(h[1]);
        init.setAccessible(true);
        PM2_OVERRIDE.set(Boolean.TRUE);
        try {
          init.invoke(p);
        } finally {
          PM2_OVERRIDE.remove();
        }
      } catch (Throwable t) {
        log(Log.WARN, "add hidden item " + h[0] + " failed", t);
      }
    }
  }

  private void focusFirst(Object p) {
    try {
      ViewGroup holder = holder(p);
      for (int i = 0; i < holder.getChildCount(); i++) {
        View c = holder.getChildAt(i);
        if (c.getVisibility() == View.VISIBLE && c.isFocusable() && c.requestFocus()) {
          return;
        }
      }
    } catch (Throwable t) {
      log(Log.WARN, "cannot focus first item", t);
    }
  }

  private void addButton(Object main) throws Exception {
    Object display = fDisplay.get(main);
    Class<?> bc = display.getClass();
    Context ctx = (Context) fContext.get(main);
    LinearLayout src = (LinearLayout) display;
    LinearLayout btn = (LinearLayout) bc.getConstructor(Context.class).newInstance(ctx);

    for (String name :
        new String[] {"titleStr", "textSize", "textColorStateList", "backGroundDrawable"}) {
      Field f = field(bc, name);
      f.set(btn, f.get(display));
    }
    field(bc, "titleStr").set(btn, "游戏");
    Drawable bg = (Drawable) field(bc, "backGroundDrawable").get(display);
    if (bg != null && bg.getConstantState() != null) {
      field(bc, "backGroundDrawable").set(btn, bg.getConstantState().newDrawable().mutate());
    }
    Drawable icon = gameIcon(ctx);
    if (icon != null) {
      field(bc, "iconDrawable").set(btn, icon);
    } else {
      Drawable d = (Drawable) field(bc, "iconDrawable").get(display);
      field(bc, "iconDrawable").set(btn, d.getConstantState().newDrawable());
    }

    btn.setId(buttonId);
    btn.setOrientation(src.getOrientation());
    btn.setGravity(src.getGravity());
    btn.setPadding(
        src.getPaddingLeft(), src.getPaddingTop(), src.getPaddingRight(), src.getPaddingBottom());
    btn.setLayoutParams(
        new LinearLayout.LayoutParams((LinearLayout.LayoutParams) src.getLayoutParams()));
    Method finish = bc.getDeclaredMethod("onFinishInflate");
    finish.setAccessible(true);
    finish.invoke(btn);

    for (Method m : bc.getMethods()) {
      if (m.getName().equals("setOnMiuiFocusChangeListener")) {
        m.invoke(btn, main);
      }
    }
    ViewGroup parent = (ViewGroup) src.getParent();
    parent.addView(btn, parent.indexOfChild(src) + 1);

    @SuppressWarnings("unchecked")
    Map<Integer, Object> map = (Map<Integer, Object>) fMap.get(main);
    map.put(INDEX, btn);
    button = btn;
    owner = null;
    page = null;
  }

  private static Drawable gameIcon(Context ctx) {
    Resources r = ctx.getResources();
    int res =
        r.getIdentifier("mitv_icon_game_handle_none_normal", "drawable", ctx.getPackageName());
    if (res == 0 || !(r.getDrawable(res, null) instanceof BitmapDrawable)) {
      return null;
    }
    Bitmap src = ((BitmapDrawable) r.getDrawable(res, null)).getBitmap();
    StateListDrawable d = new StateListDrawable();
    d.addState(new int[] {R.attr.state_focused}, glyph(r, src, 0xFFFFFF));
    d.addState(new int[0], glyph(r, src, 0xA8AAAA));
    return d;
  }

  /** 原图是紫色底白手柄，取白色部分作透明度并染成指定颜色，和其它标签的单色图标一致。 */
  private static Drawable glyph(Resources r, Bitmap in, int rgb) {
    int w = in.getWidth();
    int h = in.getHeight();
    int[] px = new int[w * h];
    in.getPixels(px, 0, w, 0, 0, w, h);
    for (int i = 0; i < px.length; i++) {
      int c = px[i];
      int min = Math.min(Math.min((c >> 16) & 0xff, (c >> 8) & 0xff), c & 0xff);
      int a = Math.max(0, Math.min(255, (min - 88) * 255 / (255 - 88))) * (c >>> 24) / 255;
      px[i] = (a << 24) | rgb;
    }
    Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    out.setPixels(px, 0, w, 0, 0, w, h);
    return new BitmapDrawable(r, out);
  }

  private void loadPage(Object main) throws Exception {
    if (owner != main || page == null) {
      Context ctx = (Context) fContext.get(main);
      Object p;
      BUILDING.set(Boolean.TRUE);
      try {
        p = pageClass.getConstructor(Context.class).newInstance(ctx);
      } finally {
        BUILDING.remove();
      }
      addItems(p, ctx);
      pageClass.getMethod("refreshItemState").invoke(p);
      pageClass.getMethod("updateFreeSyncState").invoke(p);
      page = p;
      owner = main;
    }
    // 监听器是 MonitorMainView 上的单个字段，切到别的标签页会被换掉，缓存的页面每次显示都要重新挂上，
    // 否则在二级菜单里按返回键会被当成“退出右侧”，二级菜单留在原地
    invokeByName(main, "setOnThirdPageListen", page);
    invokeByName(main, "setOnParamsReportListen", page);
    fLastFocused.setInt(main, INDEX);
    ((ViewGroup) fRight.get(main)).addView((View) page);
  }

  private static void invokeByName(Object target, String name, Object arg) throws Exception {
    for (Method m : target.getClass().getDeclaredMethods()) {
      if (m.getName().equals(name) && m.getParameterTypes().length == 1) {
        m.setAccessible(true);
        m.invoke(target, arg);
        return;
      }
    }
    throw new NoSuchMethodException(name);
  }

  private void addItems(Object p, Context ctx) throws Exception {
    ViewGroup holder = holder(p);
    List<View> keep = moved(p);
    holder.removeAllViews();
    Object cat = fCategory.get(p);

    Object pm = pictureManager();
    Method sceneMode = pm.getClass().getMethod("getSceneMode");
    Method getPreset = pm.getClass().getMethod("getPresetScenario");
    Method setPreset = pm.getClass().getMethod("setPresetScenario", int.class);
    Method getStretch = pm.getClass().getMethod("getBlackStretch");
    Method setStretch = pm.getClass().getMethod("setBlackStretch", int.class);
    Method getResponse = pm.getClass().getMethod("getResponseTime");
    Method setResponse = pm.getClass().getMethod("setResponseTime", int.class);

    Supplier<int[]> family = () -> presetValues((int) call(sceneMode, pm));
    IntSupplier presetGet =
        () -> {
          int[] values = family.get();
          int cur = (int) call(getPreset, pm);
          for (int i = 0; values != null && i < values.length; i++) {
            if (values[i] == cur) {
              return i;
            }
          }
          return 0;
        };
    IntConsumer presetSet = i -> call(setPreset, pm, family.get()[i]);
    menuItem(p, ctx, cat, "预置场景", PRESET_NAMES, presetGet, presetSet, () -> family.get() != null);

    ContentResolver cr = ctx.getContentResolver();
    menuItem(
        p,
        ctx,
        cat,
        "准星",
        new String[] {"关闭", "准星一", "准星二", "准星三", "准星四", "准星五"},
        () -> Settings.Global.getInt(cr, FRONT_SIGHT_KEY, 0),
        i -> {
          Settings.Global.putInt(cr, FRONT_SIGHT_KEY, i);
          ctx.sendBroadcast(
              new Intent(HookEntry.ACTION_FRONT_SIGHT)
                  .setPackage(HookEntry.PLAYER)
                  .putExtra("index", i));
        },
        () -> true);
    menuItem(
        p,
        ctx,
        cat,
        "暗区增强",
        new String[] {"关闭", "低", "中", "高"},
        () -> (int) call(getStretch, pm),
        i -> call(setStretch, pm, i),
        () -> true);

    View hz320 = (View) fMoved[0].get(p);
    if (hz320 != null) {
      try {
        hz320
            .getClass()
            .getMethod("setDescription", String.class)
            .invoke(hz320, "开启后支持 1080P 320Hz 刷新率");
      } catch (Throwable t) {
        log(Log.WARN, "320Hz description failed", t);
      }
    }
    for (View v : keep) {
      holder.addView(v);
    }

    menuItem(
        p,
        ctx,
        cat,
        "灰阶响应时间",
        new String[] {"普通", "捷速", "高速"},
        () -> (int) call(getResponse, pm),
        i -> call(setResponse, pm, i),
        () -> true);
  }

  private Object pictureManager() throws Exception {
    if (picture == null) {
      picture = cl.loadClass(PICTURE).getMethod("getInstance").invoke(null);
    }
    return picture;
  }

  private static int[] presetValues(int scene) {
    for (int[] values : PRESET_VALUES) {
      for (int v : values) {
        if (v == scene) {
          return values;
        }
      }
    }
    return null;
  }

  /* 失败返回 -1：不是合法选项下标，界面上表现为“没有选中项”，而不是误显示成第 0 项。 */
  private Object call(Method m, Object target, Object... args) {
    try {
      return m.invoke(target, args);
    } catch (Throwable t) {
      log(Log.ERROR, m.getName() + " failed", t);
      return -1;
    }
  }

  private View menuItem(
      Object p,
      Context ctx,
      Object cat,
      String title,
      String[] names,
      IntSupplier get,
      IntConsumer set,
      BooleanSupplier valid)
      throws Exception {
    Object item =
        itemClass.getMethod("createItem", Context.class, ViewGroup.class).invoke(null, ctx, cat);
    itemClass.getMethod("setTitle", String.class).invoke(item, title);
    itemClass.getMethod("setRightArrowVisibility", int.class).invoke(item, View.VISIBLE);
    itemClass
        .getMethod("setOnClickListener", View.OnClickListener.class)
        .invoke(
            item,
            (View.OnClickListener)
                v -> {
                  if (!valid.getAsBoolean()) {
                    return;
                  }
                  try {
                    openThird(p, ctx, item, names, get.getAsInt(), set);
                  } catch (Throwable t) {
                    log(Log.ERROR, "open " + title + " failed", t);
                  }
                });
    if (!valid.getAsBoolean()) {
      ((View) item).setAlpha(0.4f);
    }
    return (View) item;
  }

  private void openThird(
      Object p, Context ctx, Object parentItem, String[] names, int current, IntConsumer pick)
      throws Exception {
    ViewGroup third = (ViewGroup) fThirdView.get(p);
    third.removeAllViews();
    Object cat = createCategory.invoke(null, third);
    fThirdCategory.set(p, cat);
    int width = ctx.getResources().getDimensionPixelSize(THIRD_ITEM_WIDTH);
    Method create = radioClass.getMethod("createItem", Context.class, ViewGroup.class);
    View focus = null;
    for (int i = 0; i < names.length; i++) {
      Object r = create.invoke(null, ctx, cat);
      radioClass.getMethod("setItemWidth", int.class).invoke(r, width);
      radioClass.getMethod("setTitle", String.class).invoke(r, names[i]);
      radioClass.getMethod("setSelect", boolean.class).invoke(r, i == current);
      int index = i;
      Object listener =
          Proxy.newProxyInstance(
              cl,
              new Class<?>[] {radioListener},
              (proxy, m, args) -> {
                if (m.getName().equals("onRadioItemClick")) {
                  pick.accept(index);
                  closeThird(p);
                }
                return null;
              });
      radioClass.getMethod("setOnRadioItemClickListener", radioListener).invoke(r, listener);
      if (i == current || focus == null) {
        focus = (View) r;
      }
    }
    for (Object v : new Object[] {fWhiteLine.get(p), third, fThirdHolder.get(p)}) {
      ((View) v).setVisibility(View.VISIBLE);
    }
    fShowThird.setBoolean(p, true);
    lastItem = parentItem;
    itemClass.getMethod("transform").invoke(parentItem);
    if (focus != null) {
      focus.requestFocus();
    }
  }

  private void closeThird(Object p) throws Exception {
    ((ViewGroup) fThirdView.get(p)).removeAllViews();
    for (Object v : new Object[] {fWhiteLine.get(p), fThirdView.get(p), fThirdHolder.get(p)}) {
      ((View) v).setVisibility(View.INVISIBLE);
    }
    fShowThird.setBoolean(p, false);
    Method back = pageClass.getDeclaredMethod("backToRight");
    back.setAccessible(true);
    back.invoke(p);
  }
}
