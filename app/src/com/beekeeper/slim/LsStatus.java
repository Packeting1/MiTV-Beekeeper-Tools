package com.beekeeper.slim;

import java.util.HashSet;
import java.util.Set;

import static com.beekeeper.slim.Catalog.*;

/** LSPosed 的运行状态和模块启用/作用域读取。 */
final class LsStatus extends Part {

    LsStatus(MainActivity a) {
        super(a);
    }

    /** LSPosed 状态；lsKnown=false 表示还没读到。lsEnabled/lsScope 为 null = 读不到配置库。 */
    boolean lsKnown, lsRunning, lsServer;

    java.util.Map<String, Boolean> lsEnabled;

    java.util.Map<String, Set<String>> lsScope;

    /** 读 su 复制到本应用缓存目录的 LSPosed 配置库副本；读完就删。 */
    boolean readLsDb(java.util.Map<String, Boolean> en, java.util.Map<String, Set<String>> sc) {
        java.io.File db = new java.io.File(getCacheDir(), "lsp_modules_config.db");
        android.database.sqlite.SQLiteDatabase d = null;
        try {
            if (!db.exists()) return false;
            d = android.database.sqlite.SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                    | android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            android.database.Cursor c = d.rawQuery("select module_pkg_name, enabled from modules_state where user_id=0", null);
            while (c.moveToNext()) en.put(c.getString(0), c.getInt(1) != 0);
            c.close();
            c = d.rawQuery("select module_pkg_name, app_pkg_name from scope where user_id=0", null);
            while (c.moveToNext()) {
                Set<String> s = sc.get(c.getString(0));
                if (s == null) { s = new HashSet<String>(); sc.put(c.getString(0), s); }
                s.add(c.getString(1));
            }
            c.close();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (d != null) d.close();
            new java.io.File(getCacheDir(), "lsp_modules_config.db").delete();
            new java.io.File(getCacheDir(), "lsp_modules_config.db-wal").delete();
            new java.io.File(getCacheDir(), "lsp_modules_config.db-shm").delete();
        }
    }

    String lsActual(String pkg) {
        if (!lsKnown) return "实际：LSPosed 没有运行，模块不会生效";
        if (!lsRunning) return "实际：LSPosed 没有运行，模块不会生效";
        if (!lsServer) return "实际：LSPosed 已运行，但未注入系统，重启后再看";
        if (lsEnabled == null) return "实际：读不到 LSPosed 的配置";
        Boolean e = lsEnabled.get(pkg);
        if (e == null) return "实际：LSPosed 未登记此模块，重启后再看";
        if (!e) return "实际：LSPosed 里没有启用，请到 LSPosed 里启用";
        String[] need = LS_SCOPES.get(pkg);
        if (need == null) return "实际：已在 LSPosed 启用";
        Set<String> have = lsScope == null ? null : lsScope.get(pkg);
        StringBuilder miss = new StringBuilder();
        for (String a : need) {
            if (have == null || !have.contains(a)) {
                if (miss.length() > 0) miss.append("、");
                miss.append(a.startsWith("com.xiaomi.mitv.") ? a.substring(16) : a);
            }
        }
        if (miss.length() > 0) return "实际：已在 LSPosed 启用，但作用域缺 " + miss;
        return "实际：已在 LSPosed 启用，作用域齐全";
    }
}
