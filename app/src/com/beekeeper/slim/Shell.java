package com.beekeeper.slim;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/** 通过 su 执行脚本。 */
final class Shell {

  private Shell() {}

  /** 跑脚本，返回逐行读到的输出；退出码写入 rc[0]。 */
  private static String exec(String script, int[] rc) throws Exception {
    Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
    OutputStream os = p.getOutputStream();
    os.write(script.getBytes("UTF-8"));
    os.write("exit\n".getBytes("UTF-8"));
    os.flush();
    os.close();
    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = r.readLine()) != null) sb.append(line).append("\n");
    rc[0] = p.waitFor();
    return sb.toString();
  }

  /** 跑 su 脚本并原样返回输出；没有 root 或出错（退出码非 0 且没有任何输出）返回 null。 */
  static String suOut(String script) {
    try {
      int[] rc = new int[1];
      String out = exec(script, rc);
      return rc[0] != 0 && out.length() == 0 ? null : out;
    } catch (Exception e) {
      return null;
    }
  }

  static String runSu(String script) {
    try {
      int[] rc = new int[1];
      String out = exec(script, rc);
      if (rc[0] != 0 && out.length() == 0) return "su 返回 " + rc[0] + "，可能没有授予 root。\n";
      return out + "完成。\n";
    } catch (Exception e) {
      return "无法使用 root：" + e + "\n";
    }
  }

  /** runSu 的输出是否表示没有 root。 */
  static boolean noRoot(String out) {
    return out.contains("无法使用 root") || out.contains("可能没有授予 root");
  }
}
