package com.beekeeper.moredisplaysettings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 菜单键弹出的悬浮设置（图像页）里加一项“更多图像设置”，直接打开系统设置的“图像参数”页。
 * 作用域：settings + tvplayer。
 *
 * - settings：在“恢复默认图像设置”下面加这一项，点击走 MitvSettingsPopup.showPopup(0)（图像参数页）。
 *   showPopup 不会取消图像页自己的 10 秒关闭计时器，到点它发的关闭广播会把新页面一起关掉，所以点击后手动 release。
 *   图像页一出现还会给播放器发广播，让底部游戏条消失。
 * - tvplayer：游戏条（MonitorGameFrag）平时只有按“更多”、返回键、12 秒无按键才会消失，且 Activity 已保存状态时
 *   原版 keyPadBack 会跳过移除。这里收到广播就直接移除它的 Fragment。
 */
public final class HookEntry extends XposedModule {

    private static final String TAG = "MoreDisplaySettings";
    private static final String SETTINGS = "com.xiaomi.mitv.settings";
    private static final String PLAYER = "com.xiaomi.mitv.tvplayer";
    private static final String DISPLAY_VIEW = "com.xiaomi.mitv.settings.monitor.MonitorDisplayView";
    private static final String MAIN_VIEW = "com.xiaomi.mitv.settings.monitor.MonitorMainView";
    private static final String ITEM = "com.xiaomi.mitv.settings.widget.MonitorSettingItem";
    private static final String POPUP = "com.xiaomi.mitv.settings.entry.MitvSettingsPopup";
    private static final String GAME_FRAG = "com.xiaomi.mitv.tvplayer.monitor.game.MonitorGameFrag";
    private static final String V4 = "android.support.v4.app.";
    private static final String TITLE = "更多图像设置";
    private static final int TYPE_DISPLAY_PARAMETER = 0;
    /** settings → tvplayer：关掉底部游戏条。 */
    private static final String ACTION_DISMISS_GAME_BAR = "com.beekeeper.moredisplaysettings.DISMISS_GAME_BAR";

    private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());

    /** tvplayer：当前显示着的游戏条 Fragment。 */
    private static volatile Object gameFrag;
    private static boolean receiverRegistered;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if ((!SETTINGS.equals(pkg) && !PLAYER.equals(pkg)) || !HOOKED.add(pkg)) {
            return;
        }
        try {
            if (SETTINGS.equals(pkg)) {
                hookSettings(param.getDefaultClassLoader());
            } else {
                hookPlayer(param.getDefaultClassLoader());
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "cannot hook " + pkg, t);
        }
    }

    private void hookSettings(ClassLoader cl) throws Exception {
        Class<?> view = cl.loadClass(DISPLAY_VIEW);
        Class<?> item = cl.loadClass(ITEM);
        Class<?> popup = cl.loadClass(POPUP);
        Field fContext = view.getDeclaredField("mContext");
        Field fCategory = view.getDeclaredField("mCategory");
        fContext.setAccessible(true);
        fCategory.setAccessible(true);
        Method createItem = item.getMethod("createItem", Context.class, ViewGroup.class);
        Method setTitle = item.getMethod("setTitle", String.class);
        Method setClick = item.getMethod("setOnClickListener", View.OnClickListener.class);
        Method setArrow = item.getMethod("setRightArrowVisibility", int.class);
        Method getInstance = popup.getMethod("getInstance", Context.class);
        Method showPopup = popup.getMethod("showPopup", int.class);

        hook(view.getDeclaredMethod("initResetItem")).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object self = chain.getThisObject();
                Context ctx = (Context) fContext.get(self);
                Object more = createItem.invoke(null, ctx, (ViewGroup) fCategory.get(self));
                setTitle.invoke(more, TITLE);
                setArrow.invoke(more, View.VISIBLE);
                setClick.invoke(more, (View.OnClickListener) v -> {
                    dismissGameBar(ctx);
                    try {
                        showPopup.invoke(getInstance.invoke(null, ctx), TYPE_DISPLAY_PARAMETER);
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "open display parameter failed", t);
                        return;
                    }
                    releaseMainView(cl);
                });
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "add item failed", t);
            }
            return result;
        });

        hook(view.getDeclaredMethod("onAttachedToWindow")).intercept(chain -> {
            dismissGameBar(((View) chain.getThisObject()).getContext());
            return chain.proceed();
        });
        log(Log.INFO, TAG, "display view hooks installed");
    }

    /** 取消图像页的关闭计时器（MonitorMainView.release 清掉它的 Handler 回调）。 */
    private void releaseMainView(ClassLoader cl) {
        try {
            Class<?> main = cl.loadClass(MAIN_VIEW);
            Field instance = main.getDeclaredField("mInstance");
            instance.setAccessible(true);
            Object old = instance.get(null);
            if (old != null) {
                main.getMethod("release").invoke(old);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot release image page timer", t);
        }
    }

    private void dismissGameBar(Context ctx) {
        try {
            ctx.sendBroadcast(new Intent(ACTION_DISMISS_GAME_BAR).setPackage(PLAYER));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "dismiss broadcast failed", t);
        }
    }

    private void hookPlayer(ClassLoader cl) throws Exception {
        Class<?> frag = cl.loadClass(GAME_FRAG);
        Class<?> fragment = cl.loadClass(V4 + "Fragment");
        Method getActivity = frag.getMethod("getActivity");
        Method getFm = fragment.getMethod("getFragmentManager");
        Method begin = cl.loadClass(V4 + "FragmentManager").getMethod("beginTransaction");
        Class<?> tx = cl.loadClass(V4 + "FragmentTransaction");
        Method remove = tx.getMethod("remove", fragment);
        Method commit = tx.getMethod("commitAllowingStateLoss");

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Object f = gameFrag;
                try {
                    Object fm = f == null ? null : getFm.invoke(f);
                    if (fm != null) {
                        Object t = begin.invoke(fm);
                        remove.invoke(t, f);
                        commit.invoke(t);
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "dismiss game bar failed", t);
                }
            }
        };
        hook(frag.getMethod("onResume")).intercept(chain -> {
            try {
                Context ctx = (Context) getActivity.invoke(chain.getThisObject());
                if (ctx != null && !receiverRegistered) {
                    ctx.getApplicationContext().registerReceiver(receiver, new IntentFilter(ACTION_DISMISS_GAME_BAR));
                    receiverRegistered = true;
                }
                gameFrag = chain.getThisObject();
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "cannot watch game bar", t);
            }
            return chain.proceed();
        });
        hook(frag.getMethod("onPause")).intercept(chain -> {
            gameFrag = null;
            return chain.proceed();
        });
        log(Log.INFO, TAG, "game bar hooks installed");
    }
}
