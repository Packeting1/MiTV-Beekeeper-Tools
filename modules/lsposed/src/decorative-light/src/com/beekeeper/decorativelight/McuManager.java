package com.beekeeper.decorativelight;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MCU 刷写流程，只在 Magisk 的 root 进程里运行，和灯带输出串行执行，网络数据不能触发刷写。 流程：设置页发 prepare（校验并生成一次性授权）→ 等满 10 秒风险提示 →
 * 设置页发 start → 刷写。 刷写前写恢复记录，成功才删除；记录存在时禁止再次刷写，也不输出网络灯光。
 */
final class McuManager {
  interface Host {
    String savedEnable() throws Exception;

    void pause() throws Exception;

    void restore(String enable) throws Exception;
  }

  private static final String PREFIX = "beekeeper_mcu_";
  private static final String VENDOR_MCU =
      "/vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/MCU.bin";
  private static final String VENDOR_LIB = "/vendor/lib64/libbspmserver_config.so";
  private static final String[] TRANSPORTS = {"om2-flash-transport", "om2-flash-read"};
  private final File module;
  private final File base = new File("/data/adb/beekeeper-rgb");
  private final File journal = new File(base, "mcu-flash-journal.properties");
  private final Host host;
  private String token = "";
  private String action = "";
  private long armed;

  McuManager(File module, Host host) throws Exception {
    this.module = module;
    this.host = host;
    base.mkdirs();
    setting("request", "");
    setting("ticket", "");
    if (journal.exists()) NetworkDaemon.putSetting("beekeeper_net_enabled", "0");
    setting("status", journal.exists() ? "发现未完成刷写记录，禁止再次刷写；请检查日志并人工恢复" : "尚未刷写");
  }

  boolean recoveryRequired() {
    return journal.exists();
  }

  /** 请求格式：prepare:动作:UUID、cancel:令牌、start:令牌。 */
  void poll(String request) throws Exception {
    setting("request", "");
    try {
      String[] p = request.split(":");
      if (p.length == 3
          && p[0].equals("prepare")
          && (p[1].equals("install") || p[1].equals("restore"))
          && p[2].matches("[a-fA-F0-9-]{36}")) {
        reset();
        preflight();
        action = p[1];
        token = UUID.randomUUID().toString();
        armed = SystemClock.elapsedRealtime();
        setting("ticket", p[2] + ":" + token + ":" + armed);
        setting("status", "版本与镜像校验通过，等待 10 秒风险提示");
      } else if (p.length == 2 && p[0].equals("cancel") && p[1].equals(token)) {
        reset();
      } else if (p.length == 2 && p[0].equals("start") && !token.isEmpty() && p[1].equals(token)) {
        if (!McuPolicy.ready(armed, SystemClock.elapsedRealtime()))
          throw new IOException("必须等待完整 10 秒，授权 5 分钟后失效");
        String selected = action;
        reset();
        preflight();
        flash(selected);
      } else {
        throw new IOException("无效或过期的刷写请求，请重新打开页面");
      }
    } catch (Exception e) {
      reset();
      setting("status", "刷写未完成：" + e.getMessage());
      System.err.println("MCU: " + e);
    }
  }

  private void reset() throws Exception {
    token = "";
    armed = 0;
    setting("ticket", "");
  }

  private void preflight() throws Exception {
    McuPolicy.check(
        prop("ro.product.device"),
        prop("ro.boot.product_id"),
        prop("ro.build.version.incremental"),
        prop("ro.build.version.sdk"),
        prop("ro.build.fingerprint"),
        McuImage.sha256(Files.readAllBytes(new File(VENDOR_MCU).toPath())),
        McuImage.sha256(Files.readAllBytes(new File(VENDOR_LIB).toPath())));
    image(true);
    image(false);
    for (String name : TRANSPORTS) {
      File f = new File(module, "bin/" + name);
      String expected =
          new String(
                  Files.readAllBytes(new File(module, "firmware/" + name + ".sha256").toPath()),
                  StandardCharsets.US_ASCII)
              .trim();
      if (!f.canExecute() || !expected.equals(McuImage.sha256(Files.readAllBytes(f.toPath()))))
        throw new IOException("刷写传输程序校验失败");
    }
    if (journal.exists()) throw new IOException("存在未完成的刷写记录，请人工检查 /data/adb/beekeeper-rgb");
  }

  /** install 为 24 灯珠固件，否则为刷回的原版；二者都由电视自带的厂商 MCU.bin 得到。 */
  private byte[] image(boolean install) throws Exception {
    byte[] vendor = Files.readAllBytes(new File(VENDOR_MCU).toPath());
    if (!install) return McuImage.original(vendor);
    return McuImage.custom(
        vendor,
        new String(
            Files.readAllBytes(new File(module, "firmware/pixels.patch").toPath()),
            StandardCharsets.US_ASCII));
  }

  private void flash(String selected) throws Exception {
    boolean install = selected.equals("install");
    byte[] raw = image(install);
    String network = NetworkDaemon.readSettings().get("beekeeper_net_enabled");
    String enable = host.savedEnable();
    Properties record = new Properties();
    record.setProperty("action", selected);
    record.setProperty("network", String.valueOf(network));
    record.setProperty("driver", enable);
    record.setProperty("image", McuImage.sha256(raw));
    record.setProperty("phase", "prepared");
    save(record);
    boolean success = false;
    try {
      host.pause();
      setting("status", "正在读取实时 MCU 版本");
      McuProtocol.version(transport(McuProtocol.command(2)));
      record.setProperty("phase", "staging");
      save(record);
      McuProtocol.stage(
          raw,
          new McuProtocol.Link() {
            @Override
            public byte[] send(byte[] packet) throws Exception {
              return transport(packet);
            }

            @Override
            public void progress(int blocks) throws Exception {
              record.setProperty("blocks", String.valueOf(blocks));
              save(record);
              setting("status", "暂存 MCU 镜像 " + blocks + " / 61，请勿断电");
            }
          });
      record.setProperty("phase", "commit-requested");
      save(record);
      Files.deleteIfExists(new File(Om2Output.MARKER).toPath());
      setting("status", "正在提交 MCU 应用区，请勿断电或重启");
      byte[] commit = transport(McuProtocol.command(4));
      // 提交后 MCU 可能直接重启，响应不一定是确认包，只在确实是确认包时才校验。
      if (commit[2] == 2 && commit[3] == 4) McuProtocol.ack(commit, 4);
      Thread.sleep(5000);
      McuProtocol.version(transport(McuProtocol.command(2)));
      if (install) {
        File temp = new File(base, "pixel-marker.tmp");
        Files.write(temp.toPath(), (McuPolicy.CUSTOM + "\n").getBytes(StandardCharsets.US_ASCII));
        Files.move(
            temp.toPath(),
            new File(Om2Output.MARKER).toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      }
      record.setProperty("phase", "version-verified");
      save(record);
      success = true;
    } finally {
      try {
        host.restore(enable);
      } catch (Exception e) {
        success = false;
        throw e;
      } finally {
        NetworkDaemon.putSetting(
            "beekeeper_net_capacity",
            String.valueOf(Om2Output.customFirmware() ? Om2Frame.PIXELS : 1));
        NetworkDaemon.putSetting(
            "beekeeper_net_enabled", success && "1".equals(network) ? "1" : "0");
        if (success) {
          Files.move(
              journal.toPath(),
              new File(base, "mcu-flash-last.properties").toPath(),
              StandardCopyOption.REPLACE_EXISTING);
          setting("status", install ? "刷写完成，已恢复通信；24 灯珠模式，请检查画面和灯光" : "原版刷写完成，已恢复通信；单灯模式，请检查画面和灯光");
        }
      }
    }
  }

  private void save(Properties record) throws Exception {
    File temp = new File(base, "mcu-flash-journal.tmp");
    try (FileOutputStream out = new FileOutputStream(temp)) {
      record.store(out, "MCU flash recovery record");
      out.getFD().sync();
    }
    Files.move(
        temp.toPath(),
        journal.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
  }

  /** 发一个包并读回 32 字节响应；写命令只发一次，响应超时也不重发。 */
  private byte[] transport(byte[] packet) throws Exception {
    if (packet.length < 1 || packet.length > 544) throw new IOException("MCU 数据包越界");
    Files.write(new File(base, "mcu-packet.bin").toPath(), packet);
    int cmd = packet[0] & 255;
    log("write command=" + cmd + " bytes=" + packet.length);
    exec(new String[] {new File(module, "bin/om2-flash-transport").getPath(), "write"}, 5000);
    Thread.sleep(cmd == 1 || cmd == 4 || cmd == 5 ? 3000 : 100);
    for (int i = 0; i < (cmd == 4 ? 120 : 10); i++) {
      try {
        byte[] response =
            exec(new String[] {new File(module, "bin/om2-flash-read").getPath()}, 3000);
        if (response.length == 32) {
          log("response command=" + cmd + " bytes=" + Arrays.toString(response));
          return response;
        }
      } catch (IOException e) {
        System.err.println("MCU read: " + e.getMessage());
      }
      Thread.sleep(300);
    }
    throw new IOException("MCU 响应超时；未重发写入命令，请检查恢复记录");
  }

  private byte[] exec(String[] args, long timeout) throws Exception {
    Process child = new ProcessBuilder(args).start();
    if (!child.waitFor(timeout, TimeUnit.MILLISECONDS)) {
      child.destroyForcibly();
      throw new IOException("程序超时：" + new File(args[0]).getName());
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    try (InputStream in = child.getInputStream()) {
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }
    if (child.exitValue() != 0) throw new IOException("传输程序退出：" + child.exitValue());
    return out.toByteArray();
  }

  private String prop(String name) throws Exception {
    return new String(
            exec(new String[] {"/system/bin/getprop", name}, 3000), StandardCharsets.UTF_8)
        .trim();
  }

  private void setting(String key, String value) throws Exception {
    NetworkDaemon.putSetting(PREFIX + key, value);
  }

  private void log(String event) throws IOException {
    try (FileOutputStream out = new FileOutputStream(new File(base, "mcu-flash.log"), true)) {
      out.write((System.currentTimeMillis() + " " + event + "\n").getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    }
  }
}
