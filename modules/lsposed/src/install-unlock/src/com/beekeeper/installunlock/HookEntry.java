package com.beekeeper.installunlock;

import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/*
 * 去掉小米系统安装器按云端黑名单拦截安装（例如“根据互联网电视相关要求，应用存在违规功能，禁止安装”）。
 *
 * 安装器每次进入 InstallStaging 时会向小米服务器请求 pi_config（失败则用本地缓存），
 * 在 Config.app_pi_control 为 true 时按 4 个黑名单（来源/安装包 × 普通/强）拦截。
 * 这里在拿到 Config 的两个回调里把 app_pi_control 改成 false，这样不管云端下发什么名单都不拦。
 * 不碰云端配置本身，也不影响安装器的其它检查（未知来源开关、签名校验等）。
 */
public final class HookEntry extends XposedModule {

  private static final String TAG = "InstallUnlock";
  private static final String INSTALLER = "com.android.packageinstaller";
  private static final String CONFIG = "com.mitv.business.modle.Config";
  private static final String[] CALLBACKS = {
    "com.android.packageinstaller.InstallStaging$b",
    "com.android.packageinstaller.InstallStaging$c$a",
  };

  private static final Set<String> HOOKED = Collections.synchronizedSet(new HashSet<String>());

  @Override
  public void onModuleLoaded(ModuleLoadedParam param) {
    log(Log.INFO, TAG, "module loaded in " + param.getProcessName());
  }

  @Override
  public void onPackageLoaded(PackageLoadedParam param) {
    if (!INSTALLER.equals(param.getPackageName()) || !HOOKED.add(INSTALLER)) {
      return;
    }
    ClassLoader cl = param.getDefaultClassLoader();
    int n = 0;
    for (String name : CALLBACKS) {
      try {
        n += hookConfigConsumers(cl.loadClass(name));
      } catch (Throwable t) {
        log(Log.ERROR, TAG, "cannot hook " + name, t);
      }
    }
    log(Log.INFO, TAG, "install interception hooks installed: " + n);
  }

  /** 只有一个 Config 参数的方法（拿到云端/缓存配置后的回调）。 */
  private int hookConfigConsumers(Class<?> c) {
    int n = 0;
    for (Method m : c.getDeclaredMethods()) {
      Class<?>[] p = m.getParameterTypes();
      if (p.length != 1 || !CONFIG.equals(p[0].getName())) {
        continue;
      }
      hook(m)
          .intercept(chain -> {
            try {
              disableControl(chain.getArgs().get(0));
            } catch (Throwable t) {
              log(Log.ERROR, TAG, "disable pi control failed", t);
            }
            return chain.proceed();
          });
      n++;
    }
    return n;
  }

  private void disableControl(Object cfg) throws Exception {
    if (cfg == null) {
      return;
    }
    Field f = cfg.getClass().getDeclaredField("app_pi_control");
    f.setAccessible(true);
    if (f.getBoolean(cfg)) {
      f.setBoolean(cfg, false);
      log(Log.INFO, TAG, "app_pi_control forced off");
    }
  }
}
