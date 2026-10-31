package com.beekeeper.decorativelight;

import android.content.Context;
import android.widget.TextView;

/** 设置页弹窗共用的界面小工具。 */
final class Ui {
  static int dp(Context ctx, int value) {
    return Math.round(value * ctx.getResources().getDisplayMetrics().density);
  }

  static TextView text(Context ctx, String text, int size) {
    TextView view = new TextView(ctx);
    view.setText(text);
    view.setTextSize(size);
    return view;
  }

  private Ui() {}
}
