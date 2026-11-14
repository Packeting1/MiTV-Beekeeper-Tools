package com.beekeeper.slim;

import android.content.DialogInterface;
import java.util.ArrayList;
import java.util.List;

/** 启动时发现旧的 Zygisk / LSPosed 环境：确认后卸载它们，开启 Magisk Zygisk、安装 Vector，并重启电视。 */
final class Migration extends Part {

  /** {模块 id, 显示名, 目录名}。NeoZygisk 与 Zygisk Next 的目录同为 zygisksu，id 由状态查询按模块名区分。 */
  private static final String[][] LEGACY = {
    {"neozygisk", "NeoZygisk", "zygisksu"},
    {"zygisksu", "Zygisk Next", "zygisksu"},
    {"zygote64_fix", "Zygote64 修复", "zygote64_fix"},
    {"zygisk_lsposed", "LSPosed", "zygisk_lsposed"},
  };

  private boolean asked;

  Migration(MainActivity a) {
    super(a);
  }

  private List<String[]> found() {
    List<String[]> r = new ArrayList<String[]>();
    if (a.modPresent == null) return r;
    for (String[] l : LEGACY) {
      if (a.modPresent.contains(l[0]) && !(a.modRemoving != null && a.modRemoving.contains(l[0]))) {
        r.add(l);
      }
    }
    return r;
  }

  boolean needed() {
    return !found().isEmpty();
  }

  /** 每次启动只提示一次；选“暂不”后，开启 Magisk Zygisk 和安装 Vector 保持锁定。 */
  void offer() {
    if (asked || a.isFinishing()) return;
    final List<String[]> found = found();
    if (found.isEmpty()) return;
    asked = true;
    StringBuilder names = new StringBuilder();
    for (String[] l : found) names.append(names.length() > 0 ? "、" : "").append(l[1]);
    new Dlg.Builder(a)
        .setTitle("环境迁移")
        .setMessage(
            "检测到已安装 "
                + names
                + "。\n\n因兼容性、使用场景和统一适配的考虑，建议卸载它们，并开启 Magisk Zygisk、安装 Vector。"
                + "\n确认后将自动执行以上迁移并重启电视。是否迁移？")
        .setPositiveButton(
            "迁移并重启",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                run(found);
              }
            })
        .setNegativeButton("暂不", null)
        .show();
  }

  private static String removeScript(String[] l) {
    String dir = l[2];
    // 暂存的更新要一并删掉：重启时 Magisk 会先用它覆盖旧目录，移除标记随之丢失
    String staged = "rm -rf /data/adb/modules_update/" + dir + "\n";
    if (l[0].equals("zygisk_lsposed")) {
      // 直接删目录而不打移除标记：LSPosed 的卸载脚本会删掉与 Vector 共用的 /data/adb/lspd
      return "rm -rf /data/adb/modules/" + dir + "\n" + staged;
    }
    return "[ -d /data/adb/modules/"
        + dir
        + " ] && touch /data/adb/modules/"
        + dir
        + "/remove\n"
        + staged;
  }

  private Group vector() {
    for (Group g : a.GROUPS) if ("zygisk_vector".equals(g.modId)) return g;
    throw new IllegalStateException();
  }

  private void run(final List<String[]> found) {
    final Progress pg = new Progress(a);
    pg.waiting("正在执行……");
    final Dlg progress = new Dlg.Builder(a).setTitle("正在执行").setView(pg.box).show();
    new Thread(
            new Runnable() {
              public void run() {
                final ApplyRunner ap = a.applier;
                final Group v = vector();
                final String install;
                try {
                  install = ap.installModScript(v);
                } catch (final Exception e) {
                  main.post(
                      new Runnable() {
                        public void run() {
                          progress.dismiss();
                          showResult("迁移失败", "无法读取内置模块 " + v.asset + "：" + e);
                        }
                      });
                  return;
                }
                StringBuilder script = ap.newScript();
                ap.step(script, v.title, "正在安装", "已安装，重启后生效", install);
                ap.step(script, "Magisk Zygisk", "正在开启", "已开启，重启后生效", ApplyRunner.zygiskScript(1));
                for (String[] l : found) {
                  ap.step(script, l[1], "正在卸载", "已卸载，重启后生效", removeScript(l));
                }
                ap.autoReboot = true;
                ap.runSteps(script, null, pg, progress, true, false);
              }
            },
            "slim-migrate")
        .start();
  }
}
