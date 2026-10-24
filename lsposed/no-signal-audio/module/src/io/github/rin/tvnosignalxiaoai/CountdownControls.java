package io.github.rin.tvnosignalxiaoai;

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
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Add an explicit audio-mode action without replacing the original button listeners. */
final class CountdownControls {
    private static final String TAG = "NoSignalControls";
    private static final String AUDIO_BUTTON_TAG = "NoSignalXiaoAi:audio-button";

    static void install(ClassLoader loader) {
        Class<?> searching = XposedHelpers.findClass(
                "com.xiaomi.mitv.tvplayer.widget.views.SearchingView", loader);
        XC_MethodHook update = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable()) return;
                try { update((ViewGroup) p.thisObject); }
                catch (Throwable error) {
                    Log.e(TAG, "Countdown UI unavailable: " + error.getClass().getSimpleName());
                }
            }
        };
        XposedHelpers.findAndHookMethod(searching, "onFinishInflate", update);
        XposedHelpers.findAndHookMethod(searching, "show", Handler.class, update);
    }

    private static void update(final ViewGroup searching) {
        replaceExplanation(searching);
        if (searching.findViewWithTag(AUDIO_BUTTON_TAG) != null) return;
        Button home = (Button) XposedHelpers.getObjectField(searching, "mHomeBt");
        Button standby = (Button) XposedHelpers.getObjectField(searching, "mStandby");
        Button shutdown = (Button) XposedHelpers.getObjectField(searching, "mShutDownBt");
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
        audio.setPadding(standby.getPaddingLeft(), standby.getPaddingTop(),
                standby.getPaddingRight(), standby.getPaddingBottom());
        Drawable background = standby.getBackground();
        if (background != null && background.getConstantState() != null) {
            audio.setBackground(background.getConstantState()
                    .newDrawable(searching.getResources()).mutate());
        }
        audio.setFocusable(true);
        audio.setFocusableInTouchMode(standby.isFocusableInTouchMode());
        audio.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View clicked) {
                try {
                    PowerManager power = (PowerManager) searching.getContext()
                            .getSystemService(Context.POWER_SERVICE);
                    if (power == null) return;
                    XposedHelpers.callMethod(power, "goToSleep", SystemClock.uptimeMillis(), 8, 0);
                    // Cancel this countdown only after the sleep request succeeds.
                    Handler handler = (Handler) XposedHelpers.getObjectField(searching, "mHandler");
                    Runnable countdown = (Runnable) XposedHelpers.getObjectField(searching, "updateRunnable");
                    if (handler != null && countdown != null) handler.removeCallbacks(countdown);
                    Log.i(TAG, "Audio button: requested XiaoAi mode; countdown cancelled");
                } catch (Throwable error) {
                    Log.e(TAG, "Audio button failed: " + error.getClass().getSimpleName());
                }
            }
        });
        row.addView(audio, row.indexOfChild(home) + 1,
                new LinearLayout.LayoutParams((LinearLayout.LayoutParams) standby.getLayoutParams()));
        Log.i(TAG, "Countdown controls: home, audio, standby, shutdown; audio-mode explanation applied");
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
