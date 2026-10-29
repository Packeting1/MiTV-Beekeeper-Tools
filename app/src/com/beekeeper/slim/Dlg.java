package com.beekeeper.slim;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** 与应用界面一致的深色弹窗；用法和 AlertDialog.Builder 相同，按钮可聚焦并带高亮描边。 */
final class Dlg extends Dialog {

    static final int BUTTON_POSITIVE = DialogInterface.BUTTON_POSITIVE,
        BUTTON_NEGATIVE = DialogInterface.BUTTON_NEGATIVE,
        BUTTON_NEUTRAL = DialogInterface.BUTTON_NEUTRAL;

    private final Button[] buttons = new Button[3];

    private Dlg(Context c) {
        super(c, android.R.style.Theme_Material_Dialog_NoActionBar);
    }

    Button getButton(int which) {
        return buttons[-which - 1];
    }

    static final class Builder {
        private final Context c;
        private String title, message;
        private View view;
        private final String[] labels = new String[3];
        private final DialogInterface.OnClickListener[] clicks = new DialogInterface.OnClickListener[3];
        private boolean cancelable = true;
        private DialogInterface.OnCancelListener onCancel;

        Builder(Context c) { this.c = c; }

        Builder setTitle(String t) { title = t; return this; }

        Builder setMessage(String m) { message = m; return this; }

        Builder setView(View v) { view = v; return this; }

        Builder setPositiveButton(String t, DialogInterface.OnClickListener l) { return btn(BUTTON_POSITIVE, t, l); }

        Builder setNegativeButton(String t, DialogInterface.OnClickListener l) { return btn(BUTTON_NEGATIVE, t, l); }

        Builder setNeutralButton(String t, DialogInterface.OnClickListener l) { return btn(BUTTON_NEUTRAL, t, l); }

        Builder setCancelable(boolean b) { cancelable = b; return this; }

        Builder setOnCancelListener(DialogInterface.OnCancelListener l) { onCancel = l; return this; }

        private Builder btn(int which, String t, DialogInterface.OnClickListener l) {
            labels[-which - 1] = t;
            clicks[-which - 1] = l;
            return this;
        }

        Dlg create() {
            final Dlg d = new Dlg(c);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.shape(c, 0xFF1E2229, Ui.LINE, 1, 12));
            card.setPadding(Ui.dp(c, 22), Ui.dp(c, 16), Ui.dp(c, 22), Ui.dp(c, 14));
            if (title != null) {
                TextView t = new TextView(c);
                t.setText(title);
                t.setTextSize(17);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(Ui.TEXT);
                t.setPadding(0, 0, 0, Ui.dp(c, 8));
                card.addView(t);
            }
            boolean hasButtons = labels[0] != null || labels[1] != null || labels[2] != null;
            View body = view;
            if (body == null && message != null) {
                TextView m = new TextView(c);
                m.setText(message);
                m.setTextSize(12.5f);
                m.setTextColor(Ui.TEXT);
                m.setLineSpacing(0, 1.2f);
                if (hasButtons) {
                    ScrollView sv = new ScrollView(c);
                    sv.setFocusable(true);
                    sv.addView(m);
                    body = sv;
                } else {
                    // 没有按钮的是“正在处理”提示，补一条不定进度条表示在忙
                    LinearLayout busy = new LinearLayout(c);
                    busy.setOrientation(LinearLayout.VERTICAL);
                    busy.addView(m);
                    ProgressBar pb = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
                    pb.setIndeterminate(true);
                    pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
                    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    plp.topMargin = Ui.dp(c, 14);
                    busy.addView(pb, plp);
                    body = busy;
                }
            }
            if (body != null) card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            if (hasButtons) {
                LinearLayout row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = Ui.dp(c, 12);
                card.addView(row, rlp);
                // 左：中性按钮；右：取消、确定
                int[] order = { BUTTON_NEUTRAL, -100, BUTTON_NEGATIVE, BUTTON_POSITIVE };
                for (int which : order) {
                    if (which == -100) {
                        row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
                        continue;
                    }
                    final int w = which;
                    final int i = -which - 1;
                    if (labels[i] == null) continue;
                    Button b = Ui.button(c, labels[i], which == BUTTON_POSITIVE);
                    b.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            if (clicks[i] != null) clicks[i].onClick(d, w);
                            d.dismiss();
                        }
                    });
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    blp.leftMargin = Ui.dp(c, 8);
                    row.addView(b, blp);
                    d.buttons[i] = b;
                }
                Button first = d.buttons[0] != null ? d.buttons[0] : d.buttons[1];
                if (first != null) first.requestFocus();
            }
            // 外面留一圈透明边，长内容不会顶到屏幕边缘
            android.widget.FrameLayout frame = new android.widget.FrameLayout(c);
            frame.setPadding(0, Ui.dp(c, 20), 0, Ui.dp(c, 20));
            frame.addView(card);
            d.setContentView(frame);
            Window win = d.getWindow();
            win.setBackgroundDrawable(new ColorDrawable(Ui.CLEAR));
            win.setDimAmount(0.65f);
            win.setLayout(Ui.dp(c, 580), ViewGroup.LayoutParams.WRAP_CONTENT);
            d.setCancelable(cancelable);
            d.setCanceledOnTouchOutside(false);
            if (onCancel != null) d.setOnCancelListener(onCancel);
            return d;
        }

        Dlg show() {
            Dlg d = create();
            d.show();
            return d;
        }
    }
}
