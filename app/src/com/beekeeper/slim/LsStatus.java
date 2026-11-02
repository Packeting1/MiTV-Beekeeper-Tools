package com.beekeeper.slim;

import static com.beekeeper.slim.Catalog.*;

import java.util.HashSet;
import java.util.Set;

/** LSPosed 的运行状态和模块启用/作用域读取。 */
final class LsStatus extends Part {

  LsStatus(MainActivity a) {
    super(a);
  }

  /** lsKnown=false 表示还没读到状态；lsEnabled/lsScope 为 null = 读不到配置库。 */
  boolean lsKnown, lsRunning, lsServer;

  /** 是否装了 LSPosed / Vector（Magisk 模块目录存在）；没读到状态前按已安装处理。 */
  boolean lsInstalled = true;

  java.util.Map<String, Boolean> lsEnabled;

  java.util.Map<String, Set<String>> lsScope;

  /** 读 su 复制到缓存目录的配置库副本。 */
  boolean readLsDb(java.util.Map<String, Boolean> en, java.util.Map<String, Set<String>> sc) {
    java.io.File db = new java.io.File(getCacheDir(), "lsp_modules_config.db");
    android.database.sqlite.SQLiteDatabase d = null;
    try {
      if (!db.exists()) return false;
      d =
          android.database.sqlite.SQLiteDatabase.openDatabase(
              db.getAbsolutePath(),
              null,
              android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                  | android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS);
      // 两种库结构：旧 LSPosed 有 modules_state 表、scope 直接带包名；
      // 原版 LSPosed 1.9+ 和 Vector 的 modules 表自带 enabled，scope 用 mid 关联。
      boolean legacy = false;
      android.database.Cursor c =
          d.rawQuery("select 1 from sqlite_master where name='modules_state'", null);
      if (c.moveToFirst()) legacy = true;
      c.close();
      c =
          d.rawQuery(
              legacy
                  ? "select module_pkg_name, enabled from modules_state where user_id=0"
                  : "select module_pkg_name, enabled from modules",
              null);
      while (c.moveToNext()) en.put(c.getString(0), c.getInt(1) != 0);
      c.close();
      if (legacy) {
        // 装了但从没启用过的模块只在 modules 表里，没有 modules_state 行，算“未启用”而不是“未登记”。
        c = d.rawQuery("select module_pkg_name from modules", null);
        while (c.moveToNext()) if (!en.containsKey(c.getString(0))) en.put(c.getString(0), false);
        c.close();
      }
      c =
          d.rawQuery(
              legacy
                  ? "select module_pkg_name, app_pkg_name from scope where user_id=0"
                  : "select m.module_pkg_name, s.app_pkg_name from scope s join modules m on"
                      + " m.mid=s.mid where s.user_id=0",
              null);
      while (c.moveToNext()) {
        Set<String> s = sc.get(c.getString(0));
        if (s == null) {
          s = new HashSet<String>();
          sc.put(c.getString(0), s);
        }
        s.add(c.getString(1));
      }
      c.close();
      return true;
    } catch (Exception e) {
      return false;
    } finally {
      if (d != null) d.close();
      for (String suffix : new String[] {"", "-wal", "-shm"})
        new java.io.File(db.getPath() + suffix).delete();
    }
  }

  String lsActual(String pkg) {
    if (!lsInstalled) return "实际：未检测到 LSPosed / Vector";
    if (!lsKnown) return "实际：未能读取 LSPosed 的状态";
    if (!lsRunning) return "实际：LSPosed 没有运行，模块不会生效";
    if (lsEnabled == null) return "实际：未能读取 LSPosed 的配置";
    Boolean e = lsEnabled.get(pkg);
    if (e == null) return "实际：LSPosed 未登记此模块";
    if (!e) return "实际：模块未在 LSPosed 中启用";
    if (!lsServer) return "实际：LSPosed 已运行，但未注入系统，请重启后重试";
    if (LS_SCOPES.get(pkg) == null) return "实际：已在 LSPosed 启用";
    String miss = missingScope(pkg);
    if (miss.length() > 0) return "实际：已在 LSPosed 启用，但作用域缺 " + miss;
    return "实际：已在 LSPosed 启用，作用域齐全";
  }

  private String missingScope(String pkg) {
    String[] need = LS_SCOPES.get(pkg);
    if (need == null) return "";
    Set<String> have = lsScope == null ? null : lsScope.get(pkg);
    StringBuilder miss = new StringBuilder();
    for (String a : need) {
      if (have == null || !have.contains(a)) {
        if (miss.length() > 0) miss.append("、");
        miss.append(a.startsWith("com.xiaomi.mitv.") ? a.substring(16) : a);
      }
    }
    return miss.toString();
  }

  /** 已读到配置库，且模块没启用或作用域不全：点“应用”时由 LsDb 补上。 */
  boolean needsFix(String pkg) {
    if (!lsKnown || !lsRunning || lsEnabled == null) return false;
    return !Boolean.TRUE.equals(lsEnabled.get(pkg)) || missingScope(pkg).length() > 0;
  }
}
