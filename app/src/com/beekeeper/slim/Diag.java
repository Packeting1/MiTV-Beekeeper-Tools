package com.beekeeper.slim;

import static com.beekeeper.slim.Catalog.*;

import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/** 上传诊断：收集固件、模块和日志信息，脱敏后上传换诊断码。 */
final class Diag extends Part {

  /** 上传地址存在仓库里，换域名只需改这个文件，不用发版。 */
  private static final String ENDPOINT_PATH = "main/tools/diag-worker/endpoint.txt";

  private static final String[] SCRIPT = {
    "sec() { echo; echo \"=== $1 ===\"; }",
    "tl() { for f in $(ls -t $1 2>/dev/null | head -n $2); do echo \"--- $f\"; tail -n $3 $f; done;"
        + " }",
    "sec date; date; uptime",
    "sec getprop; getprop",
    "sec kernel; cat /proc/version; head -n 3 /proc/meminfo; wm size; wm density",
    "sec magisk; magisk -v; magisk -V",
    "sec 'magisk modules'",
    "for d in /data/adb/modules/*/; do [ -d \"$d\" ] || continue; d=${d%/}; f=;"
        + " [ -e $d/disable ] && f=\"$f disable\"; [ -e $d/remove ] && f=\"$f remove\";"
        + " echo \"${d##*/}: $(grep -E '^version(Code)?=' $d/module.prop 2>/dev/null | tr '\\n'"
        + " ' ')$f\"; done",
    "sec 'modules_update'; ls /data/adb/modules_update 2>/dev/null",
    "sec lsposed",
    "LM=/data/adb/modules/zygisk_lsposed; [ -d /data/adb/modules/zygisk_vector ] &&"
        + " LM=/data/adb/modules/zygisk_vector",
    "if [ -f $LM/lspctl ]; then CTL=lspctl; else CTL=cli; fi",
    "ls $LM 2>/dev/null | tr '\\n' ' '; echo; timeout 15 sh $LM/$CTL status 2>&1",
    "sec 'lspd modules log'; tl '/data/adb/lspd/log/modules_*.log' 2 300",
    "sec 'lspd verbose log'; tl '/data/adb/lspd/log/verbose_*.log' 1 200",
    "sec 'module data logs'; tl '/data/adb/*/*.log' 8 100; tl '/data/adb/*/log.txt' 8 100",
    "sec packages",
    "pm list packages --show-versioncode | grep -E 'beekeeper|app.orchard.tv|rin\\.|xbmc'",
    "echo disabled:; pm list packages -d",
    "sec settings",
    "settings list system | grep -iE 'signalkeep|tvplayerkeep|beekeeper'",
    "settings list global | grep -iE 'voicekeymap|signalkeep|mediabadge|picture_mode'",
    "sec files",
    "grep -E 'libmtkapp|EDID' /proc/mounts",
    "sha256sum /vendor/tvconfig/bsp/common/EDID_BIN/GPro_*.bin"
        + " /vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/ldm.ini"
        + " /mnt/vendor/linux_rootfs/lib/libmtkapp.so 2>&1",
    "sec 'system crash log'",
    "timeout 15 logcat -b crash -d -t 300 2>&1 | grep -iE -B3 -A25 'com\\.beekeeper' | tail -n 300",
    "sec logcat",
    "timeout 20 logcat -d -t 20000 2>&1 | grep -iE"
        + " 'signalkeep|tvplayerkeep|castpip|homeref|installunlock|nosignalaudio|moredisplay"
        + "|dolbyvisiontitle|mediabadge|voicekeymap|lsposed|beekeeper' | tail -n 500",
  };

  Diag(MainActivity a) {
    super(a);
  }

  void start() {
    new Dlg.Builder(a)
        .setTitle("上传诊断")
        .setMessage(
            "我们将收集您设备的系统版本、模块状态、Xposed 与模块日志、系统属性，并脱去序列号、MAC、IP 等敏感信息。"
                + "\n\n在上传后，您将会得到一个 8 位的诊断码，有效期 14 天。反馈问题时请附上诊断码，开发者凭它排查。")
        .setPositiveButton(
            "上传",
            new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface d, int w) {
                run();
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void run() {
    final Progress p = new Progress(a);
    p.waiting("正在收集……");
    final Dlg dlg = new Dlg.Builder(a).setTitle("上传诊断").setView(p.box).setCancelable(false).show();
    new Thread(
            new Runnable() {
              public void run() {
                String title = "上传失败";
                String msg = null, code = null;
                try {
                  String text = collect(p);
                  if (text == null) {
                    title = "上传诊断";
                    msg = "无法使用 root，未能收集诊断信息。";
                  } else {
                    main.post(
                        new Runnable() {
                          public void run() {
                            p.waiting("正在上传……");
                          }
                        });
                    code = upload(text, endpoint());
                  }
                } catch (Exception e) {
                  msg = e.getMessage();
                }
                final String t = title, m = msg, c = code;
                main.post(
                    new Runnable() {
                      public void run() {
                        if (dlg.isShowing()) dlg.dismiss();
                        if (c != null) showCode(c);
                        else showResult(t, m);
                      }
                    });
              }
            },
            "slim-diag")
        .start();
  }

  private void showCode(String code) {
    TextView big = new TextView(a);
    big.setText(code);
    big.setTextSize(44);
    big.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    big.setLetterSpacing(0.12f);
    big.setTextColor(Ui.ACCENT);
    big.setGravity(Gravity.CENTER);
    TextView hint = new TextView(a);
    hint.setText("有效期 14 天，反馈问题时请附带此诊断码。");
    hint.setTextSize(13);
    hint.setTextColor(Ui.SUB);
    hint.setGravity(Gravity.CENTER);
    LinearLayout box = new LinearLayout(a);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(0, dp(12), 0, dp(4));
    box.addView(big);
    LinearLayout.LayoutParams lp = Ui.lp();
    lp.topMargin = dp(12);
    box.addView(hint, lp);
    new Dlg.Builder(a).setTitle("诊断码").setView(box).setPositiveButton("确定", null).show();
  }

  /** 收集并脱敏；没有 root 返回 null。 */
  private String collect(final Progress p) throws Exception {
    StringBuilder s = new StringBuilder();
    int n = 0;
    for (String l : SCRIPT) {
      s.append(l).append('\n');
      if (l.startsWith("sec ")) n++;
    }
    final int total = n;
    final int[] seen = new int[1];
    String out =
        Shell.suOut(
            s.toString(),
            new Shell.Lines() {
              public void line(String l) {
                if (!l.startsWith("=== ") || !l.endsWith(" ===")) return;
                final int pct = seen[0]++ * 100 / total;
                main.post(
                    new Runnable() {
                      public void run() {
                        p.collecting(pct);
                      }
                    });
              }
            });
    if (out == null) return null;
    StringBuilder h = new StringBuilder();
    String ver = "?";
    try {
      ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception ignore) {
    }
    h.append("=== app ===\nMiTV-Beekeeper-Tools ").append(ver).append('\n');
    h.append("lsInstalled=").append(a.ls.lsInstalled);
    h.append(" lsKnown=").append(a.ls.lsKnown);
    h.append(" lsRunning=").append(a.ls.lsRunning);
    h.append(" lsServer=").append(a.ls.lsServer).append('\n');
    for (String pkg : LS_SCOPES.keySet())
      h.append(pkg).append(": ").append(a.ls.lsActual(pkg)).append('\n');
    return redact(h + "\n" + out);
  }

  private static final Pattern KEY =
      Pattern.compile(
          "(?i)^\\[[^\\]]*(serial|mac|imei|imsi|ssid|android_?id|device_?id|token|pass(word|wd)|\\.sn(?![a-z])|btaddr|uuid|guid)[^\\]]*\\]:"
              + " \\[");
  private static final Pattern MAC = Pattern.compile("(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b");
  private static final Pattern IP =
      Pattern.compile("(?<![\\w.])(\\d{1,3}\\.){3}\\d{1,3}(?![\\w.])");
  private static final Pattern SSID = Pattern.compile("(?i)(b?ssid\\W{0,3})[=:]\\s*\\S+");

  static String redact(String text) {
    String[] lines = text.split("\n");
    // 敏感属性的值在别处（如 adb 的 guid、日志）也可能出现，一并替换
    List<String> secrets = new ArrayList<String>();
    for (String line : lines) {
      Matcher k = KEY.matcher(line);
      if (k.find() && line.endsWith("]")) {
        String v = line.substring(k.end(), line.length() - 1);
        if (v.length() >= 8) secrets.add(v);
      }
    }
    StringBuilder sb = new StringBuilder();
    for (String line : lines) {
      Matcher k = KEY.matcher(line);
      if (k.find()) line = line.substring(0, k.end()) + "已去除]";
      for (String s : secrets) line = line.replace(s, "已去除");
      line = MAC.matcher(line).replaceAll("xx:xx:xx:xx:xx:xx");
      line = IP.matcher(line).replaceAll("x.x.x.x");
      line = SSID.matcher(line).replaceAll("$1=已去除");
      sb.append(line).append('\n');
    }
    return sb.toString();
  }

  /** 从仓库读上传地址（官方、镜像依次试），只接受 https。 */
  private String endpoint() throws Exception {
    Throwable last = null;
    for (String cand :
        Net.withMirrors("https://raw.githubusercontent.com/" + Net.GH_REPO + "/" + ENDPOINT_PATH)) {
      HttpURLConnection c = null;
      try {
        c = (HttpURLConnection) new URL(cand).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "MiTV-Beekeeper-Tools");
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        String u = Net.readAll(c.getInputStream()).trim();
        if (!u.startsWith("https://") || u.contains("\n") || u.length() > 200)
          throw new Exception("地址格式不对");
        return u;
      } catch (Throwable t) {
        last = t;
      } finally {
        if (c != null) c.disconnect();
      }
    }
    throw new Exception("无法获取上传地址，建议换网络（例如手机热点）后重试。\n" + last);
  }

  private String upload(String text, String url) throws Exception {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    GZIPOutputStream gz = new GZIPOutputStream(bo);
    gz.write(text.getBytes("UTF-8"));
    gz.close();
    byte[] body = bo.toByteArray();
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL(url).openConnection();
      c.setConnectTimeout(8000);
      c.setReadTimeout(15000);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setFixedLengthStreamingMode(body.length);
      c.setRequestProperty("Content-Type", "application/gzip");
      c.setRequestProperty("User-Agent", "MiTV-Beekeeper-Tools");
      c.getOutputStream().write(body);
      int code = c.getResponseCode();
      if (code == 429) throw new Exception("上传太频繁，请 10 分钟后再试。");
      if (code != 200) throw new Exception("服务器返回 HTTP " + code);
      Matcher m =
          Pattern.compile("\"code\"\\s*:\\s*\"([A-Z0-9]+)\"")
              .matcher(Net.readAll(c.getInputStream()));
      if (!m.find()) throw new Exception("服务器响应异常");
      return m.group(1);
    } catch (IOException e) {
      throw new Exception("网络连接失败，建议换网络（例如手机热点）后重试。\n" + e.getMessage());
    } finally {
      if (c != null) c.disconnect();
    }
  }
}
