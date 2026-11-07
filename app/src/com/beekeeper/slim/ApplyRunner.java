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
        if (g.box.isChecked() && (!inst || a.ls.needsFix(g.pkgs[0]))) modOn.add(g);
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
    for (Group g : modOn) {
      boolean fixOnly = g.kind == KIND_APK && state(g.pkgs[0]) != MISSING;
      msg.append(g.kind != KIND_APK ? "将启用：" : fixOnly ? "将在 LSPosed 启用：" : "将安装并在 LSPosed 启用：")
          .append(g.title)
          .append("\n");
    }
    for (Group g : modOff)
      msg.append(g.kind == KIND_APK ? "将卸载：" : "将关闭：").append(g.title).append("\n");
    msg.append("\n确定继续吗？");
    new Dlg.Builder(a)
        .setTitle("确认")
        .setMessage(Ui.tagged(msg.toString(), a.GROUPS))
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

  /** 先写 LSPosed 库再安装：守护进程在包更新时重新读取，不用重启就能加载。 */
  String installApkScript(String pkg, String asset) throws Exception {
    String apk = copyAsset(asset);
    StringBuilder b = new StringBuilder();
    b.append("CLASSPATH=")
        .append(a.getPackageCodePath())
        .append(" app_process /system/bin com.beekeeper.slim.LsDb ")
        .append(pkg);
    String[] scopes = LS_SCOPES.get(pkg);
    if (scopes != null) for (String s : scopes) b.append(' ').append(s);
    b.append("\n");
    b.append("pm install -r ").append(apk).append("\n");
    b.append("rm -f ").append(apk).append("\n");
    return b.toString();
  }

  /** LSPosed 模块的作用域含常驻进程（系统进程、保持后台的播放器）：这些进程只有重启电视才会重新加载。 */
  static boolean inSystemProcess(Group g) {
    String[] scopes = g.kind == KIND_APK ? LS_SCOPES.get(g.pkgs[0]) : null;
    if (scopes != null) {
      for (String s : scopes) {
        if (s.equals("system")
            || s.equals("mitv.service")
            || s.equals("com.mediatek.tv.service")
            || s.equals("com.mediatek.tvinput")
            || s.equals("com.xiaomi.mitv.smartshare")
            || s.equals("com.xiaomi.mitv.tvplayer")) {
          return true;
        }
      }
    }
    return false;
  }

  /** 每个步骤在 root 脚本里的包装：先报“正在…”，结束后只输出一行成功或失败，原始输出不外泄。 */
  private static final String STEP =
      "step() {\n"
          + "  echo \"@@RUN|$1 $2\"\n"
          + "  o=$(\"$4\" 2>&1)\n"
          + "  case \"$o\" in\n"
          + "    *Failure*|*Error*|*Exception*|*\"Installation failed\"*)\n"
          + "      echo \"@@FAIL|$1|$(printf '%s' \"$o\" | grep -v '^$' | tail -n 2 | tr '\\n' '"
          + " ')\";;\n"
          + "    *)\n"
          + "      m=$(printf '%s\\n' \"$o\" | sed -n 's/^@@MSG //p' | tail -n 1)\n"
          + "      echo \"@@OK|$1|${m:-$3}\";;\n"
          + "  esac\n"
          + "}\n";

  private int steps;

  StringBuilder newScript() {
    steps = 0;
    return new StringBuilder(STEP);
  }

  void step(StringBuilder script, String label, String run, String done, String body) {
    steps++;
    script.append("s").append(steps).append("() {\n").append(body).append("}\n");
    script
        .append("step '")
        .append(label)
        .append("' '")
        .append(run)
        .append("' '")
        .append(done)
        .append("' s")
        .append(steps)
        .append("\n");
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
    final Progress pg = new Progress(a);
    pg.waiting("正在执行……");
    final Dlg progress = new Dlg.Builder(a).setTitle("正在执行").setView(pg.box).show();
    new Thread(
            new Runnable() {
              public void run() {
                final StringBuilder script = newScript();
                String err = null;
                if (!toDisable.isEmpty()) {
                  StringBuilder b = new StringBuilder();
                  for (String p : toDisable) {
                    b.append("pm disable-user --user 0 ").append(p).append("\n");
                    b.append("am force-stop ").append(p).append("\n");
                  }
                  step(script, "已选应用", "正在禁用", "已禁用 " + toDisable.size() + " 个", b.toString());
                }
                if (!toEnable.isEmpty()) {
                  StringBuilder b = new StringBuilder();
                  for (String p : toEnable) b.append("pm enable ").append(p).append("\n");
                  step(script, "已选应用", "正在恢复", "已恢复 " + toEnable.size() + " 个", b.toString());
                }
                if (dbOn) {
                  try {
                    String apk = copyAsset("aerial.apk");
                    step(script, "Aerial 桌面", "正在安装", "已安装", aerialScript(apk));
                  } catch (Exception e) {
                    err = (err == null ? "" : err) + "无法读取内置的 Aerial 安装包：" + e + "\n";
                  }
                }
                if (nhOn && err == null) {
                  step(script, "系统桌面", "正在禁用", "已禁用", noHomeScript());
                }
                if (nhOff || dbOff) {
                  step(script, "系统桌面", "正在恢复", "已恢复", "pm enable " + STOCK_HOME_PKG + "\n");
                }
                if (dbOff) {
                  step(
                      script,
                      "Aerial 桌面",
                      "正在移除",
                      "已移除，重启后消失",
                      "cmd package set-home-activity "
                          + STOCK_HOME_COMP
                          + "\nrm -rf /data/adb/modules/"
                          + DB_MODULE
                          + "\n");
                }
                for (Group g : modOn) {
                  if (g.kind == KIND_APK) {
                    try {
                      boolean fresh = state(g.pkgs[0]) == MISSING;
                      step(
                          script,
                          g.title,
                          fresh ? "正在安装" : "正在启用",
                          fresh ? "已安装" : "已启用",
                          installApkScript(g.pkgs[0], g.asset));
                    } catch (Exception e) {
                      err = (err == null ? "" : err) + "无法读取内置安装包 " + g.asset + "：" + e + "\n";
                    }
                    continue;
                  }
                  if (g.companion != null) {
                    try {
                      step(
                          script,
                          g.title + " 配套 APK",
                          "正在安装",
                          "已安装",
                          installApkScript(g.pkgs[0], g.companion));
                    } catch (Exception e) {
                      err = (err == null ? "" : err) + "无法读取内置安装包 " + g.companion + "：" + e + "\n";
                    }
                  }
                  try {
                    String zip = copyAsset(g.asset);
                    StringBuilder b = new StringBuilder();
                    b.append("if [ -d /data/adb/modules/").append(g.modId).append(" ]; then\n");
                    b.append("  rm -f /data/adb/modules/").append(g.modId).append("/remove\n");
                    b.append("  echo '@@MSG 已启用，重启后生效'\n");
                    b.append("else magisk --install-module ").append(zip).append("; fi\n");
                    b.append("rm -f ").append(zip).append("\n");
                    step(script, g.title, "正在安装", "已安装，重启后生效", b.toString());
                  } catch (Exception e) {
                    err = (err == null ? "" : err) + "无法读取内置模块 " + g.asset + "：" + e + "\n";
                  }
                }
                for (Group g : modOff) {
                  StringBuilder b = new StringBuilder();
                  if (g.kind == KIND_APK) {
                    b.append("pm uninstall ").append(g.pkgs[0]).append("\n");
                    // 钩子重启前仍在运行，清掉配置才能立即恢复语音键
                    if (VoiceKey.MODULE.equals(g.pkgs[0])) {
                      b.append("settings delete global voicekeymap\n");
                    }
                    step(script, g.title, "正在卸载", "已卸载", b.toString());
                    continue;
                  }
                  if (g.companion != null) {
                    step(
                        script,
                        g.title + " 配套 APK",
                        "正在卸载",
                        "已卸载",
                        "pm uninstall " + g.pkgs[0] + "\n");
                  }
                  b.append("if [ -d /data/adb/modules/").append(g.modId).append(" ]; then\n");
                  b.append("  touch /data/adb/modules/").append(g.modId).append("/remove\n");
                  b.append("  echo '@@MSG 已关闭，重启后生效'\n");
                  b.append("else rm -rf /data/adb/modules_update/")
                      .append(g.modId)
                      .append("; echo '@@MSG 已移除'; fi\n");
                  step(script, g.title, "正在关闭", "已关闭", b.toString());
                }
                boolean modReboot = false, apkOn = false;
                for (Group g : modOn) {
                  if (g.kind == KIND_MOD) modReboot = true;
                  else apkOn = true;
                }
                for (Group g : modOff) if (g.kind == KIND_MOD) modReboot = true;
                boolean sysApk = false;
                for (Group g : modOn) sysApk |= inSystemProcess(g);
                for (Group g : modOff) sysApk |= inSystemProcess(g);
                final boolean needReboot = dbOn || dbOff || modReboot || sysApk;
                runSteps(script, err, pg, progress, needReboot, apkOn && !needReboot);
              }
            },
            "slim-apply")
        .start();
  }

  private static String aerialScript(String apk) {
    StringBuilder b = new StringBuilder();
    // 系统拒绝把非系统应用设为默认桌面：用 Magisk 模块把 Aerial 放进 /system/app，重启后生效
    b.append("rm -rf /data/adb/modules/").append(OLD_DB_MODULE).append("\n");
    b.append("PKG=").append(DB_PKG).append("\n");
    b.append("MD=/data/adb/modules/").append(DB_MODULE).append("\n");
    b.append("if [ -d $MD ] && pm list packages -s | grep -q \"package:$PKG\\$\"; then\n");
    b.append("  cmd package set-home-activity --user 0 ").append(DB_COMP).append("\n");
    b.append("  echo '@@MSG 已设为默认桌面'\n");
    b.append("else\n");
    b.append("  rm -rf $MD /data/local/tmp/dbx\n");
    b.append("  mkdir -p $MD/system/app/Aerial/lib/arm64 /data/local/tmp/dbx\n");
    b.append("  cp ").append(apk).append(" $MD/system/app/Aerial/Aerial.apk\n");
    b.append("  unzip -o ").append(apk).append(" -d /data/local/tmp/dbx\n");
    b.append("  cp /data/local/tmp/dbx/lib/arm64-v8a/*.so $MD/system/app/Aerial/lib/arm64/\n");
    b.append("  rm -rf /data/local/tmp/dbx\n");
    b.append(
        "  printf 'id=beekeeper_aerial\\n"
            + "name=Aerial Launcher\\n"
            + "version=2026.10.28\\n"
            + "versionCode=20261028\\n"
            + "author=酷安@pkkka\\n"
            + "description=Installs Aerial as a system app and sets it as the default home.\\n"
            + "' > $MD/module.prop\n");
    b.append("  cat > $MD/service.sh <<'SVCEOF'\n");
    b.append("#!/system/bin/sh\n");
    b.append("until [ \"$(getprop sys.boot_completed)\" = 1 ]; do sleep 3; done\n");
    b.append("sleep 10\n");
    b.append("P=").append(DB_PKG).append("\n");
    b.append(
        "for p in WRITE_SECURE_SETTINGS ACCESS_FINE_LOCATION; do pm grant $P"
            + " android.permission.$p; done\n");
    b.append("cmd package set-home-activity --user 0 ").append(DB_COMP).append("\n");
    b.append("SVCEOF\n");
    b.append("  chmod -R a+rX $MD; chmod 755 $MD/service.sh\n");
    b.append("  pm uninstall $PKG >/dev/null 2>&1\n");
    b.append("  echo '@@MSG 已安装，重启后设为默认桌面'\n");
    b.append("fi\n");
    b.append("rm -f ").append(apk).append("\n");
    return b.toString();
  }

  private static String noHomeScript() {
    // 先确认Aerial是系统应用；禁用后再确认默认桌面确实落到Aerial，否则立刻恢复系统桌面。
    StringBuilder b = new StringBuilder();
    b.append("if pm list packages -s | grep -q \"package:").append(DB_PKG).append("\\$\"; then\n");
    b.append("  pm disable-user --user 0 ").append(STOCK_HOME_PKG).append("\n");
    b.append(
        "  H=$(cmd package resolve-activity --brief -a android.intent.action.MAIN"
            + " -c android.intent.category.HOME | tail -1)\n");
    b.append("  case \"$H\" in\n");
    b.append("    ")
        .append(DB_PKG)
        .append("/*) am force-stop ")
        .append(STOCK_HOME_PKG)
        .append("; echo '@@MSG 已禁用';;\n");
    b.append("    *) pm enable ")
        .append(STOCK_HOME_PKG)
        .append("; echo '@@MSG 默认桌面不是 Aerial，已自动恢复系统桌面';;\n");
    b.append("  esac\n");
    b.append("else echo '@@MSG Aerial 桌面还不是系统应用，已跳过'; fi\n");
    return b.toString();
  }

  /** 在工作线程里执行步骤脚本，实时刷新进度，结束后弹出每项一行的结果（需要重启时带重启按钮）。 */
  void runSteps(
      StringBuilder script,
      String err,
      final Progress pg,
      final Dlg progress,
      final boolean needReboot,
      boolean lateLoad) {
    final int total = steps;
    final int[] done = {0};
    final String out =
        suOut(
            script.toString(),
            new Shell.Lines() {
              public void line(String l) {
                if (!l.startsWith("@@RUN|")) return;
                final CharSequence t = Ui.tagged(l.substring(6) + "……", a.GROUPS);
                final String title = "正在执行（" + ++done[0] + "/" + total + "）";
                main.post(
                    new Runnable() {
                      public void run() {
                        pg.waiting(t);
                        progress.setTitle(title);
                      }
                    });
              }
            });
    final StringBuilder sum = new StringBuilder(err == null ? "" : err);
    boolean good = err == null;
    if (out == null) {
      sum.append("无法使用 root，可能没有授予 root。\n");
      good = false;
    } else {
      for (String l : out.split("\n")) {
        boolean fail = l.startsWith("@@FAIL|");
        if (!fail && !l.startsWith("@@OK|")) continue;
        String[] f = l.substring(fail ? 7 : 5).split("\\|", 2);
        String detail = f.length > 1 ? f[1].trim() : "";
        sum.append(f[0]).append("：");
        if (fail) {
          good = false;
          sum.append("失败").append(detail.isEmpty() ? "" : "（" + detail + "）");
        } else {
          sum.append(detail.isEmpty() ? "完成" : detail);
        }
        sum.append("\n");
      }
    }
    if (lateLoad && good) sum.append("\nLSPosed 模块将在目标应用下次启动时生效。");
    final String text = sum.toString().trim();
    final boolean ok = good;
    main.post(
        new Runnable() {
          public void run() {
            if (progress.isShowing()) progress.dismiss();
            if (needReboot && ok) a.showResultReboot(text);
            else showResult(text);
            refresh();
          }
        });
  }
}
