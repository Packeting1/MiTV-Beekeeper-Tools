package com.beekeeper.slim;

import static com.beekeeper.slim.Catalog.*;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import java.util.LinkedHashMap;

final class GroupStatus extends Part {
  GroupStatus(MainActivity a) {
    super(a);
  }

  /** 有依赖的模块：被依赖项没勾选时锁定且不能勾选（已装的仍可取消）；有依赖它的模块勾选着时，被依赖项也锁定。 */
  void updateDependents() {
    for (Group r : a.GROUPS) {
      boolean has = false, used = false;
      for (Group g : a.GROUPS) {
        if (g.requires == null || !g.requires.equals(r.modId)) continue;
        has = true;
        boolean unmet = !r.box.isChecked() && !modInstalled(g);
        g.box.setLocked(a.modPresent == null || unmet, unmet ? g.requiresMsg : null);
        if (unmet) g.box.setChecked(false);
        used |= g.box.isChecked();
      }
      boolean keep = used && r.box.isChecked();
      if (has) r.box.setLocked(a.modPresent == null || keep, keep ? "需先取消依赖它的模块" : null);
    }
    Group vk = a.voiceKeyGroup;
    for (Group g : a.GROUPS) {
      if (g.kind == KIND_APK) {
        g.box.setLocked(!a.ls.lsInstalled, a.ls.lsInstalled ? null : "未检测到 Xposed 框架，请先安装");
        if (!a.ls.lsInstalled) continue;
      }
      // 语音助手没禁用时语音键拦不住：未安装的语音键自定义不能勾选；勾选着它时，语音助手要保持禁用
      if (g == vk) {
        boolean unmet = VoiceKey.assistantEnabled(a) && state(VoiceKey.MODULE) == MISSING;
        g.box.setLocked(unmet, unmet ? "请先在「应用类」禁用「语音助手」并点“应用”" : null);
        if (unmet) g.box.setChecked(false);
      } else if (g.kind == KIND_PKGS && g.pkgs[0].equals(VoiceKey.ASSISTANT[0])) {
        boolean keep = vk.box.isChecked() && g.box.isChecked();
        g.box.setLocked(keep, keep ? "已勾选“语音键自定义”，需先取消它才能启用语音助手" : null);
      }
      if ((g.zygisk || "zygisk_vector".equals(g.modId)) && a.migration.needed()) {
        g.box.setLocked(true, "检测到旧的 Zygisk / LSPosed 环境，请先完成迁移");
      }
      if (fwMismatch(g) && !modInstalled(g)) {
        g.box.setChecked(false);
        g.box.setLocked(true, "仅适用于 " + FIRMWARE + "，当前固件为 " + Build.VERSION.INCREMENTAL);
      }
    }
    if (a.voiceKey != null) a.voiceKey.syncVisible();
    if (a.micPairs != null) a.micPairs.syncVisible();
    a.updatePending();
  }

  /** 固件相关的模块在其它固件上：未安装的不能勾选，已安装的仍可取消。 */
  boolean fwMismatch(Group g) {
    return g.fwOnly && !FIRMWARE.equals(Build.VERSION.INCREMENTAL);
  }

  void confirmEnable(final Group g) {
    new Dlg.Builder(a)
        .setTitle(g.title)
        .setMessage(g.confirm + "\n\n确定开启吗？")
        .setPositiveButton(
            "开启",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                g.box.setChecked(true);
                updateDependents();
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  void confirmDisable(final Group g) {
    new Dlg.Builder(a)
        .setTitle(g.title)
        .setMessage(g.confirmOff + "\n\n确定关闭吗？")
        .setPositiveButton(
            "关闭",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                g.box.setChecked(false);
                updateDependents();
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  int packageState(String pkg) {
    PackageManager pm = getPackageManager();
    int s;
    try {
      s = pm.getApplicationEnabledSetting(pkg);
    } catch (Exception e) {
      return MISSING;
    }
    boolean off =
        s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
    if (!off) {
      // 有些版本上被禁用的应用查不到 ApplicationInfo，这时只按启用设置判断。
      try {
        off = !pm.getApplicationInfo(pkg, 0).enabled;
      } catch (Exception ignored) {
      }
    }
    return off ? DISABLED : ENABLED;
  }

  String homePackage() {
    try {
      Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
      ResolveInfo ri = getPackageManager().resolveActivity(i, 0);
      return ri == null || ri.activityInfo == null ? "" : ri.activityInfo.packageName;
    } catch (Exception e) {
      return "";
    }
  }

  int dbState() {
    boolean pending = a.modPresent != null && a.modPresent.contains(DB_MODULE);
    try {
      ApplicationInfo ai = getPackageManager().getApplicationInfo(DB_PKG, 0);
      boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
      // 模块目录已被移除但Aerial还在系统分区里：重启后才会消失。
      if (sys && a.modPresent != null && !pending) return DB_REMOVING;
      if (sys) return DB_PKG.equals(homePackage()) ? DB_ACTIVE : DB_SYSTEM;
      return pending ? DB_PENDING : DB_USERAPP;
    } catch (Exception e) {
      return pending ? DB_PENDING : DB_NONE;
    }
  }

  boolean noHomeReady() {
    return dbState() == DB_ACTIVE && state("com.beekeeper.homeref") != MISSING;
  }

  /** 系统桌面开关：Aerial已装成系统应用且已是默认桌面、Aerial开关开着才能开；已经禁用时总能点，方便恢复。 */
  void updateNoHomeBox() {
    Group nh = groupOfKind(KIND_NOHOME);
    boolean alreadyOff = state(STOCK_HOME_PKG) == DISABLED;
    boolean allowed = noHomeReady() && groupOfKind(KIND_DB).box.isChecked();
    nh.box.setLocked(!allowed && !alreadyOff);
    if (!allowed && !alreadyOff) nh.box.setChecked(false);
    a.updatePending();
  }

  boolean modInstalled(Group g) {
    return a.modPresent != null
        && a.modPresent.contains(g.modId)
        && !(a.modRemoving != null && a.modRemoving.contains(g.modId));
  }

  void render() {
    LinkedHashMap<Group, String[]> ups = a.modUp.pendingUpdates();
    for (Group g : a.GROUPS) {
      String t;
      int lv = ItemView.NEUTRAL;
      if (g.kind == KIND_DB) {
        int st = dbState();
        t =
            st == DB_ACTIVE
                ? "当前：已是默认桌面"
                : st == DB_SYSTEM
                    ? "当前：已安装，尚未设为默认桌面（请再次点击“应用”）"
                    : st == DB_PENDING
                        ? "当前：已安装，重启电视后生效"
                        : st == DB_REMOVING
                            ? "当前：已移除，重启电视后 Aerial 消失"
                            : st == DB_USERAPP ? "当前：已装成普通应用，不能设为默认桌面（勾选并应用后可改正）" : "当前：未安装";
        lv = st == DB_ACTIVE ? ItemView.GOOD : st == DB_NONE ? ItemView.NEUTRAL : ItemView.WARN;
        String av = a.aerial.aerialVersion();
        if (av != null && a.aerial.aerialInstalledOk()) t += "\n版本：" + av;
        g.box.setActual(st == DB_ACTIVE || st == DB_SYSTEM || st == DB_PENDING);
      } else if (g.kind == KIND_NOHOME) {
        boolean off = state(STOCK_HOME_PKG) == DISABLED;
        t = off ? "当前：已禁用" : "当前：启用中";
        lv = off ? ItemView.GOOD : ItemView.NEUTRAL;
        if (!noHomeReady() && !off) {
          t += "（需先启用「默认桌面引用」和「Aerial 桌面」并设为默认桌面）";
          lv = ItemView.WARN;
        }
        g.box.setActual(off);
      } else if (g.kind == KIND_APK) {
        boolean inst = state(g.pkgs[0]) != MISSING;
        String apkVer = "";
        if (inst) {
          try {
            PackageInfo pi = getPackageManager().getPackageInfo(g.pkgs[0], 0);
            apkVer = pi.versionName != null ? " " + pi.versionName : " （没有版本号的旧版）";
          } catch (Exception ignored) {
          }
        }
        t = inst ? "当前：已安装" + apkVer : "当前：未安装";
        if (ups.containsKey(g)) t += "\n⬆ 有新版本：" + ups.get(g)[0] + " → " + ups.get(g)[1];
        if (inst) {
          boolean disabled = state(g.pkgs[0]) == DISABLED;
          String act = disabled ? "实际：APK 在系统中被停用" : a.ls.lsActual(g.pkgs[0]);
          t += "\n" + act + (disabled || a.ls.needsFix(g.pkgs[0]) ? "（点“应用”自动启用）" : "");
          lv = act.startsWith("实际：已在") && !act.contains("缺") ? ItemView.GOOD : ItemView.WARN;
        }
        g.box.setActual(inst);
      } else if (g.kind == KIND_MOD) {
        if (a.modPresent == null) {
          t = "当前：正在读取状态（需要 root 授权）";
        } else if (a.modPresent.contains(g.modId) && a.modRemoving.contains(g.modId)) {
          t = "当前：已关闭，重启后生效（再勾选可取消）";
          lv = ItemView.WARN;
        } else if (a.modPresent.contains(g.modId)) {
          boolean pend = a.modPending != null && a.modPending.contains(g.modId);
          String[] iv = a.modVer.get(g.modId);
          boolean off = a.modDisabled.contains(g.modId), on = a.modActive.contains(g.modId);
          String act = off ? "实际：在 Magisk 中被停用，未生效" : on ? "实际：已生效" : "实际：未生效（需重启）";
          String has = g.zygisk ? "已开启" : "已安装";
          t =
              pend
                  ? "当前：" + has + "，重启后生效"
                  : "当前：" + has + (iv == null ? "" : " " + iv[1]) + "\n" + act;
          lv = pend || off || !on ? ItemView.WARN : ItemView.GOOD;
          String[] up = g.base && g.repo != null ? a.baseMods.update(g) : ups.get(g);
          if (up != null) t += "\n⬆ 有新版本：" + up[0] + " → " + up[1];
        } else {
          t = g.zygisk ? "当前：未开启" : "当前：未安装";
        }
        if (fwMismatch(g)) {
          t += "\n固件不匹配：仅适用于 " + FIRMWARE + "，当前为 " + Build.VERSION.INCREMENTAL;
          lv = ItemView.WARN;
        }
        g.box.setActual(modInstalled(g));
        g.box.setLocked(a.modPresent == null);
        // 带配套 APK 的 Magisk 模块同时显示 Xposed 启用及作用域状态。
        if (modInstalled(g) && g.pkgs.length > 0) {
          String companion =
              state(g.pkgs[0]) == MISSING
                  ? "实际：配套 APK 未安装"
                  : state(g.pkgs[0]) == DISABLED ? "实际：配套 APK 已停用" : a.ls.lsActual(g.pkgs[0]);
          t += "\n" + companion;
          if (!companion.startsWith("实际：已在") || companion.contains("缺")) lv = ItemView.WARN;
        }
      } else {
        int dis = 0, en = 0, miss = 0;
        for (String p : g.pkgs) {
          int s = state(p);
          if (s == DISABLED) dis++;
          else if (s == ENABLED) en++;
          else miss++;
        }
        t = dis == 0 ? "当前：启用中" : (en == 0 ? "当前：已禁用" : "当前：部分已禁用");
        if (miss > 0 && dis == 0 && en == 0) t = "当前：本机没有这些应用";
        lv = dis > 0 && en == 0 ? ItemView.GOOD : dis > 0 ? ItemView.WARN : ItemView.NEUTRAL;
        g.shown = dis > 0 && en == 0;
        g.box.setActual(g.shown);
      }
      g.box.setInfo(g.title, g.desc, g.warn, t, lv, g.tags());
    }
    updateNoHomeBox();
    updateDependents();
    a.aerial.updateAerialUi();
    a.baseMods.updateUi();
  }
}
