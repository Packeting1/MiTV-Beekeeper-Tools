package com.beekeeper.decorativelight;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/** 设置页弹窗共用的界面小工具。 */
final class Ui {
  static int dp(Context ctx, int value) {
    return Math.round(value * ctx.getResources().getDisplayMetrics().density);
  }

  /** 菜单键悬浮窗的 Context 不是 Activity（没有窗口令牌），弹窗必须改用与悬浮窗相同的系统窗口类型，否则 BadTokenException 会让设置进程崩溃。 */
  static void show(Context ctx, Dialog dialog) {
    while (ctx instanceof ContextWrapper && !(ctx instanceof Activity)) {
      ctx = ((ContextWrapper) ctx).getBaseContext();
    }
    if (!(ctx instanceof Activity)) {
      dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
    }
    dialog.show();
  }

  /** 遥控器操作的按钮：获得焦点时高亮，系统默认样式在这里看不出选中。 */
  static Button button(Context ctx, String text) {
    Button button = new Button(ctx);
    button.setText(text);
    GradientDrawable normal = new GradientDrawable();
    normal.setColor(Color.WHITE);
    normal.setCornerRadius(dp(ctx, 4));
    GradientDrawable focused = new GradientDrawable();
    focused.setColor(Color.rgb(61, 123, 255));
    focused.setCornerRadius(dp(ctx, 4));
    StateListDrawable background = new StateListDrawable();
    background.addState(new int[] {android.R.attr.state_focused}, focused);
    background.addState(new int[] {android.R.attr.state_pressed}, focused);
    background.addState(new int[0], normal);
    button.setBackground(background);
    button.setTextColor(
        new ColorStateList(
            new int[][] {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
            new int[] {Color.WHITE, Color.WHITE, Color.BLACK}));
    return button;
  }

  static TextView text(Context ctx, String text, int size) {
    TextView view = new TextView(ctx);
    view.setText(text);
    view.setTextSize(size);
    view.setLineSpacing(dp(ctx, 3), 1);
    return view;
  }

  private Ui() {}
}
