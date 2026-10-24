package com.beekeeper.slim;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

import static com.beekeeper.slim.Catalog.*;

/** 通过 su 执行脚本。 */
final class Shell {

    private Shell() { }

    /** 跑 su 脚本并原样返回输出；没有 root 或出错返回 null。 */
    static String suOut(String script) {
        try {
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
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String runSu(String script) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            OutputStream os = p.getOutputStream();
            os.write(script.getBytes("UTF-8"));
            os.write("exit\n".getBytes("UTF-8"));
            os.flush();
            os.close();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.equals("__DONE__")) continue;
                sb.append(line).append("\n");
            }
            int rc = p.waitFor();
            if (rc != 0 && sb.length() == 0) sb.append("su 返回 ").append(rc).append("，可能没有授予 root。\n");
            else sb.append("完成。\n");
        } catch (Exception e) {
            sb.append("无法使用 root：").append(e).append("\n");
        }
        return sb.toString();
    }
}
