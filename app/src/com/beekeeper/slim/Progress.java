package com.beekeeper.slim;

import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** 下载进度弹窗的内容：一行文字加一条进度条。 */
final class Progress {

    final TextView text;
    final ProgressBar bar;
    final LinearLayout box;

    Progress(MainActivity a) {
        text = new TextView(a);
        text.setText("正在下载……0%");
        text.setTextSize(14);
        bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.dp(24), a.dp(16), a.dp(24), a.dp(8));
        box.addView(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = a.dp(10);
        box.addView(bar, lp);
    }

    void waiting(String msg) {
        bar.setIndeterminate(true);
        text.setText(msg);
    }

    void downloaded(int pct, long got, long total) {
        bar.setIndeterminate(false);
        bar.setProgress(pct);
        text.setText(String.format("正在下载……%d%%（%.1f / %.1f MB）", pct, got / 1048576.0, total / 1048576.0));
    }
}
