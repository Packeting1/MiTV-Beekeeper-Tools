package com.beekeeper.slim;

import static com.beekeeper.slim.Catalog.*;
import static com.beekeeper.slim.Shell.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ModuleQuery extends Part {
  private boolean reading, again;

  ModuleQuery(MainActivity a) {
    super(a);
  }

  void refresh() {
    // 查询共用缓存目录中的临时文件，不能重叠执行。
    if (reading) {
      again = true;
      return;
    }
    reading = true;
    new Thread(
            new Runnable() {
              public void run() {
                query();
              }
            },
            "slim-query")
        .start();
  }

  private void query() {
    final Map<String, String[]> bundled = new HashMap<String, String[]>(),
        apks = new HashMap<String, String[]>();
    Thread reader = null;
    if (a.bundledMod.isEmpty() && a.bundledApk.isEmpty()) {
      reader =
          new Thread(
              new Runnable() {
                public void run() {
                  for (Group g : a.GROUPS) {
                    if (g.kind == KIND_MOD) {
                      String[] v = a.modUp.readBundledMod(g.asset);
                      if (v != null) bundled.put(g.modId, v);
                      if (g.companion != null) {
                        v = a.modUp.readBundledApk(g.companion);
                        if (v != null) apks.put(g.pkgs[0], v);
                      }
                    } else if (g.kind == KIND_APK) {
                      String[] v = a.modUp.readBundledApk(g.asset);
                      if (v != null) apks.put(g.pkgs[0], v);
                    }
                  }
                }
              },
              "slim-bundled");
      reader.start();
    }
    final Snapshot s = parse(suOut(queryScript()));
    final Map<String, Boolean> enabled = new HashMap<String, Boolean>();
    final Map<String, Set<String>> scopes = new HashMap<String, Set<String>>();
    final boolean dbRead = s.ok && a.ls.readLsDb(enabled, scopes);
    if (reader != null) {
      try {
        reader.join();
      } catch (InterruptedException ignored) {
      }
    }
    main.post(
        new Runnable() {
          public void run() {
            try {
              if (!bundled.isEmpty() || !apks.isEmpty()) {
                a.bundledMod = bundled;
                a.bundledApk = apks;
              }
              a.modVer = s.versions;
              a.modUpVer = s.upVersions;
              a.modPresent = s.ok ? s.present : null;
              a.modRemoving = s.ok ? s.removing : null;
              a.modPending = s.ok ? s.pending : null;
              a.modDisabled = s.disabled;
              a.modActive = s.active;
              a.ls.lsInstalled = s.installed;
              a.ls.lsKnown = s.known;
              a.ls.lsRunning = s.running;
              a.ls.lsServer = s.server;
              a.ls.lsEnabled = dbRead ? enabled : null;
              a.ls.lsScope = dbRead ? scopes : null;
              a.render();
              a.modUp.updateBanner();
              a.baseMods.autoCheck();
              a.hideLoading();
              a.migration.offer();
            } finally {
              a.hideLoading();
              reading = false;
              if (again) {
                again = false;
                a.refresh();
              }
            }
          }
        });
  }

  String queryScript() {
    return "CD="
        + getCacheDir().getAbsolutePath()
        + "\n"
        // LSPosed 和 Vector 的模块目录不同（zygisk_lsposed / zygisk_vector）；前者用
        // lspctl，后者用 cli；部分 LSPosed 构建两者都没有，只能靠系统进程注入判断。
        // 状态命令最慢（约 2 秒），放后台和其余检测并行
        + "LM=/data/adb/modules/zygisk_lsposed; [ -d"
        + " /data/adb/modules/zygisk_vector ] &&"
        + " LM=/data/adb/modules/zygisk_vector\n"
        + "CTL=; [ -f \"$LM/lspctl\" ] && CTL=lspctl\n"
        + "[ -z \"$CTL\" ] && [ -f \"$LM/cli\" ] && CTL=cli\n"
        + "if [ -n \"$CTL\" ]; then ( timeout 15 sh \"$LM/$CTL\" status >"
        + " \"$CD/lsp_status\" 2>&1; echo $? > \"$CD/lsp_rc\" ) < /dev/null &"
        + " fi\n"
        // 只用 shell 内建命令读 module.prop，避免每个模块起多个子进程
        + "pv() { code=; ver=; nm=; [ -r \"$1/module.prop\" ] || return; while"
        + " IFS= read -r l || [ -n \"$l\" ]; do case $l in versionCode=*) [ -z"
        + " \"$code\" ] && code=${l#versionCode=};; version=*) [ -z \"$ver\" ]"
        + " && ver=${l#version=};; name=*) [ -z \"$nm\" ] && nm=${l#name=};;"
        + " esac; done < \"$1/module.prop\"; }\n"
        // NeoZygisk 和 Zygisk Next 的模块 id 都是 zygisksu，按名字区分，改报 neozygisk
        + "for d in /data/adb/modules/*/; do [ -d \"$d\" ] || continue;"
        + " d=${d%/}; id=${d##*/}; pv \"$d\"; [ \"$nm\" = NeoZygisk ] &&"
        + " id=neozygisk; echo \"M:$id\"; [ -e \"$d/remove\" ] && echo"
        + " \"R:$id\"; [ -e \"$d/disable\" ] && echo \"D:$id\"; echo"
        + " \"V:$id|$code|$ver\"; done\n"
        + "for d in /data/adb/modules_update/*/; do [ -d \"$d\" ] || continue;"
        + " d=${d%/}; id=${d##*/}; pv \"$d\"; [ \"$nm\" = NeoZygisk ] &&"
        + " id=neozygisk; echo \"U:$id\"; [ -e \"$d/disable\" ] && echo"
        + " \"D:$id\"; echo \"X:$id|$code|$ver\"; done\n"
        // Magisk Zygisk 没有模块目录，开关在 Magisk 数据库里
        + "[ \"$(magisk --sqlite \"SELECT value FROM settings WHERE"
        + " key='zygisk'\" 2>/dev/null)\" = value=1 ] && echo"
        + " \"M:magisk_zygisk\"\n"
        + "H=$(sha256sum /system/etc/mitv.config_product.xml 2>/dev/null | cut"
        + " -d' ' -f1)\n"
        + "M=$(sha256sum"
        + " /data/adb/modules/mitv_4kui_switch/system/etc/mitv.config_product.xml"
        + " 2>/dev/null | cut -d' ' -f1)\n"
        + "[ -n \"$H\" ] && [ \"$H\" = \"$M\" ] && echo"
        + " \"F:mitv_4kui_switch\"\n"
        + "grep -la \"[m]itv_adb_keeper/keeper.sh\" /proc/[0-9]*/cmdline"
        + " >/dev/null 2>&1 && echo \"F:mitv_adb_keeper\"\n"
        + "service call TvService 82 s16 support_hdr_game_hgig 2>/dev/null |"
        + " grep -q 'Y.E.S' && echo \"F:beekeeper_hgig_fix\"\n"
        + "L=$(sha256sum"
        + " /vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/ldm.ini"
        + " 2>/dev/null | cut -d' ' -f1)\n"
        + "[ \"$L\" ="
        + " 282d2ad2991845cd79660bb13111cba1da82e8116f1519e2b395606b27eb6dcf ]"
        + " && echo \"F:beekeeper_ldm_fix\"\n"
        + "E=$(sha256sum"
        + " /vendor/tvconfig/bsp/common/EDID_BIN/GPro_HDMI1_FreeSync_ver6.0.bin"
        + " 2>/dev/null | cut -d' ' -f1)\n"
        + "[ \"$E\" ="
        + " 04d48a32abcb0fe7e8a25cac683c09b9f6dbd00dd81b8d0ab170b3d5534237fc ]"
        + " && echo \"F:beekeeper_dv_edid\"\n"
        // DP 的 Enhanced EDID 已是带音频格式的版本（仅音频 / 音频+LLDV）
        + "case $(sha256sum /vendor/tvconfig/bsp/common/EDID_BIN/GPro_DP_Enhanced_ver6.0.bin"
        + " 2>/dev/null | cut -d' ' -f1) in"
        + " 29adce40ed1a7a733d3f9799c55451b00903c5eb806ab2008d324dc94e389903|e48a381124833ee6173d83ca82b948edc3918a31795a250c09f9a395233f67fc)"
        + " echo \"F:beekeeper_dp_audio\";; esac\n"
        + "for z in $(pidof zygote64); do grep -q /system/lib64/libzygisk.so /proc/$z/maps"
        + " 2>/dev/null && echo \"F:magisk_zygisk\"; done\n"
        // 只有本模块会往这个路径 bind mount 补丁库
        + "grep -q libmtkapp.so /proc/mounts && echo"
        + " \"F:beekeeper_freesync_keep\"\n"
        + "[ \"$(getprop sys.beekeeper_rgb.native)\" = 1 ] && echo"
        + " \"F:beekeeper_decorative_light\"\n"
        + "wait\n"
        + "S=$(cat \"$CD/lsp_status\" 2>/dev/null); rc=$(cat \"$CD/lsp_rc\""
        + " 2>/dev/null); rm -f \"$CD/lsp_status\" \"$CD/lsp_rc\"\n"
        + "[ -d /data/adb/modules/zygisk_lsposed ] || [ -d"
        + " /data/adb/modules/zygisk_vector ] && echo \"L:installed\"\n"
        + "[ \"$rc\" = 0 ] && echo \"L:run\"\n"
        + "[ -z \"$CTL\" ] && [ -d \"$LM\" ] && echo \"L:noctl\"\n"
        + "echo \"$S\" | grep -qE 'cannot connect|Socket file not found' &&"
        + " echo \"L:dead\"\n"
        // lspctl 直接报告；Vector 的 cli 不报告，改看系统进程里有没有加载 Zygisk 模块库。
        // Magisk Zygisk 下 maps 里没有 Vector 的库路径，再看 Vector 日志有没有记录本次
        // system_server 的注入
        + "echo \"$S\" | grep -q 'System server: connected' && echo"
        + " \"L:server\"\n"
        + "LG=$(ls -t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -1)\n"
        + "for p in $(pidof system_server); do grep -qE"
        + " 'zygisk_(lsposed|vector)' /proc/$p/maps 2>/dev/null && echo"
        + " \"L:server\"; [ -n \"$LG\" ] && grep -qE \": *$p: *$p"
        + " I/VectorNative .*Injected Vector framework\" \"$LG\" && echo"
        + " \"L:server\"; done\n"
        + "for f in modules_config.db modules_config.db-wal; do rm -f"
        + " \"$CD/lsp_$f\"; cp \"/data/adb/lspd/config/$f\" \"$CD/lsp_$f\""
        + " 2>/dev/null && chmod 666 \"$CD/lsp_$f\" && chown $(stat -c %u:%g"
        + " \"$CD\") \"$CD/lsp_$f\"; done\n"
        + flagQuery()
        + "echo __Q_OK__\n";
  }

  String flagQuery() {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : FLAG_MODS.entrySet()) {
      sb.append("service call TvService 82 s16 ")
          .append(e.getValue())
          .append(" 2>/dev/null | grep -q 'Y.E.S' && echo \"F:")
          .append(e.getKey())
          .append("\"\n");
    }
    return sb.toString();
  }

  static final class Snapshot {
    boolean ok, installed, known, running, server;
    final Set<String> present = new HashSet<String>(),
        removing = new HashSet<String>(),
        pending = new HashSet<String>(),
        disabled = new HashSet<String>(),
        active = new HashSet<String>();
    final Map<String, String[]> versions = new HashMap<String, String[]>(),
        upVersions = new HashMap<String, String[]>();
  }

  static Snapshot parse(String out) {
    Snapshot s = new Snapshot();
    s.ok = out != null && out.contains("__Q_OK__");
    boolean run = false, dead = false, server = false, installed = false, noCtl = false;
    if (s.ok) {
      for (String line : out.split("\n")) {
        line = line.trim();
        if (line.startsWith("V:") || line.startsWith("X:")) {
          String[] p = line.substring(2).split("\\|", 3);
          if (p.length == 3 && p[1].length() > 0) {
            (line.startsWith("V:") ? s.versions : s.upVersions)
                .put(p[0], new String[] {p[1], p[2].length() > 0 ? p[2] : p[1]});
          }
        } else if (line.startsWith("M:")) s.present.add(line.substring(2));
        else if (line.startsWith("R:")) s.removing.add(line.substring(2));
        else if (line.startsWith("U:")) {
          s.present.add(line.substring(2));
          s.pending.add(line.substring(2));
        } else if (line.startsWith("D:")) s.disabled.add(line.substring(2));
        else if (line.startsWith("F:")) s.active.add(line.substring(2));
        else if (line.equals("L:installed")) installed = true;
        else if (line.equals("L:run")) run = true;
        else if (line.equals("L:dead")) dead = true;
        else if (line.equals("L:noctl")) noCtl = true;
        else if (line.equals("L:server")) server = true;
      }
    }
    if (server) s.active.add("zygisk_vector");
    s.running = run || noCtl && server;
    s.server = server;
    s.known = s.ok && (run || dead || noCtl);
    s.installed = !s.ok || installed;
    return s;
  }
}
