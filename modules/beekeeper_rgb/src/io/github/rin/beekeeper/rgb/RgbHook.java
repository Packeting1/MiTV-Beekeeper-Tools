package io.github.rin.beekeeper.rgb;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class RgbHook implements IXposedHookLoadPackage {
    private static final String HEX = "beekeeper_rgb_hex";
    private static final String ENABLED = "beekeeper_rgb_enabled";
    private static final String TAG = "BeekeeperRGB";
    private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();
    private static final Set<Class<?>> MANAGERS = ConcurrentHashMap.newKeySet();
    private static final Set<View> ADDED = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!lp.packageName.equals("com.xiaomi.mitv.settings") && !lp.packageName.equals("mitv.service")) return;
        log("loaded v1.4.1 in " + lp.packageName);
        try {
            XposedHelpers.findAndHookMethod("mitv.display.PictureSettingsManager", lp.classLoader,
                    "getInstance", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getResult() != null) hookManager(p.getResult().getClass());
                }
            });
        } catch (Throwable e) { log(e); }
        if (!lp.packageName.equals("com.xiaomi.mitv.settings")) return;
        try {
            XposedHelpers.findAndHookMethod(Settings.Global.class, "putInt", ContentResolver.class,
                    String.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(APPLYING.get())) return;
                    String key = (String) p.args[1];
                    if (key.startsWith("atmosphere_light_") && Settings.Global.getInt(
                            (ContentResolver) p.args[0], "beekeeper_net_enabled", 0) == 1)
                        Settings.Global.putInt((ContentResolver) p.args[0], "beekeeper_net_enabled", 0);
                    if (key.equals("atmosphere_light_preset_color")
                            || key.equals("atmosphere_light_same_color") || key.equals("atmosphere_light_color_value")
                            || key.equals("atmosphere_light_switcher_pm2")) {
                        Settings.Global.putInt((ContentResolver) p.args[0], ENABLED, 0);
                    }
                }
            });
        } catch (Throwable e) { log(e); }
        hookPage(lp.classLoader, "com.xiaomi.mitv.settings.monitor.MonitorLightsDetailView",
                "init", "mCategory", "com.xiaomi.mitv.settings.widget.MonitorSettingItem");
        hookPage(lp.classLoader, "com.xiaomi.mitv.settings.displaysound.detail.LightsDetailView",
                "initViews", "mExpertDisplayCategory", "com.xiaomi.mitv.settings.widget.SettingItem");
    }

    private static void hookManager(Class<?> cls) {
        if (!MANAGERS.add(cls)) return;
        try {
            XposedHelpers.findAndHookMethod(cls, "setColorfulLedState", int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int mode = (Integer) p.args[0];
                    if ((mode != 1 && mode != 2) || Boolean.TRUE.equals(APPLYING.get()) || !nativeReady()) return;
                    try {
                        ClassLoader cl = p.thisObject.getClass().getClassLoader();
                        Context ctx = (Context) XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                                "mitv.impl.tv.TvContextImpl", cl), "getAndroidContext");
                        if (Settings.Global.getInt(ctx.getContentResolver(), ENABLED, 0) != 1) return;
                        int rgb = RgbColor.parse(Settings.Global.getString(ctx.getContentResolver(), HEX));
                        send(p.thisObject, mode, rgb);
                        p.setResult(null);
                    } catch (Throwable e) { log(e); }
                }
            });
            log("manager hooked: " + cls.getName());
        } catch (Throwable e) { MANAGERS.remove(cls); log(e); }
    }

    private static void hookPage(ClassLoader cl, String cls, String method, String categoryField, String itemCls) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        View page = (View) p.thisObject;
                        if (!ADDED.add(page)) return;
                        Context ctx = page.getContext();
                        Object category = XposedHelpers.getObjectField(page, categoryField);
                        Object manager = XposedHelpers.getObjectField(page, "mPicManager");
                        hookManager(manager.getClass());
                        Object item = XposedHelpers.callStaticMethod(XposedHelpers.findClass(itemCls, cl),
                                "createItem", ctx, category);
                        XposedHelpers.callMethod(item, "setTitle", "自定义颜色");
                        XposedHelpers.callMethod(item, "setRightArrowVisibility", View.VISIBLE);
                        View rgbRow = (View) item;
                        new Handler(Looper.getMainLooper()).post(() -> moveBeforeSameColor(page, rgbRow));
                        refresh(item, ctx);
                        observe((View) item, ctx, () -> refresh(item, ctx), HEX, ENABLED);
                        ((View) item).setOnClickListener(v -> showDialog(ctx, manager, item));
                        Object network = XposedHelpers.callStaticMethod(XposedHelpers.findClass(itemCls, cl),
                                "createItem", ctx, category);
                        XposedHelpers.callMethod(network, "setTitle", "网络灯控");
                        XposedHelpers.callMethod(network, "setRightArrowVisibility", View.VISIBLE);
                        Runnable refreshNetwork = () -> XposedHelpers.callMethod(network, "setValue",
                                NetworkSettings.get(ctx, "enabled", 0) == 1 ? "已启用" : "已关闭");
                        refreshNetwork.run();
                        observe((View) network, ctx, refreshNetwork, "beekeeper_net_enabled");
                        ((View) network).setOnClickListener(v -> NetworkSettings.show(ctx, refreshNetwork));
                        new Handler(Looper.getMainLooper()).post(() -> moveBeforeSameColor(page, (View) network));
                        log("RGB row added: " + cls);
                    } catch (Throwable e) { log(e); }
                }
            });
        } catch (Throwable e) { log(e); }
    }

    private static void observe(View view, Context ctx, Runnable refresh, String... keys) {
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { refresh.run(); }
        };
        boolean[] registered = {false};
        Runnable attach = () -> {
            if (!registered[0]) {
                for (String key : keys) ctx.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(key), false, observer);
                registered[0] = true;
            }
            refresh.run();
        };
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { attach.run(); }
            @Override public void onViewDetachedFromWindow(View v) {
                if (registered[0]) ctx.getContentResolver().unregisterContentObserver(observer);
                registered[0] = false;
            }
        });
        if (view.isAttachedToWindow()) attach.run();
    }

    private static void moveBeforeSameColor(View page, View rgbRow) {
        try {
            View sameColor = (View) XposedHelpers.getObjectField(page, "mSameColorSwitcher");
            if (sameColor == null) return;
            View moving = rgbRow;
            while (moving.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) moving.getParent();
                View anchor = sameColor;
                while (anchor.getParent() instanceof View && anchor.getParent() != parent)
                    anchor = (View) anchor.getParent();
                if (anchor.getParent() == parent) {
                    ViewGroup.LayoutParams params = moving.getLayoutParams();
                    parent.removeView(moving);
                    parent.addView(moving, parent.indexOfChild(anchor), params);
                    log("RGB row moved before screen color");
                    return;
                }
                moving = parent;
            }
        } catch (Throwable e) { log(e); }
    }

    private static boolean nativeReady() {
        try {
            Class<?> props = XposedHelpers.findClass("android.os.SystemProperties", null);
            return "1".equals(XposedHelpers.callStaticMethod(props, "get", "sys.beekeeper_rgb.native", "0"))
                    && "1".equals(XposedHelpers.callStaticMethod(props, "get", "sys.beekeeper_rgb.effects", "0"));
        } catch (Throwable e) { return false; }
    }

    private static void send(Object manager, int mode, int rgb) {
        Object proxy = XposedHelpers.getObjectField(manager, "miTvCommonInfoHidlProxy");
        if (proxy == null) {
            Object api = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                    "com.mitv.mtvapiclientwrapper.MiTvApiManagerProxy", manager.getClass().getClassLoader()), "getInstance");
            proxy = XposedHelpers.callMethod(api, "getMiTvFApiSysCfg");
            XposedHelpers.setObjectField(manager, "miTvCommonInfoHidlProxy", proxy);
        }
        Object result = XposedHelpers.callMethod(proxy, "SetColorfulLed", mode, RgbColor.encode(rgb));
        if (result instanceof Number && ((Number) result).intValue() < 0)
            throw new IllegalStateException("灯控服务拒绝自定义颜色，请检查灯控扩展是否加载");
        log("applied " + RgbColor.format(rgb) + " mode=" + mode);
    }

    private static void refresh(Object item, Context ctx) {
        String hex = Settings.Global.getString(ctx.getContentResolver(), HEX);
        boolean active = Settings.Global.getInt(ctx.getContentResolver(), ENABLED, 0) == 1;
        String value = active && hex != null ? hex : "输入 #RRGGBB";
        try { XposedHelpers.callMethod(item, "setValue", value); } catch (Throwable e) { log(e); }
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    private static void showDialog(Context ctx, Object manager, Object row) {
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), dp(ctx, 12));
        TextView caption = new TextView(ctx);
        caption.setText("输入六位十六进制颜色，支持带或不带 #。");
        caption.setTextSize(16);
        content.addView(caption);
        EditText input = new EditText(ctx);
        input.setId(0x66ffcc01);
        input.setSingleLine(true);
        input.setTextSize(24);
        input.setTypeface(Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setKeyListener(DigitsKeyListener.getInstance("#0123456789abcdefABCDEF"));
        input.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setImeActionLabel("应用", EditorInfo.IME_ACTION_DONE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        input.setHint("#66FFCC");
        String current = Settings.Global.getString(ctx.getContentResolver(), HEX);
        input.setText(current == null ? "#66FFCC" : current);
        input.setSelectAllOnFocus(true);
        content.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 58)));
        TextView preview = new TextView(ctx);
        preview.setGravity(android.view.Gravity.CENTER);
        preview.setTextSize(17);
        content.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 54)));
        Runnable repaint = () -> {
            try {
                int rgb = RgbColor.parse(input.getText().toString());
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xff000000 | rgb);
                bg.setCornerRadius(dp(ctx, 8));
                preview.setBackground(bg);
                int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                preview.setTextColor(r * 299 + g * 587 + b * 114 >= 140000 ? Color.BLACK : Color.WHITE);
                preview.setText("R " + r + "    G " + g + "    B " + b);
            } catch (IllegalArgumentException e) {
                preview.setBackgroundColor(Color.TRANSPARENT);
                preview.setTextColor(Color.GRAY);
                preview.setText("请输入完整的六位颜色值");
            }
        };
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            public void onTextChanged(CharSequence s, int st, int before, int count) { repaint.run(); }
            public void afterTextChanged(Editable e) { }
        });
        repaint.run();
        AlertDialog dialog = new AlertDialog.Builder(ctx).setTitle("自定义颜色").setView(content)
                .setPositiveButton("应用", null).setNegativeButton("取消", null)
                .setNeutralButton("使用预设", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int rgb;
                try { rgb = RgbColor.parse(input.getText().toString()); }
                catch (IllegalArgumentException e) { input.setError(e.getMessage()); return; }
                if (!nativeReady()) {
                    input.setError("请更新配套 Magisk 灯控模块并重启设备");
                    return;
                }
                APPLYING.set(true);
                ContentResolver cr = ctx.getContentResolver();
                String oldHex = Settings.Global.getString(cr, HEX);
                int oldEnabled = Settings.Global.getInt(cr, ENABLED, 0);
                try {
                    NetworkSettings.pause(ctx);
                    int mode = Settings.Global.getInt(cr, "atmosphere_light_effect", 0) + 1;
                    if (mode != 1 && mode != 2) {
                        input.setError("请先选择常亮或呼吸光效");
                        return;
                    }
                    send(manager, mode, rgb);
                    Settings.Global.putString(cr, HEX, RgbColor.format(rgb));
                    Settings.Global.putInt(cr, ENABLED, 1);
                    Settings.Global.putInt(cr, "atmosphere_light_same_color", 0);
                    Settings.Global.putInt(cr, "atmosphere_light_switcher", 1);
                    refresh(row, ctx);
                    dialog.dismiss();
                } catch (Throwable e) {
                    Settings.Global.putString(cr, HEX, oldHex);
                    Settings.Global.putInt(cr, ENABLED, oldEnabled);
                    log(e);
                    input.setError("应用失败，请稍后重试");
                } finally { APPLYING.remove(); }
            });
            input.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)
                    return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            });
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (event != null) {
                    int key = event.getKeyCode();
                    if (key != KeyEvent.KEYCODE_ENTER && key != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    return true;
                }
                if (actionId != EditorInfo.IME_ACTION_DONE && actionId != EditorInfo.IME_ACTION_GO
                        && actionId != EditorInfo.IME_ACTION_SEND && actionId != EditorInfo.IME_NULL)
                    return false;
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                APPLYING.set(true);
                try {
                    NetworkSettings.pause(ctx);
                    ContentResolver cr = ctx.getContentResolver();
                    Settings.Global.putInt(cr, ENABLED, 0);
                    int mode = Settings.Global.getInt(cr, "atmosphere_light_switcher", 1) == 1
                            ? Settings.Global.getInt(cr, "atmosphere_light_effect", 0) + 1 : 0;
                    int preset = Settings.Global.getInt(cr, "atmosphere_light_preset_color", 0) + 1;
                    XposedHelpers.callMethod(manager, "setColorfulLedState", mode, preset);
                    refresh(row, ctx);
                    dialog.dismiss();
                } catch (Throwable e) {
                    log(e);
                    Toast.makeText(ctx, "恢复预设失败，请重新选择预设颜色", Toast.LENGTH_LONG).show();
                } finally { APPLYING.remove(); }
            });
        });
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        input.requestFocus();
    }

    private static void log(String message) { XposedBridge.log(TAG + ": " + message); }
    private static void log(Throwable error) { XposedBridge.log(TAG + ": " + error); XposedBridge.log(error); }
}
