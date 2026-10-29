package com.beekeeper.slim;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 左侧导航项：焦点移上去就切换右侧页面；选中项用蓝字和淡蓝底标出。 */
final class NavItem extends LinearLayout {

    /** 右上的红点（有新版本时显示）。 */
    final View dot;
    private final TextView label;

    NavItem(Context c, String text) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(true);
        setClickable(true);
        setId(View.generateViewId());
        setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        Ui.noDefaultHighlight(this);
        label = new TextView(c);
        label.setText(text);
        label.setTextSize(17);
        addView(label);
        dot = Ui.dot(c);
        LayoutParams lp = new LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8));
        lp.leftMargin = Ui.dp(c, 6);
        lp.bottomMargin = Ui.dp(c, 8);
        addView(dot, lp);
        select(false);
    }

    void select(boolean on) {
        label.setTextColor(on ? Ui.ACCENT : Ui.TEXT);
        label.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        setBackground(Ui.focusBg(getContext(), on ? 0x225AA9FF : Ui.CLEAR, Ui.CLEAR, Ui.CARD_FOCUS, Ui.ACCENT, 8));
    }
}
