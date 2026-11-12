package com.beekeeper.slim;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.WindowManager;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 把电视麦克风通过局域网 TCP 推给电脑端 MiTvMic。
 *
 * <p>协议：客户端先发 16 字节口令，通过后服务端回 "TVMC" + 采样率(int) + 声道数(byte) + 位数(byte)，之后是 PCM；口令错回 "TVNO" 后断开。
 * 没有客户端连接时不打开麦克风。电脑端在界面里选中电视后发 UDP 配对请求，电视弹窗询问，用户允许后才返回口令。
 */
public class MicService extends Service {

  static final int PORT = 9777;
  private static final long ALLOW_MS = 60000;
  private static final long ASK_TIMEOUT_MS = 45000;
  private static final int RATE = 48000;
  private static final int TOKEN_LEN = 16;
  private static final String CHANNEL = "mic";
  private static final String PREFS = "mic";
  private static final int GAIN_STEP = 180;
  private static final String FIND_CARD =
      "c=$(grep -l vocsndcard /proc/asound/card*/id | head -1 | sed"
          + " 's/.*card\\([0-9]*\\).*/\\1/')\n";

  private final Handler ui = new Handler(Looper.getMainLooper());

  /** 正在弹窗询问的电脑 IP；允许后 60 秒内该 IP 的下一次配对请求拿到口令。 */
  private String asking, allowed, denied;

  private long allowedUntil;
  private AlertDialog dialog;

  private ServerSocket server;
  private DatagramSocket disco;
  private Socket client;
  private String clientToken;
  private int origGain = -1;
  private static volatile MicService live;

  static boolean enabled(Context c) {
    return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("enabled", false);
  }

  static void setEnabled(Context c, boolean on) {
    c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", on).apply();
  }

  /** 一台已配对的电脑；每台电脑有自己的口令，可以单独取消。 */
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
  }

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences(PREFS, MODE_PRIVATE);
  }

  /** 每行一台：口令、IP、名称、配对时间、上次连接时间（0 = 没连过），用制表符分隔。 */
  private static List<Pair> readPairs(SharedPreferences p) {
    List<Pair> out = new ArrayList<Pair>();
    for (String line : p.getString("pairs", "").split("\n")) {
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

  private static void writePairs(SharedPreferences p, List<Pair> list) {
    StringBuilder sb = new StringBuilder();
    for (Pair x : list) {
      if (sb.length() > 0) sb.append('\n');
      sb.append(x.token).append('\t').append(x.ip).append('\t').append(x.name);
      sb.append('\t').append(x.time).append('\t').append(x.last);
    }
    p.edit().putString("pairs", sb.toString()).apply();
  }

  static synchronized List<Pair> pairs(Context c) {
    return readPairs(prefs(c));
  }

  /** 为这台电脑生成新口令；同一 IP 或同名的旧记录视为同一台电脑，被替换。 */
  private static synchronized String addPair(Context c, String ip, String name) {
    SharedPreferences p = prefs(c);
    List<Pair> list = readPairs(p);
    for (Iterator<Pair> it = list.iterator(); it.hasNext(); ) {
      Pair x = it.next();
      if (x.ip.equals(ip) || x.name.equals(name)) it.remove();
    }
    byte[] b = new byte[TOKEN_LEN / 2];
    new SecureRandom().nextBytes(b);
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
    list.add(new Pair(sb.toString(), ip, name, System.currentTimeMillis(), 0));
    writePairs(p, list);
    return sb.toString();
  }

  /** 记下这台电脑最近一次连接的时间；已被取消配对的口令忽略。 */
  private static synchronized void touch(Context c, String token) {
    SharedPreferences p = prefs(c);
    List<Pair> list = readPairs(p);
    for (int i = 0; i < list.size(); i++) {
      Pair x = list.get(i);
      if (x.token.equals(token)) {
        list.set(i, new Pair(x.token, x.ip, x.name, x.time, System.currentTimeMillis()));
        writePairs(p, list);
        return;
      }
    }
  }

  /** 取消一台电脑的配对，已建立的连接同时断开。 */
  static synchronized void removePair(Context c, String token) {
    SharedPreferences p = prefs(c);
    List<Pair> list = readPairs(p);
    for (Iterator<Pair> it = list.iterator(); it.hasNext(); ) {
      if (it.next().token.equals(token)) it.remove();
    }
    writePairs(p, list);
    kick(token);
  }

  static synchronized void clearPairs(Context c) {
    writePairs(prefs(c), new ArrayList<Pair>());
    kick(null);
  }

  /** 返回与客户端口令匹配的已配对口令，没有匹配返回 null。 */
  private static synchronized String matchToken(Context c, byte[] got) {
    String hit = null;
    for (Pair x : readPairs(prefs(c))) {
      if (MessageDigest.isEqual(got, x.token.getBytes(StandardCharsets.US_ASCII))) hit = x.token;
    }
    return hit;
  }

  /** 正在推流的那台电脑的口令，没有连接返回 null。 */
  static String activeToken() {
    MicService m = live;
    if (m == null) return null;
    synchronized (m) {
      return m.clientToken;
    }
  }

  private static void kick(String token) {
    MicService m = live;
    if (m != null) m.kickLocal(token);
  }

  private synchronized void kickLocal(String token) {
    if (client != null && (token == null || token.equals(clientToken))) close(client);
  }

  static void start(Context c) {
    c.startForegroundService(new Intent(c, MicService.class));
  }

  static void stop(Context c) {
    c.stopService(new Intent(c, MicService.class));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    live = this;
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    nm.createNotificationChannel(
        new NotificationChannel(CHANNEL, "麦克风推流", NotificationManager.IMPORTANCE_LOW));
    startForeground(
        1,
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("麦克风推流已开启")
            .build());
    if (server == null) {
      new Thread(
              new Runnable() {
                public void run() {
                  serve();
                }
              },
              "mic-accept")
          .start();
      new Thread(
              new Runnable() {
                public void run() {
                  discover();
                }
              },
              "mic-discover")
          .start();
    }
    return START_STICKY;
  }

  /**
   * UDP 9777：电脑端用 "MITVMIC?" 扫描电视（回复 "MITVMIC 电视名称"）；"MITVMIC-PAIR? 电脑名" 请求配对，电视弹窗询问， 电脑轮询到
   * "MITVMIC-TOKEN 口令" 或 "MITVMIC-DENY"，等待期间回 "MITVMIC-WAIT"。
   */
  private void discover() {
    try {
      disco = new DatagramSocket(PORT);
      byte[] buf = new byte[256];
      String name = Settings.Global.getString(getContentResolver(), "device_name");
      if (name == null || name.length() == 0) name = Build.MODEL;
      byte[] hello = ("MITVMIC " + name).getBytes("UTF-8");
      while (true) {
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        disco.receive(p);
        String req = new String(p.getData(), 0, p.getLength(), "UTF-8");
        byte[] reply = null;
        if (req.startsWith("MITVMIC-PAIR?")) {
          String ip = ((InetSocketAddress) p.getSocketAddress()).getAddress().getHostAddress();
          String pc = req.substring("MITVMIC-PAIR?".length()).replaceAll("[\\t\\r\\n]", " ").trim();
          if (pc.length() == 0) pc = ip;
          String r = pairState(ip, pc);
          reply =
              (r.equals("TOKEN") ? "MITVMIC-TOKEN " + addPair(this, ip, pc) : "MITVMIC-" + r)
                  .getBytes("US-ASCII");
        } else if (req.startsWith("MITVMIC?")) {
          reply = hello;
        }
        if (reply != null) {
          disco.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
        }
      }
    } catch (IOException e) {
      // 服务停止时 socket 被关闭
    }
  }

  /** 允许、拒绝（含超时）的结果各被取走一次；都没有时开始询问。 */
  private String pairState(String ip, String name) {
    synchronized (this) {
      if (ip.equals(allowed) && SystemClock.elapsedRealtime() <= allowedUntil) {
        allowed = null;
        return "TOKEN";
      }
      if (ip.equals(denied)) {
        denied = null;
        return "DENY";
      }
    }
    ask(ip, name);
    return "WAIT";
  }

  private void ask(final String ip, final String name) {
    synchronized (this) {
      if (asking != null) return;
      asking = ip;
    }
    ui.post(
        new Runnable() {
          public void run() {
            showAsk(ip, name);
          }
        });
  }

  /** 悬浮窗形式的询问框：不切走当前画面（PC 信号），遥控器确定键即可操作。 */
  private void showAsk(final String ip, String name) {
    final boolean[] done = new boolean[1];
    final AlertDialog d =
        new AlertDialog.Builder(
                new ContextThemeWrapper(this, android.R.style.Theme_Material_Dialog_Alert))
            .setTitle("电脑请求使用电视麦克风")
            .setMessage("电脑「" + name + "」（" + ip + "）想把电视麦克风当作电脑的麦克风使用。\n\n不认识这台电脑请选“拒绝”。")
            .setPositiveButton("允许", null)
            .setNegativeButton("拒绝", null)
            .create();
    d.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
    d.setOnDismissListener(
        new DialogInterface.OnDismissListener() {
          public void onDismiss(DialogInterface x) {
            finishAsk(ip, done[0]);
          }
        });
    d.show();
    dialog = d;
    d.getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            new View.OnClickListener() {
              public void onClick(View v) {
                done[0] = true;
                d.dismiss();
              }
            });
    d.getButton(AlertDialog.BUTTON_NEGATIVE)
        .setOnClickListener(
            new View.OnClickListener() {
              public void onClick(View v) {
                d.dismiss();
              }
            });
    ui.postDelayed(
        new Runnable() {
          public void run() {
            if (d.isShowing()) d.dismiss();
          }
        },
        ASK_TIMEOUT_MS);
  }

  private synchronized void finishAsk(String ip, boolean allow) {
    asking = null;
    if (allow) {
      allowed = ip;
      allowedUntil = SystemClock.elapsedRealtime() + ALLOW_MS;
    } else {
      denied = ip;
    }
  }

  private void serve() {
    try {
      server = new ServerSocket(PORT);
      while (true) {
        final Socket s = server.accept();
        new Thread(
                new Runnable() {
                  public void run() {
                    stream(s);
                  }
                },
                "mic-stream")
            .start();
      }
    } catch (IOException e) {
      server = null;
    }
  }

  /** 口令正确返回该口令，否则 null。 */
  private String authenticate(Socket s) throws IOException {
    s.setSoTimeout(3000);
    byte[] got = new byte[TOKEN_LEN];
    new DataInputStream(s.getInputStream()).readFully(got);
    s.setSoTimeout(0);
    return matchToken(this, got);
  }

  private void stream(Socket s) {
    AudioRecord rec = null;
    String tk = null;
    try {
      s.setTcpNoDelay(true);
      DataOutputStream out = new DataOutputStream(s.getOutputStream());
      tk = authenticate(s);
      if (tk == null) {
        out.writeBytes("TVNO");
        out.write(new byte[6]);
        out.flush();
        return;
      }
      // 通过口令校验后才顶掉旧连接，避免陌生设备连上来就把正常使用中的电脑踢掉
      synchronized (this) {
        close(client);
        client = s;
        clientToken = tk;
      }
      touch(this, tk);
      raiseGain();
      int min =
          AudioRecord.getMinBufferSize(
              RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      rec =
          new AudioRecord(
              MediaRecorder.AudioSource.MIC,
              RATE,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              Math.max(min, RATE / 5 * 2));
      if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
      out.writeBytes("TVMC");
      out.writeInt(RATE);
      out.writeByte(1);
      out.writeByte(16);
      out.flush();
      rec.startRecording();
      byte[] buf = new byte[RATE / 50 * 2];
      while (true) {
        int n = rec.read(buf, 0, buf.length);
        if (n <= 0) break;
        out.write(buf, 0, n);
      }
    } catch (IOException | RuntimeException e) {
      // 对端断开、口令超时或录音被系统拒绝，直接结束本次推流
    } finally {
      if (rec != null) {
        try {
          rec.stop();
        } catch (RuntimeException e) {
          // 未启动成功时 stop 会抛异常
        }
        rec.release();
      }
      boolean last;
      synchronized (this) {
        last = client == s;
        if (last) {
          client = null;
          clientToken = null;
        }
      }
      if (last) restoreGain();
      if (tk != null) touch(this, tk);
      close(s);
    }
  }

  /** 麦克风模拟增益默认在 0 档，录到的声音很小；推流期间调到 GAIN_STEP（0~480），结束后还原。 声卡编号不固定，按名字找 vocsndcard。 */
  private synchronized void raiseGain() {
    String out =
        Shell.suOut(
            FIND_CARD
                + "[ -n \"$c\" ] && tinymix -D $c 'Mic Gain Step' 2>/dev/null | head -1\n"
                + "[ -n \"$c\" ] && tinymix -D $c 'Mic Gain Step' "
                + GAIN_STEP
                + " >/dev/null 2>&1\n");
    if (origGain >= 0 || out == null) return;
    int i = out.indexOf(": ");
    if (i < 0) return;
    try {
      origGain = Integer.parseInt(out.substring(i + 2).trim().split("\\s")[0]);
    } catch (NumberFormatException e) {
      // 读不到原值就不还原
    }
  }

  private synchronized void restoreGain() {
    if (origGain < 0) return;
    Shell.suOut(FIND_CARD + "[ -n \"$c\" ] && tinymix -D $c 'Mic Gain Step' " + origGain + "\n");
    origGain = -1;
  }

  private static void close(Socket s) {
    if (s == null) return;
    try {
      s.close();
    } catch (IOException e) {
      // 已关闭
    }
  }

  @Override
  public void onDestroy() {
    if (live == this) live = null;
    try {
      if (server != null) server.close();
    } catch (IOException e) {
      // 已关闭
    }
    if (disco != null) disco.close();
    if (dialog != null && dialog.isShowing()) dialog.dismiss();
    synchronized (this) {
      close(client);
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
