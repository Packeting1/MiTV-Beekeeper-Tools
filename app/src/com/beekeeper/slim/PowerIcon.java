package com.beekeeper.slim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** 自绘右上角的电源图标，不依赖字体或资源文件。 */
final class PowerIcon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    PowerIcon(Context c) {
        super(c);
        setFocusable(true);
        setClickable(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }
    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.28f;
        float cx = w / 2, cy = h / 2 + r * 0.1f;
        if (isFocused() || isPressed()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x44FFFFFF);
            canvas.drawCircle(w / 2, h / 2, Math.min(w, h) / 2, paint);
            paint.setStyle(Paint.Style.STROKE);
        }
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(Math.max(2f, r * 0.22f));
        canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -60, 300, false, paint);
        canvas.drawLine(cx, cy - r * 1.15f, cx, cy - r * 0.15f, paint);
    }
    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }
}
