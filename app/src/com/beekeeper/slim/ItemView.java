package com.beekeeper.slim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 一张可聚焦的卡片：标题、说明、风险、状态，右侧是开关；action 模式右侧是箭头，点击由调用方处理。 确认键 / 点击切换开关；被锁定时仍可聚焦（能读到说明），只是不能切换。 */
final class ItemView extends LinearLayout {

  interface Listener {
    void onChange(boolean on);
  }

  /** 状态行颜色：0 灰 / 1 绿 / 2 琥珀 / 3 红。 */
  static final int NEUTRAL = 0, GOOD = 1, WARN = 2, BAD = 3;

  private final TextView title, desc, warn, status, pending;
  private final Toggle toggle;
  private boolean checked, base, locked;
  private String lockedMsg = "当前不可修改";
  private Listener listener;

  ItemView(Context c, boolean action) {
    super(c);
    setOrientation(HORIZONTAL);
    setGravity(Gravity.CENTER_VERTICAL);
    setFocusable(true);
    setClickable(true);
    setId(View.generateViewId());
    setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
    setBackground(Ui.focusBg(c, Ui.CARD, Ui.LINE, Ui.CARD_FOCUS, Ui.ACCENT, 8));
    Ui.noDefaultHighlight(this);

    LinearLayout col = new LinearLayout(c);
    col.setOrientation(VERTICAL);
    title = text(c, 15, Ui.TEXT, true);
    desc = text(c, 12, Ui.SUB, false);
    warn = text(c, 11, Ui.WARN, false);
    status = text(c, 11, Ui.SUB, false);
    col.addView(title);
    col.addView(desc);
    col.addView(warn);
    col.addView(status);
    addView(col, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

    if (action) {
      TextView arrow = text(c, 22, Ui.SUB, false);
      arrow.setText("›");
      arrow.setPadding(Ui.dp(c, 12), 0, 0, 0);
      addView(arrow);
      toggle = null;
      pending = null;
    } else {
      LinearLayout right = new LinearLayout(c);
      right.setOrientation(VERTICAL);
      right.setGravity(Gravity.CENTER_HORIZONTAL);
      right.setPadding(Ui.dp(c, 12), 0, 0, 0);
      toggle = new Toggle(c);
      right.addView(toggle, new LayoutParams(Ui.dp(c, 38), Ui.dp(c, 21)));
      pending = text(c, 10, Ui.ACCENT, false);
      pending.setText("待应用");
      pending.setVisibility(GONE);
      pending.setPadding(0, Ui.dp(c, 4), 0, 0);
      right.addView(pending);
      addView(right);
      setOnClickListener(
          new OnClickListener() {
            public void onClick(View v) {
              flip();
            }
          });
    }
  }

  private static TextView text(Context c, int sp, int color, boolean bold) {
    TextView t = new TextView(c);
    t.setTextSize(sp);
    t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
    t.setLineSpacing(0, 1.1f);
    t.setPadding(0, Ui.dp(c, 1), 0, 0);
    return t;
  }

  void setInfo(String t, String d, String w, String s, int level) {
    title.setText(t);
    desc.setText(d);
    warn.setText(w == null ? "" : "⚠ " + w);
    warn.setVisibility(w == null ? GONE : VISIBLE);
    status.setText(s == null ? "" : s);
    status.setVisibility(s == null || s.length() == 0 ? GONE : VISIBLE);
    status.setTextColor(
        level == GOOD ? Ui.GOOD : level == WARN ? Ui.WARN : level == BAD ? Ui.BAD : Ui.SUB);
  }

  private void flip() {
    if (locked) {
      Toast.makeText(getContext(), lockedMsg, Toast.LENGTH_SHORT).show();
      return;
    }
    setChecked(!checked);
    if (listener != null) listener.onChange(checked);
  }

  void setListener(Listener l) {
    listener = l;
  }

  boolean isChecked() {
    return checked;
  }

  /** 只改显示，不触发监听。 */
  void setChecked(boolean on) {
    checked = on;
    toggle.set(on);
    pending.setVisibility(changed() ? VISIBLE : GONE);
  }

  /** 以当前勾选状态作为“已生效”的基线；之后与基线不同就显示“待应用”。 */
  void markBase() {
    base = checked;
    pending.setVisibility(GONE);
  }

  boolean changed() {
    return toggle != null && checked != base;
  }

  /** 锁定时点击给出的提示；msg 为 null 用默认文字。 */
  void setLocked(boolean on, String msg) {
    lockedMsg = msg == null ? "当前不可修改" : msg;
    setLocked(on);
  }

  void setLocked(boolean on) {
    locked = on;
    setAlpha(on ? 0.55f : 1f);
  }

  /** 自绘的开关：不抢焦点，整张卡片才是点击对象。 */
  private static final class Toggle extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean on;

    Toggle(Context c) {
      super(c);
    }

    void set(boolean v) {
      on = v;
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth(), h = getHeight();
      paint.setColor(on ? Ui.ACCENT : 0xFF4A515C);
      rect.set(0, 0, w, h);
      canvas.drawRoundRect(rect, h / 2, h / 2, paint);
      paint.setColor(Ui.TEXT);
      float r = h / 2 - h * 0.14f;
      canvas.drawCircle(on ? w - h / 2 : h / 2, h / 2, r, paint);
    }
  }
}
