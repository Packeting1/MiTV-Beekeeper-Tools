package com.beekeeper.slim;

import static com.beekeeper.slim.Net.*;

import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.view.View;

/** 功能类的共同基类：转发 MainActivity 的 UI 和状态。 */
abstract class Part {

  final MainActivity a;
  final Handler main;

  Part(MainActivity a) {
    this.a = a;
    this.main = a.main;
  }

  PackageManager getPackageManager() {
    return a.getPackageManager();
  }

  String getPackageName() {
    return a.getPackageName();
  }

  java.io.File getCacheDir() {
    return a.getCacheDir();
  }

  android.content.res.AssetManager getAssets() {
    return a.getAssets();
  }

  int dp(int v) {
    return a.dp(v);
  }

  void showResult(String text) {
    a.showResult(text);
  }

  void showResult(String title, String text) {
    a.showResult(title, text);
  }

  void refresh() {
    a.refresh();
  }

  int state(String pkg) {
    return a.state(pkg);
  }

  int dbState() {
    return a.dbState();
  }

  String homePackage() {
    return a.homePackage();
  }

  Group groupOfKind(int kind) {
    return a.groupOfKind(kind);
  }

  boolean noHomeReady() {
    return a.noHomeReady();
  }

  boolean modInstalled(Group g) {
    return a.modInstalled(g);
  }

  /** 带“取消”按钮的进度弹窗；返回键同样取消。 */
  Dlg showProgress(String title, View body, final CancelToken tok, final String cancelMsg) {
    final Runnable doCancel =
        new Runnable() {
          public void run() {
            if (tok.cancel())
              android.widget.Toast.makeText(a, cancelMsg, android.widget.Toast.LENGTH_SHORT).show();
          }
        };
    return new Dlg.Builder(a)
        .setTitle(title)
        .setView(body)
        .setCancelable(true)
        .setOnCancelListener(
            new DialogInterface.OnCancelListener() {
              public void onCancel(DialogInterface d) {
                doCancel.run();
              }
            })
        .setNegativeButton(
            "取消",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                doCancel.run();
              }
            })
        .show();
  }

  /** 下载或校验出错：收起进度弹窗并提示；用户取消时不提示。 */
  void showDownloadError(final Dlg progress, Throwable t) {
    final boolean cancelled = t instanceof CancelledException;
    final boolean netFail = t instanceof NetFail;
    final String err = netFail ? t.getMessage() : String.valueOf(t);
    main.post(
        new Runnable() {
          public void run() {
            if (progress.isShowing()) progress.dismiss();
            if (cancelled) return;
            if (netFail) showNetFail("下载", err);
            else showResult("更新失败：" + err);
          }
        });
  }

  /** 下载完成、开始安装：不能再取消。 */
  void showInstalling(final Dlg progress, final Progress p, final String msg) {
    main.post(
        new Runnable() {
          public void run() {
            progress.setCancelable(false);
            progress.getButton(Dlg.BUTTON_NEGATIVE).setEnabled(false);
            p.waiting(msg);
          }
        });
  }

  /** 更新确认框；说明了不止 2 个版本时多一个“查看完整更新日志”按钮。 */
  void showUpdateDialog(
      String title, String body, Release rel, final Runnable onUpdate, final Runnable onFull) {
    String notes = rel.notes == null || rel.notes.length() == 0 ? "（该版本没有更新说明）" : rel.notes;
    Dlg.Builder b =
        new Dlg.Builder(a)
            .setTitle(title)
            .setView(a.scrollText(body + "\n\n更新说明：\n" + notes))
            .setPositiveButton(
                "更新",
                new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface d, int w) {
                    onUpdate.run();
                  }
                })
            .setNegativeButton("取消", null);
    if (rel.count > 2) {
      b.setNeutralButton(
          "查看完整更新日志",
          new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
              onFull.run();
            }
          });
    }
    b.show();
  }

  void showFullNotes(String title, Release rel, final Runnable back) {
    new Dlg.Builder(a)
        .setTitle(title + "（共 " + rel.count + " 个版本）")
        .setView(a.scrollText(rel.notesFull))
        .setPositiveButton(
            "返回",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                back.run();
              }
            })
        .show();
  }

  void showNetFail(String what, String detail) {
    showResult("网络连接失败", "所有线路都" + what + "失败。\n\n建议更换其他网络（例如手机热点）后重试。\n\n最后一个错误：" + detail);
  }

  /** 把内置资源复制到缓存目录（同名），让 root 能读到；返回路径。 */
  String copyAsset(String asset) throws Exception {
    java.io.File f = new java.io.File(getCacheDir(), asset);
    try (java.io.InputStream in = getAssets().open(asset);
        java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
      byte[] buf = new byte[65536];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
    f.setReadable(true, false);
    return f.getAbsolutePath();
  }
}
