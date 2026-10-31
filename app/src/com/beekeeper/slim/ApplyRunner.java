package com.beekeeper.slim;

import static com.beekeeper.slim.Catalog.*;
import static com.beekeeper.slim.Shell.*;

import android.content.DialogInterface;
import java.util.ArrayList;
import java.util.List;

/** 点“应用”后：汇总勾选变化、确认、生成并执行 root 脚本。 */
final class ApplyRunner extends Part {

  ApplyRunner(MainActivity a) {
    super(a);
  }

  void confirmApply() {
    final List<String> toDisable = new ArrayList<String>();
    final List<String> toEnable = new ArrayList<String>();
    for (Group g : a.GROUPS) {
      if (g.kind != KIND_PKGS || g.box.isChecked() == g.shown) continue;
      for (String p : g.pkgs) {
        int s = state(p);
        if (g.box.isChecked() && s == ENABLED) toDisable.add(p);
        if (!g.box.isChecked() && s == DISABLED) toEnable.add(p);
      }
    }
    boolean dbWant = groupOfKind(KIND_DB).box.isChecked();
    int dbSt = dbState();
    final boolean dbOn = dbWant && dbSt != DB_ACTIVE && dbSt != DB_PENDING;
    final boolean dbOff = !dbWant && (dbSt == DB_ACTIVE || dbSt == DB_SYSTEM || dbSt == DB_PENDING);
    boolean nhWant = dbWant && groupOfKind(KIND_NOHOME).box.isChecked();
    boolean nhCur = state(STOCK_HOME_PKG) == DISABLED;
    final boolean nhOn = nhWant && !nhCur && noHomeReady();
    final boolean nhOff = !nhWant && nhCur;
    final List<Group> modOn = new ArrayList<Group>();
    final List<Group> modOff = new ArrayList<Group>();
    for (Group g : a.GROUPS) {
      if (g.kind == KIND_APK) {
        boolean inst = state(g.pkgs[0]) != MISSING;
        if (g.box.isChecked() && !inst) modOn.add(g);
        if (!g.box.isChecked() && inst) modOff.add(g);
        continue;
      }
      if (g.kind != KIND_MOD || a.modPresent == null) continue;
      boolean want = g.box.isChecked(), cur = modInstalled(g);
      if (want && !cur) modOn.add(g);
      if (!want && cur) modOff.add(g);
    }
    if (toDisable.isEmpty()
        && toEnable.isEmpty()
        && !dbOn
        && !dbOff
        && !nhOn
        && !nhOff
        && modOn.isEmpty()
        && modOff.isEmpty()) {
      showResult("没有需要修改的项目。");
      return;
    }
    StringBuilder msg = new StringBuilder();
    if (!toDisable.isEmpty()) msg.append("将禁用 ").append(toDisable.size()).append(" 个应用\n");
    if (!toEnable.isEmpty()) msg.append("将恢复 ").append(toEnable.size()).append(" 个应用\n");
    if (dbOn) msg.append("将安装 Aerial 桌面并设为默认桌面（需重启电视）\n");
    if (dbOff) msg.append("将恢复系统桌面，并移除 Aerial 桌面（重启后 Aerial 消失）\n");
    if (nhOn) msg.append("将禁用系统桌面（有风险）\n");
    if (nhOff) msg.append("将重新启用系统桌面\n");
    for (Group g : modOn)
      msg.append(g.kind == KIND_APK ? "将安装：" : "将启用：").append(g.title).append("\n");
    for (Group g : modOff)
      msg.append(g.kind == KIND_APK ? "将卸载：" : "将关闭：").append(g.title).append("\n");
    msg.append("\n确定继续吗？");
    new Dlg.Builder(a)
        .setTitle("确认")
        .setMessage(msg.toString())
        .setPositiveButton(
            "确定",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                runApply(toDisable, toEnable, dbOn, dbOff, nhOn, nhOff, modOn, modOff);
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  void runApply(
      final List<String> toDisable,
      final List<String> toEnable,
      final boolean dbOn,
      final boolean dbOff,
      final boolean nhOn,
      final boolean nhOff,
      final List<Group> modOn,
      final List<Group> modOff) {
    final Dlg progress = new Dlg.Builder(a).setTitle("正在执行").setMessage("请在弹窗里允许 root……").show();
    new Thread(
            new Runnable() {
              public void run() {
                final StringBuilder script = new StringBuilder();
                String err = null;
                for (String p : toDisable) {
                  script.append("pm disable-user --user 0 ").append(p).append(" 2>&1\n");
                  script.append("am force-stop ").append(p).append(" 2>&1\n");
                }
                for (String p : toEnable) {
                  script.append("pm enable ").append(p).append(" 2>&1\n");
                }
                if (dbOn) {
                  try {
                    String apk = copyAsset("aerial.apk");
                    // 系统补丁 (PreferredActivityHelper) 拒绝把非系统应用设为默认桌面，所以要让Aerial成为系统应用：
                    // 写一个 Magisk 模块把它放进 /system/app，重启后生效；模块的 service.sh 每次开机再设默认桌面。
                    script.append("rm -rf /data/adb/modules/").append(OLD_DB_MODULE).append("\n");
                    script.append("PKG=").append(DB_PKG).append("\n");
                    script.append("MD=/data/adb/modules/").append(DB_MODULE).append("\n");
                    script.append(
                        "if [ -d $MD ] && pm list packages -s | grep -q \"package:$PKG\\$\";"
                            + " then\n");
                    script
                        .append("  cmd package set-home-activity --user 0 ")
                        .append(DB_COMP)
                        .append(" 2>&1\n");
                    script.append("  echo 已设为默认桌面\n");
                    script.append("else\n");
                    script.append("  rm -rf $MD /data/local/tmp/dbx\n");
                    script.append(
                        "  mkdir -p $MD/system/app/Aerial/lib/arm64 /data/local/tmp/dbx\n");
                    script
                        .append("  cp ")
                        .append(apk)
                        .append(" $MD/system/app/Aerial/Aerial.apk\n");
                    script
                        .append("  unzip -o ")
                        .append(apk)
                        .append(" -d /data/local/tmp/dbx >/dev/null 2>&1\n");
                    script.append(
                        "  cp /data/local/tmp/dbx/lib/arm64-v8a/*.so"
                            + " $MD/system/app/Aerial/lib/arm64/\n");
                    script.append("  rm -rf /data/local/tmp/dbx\n");
                    script.append(
                        "  printf 'id=beekeeper_aerial\\n"
                            + "name=Aerial Launcher\\n"
                            + "version=2026.10.28\\n"
                            + "versionCode=20261028\\n"
                            + "author=酷安@pkkka\\n"
                            + "description=Installs Aerial as a system app and sets it as the"
                            + " default home.\\n"
                            + "' > $MD/module.prop\n");
                    script.append("  cat > $MD/service.sh <<'SVCEOF'\n");
                    script.append("#!/system/bin/sh\n");
                    script.append(
                        "until [ \"$(getprop sys.boot_completed)\" = 1 ]; do sleep 3; done\n");
                    script.append("sleep 10\n");
                    script.append("P=").append(DB_PKG).append("\n");
                    script.append(
                        "for p in WRITE_SECURE_SETTINGS ACCESS_FINE_LOCATION; do pm grant $P"
                            + " android.permission.$p; done\n");
                    script
                        .append("cmd package set-home-activity --user 0 ")
                        .append(DB_COMP)
                        .append("\n");
                    script.append("SVCEOF\n");
                    script.append("  chmod -R a+rX $MD; chmod 755 $MD/service.sh\n");
                    script.append("  pm uninstall $PKG 2>&1\n");
                    script.append("  echo 请重启电视；重启后自动设为默认桌面\n");
                    script.append("fi\n");
                    script.append("rm -f ").append(apk).append("\n");
                  } catch (Exception e) {
                    err = "无法读取内置的 Aerial 安装包：" + e + "\n";
                  }
                }
                if (nhOn && err == null) {
                  // 先确认Aerial是系统应用；禁用后再确认默认桌面确实落到Aerial，否则立刻恢复系统桌面。
                  script
                      .append("if pm list packages -s | grep -q \"package:")
                      .append(DB_PKG)
                      .append("\\$\"; then\n");
                  script
                      .append("  pm disable-user --user 0 ")
                      .append(STOCK_HOME_PKG)
                      .append(" 2>&1\n");
                  script.append(
                      "  H=$(cmd package resolve-activity --brief -a android.intent.action.MAIN"
                          + " -c android.intent.category.HOME | tail -1)\n");
                  script.append("  case \"$H\" in\n");
                  script
                      .append("    ")
                      .append(DB_PKG)
                      .append("/*) am force-stop ")
                      .append(STOCK_HOME_PKG)
                      .append("; echo 已禁用系统桌面;;\n");
                  script
                      .append("    *) pm enable ")
                      .append(STOCK_HOME_PKG)
                      .append("; echo \"默认桌面不是 Aerial（$H），已自动恢复系统桌面\";;\n");
                  script.append("  esac\n");
                  script.append("else echo Aerial 桌面还不是系统应用，已跳过禁用系统桌面; fi\n");
                }
                if (nhOff || dbOff) {
                  script.append("pm enable ").append(STOCK_HOME_PKG).append(" 2>&1\n");
                }
                if (dbOff) {
                  script
                      .append("cmd package set-home-activity ")
                      .append(STOCK_HOME_COMP)
                      .append(" 2>&1\n");
                  script.append("rm -rf /data/adb/modules/").append(DB_MODULE).append("\n");
                  script.append("echo 已恢复系统桌面，重启后 Aerial 消失\n");
                }
                for (Group g : modOn) {
                  if (g.kind == KIND_APK) {
                    try {
                      String apk = copyAsset(g.asset);
                      script.append("echo \"== 安装 ").append(g.title).append("\"\n");
                      script.append("pm install -r ").append(apk).append(" 2>&1\n");
                      script.append("rm -f ").append(apk).append("\n");
                    } catch (Exception e) {
                      err = (err == null ? "" : err) + "无法读取内置安装包 " + g.asset + "：" + e + "\n";
                    }
                    continue;
                  }
                  try {
                    String zip = copyAsset(g.asset);
                    script.append("echo \"== 安装 ").append(g.modId).append("\"\n");
                    script
                        .append("if [ -d /data/adb/modules/")
                        .append(g.modId)
                        .append(" ]; then\n");
                    script.append("  rm -f /data/adb/modules/").append(g.modId).append("/remove\n");
                    script.append("  echo 已恢复启用\n");
                    script
                        .append("else magisk --install-module ")
                        .append(zip)
                        .append(" 2>&1; fi\n");
                    script.append("rm -f ").append(zip).append("\n");
                  } catch (Exception e) {
                    err = (err == null ? "" : err) + "无法读取内置模块 " + g.asset + "：" + e + "\n";
                  }
                }
                for (Group g : modOff) {
                  if (g.kind == KIND_APK) {
                    script.append("echo \"== 卸载 ").append(g.title).append("\"\n");
                    script.append("pm uninstall ").append(g.pkgs[0]).append(" 2>&1\n");
                    continue;
                  }
                  script.append("echo \"== 卸载 ").append(g.modId).append("\"\n");
                  script.append("if [ -d /data/adb/modules/").append(g.modId).append(" ]; then\n");
                  script.append("  touch /data/adb/modules/").append(g.modId).append("/remove\n");
                  script.append("  echo 已关闭，重启后生效\n");
                  script
                      .append("else rm -rf /data/adb/modules_update/")
                      .append(g.modId)
                      .append("; echo 已移除; fi\n");
                }
                boolean modReboot = false;
                for (Group g : modOn) if (g.kind == KIND_MOD) modReboot = true;
                for (Group g : modOff) if (g.kind == KIND_MOD) modReboot = true;
                final boolean needReboot = dbOn || dbOff || modReboot;
                if (needReboot) script.append("echo 有改动需要重启电视后生效\n");
                final String out = (err == null ? "" : err) + runSu(script.toString());
                main.post(
                    new Runnable() {
                      public void run() {
                        if (progress.isShowing()) progress.dismiss();
                        showResult(out);
                        refresh();
                      }
                    });
              }
            },
            "slim-apply")
        .start();
  }
}
