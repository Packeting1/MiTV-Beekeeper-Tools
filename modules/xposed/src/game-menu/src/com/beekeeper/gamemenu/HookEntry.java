package com.beekeeper.gamemenu;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.view.KeyEvent;
import io.github.libxposed.api.XposedModule;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/*
 * 取消菜单键弹出的游戏设置条，功能挪进快捷设置（图像页）新增的“游戏”标签（settings 侧见 GameTab）。
 * tvplayer 侧只在处理菜单键期间让 isGameModePic() 返回 false，走原版“非游戏模式”分支；它还被准星恢复等逻辑使用，不能全局改。
 * 准星是播放器进程的叠加视图，设置进程写完设置后发广播，这里调 onChanged 刷新。
 */
public final class HookEntry extends XposedModule {

  static final String TAG = "GameMenu";
  static final String SETTINGS = "com.xiaomi.mitv.settings";
  static final String PLAYER = "com.xiaomi.mitv.tvplayer";
  static final String ACTION_FRONT_SIGHT = "com.beekeeper.gamemenu.FRONT_SIGHT";

  private static final String ACTIVITY = "com.xiaomi.mitv.tvplayer.ExternalSourceActivity";
  private static final String MONITOR_UTILS = "com.xiaomi.mitv.tvplayer.monitor.MonitorUtils";
  private static final int KEYCODE_MENU = 82;

  private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());
  private static final ThreadLocal<Boolean> IN_MENU_KEY = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> FORCE_PM2 = new ThreadLocal<>();

  private static volatile WeakReference<Activity> activity = new WeakReference<>(null);
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
        new GameTab(this, param.getDefaultClassLoader()).install();
      } else {
        hookPlayer(param.getDefaultClassLoader());
      }
    } catch (Throwable t) {
      log(Log.ERROR, TAG, "cannot hook " + pkg, t);
    }
  }

  private void hookPlayer(ClassLoader cl) throws Exception {
    Class<?> esa = cl.loadClass(ACTIVITY);
    Method onChanged = esa.getMethod("onChanged", int.class);

    hook(esa.getDeclaredMethod("isGameModePic"))
        .intercept(
            chain -> Boolean.TRUE.equals(IN_MENU_KEY.get()) ? Boolean.FALSE : chain.proceed());

    hook(esa.getDeclaredMethod("onKeyDown", int.class, KeyEvent.class))
        .intercept(chain -> {
          if ((Integer) chain.getArg(0) != KEYCODE_MENU) {
            return chain.proceed();
          }
          IN_MENU_KEY.set(Boolean.TRUE);
          try {
            return chain.proceed();
          } finally {
            IN_MENU_KEY.remove();
          }
        });

    // 帧率/计时/秒表悬浮层的显示与移除被 isSupportPm2Monitor 拦住，只在这几个方法执行期间放行
    Method pm2 = cl.loadClass(MONITOR_UTILS).getDeclaredMethod("isSupportPm2Monitor");
    hook(pm2)
        .intercept(chain -> Boolean.TRUE.equals(FORCE_PM2.get()) ? Boolean.TRUE : chain.proceed());
    for (String name : new String[] {"updateFragment", "createFragment", "removeFpsFragment"}) {
      hook(esa.getDeclaredMethod(name))
          .intercept(chain -> {
            Boolean previous = FORCE_PM2.get();
            FORCE_PM2.set(Boolean.TRUE);
            try {
              return chain.proceed();
            } finally {
              if (previous == null) {
                FORCE_PM2.remove();
              } else {
                FORCE_PM2.set(previous);
              }
            }
          });
    }

    BroadcastReceiver receiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            Activity a = activity.get();
            if (a == null) {
              return;
            }
            try {
              onChanged.invoke(a, intent.getIntExtra("index", 0));
            } catch (Throwable t) {
              log(Log.ERROR, TAG, "update front sight failed", t);
            }
          }
        };
    hook(esa.getDeclaredMethod("onResume"))
        .intercept(chain -> {
          Object self = chain.getThisObject();
          activity = new WeakReference<>((Activity) self);
          if (!receiverRegistered) {
            receiverRegistered = true;
            ((Activity) self)
                .getApplicationContext()
                .registerReceiver(receiver, new IntentFilter(ACTION_FRONT_SIGHT));
          }
          return chain.proceed();
        });
    log(Log.INFO, TAG, "player hooks installed");
  }
}
