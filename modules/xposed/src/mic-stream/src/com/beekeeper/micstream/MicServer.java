package com.beekeeper.micstream;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;
import io.github.libxposed.api.XposedModule;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把电视麦克风通过局域网 TCP 推给电脑端 MiTvMic，运行在系统常驻的 mitv.service 进程里。
 *
 * <p>协议：客户端先发 16 字节口令，通过后服务端回 "TVMC" + 采样率(int) + 声道数(byte) + 位数(byte)，之后是 PCM；口令错回 "TVNO"
 * 后断开。开关关闭或没有客户端连接时不占用麦克风。电脑端在界面里选中电视后发 UDP 配对请求，电视弹窗询问，用户允许后才返回口令。
 */
final class MicServer {

  private static final long ALLOW_MS = 60000;
  private static final long ASK_TIMEOUT_MS = 45000;
  private static final long IDLE_MS = 10 * 60 * 1000;
  private static final long STREAM_CHECK_INTERVAL_MS = 2000;
  private static final long STREAM_TIMEOUT_MS = 5000;
  private static final int RATE = 48000;

  /** 16 个 20 ms 的 PCM 块，Linux 会把设置值翻倍。 */
  private static final int SEND_BUFFER_BYTES = RATE / 50 * 2 * 16;

  private static final int MAX_CONNECTIONS = 8;
  private static final int GAIN_STEP = 180;
  private static final String GAIN_CONTROL = "Mic Gain Step";

  private final XposedModule mod;
  private final Handler h;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final Object statusLock = new Object();

  /** 增益读写要调用外部进程，单独加锁，避免挡住配对和连接状态所用的 this 锁。 */
  private final Object gainLock = new Object();

  private final Object audioLock = new Object();
  private final Set<Socket> connections = new HashSet<>();

  private Context ctx;
  private ContentResolver cr;
  private Pairs pairs;
  private SharedPreferences prefs;
  private volatile boolean running;

  private ServerSocket server;
  private DatagramSocket disco;
  private Socket client;
  private String clientToken;
  private boolean streaming;
  private AtomicLong lastAudioWrite;
  private AudioRecord recording;
  private long generation;
  private int origGain = -1;

  /** 正在弹窗询问的电脑 IP；允许后 60 秒内该 IP 的下一次配对请求拿到口令，拒绝同理。 */
  private String asking, allowed, denied;

  private long allowedUntil, deniedUntil;
  private AlertDialog dialog;

  private final Runnable idleCheck =
      new Runnable() {
        @Override
        public void run() {
          long d = prefs.getLong("deadline", 0);
          boolean busy;
          synchronized (MicServer.this) {
            busy = client != null;
          }
          if (running && d > 0 && System.currentTimeMillis() >= d && !busy) {
            Settings.Global.putInt(cr, Keys.ENABLED, 0);
          } else {
            scheduleIdle();
          }
        }
      };

  private final Runnable streamCheck = this::checkStream;

  private void checkStream() {
    synchronized (this) {
      if (client == null || client.isClosed() || lastAudioWrite == null) return;
      if (SystemClock.elapsedRealtime() - lastAudioWrite.get() < STREAM_TIMEOUT_MS) {
        h.removeCallbacks(streamCheck);
        h.postDelayed(streamCheck, STREAM_CHECK_INTERVAL_MS);
        return;
      }
      // Socket 的读超时不限制写入。关闭连接，唤醒断网后阻塞的写线程。
      close(client);
      streaming = false;
    }
    publish();
  }

  private MicServer(XposedModule mod, Handler h) {
    this.mod = mod;
    this.h = h;
  }

  static void start(XposedModule mod) {
    HandlerThread t = new HandlerThread(HookEntry.TAG);
    t.start();
    final MicServer s = new MicServer(mod, new Handler(t.getLooper()));
    s.h.post(s::begin);
  }

  private void begin() {
    try {
      ctx =
          (Context)
              Class.forName("android.app.ActivityThread")
                  .getMethod("currentApplication")
                  .invoke(null);
      if (ctx == null) {
        h.postDelayed(this::begin, 1000);
        return;
      }
      cr = ctx.getContentResolver();
      pairs = new Pairs(ctx);
      prefs = ctx.getSharedPreferences("beekeeper_mic", Context.MODE_PRIVATE);
      ContentObserver observer =
          new ContentObserver(h) {
            @Override
            public void onChange(boolean selfChange) {
              sync(false);
            }
          };
      cr.registerContentObserver(Settings.Global.getUriFor(Keys.ENABLED), false, observer);
      cr.registerContentObserver(Settings.Global.getUriFor(Keys.UNPAIR), false, observer);
      publish();
      sync(true);
      mod.log(Log.INFO, HookEntry.TAG, "mic service ready");
    } catch (Throwable t) {
      mod.log(Log.ERROR, HookEntry.TAG, "cannot start mic service", t);
    }
  }

  /** 按开关和取消配对请求调整状态；boot 表示进程刚启动，保留上次留下的期限。 */
  private void sync(boolean boot) {
    try {
      String un = Settings.Global.getString(cr, Keys.UNPAIR);
      if (un != null && un.length() > 0) {
        synchronized (this) {
          for (String token : pairs.remove(un)) kick(token);
        }
        Settings.Global.putString(cr, Keys.UNPAIR, "");
        publish();
      }
      boolean on = Settings.Global.getInt(cr, Keys.ENABLED, 0) != 0;
      if (on && !running) {
        if (!boot) prefs.edit().putLong("deadline", System.currentTimeMillis() + IDLE_MS).apply();
        listen();
        publish();
        scheduleIdle();
      } else if (!on && running) {
        h.removeCallbacks(idleCheck);
        prefs.edit().putLong("deadline", 0).apply();
        unlisten();
        Settings.Global.putString(cr, Keys.STATUS, null);
        // 已打开的配对列表也要收到所有电脑已断开的状态。
        publish();
      }
    } catch (Throwable t) {
      mod.log(Log.ERROR, HookEntry.TAG, "sync failed", t);
    }
  }

  private void scheduleIdle() {
    h.removeCallbacks(idleCheck);
    long d = prefs.getLong("deadline", 0);
    if (d > 0) h.postDelayed(idleCheck, Math.max(1000, d - System.currentTimeMillis()));
  }

  private void listen() throws IOException {
    ServerSocket s = null;
    DatagramSocket d = null;
    boolean ok = false;
    try {
      s = new ServerSocket();
      s.setReuseAddress(true);
      s.bind(new InetSocketAddress(Keys.PORT));
      d = new DatagramSocket(null);
      d.setReuseAddress(true);
      d.bind(new InetSocketAddress(Keys.PORT));
      ok = true;
    } finally {
      // 只绑定成功了一半时必须释放，否则残留的 TCP 监听会让之后每次重新开启都绑定失败
      if (!ok) {
        try {
          if (s != null) s.close();
        } catch (IOException e) {
          // 已关闭
        }
        if (d != null) d.close();
      }
    }
    final ServerSocket accepting = s;
    final DatagramSocket discovering = d;
    final long session;
    synchronized (this) {
      server = accepting;
      disco = discovering;
      running = true;
      session = ++generation;
    }
    new Thread(() -> serve(accepting, session), "mic-accept").start();
    new Thread(() -> discover(discovering), "mic-discover").start();
  }

  private void unlisten() {
    synchronized (this) {
      running = false;
      generation++;
      try {
        if (server != null) server.close();
      } catch (IOException e) {
        // 已关闭
      }
      if (disco != null) disco.close();
      server = null;
      disco = null;
      for (Socket s : connections) close(s);
      connections.clear();
      close(client);
      stopRecording(recording);
      client = null;
      clientToken = null;
      streaming = false;
      lastAudioWrite = null;
      h.removeCallbacks(streamCheck);
      asking = allowed = denied = null;
    }
    ui.post(() -> {
      if (dialog != null && dialog.isShowing()) dialog.dismiss();
    });
  }

  private synchronized void kick(String token) {
    if (client != null && token.equals(clientToken)) {
      close(client);
      stopRecording(recording);
    }
  }

  /** 把连接状态（“未连接”或“已连接：电脑名（IP）”）和已配对列表写入 Settings.Global；加锁保证最后写入的是最新状态。 */
  private void publish() {
    synchronized (statusLock) {
      String active;
      synchronized (this) {
        active = streaming && client != null && !client.isClosed() ? clientToken : null;
      }
      StringBuilder sb = new StringBuilder();
      String status = "未连接";
      for (Pairs.Pair p : pairs.list()) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(p.id()).append('\t').append(p.ip).append('\t').append(p.name);
        sb.append('\t').append(p.time).append('\t').append(p.last);
        boolean isActive = p.token.equals(active);
        sb.append('\t').append(isActive ? 1 : 0);
        if (isActive) status = "已连接：" + (p.name.equals(p.ip) ? p.ip : p.name + "（" + p.ip + "）");
      }
      try {
        if (running) Settings.Global.putString(cr, Keys.STATUS, status);
        Settings.Global.putString(cr, Keys.PAIRS, sb.toString());
      } catch (Throwable t) {
        mod.log(Log.ERROR, HookEntry.TAG, "cannot publish status", t);
      }
    }
  }

  /**
   * UDP 9777：电脑端用 "MITVMIC?" 扫描电视（回复 "MITVMIC 电视名称"）；"MITVMIC-PAIR? 电脑名" 请求配对，电视弹窗询问， 电脑轮询到
   * "MITVMIC-TOKEN 口令" 或 "MITVMIC-DENY"，等待期间回 "MITVMIC-WAIT"。
   */
  private void discover(DatagramSocket sock) {
    try {
      byte[] buf = new byte[256];
      String name = Settings.Global.getString(cr, "device_name");
      if (name == null || name.length() == 0) name = Build.MODEL;
      byte[] hello = ("MITVMIC " + name).getBytes("UTF-8");
      while (true) {
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        sock.receive(p);
        String req = new String(p.getData(), 0, p.getLength(), "UTF-8");
        byte[] reply = null;
        if (req.startsWith("MITVMIC-PAIR?")) {
          String ip = ((InetSocketAddress) p.getSocketAddress()).getAddress().getHostAddress();
          String pc = req.substring("MITVMIC-PAIR?".length()).replaceAll("[\\t\\r\\n]", " ").trim();
          if (pc.length() == 0) pc = ip;
          synchronized (this) {
            if (!running || disco != sock) break;
            String r = pairState(ip, pc);
            if (r.equals("TOKEN")) {
              String token = pairs.add(ip, pc);
              if (clientToken != null && !pairs.contains(clientToken)) kick(clientToken);
              reply = ("MITVMIC-TOKEN " + token).getBytes("US-ASCII");
            } else {
              reply = ("MITVMIC-" + r).getBytes("US-ASCII");
            }
          }
          publish();
        } else if (req.startsWith("MITVMIC?")) {
          reply = hello;
        }
        if (reply != null) sock.send(new DatagramPacket(reply, reply.length, p.getSocketAddress()));
      }
    } catch (IOException e) {
      // 服务停止时 socket 被关闭
    }
  }

  /** 允许、拒绝（含超时）的结果各被取走一次；都没有时开始询问。 */
  private String pairState(String ip, String name) {
    final long session;
    synchronized (this) {
      long now = SystemClock.elapsedRealtime();
      // 电脑端不再轮询时结果会残留，超过有效期的结果丢弃，否则下一次配对会直接拿到旧结果
      if (ip.equals(allowed)) {
        allowed = null;
        if (now <= allowedUntil) return "TOKEN";
      }
      if (ip.equals(denied)) {
        denied = null;
        if (now <= deniedUntil) return "DENY";
      }
      if (asking != null) return "WAIT";
      asking = ip;
      session = generation;
    }
    ui.post(() -> showAsk(ip, name, session));
    return "WAIT";
  }

  /** 悬浮窗形式的询问框：不切走当前画面（PC 信号），遥控器确定键即可操作。 */
  private void showAsk(final String ip, String name, long session) {
    synchronized (this) {
      if (!running || generation != session || !ip.equals(asking)) return;
    }
    try {
      final boolean[] done = new boolean[1];
      final AlertDialog d =
          new AlertDialog.Builder(
                  new ContextThemeWrapper(ctx, android.R.style.Theme_Material_Dialog_Alert))
              .setTitle("电脑请求使用电视麦克风")
              .setMessage("电脑「" + name + "」（" + ip + "）想把电视麦克风当作电脑的麦克风使用。\n\n不认识这台电脑请选“拒绝”。")
              .setPositiveButton("允许", null)
              .setNegativeButton("拒绝", null)
              .create();
      d.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
      d.setOnDismissListener(x -> finishAsk(ip, done[0], session));
      d.show();
      dialog = d;
      d.getButton(AlertDialog.BUTTON_POSITIVE)
          .setOnClickListener(v -> {
            done[0] = true;
            d.dismiss();
          });
      d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> d.dismiss());
      ui.postDelayed(
          () -> {
            if (d.isShowing()) d.dismiss();
          },
          ASK_TIMEOUT_MS);
    } catch (Throwable t) {
      mod.log(Log.ERROR, HookEntry.TAG, "cannot show pairing dialog", t);
      finishAsk(ip, false, session);
    }
  }

  private synchronized void finishAsk(String ip, boolean allow, long session) {
    if (!running || generation != session || !ip.equals(asking)) return;
    asking = null;
    long until = SystemClock.elapsedRealtime() + ALLOW_MS;
    if (allow) {
      allowed = ip;
      allowedUntil = until;
      if (ip.equals(denied)) denied = null;
    } else {
      denied = ip;
      deniedUntil = until;
      if (ip.equals(allowed)) allowed = null;
    }
  }

  private void serve(ServerSocket s, long session) {
    try {
      while (true) {
        final Socket c = s.accept();
        synchronized (this) {
          if (!running
              || generation != session
              || server != s
              || connections.size() >= MAX_CONNECTIONS) {
            close(c);
            continue;
          }
          connections.add(c);
        }
        new Thread(() -> stream(c, session), "mic-stream").start();
      }
    } catch (IOException e) {
      // 服务停止时 socket 被关闭
    }
  }

  /** 口令正确返回该口令，否则 null。 */
  private String authenticate(Socket s) throws IOException {
    s.setSoTimeout(3000);
    byte[] got = new byte[Pairs.TOKEN_LEN];
    new DataInputStream(s.getInputStream()).readFully(got);
    s.setSoTimeout(0);
    return pairs.match(got);
  }

  /** 设置观察者尚未处理时，也不能接纳已关闭或正在取消配对的连接。 */
  private boolean permitted(String token) {
    if (token == null || Settings.Global.getInt(cr, Keys.ENABLED, 0) == 0) return false;
    String un = Settings.Global.getString(cr, Keys.UNPAIR);
    return !"*".equals(un) && !Integer.toHexString(token.hashCode()).equals(un);
  }

  private synchronized boolean active(Socket s, long session) {
    return running
        && generation == session
        && client == s
        && !s.isClosed()
        && permitted(clientToken);
  }

  private static void stopRecording(AudioRecord rec) {
    if (rec == null) return;
    try {
      rec.stop();
    } catch (RuntimeException e) {
      // 未启动成功或已经停止
    }
  }

  private void stream(Socket s, long session) {
    AudioRecord rec = null;
    String tk = null;
    try {
      s.setTcpNoDelay(true);
      // 默认的自动调优缓冲可到几 MB，断网后要几十秒写线程才阻塞，所以设上限；上限太小则 ACK 稍慢写线程就被挡住。
      s.setSendBufferSize(SEND_BUFFER_BYTES);
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
        if (!running
            || generation != session
            || s.isClosed()
            || !pairs.contains(tk)
            || !permitted(tk)) return;
        close(client);
        stopRecording(recording);
        client = s;
        clientToken = tk;
        streaming = false;
        lastAudioWrite = null;
        h.removeCallbacks(streamCheck);
        prefs.edit().putLong("deadline", 0).apply();
        pairs.touch(tk);
      }
      publish();
      // 旧录音释放、增益还原后才启动新录音；关闭服务不必等待此锁。
      synchronized (audioLock) {
        try {
          synchronized (this) {
            if (!active(s, session) || !pairs.contains(tk)) return;
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
            if (rec.getState() != AudioRecord.STATE_INITIALIZED || !active(s, session)) return;
            recording = rec;
            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) return;
          }
          if (!active(s, session)) return;
          out.writeBytes("TVMC");
          out.writeInt(RATE);
          out.writeByte(1);
          out.writeByte(16);
          out.flush();
          raiseGain();
          AtomicLong progress = new AtomicLong(SystemClock.elapsedRealtime());
          synchronized (this) {
            if (!active(s, session)) return;
            lastAudioWrite = progress;
            h.postDelayed(streamCheck, STREAM_CHECK_INTERVAL_MS);
          }
          byte[] buf = new byte[RATE / 50 * 2];
          boolean sentAudio = false;
          while (active(s, session)) {
            int n = rec.read(buf, 0, buf.length);
            if (n <= 0 || !active(s, session)) break;
            out.write(buf, 0, n);
            progress.set(SystemClock.elapsedRealtime());
            if (!sentAudio) {
              synchronized (this) {
                if (client != s || s.isClosed()) break;
                streaming = true;
              }
              sentAudio = true;
              publish();
            }
          }
        } finally {
          boolean wasStreaming = false;
          synchronized (this) {
            if (client == s) {
              wasStreaming = streaming;
              streaming = false;
              lastAudioWrite = null;
              h.removeCallbacks(streamCheck);
            }
          }
          if (wasStreaming) publish();
          if (rec != null) {
            synchronized (this) {
              if (recording == rec) recording = null;
              stopRecording(rec);
            }
            try {
              rec.release();
            } finally {
              restoreGain();
            }
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      // 对端断开、口令超时或录音被系统拒绝，直接结束本次推流
    } finally {
      boolean last;
      synchronized (this) {
        connections.remove(s);
        last = client == s;
        if (last) {
          client = null;
          clientToken = null;
        }
      }
      if (last) {
        publish();
        h.post(this::scheduleIdle);
      }
      if (tk != null) pairs.touch(tk);
      close(s);
    }
  }

  /** 声卡编号不固定，按名字找 vocsndcard；找不到返回 null。 */
  private static String findCard() {
    for (int i = 0; i < 8; i++) {
      try (BufferedReader r = new BufferedReader(new FileReader("/proc/asound/card" + i + "/id"))) {
        if ("vocsndcard".equals(r.readLine())) return String.valueOf(i);
      } catch (IOException e) {
        // 没有这张卡，看下一个
      }
    }
    return null;
  }

  /** value 为 null 时读取增益，返回 tinymix 输出的第一行；否则设置增益。失败返回 null。 */
  private static String gain(String card, String value) {
    Process p = null;
    try {
      p =
          new ProcessBuilder(
                  value == null
                      ? new String[] {"tinymix", "-D", card, GAIN_CONTROL}
                      : new String[] {"tinymix", "-D", card, GAIN_CONTROL, value})
              .redirectErrorStream(true)
              .start();
      p.getOutputStream().close();
      // 输出只有几行，先等进程结束再读，tinymix 卡住时靠超时放弃
      if (!p.waitFor(3, TimeUnit.SECONDS)) return null;
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
        return r.readLine();
      }
    } catch (IOException | InterruptedException e) {
      return null;
    } finally {
      if (p != null) p.destroyForcibly();
    }
  }

  /** 麦克风模拟增益默认在 0 档，录到的声音很小；推流期间调到 GAIN_STEP（0~480），结束后还原。必须在开始录音之后设置， 录音打开时系统会把它重置回 0。 */
  private void raiseGain() {
    synchronized (gainLock) {
      String card = findCard();
      if (card == null) return;
      String cur = gain(card, null);
      gain(card, String.valueOf(GAIN_STEP));
      if (origGain >= 0 || cur == null) return;
      int i = cur.indexOf(": ");
      if (i < 0) return;
      try {
        origGain = Integer.parseInt(cur.substring(i + 2).trim().split("\\s")[0]);
      } catch (NumberFormatException e) {
        // 读不到原值就不还原
      }
    }
  }

  private void restoreGain() {
    synchronized (gainLock) {
      String card = origGain < 0 ? null : findCard();
      if (card != null) gain(card, String.valueOf(origGain));
      origGain = -1;
    }
  }

  private static void close(Socket s) {
    if (s == null) return;
    try {
      s.close();
    } catch (IOException e) {
      // 已关闭
    }
  }
}
