package com.beekeeper.micstream;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** 已配对的电脑。每台电脑有自己的口令，口令只保存在服务进程的私有目录里。 */
final class Pairs {

  static final int TOKEN_LEN = 16;

  static final class Pair {
    final String token, ip, name;
    final long time, last;

    Pair(String token, String ip, String name, long time, long last) {
      this.token = token;
      this.ip = ip;
      this.name = name;
      this.time = time;
      this.last = last;
    }

    /** 对外使用的编号（32 位哈希），不能反推口令。 */
    String id() {
      return Integer.toHexString(token.hashCode());
    }
  }

  private final SharedPreferences prefs;

  Pairs(Context c) {
    prefs = c.getSharedPreferences("beekeeper_mic", Context.MODE_PRIVATE);
  }

  /** 每行一台：口令、IP、名称、配对时间、上次连接时间（0 = 没连过），用制表符分隔。 */
  synchronized List<Pair> list() {
    List<Pair> out = new ArrayList<>();
    for (String line : prefs.getString("pairs", "").split("\n")) {
      String[] f = line.split("\t", -1);
      if (f.length != 5 || f[0].length() != TOKEN_LEN) continue;
      try {
        out.add(new Pair(f[0], f[1], f[2], Long.parseLong(f[3]), Long.parseLong(f[4])));
      } catch (NumberFormatException e) {
        // 记录损坏，丢弃
      }
    }
    return out;
  }

  private void save(List<Pair> list) {
    StringBuilder sb = new StringBuilder();
    for (Pair x : list) {
      if (sb.length() > 0) sb.append('\n');
      sb.append(x.token).append('\t').append(x.ip).append('\t').append(x.name);
      sb.append('\t').append(x.time).append('\t').append(x.last);
    }
    prefs.edit().putString("pairs", sb.toString()).apply();
  }

  /** 为这台电脑生成新口令；同一 IP 或同名的旧记录视为同一台电脑，被替换。 */
  synchronized String add(String ip, String name) {
    List<Pair> list = list();
    for (Iterator<Pair> it = list.iterator(); it.hasNext(); ) {
      Pair x = it.next();
      if (x.ip.equals(ip) || x.name.equals(name)) it.remove();
    }
    byte[] b = new byte[TOKEN_LEN / 2];
    new SecureRandom().nextBytes(b);
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
    list.add(new Pair(sb.toString(), ip, name, System.currentTimeMillis(), 0));
    save(list);
    return sb.toString();
  }

  /** 记下这台电脑最近一次连接的时间；已被取消配对的口令忽略。 */
  synchronized void touch(String token) {
    List<Pair> list = list();
    for (int i = 0; i < list.size(); i++) {
      Pair x = list.get(i);
      if (x.token.equals(token)) {
        list.set(i, new Pair(x.token, x.ip, x.name, x.time, System.currentTimeMillis()));
        save(list);
        return;
      }
    }
  }

  /** 按编号取消配对，"*" 表示全部；返回被取消的口令。 */
  synchronized List<String> remove(String id) {
    List<Pair> list = list();
    List<String> gone = new ArrayList<>();
    for (Iterator<Pair> it = list.iterator(); it.hasNext(); ) {
      Pair x = it.next();
      if (id.equals("*") || x.id().equals(id)) {
        gone.add(x.token);
        it.remove();
      }
    }
    save(list);
    return gone;
  }

  /** 返回与客户端口令匹配的已配对口令，没有匹配返回 null。 */
  synchronized String match(byte[] got) {
    String hit = null;
    for (Pair x : list()) {
      if (MessageDigest.isEqual(got, x.token.getBytes(StandardCharsets.US_ASCII))) hit = x.token;
    }
    return hit;
  }

  synchronized Pair find(String token) {
    for (Pair x : list()) {
      if (x.token.equals(token)) return x;
    }
    return null;
  }
}
