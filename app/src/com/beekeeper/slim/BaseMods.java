package com.beekeeper.slim;

import static com.beekeeper.slim.Net.*;
import static com.beekeeper.slim.Shell.*;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

/** 「基础」页的 Zygisk / Xposed 框架：从 GitHub Releases 检测新版本，更新时下载官方发布包。 */
final class BaseMods extends Part {

  LinearLayout bar;

  TextView barText;

  /** 最近一次查到的最新版本；没查到（或查失败）的没有记录。 */
  private final Map<Group, Release> rels = new HashMap<Group, Release>();

  private boolean checked;

  BaseMods(MainActivity a) {
    super(a);
  }

  /** 已装（含已暂存、重启后生效）的版本号，如 1.5.3；没装返回 null。 */
  private String installed(Group g) {
    String id = g.modId;
    if (a.modPresent == null
        || !a.modPresent.contains(id)
        || a.modRemoving != null && a.modRemoving.contains(id)) return null;
    String[] v = a.modUpVer.containsKey(id) ? a.modUpVer.get(id) : a.modVer.get(id);
    return v == null ? "0" : v[1].split(" ")[0];
  }

  private static Release query(Group g, String cur) throws Exception {
    return fetchRelease(g.repo, g.repoAsset, cur);
  }

  /** 有新版本时返回 {当前, 最新}，否则 null。 */
  String[] update(Group g) {
    Release r = rels.get(g);
    String cur = installed(g);
    if (r == null || cur == null || cmpVersion(r.tag, cur) <= 0) return null;
    return new String[] {cur, r.tag};
  }

  private Group firstUpdate() {
    for (Group g : a.GROUPS) if (g.base && update(g) != null) return g;
    return null;
  }

  void updateUi() {
    if (bar == null) return;
    StringBuilder sb = new StringBuilder();
    int n = 0;
    for (Group g : a.GROUPS) {
      String[] up = g.base ? update(g) : null;
      if (up == null) continue;
      sb.append(n++ > 0 ? "；" : "")
          .append(g.title)
          .append(" ")
          .append(up[0])
          .append(" → ")
          .append(up[1]);
    }
    bar.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    if (n > 0) barText.setText("基础组件有新版本：" + sb);
  }

  /** 读完模块状态后对已安装的检查一次；失败就静默跳过。 */
  void autoCheck() {
    if (checked || a.modPresent == null) return;
    checked = true;
    final Map<Group, String> todo = new HashMap<Group, String>();
    for (Group g : a.GROUPS) {
      String cur = g.base && g.repo != null ? installed(g) : null;
      if (cur != null) todo.put(g, cur);
    }
    if (todo.isEmpty()) return;
    new Thread(
            new Runnable() {
              public void run() {
                final Map<Group, Release> found = new HashMap<Group, Release>();
                for (Map.Entry<Group, String> e : todo.entrySet()) {
                  try {
                    found.put(e.getKey(), query(e.getKey(), e.getValue()));
                  } catch (Throwable ignore) {
                  }
                }
                main.post(
                    new Runnable() {
                      public void run() {
                        rels.putAll(found);
                        a.render();
                      }
                    });
              }
            },
            "slim-basecheck")
        .start();
  }

  /** 把最新版下载到缓存目录并校验；在工作线程里调用，返回的文件由调用方负责删除。 */
  private File download(Group g, Progress p, CancelToken tok) throws Exception {
    Release r = rels.get(g);
    if (r == null) r = query(g, null);
    File f = new File(getCacheDir(), g.modId + ".zip");
    downloadFile(main, withMirrors(r.url), r.digest, f, p, tok);
    String[] prop = ModuleUpdater.readModProp(new FileInputStream(f));
    if (prop == null || !g.modId.equals(prop[2])) {
      f.delete();
      throw new Exception("下载的不是 " + g.title + " 模块");
    }
    f.setReadable(true, false);
    return f;
  }

  void confirmUpdate() {
    final Group g = firstUpdate();
    if (g == null) {
      updateUi();
      return;
    }
    final String[] up = update(g);
    final Release rel = rels.get(g);
    showUpdateDialog(
        g.title + " 有新版本 " + up[1],
        "更新来源："
            + "github.com/"
            + g.repo
            + "\n点“更新”将下载并安装，更新后重启电视才生效。\n\n"
            + g.title
            + " "
            + up[0]
            + " → "
            + up[1],
        rel,
        new Runnable() {
          public void run() {
            install(g, up[1]);
          }
        },
        new Runnable() {
          public void run() {
            showFullNotes(
                g.title + " 完整更新日志",
                rel,
                new Runnable() {
                  public void run() {
                    confirmUpdate();
                  }
                });
          }
        });
  }

  private void install(final Group g, final String tag) {
    final Progress p = new Progress(a);
    final CancelToken tok = new CancelToken();
    final Dlg progress = showProgress("正在更新 " + g.title, p.box, tok, "已取消");
    new Thread(
            new Runnable() {
              public void run() {
                final File f;
                try {
                  f = download(g, p, tok);
                  if (!tok.beginInstall()) {
                    f.delete();
                    throw new CancelledException();
                  }
                } catch (Throwable t) {
                  showDownloadError(progress, t);
                  return;
                }
                showInstalling(progress, p, "正在安装……");
                final String out =
                    runSu(
                        "magisk --install-module "
                            + f.getAbsolutePath()
                            + " 2>&1\necho RC=$?\nrm -f "
                            + f.getAbsolutePath()
                            + "\n");
                f.delete();
                final boolean denied = noRoot(out), ok = out.contains("RC=0");
                main.post(
                    new Runnable() {
                      public void run() {
                        if (progress.isShowing()) progress.dismiss();
                        if (ok) a.showResultReboot(g.title + " 已更新到 " + tag + "。");
                        else showResult(denied ? "没有 root 权限，无法安装。" : "安装失败。\n\n" + out.trim());
                        refresh();
                      }
                    });
              }
            },
            "slim-baseinstall")
        .start();
  }
}
