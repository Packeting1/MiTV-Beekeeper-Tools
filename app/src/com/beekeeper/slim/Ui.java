package com.beekeeper.slim;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;

/** 界面配色和控件样式；所有可聚焦控件在获得焦点时统一加亮色描边，遥控器上一眼能看到当前位置。 */
final class Ui {

    private Ui() { }

    static final int BG = 0xFF111418, PANEL = 0xFF181B21, CARD = 0xFF20252C, CARD_FOCUS = 0xFF2A3342,
        LINE = 0xFF2C323B, ACCENT = 0xFF5AA9FF, TEXT = 0xFFECEFF4, SUB = 0xFF9AA4B2,
        GOOD = 0xFF5BD68A, WARN = 0xFFFFB74D, BAD = 0xFFFF6B6B, CLEAR = 0x00000000;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context c, int fill, int stroke, float strokeDp, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    /** 普通态：fill + line 细边；聚焦/按下态：focusFill + focusStroke 粗边。 */
    static StateListDrawable focusBg(Context c, int fill, int line, int focusFill, int focusStroke, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = shape(c, focusFill, focusStroke, 2, radiusDp);
        s.addState(new int[] { android.R.attr.state_focused }, f);
        s.addState(new int[] { android.R.attr.state_pressed }, f);
        s.addState(new int[] {}, shape(c, fill, line, 1, radiusDp));
        return s;
    }

    /** 去掉系统在已处理焦点态的控件上额外叠加的高亮。 */
    static void noDefaultHighlight(View v) {
        if (android.os.Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);
    }

    static Button button(Context c, String text, boolean primary) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setMinimumHeight(dp(c, 38));
        b.setMinimumWidth(dp(c, 56));
        b.setPadding(dp(c, 14), dp(c, 5), dp(c, 14), dp(c, 5));
        b.setStateListAnimator(null);
        b.setTextColor(new ColorStateList(
            new int[][] { { -android.R.attr.state_enabled }, {} },
            new int[] { 0x66FFFFFF, primary ? 0xFF08111F : TEXT }));
        b.setBackground(primary
            ? focusBg(c, 0xFF3D8BFF, 0xFF3D8BFF, 0xFF74B4FF, 0xFFFFFFFF, 8)
            : focusBg(c, 0xFF2B313A, LINE, 0xFF384252, ACCENT, 8));
        noDefaultHighlight(b);
        return b;
    }

    static View dot(Context c) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFFF44336);
        v.setBackground(g);
        v.setVisibility(View.GONE);
        return v;
    }
}
