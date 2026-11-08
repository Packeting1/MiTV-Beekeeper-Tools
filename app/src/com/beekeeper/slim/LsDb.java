package com.beekeeper.slim;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/*
 * 在 root 下由 app_process 运行：在 Xposed 配置库里启用模块并写入作用域。用法：LsDb 模块包名 [作用域应用…]。
 * 只处理旧 LSPosed 的库结构；要先于 pm install -r 执行，守护进程在包更新时才会重新读库。
 */
public final class LsDb {

  private static final String DB = "/data/adb/lspd/config/modules_config.db";

  public static void main(String[] a) {
    int rc = 1;
    try {
      rc = seed(a);
    } catch (Throwable e) {
      System.out.println("写入 Xposed 配置失败：" + e);
    }
    // app_process 不会自己退出
    System.exit(rc);
  }

  private static int seed(String[] a) {
    SQLiteDatabase d =
        SQLiteDatabase.openDatabase(
            DB, null, SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
    try {
      Cursor c = d.rawQuery("select 1 from sqlite_master where name='modules_state'", null);
      boolean legacy = c.moveToFirst();
      c.close();
      if (!legacy) {
        System.out.println("Xposed 配置库结构不支持自动写入，请在 Xposed 中手动启用 " + a[0]);
        return 2;
      }
      d.beginTransaction();
      try {
        d.execSQL(
            "insert or replace into modules_state(module_pkg_name,user_id,enabled) values(?,0,1)",
            new Object[] {a[0]});
        for (int i = 1; i < a.length; i++)
          d.execSQL(
              "insert or ignore into scope(module_pkg_name,app_pkg_name,user_id) values(?,?,0)",
              new Object[] {a[0], a[i]});
        d.setTransactionSuccessful();
      } finally {
        d.endTransaction();
      }
      System.out.println("已在 Xposed 启用 " + a[0]);
      return 0;
    } finally {
      d.close();
    }
  }
}
