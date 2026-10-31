package com.beekeeper.decorativelight;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/** 通过模块自带的 root 小程序把帧写进 MCU（I2C），程序每写完一帧回一个 0。 */
final class Om2Output implements Closeable {
  static final String MARKER = "/data/adb/beekeeper-rgb/om2-pixel-firmware.sha256";
  private final File bin;
  private Process process;
  private OutputStream input;
  private InputStream result;
  private boolean pixels;

  Om2Output(File module) {
    bin = new File(module, "bin");
  }

  /** 标记文件由刷写流程写入，内容是自定义固件的 SHA-256。 */
  static boolean customFirmware() {
    try {
      return McuPolicy.CUSTOM.equals(
          new String(Files.readAllBytes(new File(MARKER).toPath()), StandardCharsets.US_ASCII)
              .trim());
    } catch (IOException e) {
      return false;
    }
  }

  /** 校验输出程序并返回它；接管驱动前先调用，尽早失败。 */
  File check(boolean pixels) throws IOException {
    if (pixels && !customFirmware()) throw new IOException("需要已验证的 OM2 逐灯固件");
    File writer = new File(bin, pixels ? "om2-pixel-writer" : "om2-single-writer");
    if (!writer.canExecute()) throw new IOException("缺少灯控输出程序");
    return writer;
  }

  void open(boolean pixels) throws IOException {
    File writer = check(pixels);
    this.pixels = pixels;
    process = new ProcessBuilder(writer.getPath()).start();
    input = process.getOutputStream();
    result = process.getInputStream();
  }

  void send(byte[] rgb) throws Exception {
    if (process == null) throw new IOException("OM2 输出尚未接管");
    input.write(pixels ? Om2Frame.encode(rgb) : SingleFrame.encode(rgb));
    input.flush();
    long deadline = System.nanoTime() + 1_000_000_000L;
    while (result.available() == 0) {
      if (!process.isAlive()) throw new IOException("OM2 输出进程退出：" + process.exitValue());
      if (System.nanoTime() >= deadline) throw new IOException("OM2 I2C 写入超时");
      Thread.sleep(2);
    }
    if (result.read() != 0) throw new IOException("OM2 输出响应异常");
  }

  @Override
  public void close() {
    if (process == null) return;
    try {
      input.close();
    } catch (IOException ignored) {
    }
    try {
      if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly();
    } catch (InterruptedException e) {
      process.destroy();
    }
    try {
      result.close();
    } catch (IOException ignored) {
    }
    process = null;
    input = null;
    result = null;
  }
}
