package com.beekeeper.nosignalaudiomode;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;

/** 在倒计时页新增“息屏”按钮，不替换原有按钮的监听。 */
final class CountdownControls {
  private static final String TAG = "NoSignalControls";
  private static XposedModule module;
  private static final String AUDIO_BUTTON_TAG = "NoSignalXiaoAi:audio-button";

  // SearchingView 的字段，在 install() 里一次性解析。
  private static Field homeField;
  private static Field standbyField;
  private static Field shutdownField;
  private static Field handlerField;
  private static Field countdownField;

  static void install(XposedModule m, ClassLoader loader) throws Throwable {
    module = m;
    Class<?> searching = loader.loadClass("com.xiaomi.mitv.tvplayer.widget.views.SearchingView");
    homeField = SessionKeeper.field(searching, "mHomeBt");
    standbyField = SessionKeeper.field(searching, "mStandby");
    shutdownField = SessionKeeper.field(searching, "mShutDownBt");
    handlerField = SessionKeeper.field(searching, "mHandler");
    countdownField = SessionKeeper.field(searching, "updateRunnable");
    XposedInterface.Hooker update =
        chain -> {
          Object result = chain.proceed();
          try {
            update((ViewGroup) chain.getThisObject());
          } catch (Throwable error) {
            SessionKeeper.error(TAG, "Countdown UI unavailable", error);
          }
          return result;
        };
    module.hook(searching.getDeclaredMethod("onFinishInflate")).intercept(update);
    module.hook(searching.getDeclaredMethod("show", Handler.class)).intercept(update);
  }

  private static void update(final ViewGroup searching) throws ReflectiveOperationException {
    replaceExplanation(searching);
    if (searching.findViewWithTag(AUDIO_BUTTON_TAG) != null) return;
    Button home = (Button) homeField.get(searching);
    Button standby = (Button) standbyField.get(searching);
    Button shutdown = (Button) shutdownField.get(searching);
    if (home == null || standby == null || shutdown == null) return;
    if (!(home.getParent() instanceof LinearLayout)) return;
    LinearLayout row = (LinearLayout) home.getParent();
    if (standby.getParent() != row || shutdown.getParent() != row) return;
    Button audio = new Button(searching.getContext(), null, 0);
    audio.setId(View.generateViewId());
    audio.setTag(AUDIO_BUTTON_TAG);
    audio.setText("息屏");
    audio.setTextSize(TypedValue.COMPLEX_UNIT_PX, standby.getTextSize());
    audio.setTextColor(standby.getTextColors());
    audio.setTypeface(standby.getTypeface());
    audio.setGravity(standby.getGravity());
    audio.setIncludeFontPadding(standby.getIncludeFontPadding());
    audio.setAllCaps(false);
    audio.setMinWidth(standby.getMinWidth());
    audio.setMinHeight(standby.getMinHeight());
    audio.setMinimumWidth(standby.getMinimumWidth());
    audio.setMinimumHeight(standby.getMinimumHeight());
    audio.setPadding(
        standby.getPaddingLeft(),
        standby.getPaddingTop(),
        standby.getPaddingRight(),
        standby.getPaddingBottom());
    Drawable background = standby.getBackground();
    if (background != null && background.getConstantState() != null) {
      audio.setBackground(
          background.getConstantState().newDrawable(searching.getResources()).mutate());
    }
    audio.setFocusable(true);
    audio.setFocusableInTouchMode(standby.isFocusableInTouchMode());
    audio.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View clicked) {
            try {
              PowerManager power =
                  (PowerManager) searching.getContext().getSystemService(Context.POWER_SERVICE);
              if (power == null) return;
              HookEntry.goToSleep.invoke(power, SystemClock.uptimeMillis(), 8, 0);
              // 息屏请求成功后才取消这次倒计时。
              Handler handler = (Handler) handlerField.get(searching);
              Runnable countdown = (Runnable) countdownField.get(searching);
              if (handler != null && countdown != null) handler.removeCallbacks(countdown);
              module.log(Log.INFO, TAG, "Audio button: requested XiaoAi mode; countdown cancelled");
            } catch (Throwable error) {
              SessionKeeper.error(TAG, "Audio button failed", error);
            }
          }
        });
    row.addView(
        audio,
        row.indexOfChild(home) + 1,
        new LinearLayout.LayoutParams((LinearLayout.LayoutParams) standby.getLayoutParams()));
    module.log(
        Log.INFO,
        TAG,
        "Countdown controls: home, audio, standby, shutdown; audio-mode explanation applied");
  }

  private static void replaceExplanation(View view) {
    if (view instanceof TextView) {
      TextView text = (TextView) view;
      if ("您的设备即将进入待机模式".contentEquals(text.getText())) {
        text.setText("您的设备即将进入音响模式");
      }
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) replaceExplanation(group.getChildAt(i));
    }
  }
}
