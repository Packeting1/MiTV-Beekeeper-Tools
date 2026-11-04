package com.beekeeper.signalinfobadge;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/*
 * 切换信号源时左上角的信息面板（图标 + 端口名 + 分辨率@帧率，269x93dp）改成一行小药丸，
 * 30dp 高、黑色约 80% 透明、白色粗体字，位置仍在左上角，只是缩小并略微上移。
 * 面板的显示时机和 5 秒后隐藏的逻辑不动，只在 HDMISourceInfoView.setData 填完文字后改外观，并加上淡入淡出。
 */
public final class HookEntry extends XposedModule {

  private static final String TAG = "SignalInfoBadge";
  private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
  private static final String SETTINGS = "com.xiaomi.mitv.settings";
  private static final String KEY_ENABLED = "signalinfobadge_enabled";
  private static final String INFO_VIEW =
      "com.xiaomi.mitv.tvplayer.widget.views.HDMISourceInfoView";

  private static final String ACTIVITY = "com.xiaomi.mitv.tvplayer.ExternalSourceActivity";
  private static final long FADE_IN_MS = 250;
  private static final long FADE_OUT_MS = 400;

  private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());

  private boolean fading;
  private boolean passThrough;

  @Override
  public void onModuleLoaded(ModuleLoadedParam param) {
    log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
  }

  @Override
  public void onPackageLoaded(PackageLoadedParam param) {
    String pkg = param.getPackageName();
    if (SETTINGS.equals(pkg) && HOOKED.add(SETTINGS)) {
      try {
        SettingsSwitch.install(this, param.getDefaultClassLoader(), TAG, "信号源角标", KEY_ENABLED);
      } catch (Throwable t) {
        log(Log.ERROR, TAG, "cannot add settings switch", t);
      }
      return;
    }
    if (!PLAYER.equals(pkg) || !HOOKED.add(PLAYER)) {
      return;
    }
    try {
      Class<?> c = param.getDefaultClassLoader().loadClass(INFO_VIEW);
      Field icon = field(c, "mIcon");
      Field name = field(c, "mName");
      Field info = field(c, "mInfo");
      hook(c.getDeclaredMethod("setData", int.class, String.class, String.class))
          .intercept(chain -> {
            Object result = chain.proceed();
            try {
              LinearLayout v = (LinearLayout) chain.getThisObject();
              if (!SettingsSwitch.enabled(v.getContext(), KEY_ENABLED)) {
                return result;
              }
              restyle(v, (View) icon.get(v), (TextView) name.get(v), (TextView) info.get(v));
            } catch (Throwable t) {
              log(Log.WARN, TAG, "cannot restyle source info", t);
            }
            return result;
          });
      hookFade(param.getDefaultClassLoader());
      log(Log.INFO, TAG, "source info hook installed");
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "cannot hook source info", t);
    }
  }

  // 原版直接 setVisibility 显示/隐藏；这里加上淡入（250ms）和淡出（400ms）。
  // 淡出期间推迟真正的 hideHdmiInfoView，期间又要显示时先立即收起再重新显示，否则原版会因“仍在显示”直接返回
  private void hookFade(ClassLoader cl) throws Exception {
    Class<?> a = cl.loadClass(ACTIVITY);
    Field view = field(a, "mHdmiSourceInfoView");
    Method show = a.getDeclaredMethod("infoShow");
    Method hide = a.getDeclaredMethod("hideHdmiInfoView");
    hide.setAccessible(true);

    hook(show)
        .intercept(chain -> {
          Object act = chain.getThisObject();
          View v = (View) view.get(act);
          if (v == null) {
            return chain.proceed();
          }
          if (!SettingsSwitch.enabled(v.getContext(), KEY_ENABLED)) {
            return null;
          }
          if (fading) {
            fading = false;
            v.animate().setListener(null).cancel();
            v.setAlpha(1f);
            hideNow(hide, act);
          }
          boolean wasVisible = v.getVisibility() == View.VISIBLE;
          Object result = chain.proceed();
          if (!wasVisible && v.getVisibility() == View.VISIBLE) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(FADE_IN_MS).start();
          }
          return result;
        });

    hook(hide)
        .intercept(chain -> {
          Object act = chain.getThisObject();
          View v = (View) view.get(act);
          if (passThrough
              || v == null
              || v.getVisibility() != View.VISIBLE
              || !SettingsSwitch.enabled(v.getContext(), KEY_ENABLED)) {
            return chain.proceed();
          }
          if (fading) {
            return null;
          }
          fading = true;
          v.animate()
              .alpha(0f)
              .setDuration(FADE_OUT_MS)
              .setListener(
                  new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                      if (fading) {
                        fading = false;
                        v.setAlpha(1f);
                        hideNow(hide, act);
                      }
                    }
                  })
              .start();
          return null;
        });
  }

  private void hideNow(Method hide, Object act) {
    passThrough = true;
    try {
      hide.invoke(act);
    } catch (Throwable t) {
      log(Log.WARN, TAG, "cannot hide source info", t);
    } finally {
      passThrough = false;
    }
  }

  private static Field field(Class<?> c, String name) throws NoSuchFieldException {
    Field f = c.getDeclaredField(name);
    f.setAccessible(true);
    return f;
  }

  private static void restyle(LinearLayout v, View icon, TextView name, TextView info) {
    float dp = v.getResources().getDisplayMetrics().density;
    int height = Math.round(30 * dp);

    name.setText(name.getText() + " · " + info.getText());
    icon.setVisibility(View.GONE);
    info.setVisibility(View.GONE);

    name.setAlpha(1f);
    name.setTextColor(Color.WHITE);
    name.setTypeface(Typeface.DEFAULT_BOLD);
    name.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * 0.5f);
    name.setIncludeFontPadding(false);
    name.setEllipsize(null);
    name.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    ((View) name.getParent()).setLayoutParams(new LinearLayout.LayoutParams(-2, -2));

    GradientDrawable bg = new GradientDrawable();
    bg.setColor(Color.argb(204, 0, 0, 0));
    bg.setCornerRadius(height / 2f);
    v.setBackground(bg);
    v.setGravity(Gravity.CENTER_VERTICAL);
    v.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);

    ViewGroup.LayoutParams lp = v.getLayoutParams();
    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
    lp.height = height;
    if (lp instanceof ViewGroup.MarginLayoutParams) {
      ((ViewGroup.MarginLayoutParams) lp)
          .setMargins(Math.round(30 * dp), Math.round(30 * dp), 0, 0);
    }
    v.setLayoutParams(lp);
  }
}
