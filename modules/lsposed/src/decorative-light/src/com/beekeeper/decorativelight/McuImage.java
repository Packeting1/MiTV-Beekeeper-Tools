package com.beekeeper.decorativelight;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * MCU 应用镜像都取自电视自带的厂商 MCU.bin：刷回原版直接用它，24 灯珠固件在它上面套用 firmware/pixels.patch 生成。补丁文件只含新增的代码，每行“十六进制偏移
 * 十六进制数据”。
 */
public final class McuImage {
  /** 校验厂商文件后原样返回。 */
  public static byte[] original(byte[] vendor) {
    if (!McuPolicy.ORIGINAL.equals(sha256(vendor)))
      throw new IllegalArgumentException("厂商 MCU 镜像 SHA-256 不匹配");
    McuProtocol.validate(vendor);
    return vendor;
  }

  /** 套用补丁并重算校验和，结果必须与审查过的自定义镜像完全一致。 */
  public static byte[] custom(byte[] vendor, String patch) {
    byte[] raw = original(vendor).clone();
    for (String line : patch.split("\n")) {
      if (line.trim().isEmpty()) continue;
      String[] part = line.trim().split(" ");
      if (part.length != 2) throw new IllegalArgumentException("MCU 补丁格式错误");
      int offset = Integer.parseInt(part[0], 16);
      byte[] data = hex(part[1]);
      if (offset < 32 || offset + data.length > raw.length - 1)
        throw new IllegalArgumentException("MCU 补丁格式错误");
      System.arraycopy(data, 0, raw, offset, data.length);
    }
    int crc = 255;
    for (int i = 36; i < raw.length - 1; i++) crc ^= raw[i] & 255;
    raw[raw.length - 1] = (byte) crc;
    raw[28] = (byte) crc;
    if (!McuPolicy.CUSTOM.equals(sha256(raw)))
      throw new IllegalArgumentException("自定义 MCU 镜像 SHA-256 不匹配");
    McuProtocol.validate(raw);
    return raw;
  }

  public static String sha256(byte[] bytes) {
    try {
      StringBuilder s = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
        s.append(String.format(Locale.ROOT, "%02x", b & 255));
      return s.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] hex(String text) {
    if (text.length() % 2 != 0) throw new IllegalArgumentException("MCU 补丁格式错误");
    byte[] out = new byte[text.length() / 2];
    for (int i = 0; i < out.length; i++)
      out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
    return out;
  }

  private McuImage() {}
}
