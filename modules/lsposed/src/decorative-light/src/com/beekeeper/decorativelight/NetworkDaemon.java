package com.beekeeper.decorativelight;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 由 Magisk 的 service.sh 以 root 身份通过 app_process 启动：接收 DDP / sACN 数据并写入灯带， 同时负责串行执行 MCU 刷写。设置项放在
 * Settings.Global 的 beekeeper_net_* / beekeeper_mcu_* 下。
 */
public final class NetworkDaemon {
  static final String BASE = "/sys/class/led_i2c/led_i2c/";
  private static final String INIT_PARAMS = "3 0x50 0x81 0 0\n";
  private static final long FRAME_TIMEOUT_MS = 3000;
  private static final long SOURCE_TTL_MS = 2500;

  private final Object mutex = new Object();
  private final Map<String, Source> sources = new HashMap<>();
  private final FramePacer pacer = new FramePacer();
  private final Om2Output output;
  private final McuManager mcu;
  private volatile Config config = new Config(Collections.emptyMap());
  private volatile boolean portsOpen;
  private volatile DatagramSocket sacnSocket;
  private volatile DatagramSocket ddpSocket;
  private volatile Map<String, String> settingsSnapshot = Collections.emptyMap();
  private volatile boolean settingsReady;
  private volatile String pendingStatus = "";
  private volatile String protocol = "";
  private volatile String peer = "";
  private volatile String error = "";
  private volatile long accepted;
  private volatile long outputFrames;
  private byte[] pending;
  private byte[] ddpBuffer;
  private long receivedAt;
  private long serial;
  private long rendered;
  private int ddpSequence;
  private String ddpPeer = "";
  private long ddpAt;
  private boolean ownsDriver;
  private String previousEnable = "1";
  private Object hidl;
  private Method setLed;
  private Context systemContext;
  private WifiManager.MulticastLock multicastLock;

  private static final class Source {
    int sequence;
    int priority;
    long at;
    byte[] frame;
  }

  private static final class Config {
    final boolean enabled;
    final boolean light;
    final boolean sacn;
    final boolean ddp;
    final int count;
    final int universe;
    final int channel;

    Config(Map<String, String> v) {
      enabled = number(v, "beekeeper_net_enabled", 0) == 1;
      light = number(v, "atmosphere_light_switcher", 1) == 1;
      sacn = number(v, "beekeeper_net_sacn", 1) == 1;
      ddp = number(v, "beekeeper_net_ddp", 1) == 1;
      count = number(v, "beekeeper_net_capacity", 0);
      universe = number(v, "beekeeper_net_universe", 1);
      channel = number(v, "beekeeper_net_channel", 1);
    }

    boolean valid() {
      return count > 0
          && universe >= 1
          && universe <= 63999
          && channel >= 1
          && channel + count * 3 - 1 <= 512
          && (sacn || ddp);
    }

    /** 影响监听和数据缓存的设置是否相同（light 只决定是否输出）。 */
    boolean same(Config c) {
      return enabled == c.enabled
          && sacn == c.sacn
          && ddp == c.ddp
          && count == c.count
          && universe == c.universe
          && channel == c.channel;
    }
  }

  private NetworkDaemon(File module) throws Exception {
    output = new Om2Output(module);
    mcu =
        new McuManager(
            module,
            new McuManager.Host() {
              @Override
              public String savedEnable() throws IOException {
                return ownsDriver ? previousEnable : readEnable();
              }

              /** 停止网络输出，并让灯红闪三次提示即将刷写。 */
              @Override
              public void pause() throws Exception {
                putSetting("beekeeper_net_enabled", "0");
                closePorts();
                release();
                led(13, 0);
                writeNode("init_params", INIT_PARAMS);
                writeNode("enable", "0\n");
                try {
                  output.open(false);
                  for (int i = 0; i < 3; i++) {
                    output.send(new byte[] {(byte) 255, 0, 0});
                    Thread.sleep(250);
                    output.send(new byte[3]);
                    Thread.sleep(250);
                  }
                } finally {
                  output.close();
                }
              }

              @Override
              public void restore(String enable) throws Exception {
                restoreDriver(enable);
              }
            });
  }

  public static void main(String[] args) throws Exception {
    if (android.os.Process.myUid() != 0) throw new SecurityException("需要 root");
    // 单实例：service.sh 每次开机可能执行两次，拿不到锁的进程直接退出。
    RandomAccessFile lockFile =
        new RandomAccessFile("/data/local/tmp/beekeeper-rgb-network.lock", "rw");
    FileLock lock = lockFile.getChannel().tryLock();
    if (lock == null) {
      lockFile.close();
      return;
    }
    NetworkDaemon daemon =
        new NetworkDaemon(
            new File(args.length == 1 ? args[0] : "/data/adb/modules/beekeeper_decorative_light"));
    try {
      daemon.run();
    } finally {
      daemon.closePorts();
      daemon.release();
      lock.release();
      lockFile.close();
    }
  }

  private void run() throws Exception {
    startSettingsWorker();
    long pollAt = 0;
    long statusAt = 0;
    long sampleAt = System.nanoTime();
    long sampleFrames = 0;
    String handledRequest = "";
    while (true) {
      long now = SystemClock.elapsedRealtime();
      try {
        if (settingsReady && now >= pollAt) {
          Map<String, String> current = settingsSnapshot;
          String request = current.getOrDefault("beekeeper_mcu_request", "");
          if (request.isEmpty() || request.equals("null")) {
            handledRequest = "";
          } else if (!request.equals(handledRequest)) {
            handledRequest = request;
            mcu.poll(request);
            current = settingsSnapshot;
          }
          applyConfig(new Config(current));
          pollAt = SystemClock.elapsedRealtime() + 100;
        }
        renderFrame();
        now = SystemClock.elapsedRealtime();
        if (now >= statusAt) {
          long sampleNow = System.nanoTime();
          double fps = (outputFrames - sampleFrames) * 1e9 / Math.max(1L, sampleNow - sampleAt);
          pendingStatus =
              state()
                  + " · 收到 "
                  + accepted
                  + " 帧 · 输出 "
                  + String.format(Locale.ROOT, "%.1f", fps)
                  + "/"
                  + FramePacer.FPS
                  + "fps";
          sampleAt = sampleNow;
          sampleFrames = outputFrames;
          statusAt = now + 2000;
        }
      } catch (Exception e) {
        error = e.getClass().getSimpleName() + ": " + e.getMessage();
        System.err.println(error);
        try {
          release();
        } catch (Exception ignored) {
        }
      }
      // 新数据包会唤醒空闲的渲染线程；已有待输出的帧则等到下一个限速时刻。
      synchronized (mutex) {
        long delay = 50_000_000L;
        if (active() && serial != rendered && pending != null)
          delay = Math.max(1_000_000L, Math.min(delay, pacer.delay(System.nanoTime())));
        mutex.wait(delay / 1_000_000L, (int) (delay % 1_000_000L));
      }
    }
  }

  private boolean active() {
    Config c = config;
    return c.enabled && c.light && c.valid() && !mcu.recoveryRequired();
  }

  private String state() {
    if (!config.enabled) return "已关闭";
    if (mcu.recoveryRequired()) return "MCU 刷写记录待检查，网络输出已暂停";
    if (!config.valid()) return "请设置灯珠数量和通道";
    if (!error.isEmpty()) return "错误：" + error;
    return ownsDriver ? "接收中 " + protocol + " · " + peer : "监听中 · 等待数据";
  }

  private void applyConfig(Config next) throws Exception {
    if (!next.same(config)) {
      closePorts();
      release();
      config = next;
      synchronized (mutex) {
        pending = null;
        sources.clear();
        ddpBuffer = null;
        receivedAt = 0;
        rendered = serial;
      }
      error = "";
    } else {
      config = next;
    }
    if (mcu.recoveryRequired()) closePorts();
    else if (next.enabled && next.valid() && !portsOpen) openPorts();
    if (!active()) release();
  }

  private void renderFrame() throws Exception {
    byte[] frame = null;
    long id = 0;
    long at;
    synchronized (mutex) {
      at = receivedAt;
      if (serial != rendered && pending != null && pacer.due(System.nanoTime())) {
        frame = pending.clone();
        id = serial;
      }
    }
    if (!active()) return;
    long now = SystemClock.elapsedRealtime();
    if (frame != null && now - at < FRAME_TIMEOUT_MS) {
      takeOver();
      pacer.sent(System.nanoTime());
      output.send(frame);
      outputFrames++;
      rendered = id;
      error = "";
    } else if (ownsDriver && now - at >= FRAME_TIMEOUT_MS) {
      release();
    }
  }

  /** 设置读取放在独立线程：settings 命令可能很慢，不能拖住渲染循环。 */
  private void startSettingsWorker() {
    Thread worker =
        new Thread(
            () -> {
              String lastStatus = "";
              while (true) {
                try {
                  Map<String, String> values = readSettings();
                  String capacity =
                      String.valueOf(Om2Output.customFirmware() ? Om2Frame.PIXELS : 1);
                  if (!capacity.equals(values.get("beekeeper_net_capacity")))
                    putSetting("beekeeper_net_capacity", capacity);
                  values.put("beekeeper_net_capacity", capacity);
                  settingsSnapshot = Collections.unmodifiableMap(values);
                  settingsReady = true;
                  synchronized (mutex) {
                    mutex.notifyAll();
                  }
                  String status = pendingStatus;
                  if (!status.isEmpty() && !status.equals(lastStatus)) {
                    putSetting("beekeeper_net_status", status);
                    lastStatus = status;
                  }
                } catch (Exception e) {
                  // SettingsProvider 启动阶段可能暂时读不到，沿用上一次的有效配置。
                  System.err.println("Settings worker: " + e);
                }
                try {
                  Thread.sleep(700);
                } catch (InterruptedException e) {
                  return;
                }
              }
            },
            "RGB-settings");
    worker.setDaemon(true);
    worker.start();
  }

  private void openPorts() throws Exception {
    Config c = config;
    try {
      if (c.sacn) {
        acquireMulticast();
        MulticastSocket socket = new MulticastSocket(null);
        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(5568));
        socket.setSoTimeout(1000);
        InetAddress group =
            InetAddress.getByName("239.255." + (c.universe >> 8) + "." + (c.universe & 255));
        int joined = 0;
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
          if (!iface.isUp() || iface.isLoopback() || !iface.supportsMulticast()) continue;
          try {
            socket.joinGroup(new InetSocketAddress(group, 5568), iface);
            joined++;
          } catch (IOException ignored) {
          }
        }
        sacnSocket = socket;
        System.out.println(
            "sACN UDP 5568 universe=" + c.universe + " multicast interfaces=" + joined);
      }
      if (c.ddp) {
        ddpSocket = new DatagramSocket(null);
        ddpSocket.bind(new InetSocketAddress(4048));
        ddpSocket.setSoTimeout(1000);
      }
      portsOpen = true;
      if (sacnSocket != null) listen(sacnSocket, true, c);
      if (ddpSocket != null) listen(ddpSocket, false, c);
    } catch (Exception e) {
      closePorts();
      throw e;
    }
  }

  private void closePorts() {
    portsOpen = false;
    if (sacnSocket != null) sacnSocket.close();
    if (ddpSocket != null) ddpSocket.close();
    sacnSocket = null;
    ddpSocket = null;
    if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
  }

  /** Wi-Fi 默认会过滤组播包，接收 sACN 组播前要持有 MulticastLock。 */
  private void acquireMulticast() {
    try {
      if (systemContext == null) {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
      }
      if (multicastLock == null) {
        WifiManager wifi = (WifiManager) systemContext.getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("BeekeeperRGB-sACN");
        multicastLock.setReferenceCounted(false);
      }
      multicastLock.acquire();
      System.out.println("Wi-Fi multicast lock acquired");
    } catch (Exception e) {
      System.err.println("Wi-Fi multicast lock unavailable: " + e);
    }
  }

  private void listen(DatagramSocket socket, boolean sacn, Config c) {
    Thread thread =
        new Thread(
            () -> {
              byte[] bytes = new byte[2048];
              DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
              while (!socket.isClosed()) {
                try {
                  datagram.setLength(bytes.length);
                  socket.receive(datagram);
                  int length = datagram.getLength();
                  NetworkPacket packet =
                      sacn ? NetworkPacket.sacn(bytes, length) : NetworkPacket.ddp(bytes, length);
                  if (packet != null)
                    accept(packet, sacn, datagram.getAddress().getHostAddress(), c);
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                  if (!socket.isClosed()) System.err.println(e);
                }
              }
            },
            sacn ? "RGB-sACN" : "RGB-DDP");
    thread.setDaemon(true);
    thread.start();
  }

  private void accept(NetworkPacket p, boolean sacn, String address, Config c) {
    long now = SystemClock.elapsedRealtime();
    synchronized (mutex) {
      if (c != config && !c.same(config)) return;
      int length = c.count * 3;
      if (sacn) {
        if (p.universe != c.universe) return;
        String key = Arrays.toString(p.cid);
        sources.entrySet().removeIf(e -> now - e.getValue().at > SOURCE_TTL_MS);
        Source source = sources.get(key);
        if (p.terminated) {
          sources.remove(key);
          return;
        }
        if (p.data.length < c.channel - 1 + length) return;
        if (source != null && !NetworkPacket.newer(source.sequence, p.sequence, 256)) return;
        if (source == null) {
          if (sources.size() >= 8) return;
          source = new Source();
          sources.put(key, source);
        }
        source.sequence = p.sequence;
        source.priority = p.priority;
        source.at = now;
        source.frame = Arrays.copyOfRange(p.data, c.channel - 1, c.channel - 1 + length);
        // 同一宇宙有多个来源时，只输出优先级最高的。
        for (Source other : sources.values()) {
          if (other.priority > source.priority) return;
        }
        pending = source.frame;
        protocol = "sACN";
      } else {
        if (!address.equals(ddpPeer) || now - ddpAt > SOURCE_TTL_MS) {
          ddpPeer = address;
          ddpSequence = 0;
          ddpBuffer = new byte[length];
        }
        if (ddpBuffer == null || ddpBuffer.length != length) ddpBuffer = new byte[length];
        if (p.sequence != 0
            && ddpSequence != 0
            && !NetworkPacket.newer(ddpSequence - 1, p.sequence - 1, 15)
            && !p.repeatsDdpFrame(ddpSequence, ddpBuffer)) return;
        if (p.offset < length)
          System.arraycopy(
              p.data,
              0,
              ddpBuffer,
              (int) p.offset,
              Math.min(p.data.length, length - (int) p.offset));
        ddpAt = now;
        if (!p.push) return;
        if (p.sequence != 0) ddpSequence = p.sequence;
        pending = ddpBuffer.clone();
        protocol = "DDP";
      }
      receivedAt = now;
      peer = address;
      serial++;
      accepted++;
      mutex.notifyAll();
    }
  }

  /** 关掉系统自己的灯效，由本进程直接写 MCU。 */
  private void takeOver() throws Exception {
    if (ownsDriver) return;
    boolean pixels = Om2Output.customFirmware();
    output.check(pixels);
    previousEnable = readEnable();
    ownsDriver = true;
    try {
      led(13, 0);
      writeNode("init_params", INIT_PARAMS);
      writeNode("enable", "0\n");
      output.open(pixels);
    } catch (Exception e) {
      try {
        release();
      } catch (Exception restore) {
        e.addSuppressed(restore);
      }
      throw e;
    }
  }

  private void release() throws Exception {
    if (!ownsDriver) return;
    output.close();
    restoreDriver(previousEnable);
    ownsDriver = false;
  }

  private void restoreDriver(String enable) throws Exception {
    writeNode("init_params", INIT_PARAMS);
    writeNode("enable", enable + "\n");
    restoreLight();
  }

  /** 按系统设置里的光效、预设色和自定义颜色，让灯恢复到本地光效。 */
  private void restoreLight() throws Exception {
    Map<String, String> v = readSettings();
    int mode =
        number(v, "atmosphere_light_switcher", 1) == 1
            ? number(v, "atmosphere_light_effect", 0) + 1
            : 0;
    int color = number(v, "atmosphere_light_preset_color", 0) + 1;
    if (number(v, "atmosphere_light_same_color", 0) == 1 && mode != 0) mode = 5;
    if ((mode == 1 || mode == 2) && number(v, "beekeeper_rgb_enabled", 0) == 1)
      color = RgbColor.encode(RgbColor.parse(v.get("beekeeper_rgb_hex")));
    led(mode, color);
  }

  private void led(int mode, int color) throws Exception {
    if (hidl == null) {
      Class<?> wrapper = Class.forName("com.mitv.mtvapiclientwrapper.MiTvApiManagerProxy");
      Object manager = wrapper.getMethod("getInstance").invoke(null);
      Object proxy = wrapper.getMethod("getMiTvFApiSysCfg").invoke(manager);
      if (proxy == null) throw new IOException("灯控服务不可用");
      setLed =
          Class.forName("vendor.mi.hardware.bspmserver.V2_0.IVendorConfig")
              .getMethod("SetColorfulLed", int.class, int.class);
      hidl = proxy;
    }
    Object result = setLed.invoke(hidl, mode, color);
    if (result instanceof Number && ((Number) result).intValue() < 0)
      throw new IOException("灯控扩展不支持网络接管");
  }

  private static String readEnable() throws IOException {
    String enable =
        new String(
                Files.readAllBytes(new File(BASE + "enable").toPath()), StandardCharsets.US_ASCII)
            .trim();
    if (!enable.equals("0") && !enable.equals("1")) throw new IOException("无法保存驱动状态");
    return enable;
  }

  private static void writeNode(String name, String text) throws IOException {
    try (FileOutputStream stream = new FileOutputStream(BASE + name)) {
      stream.write(text.getBytes(StandardCharsets.US_ASCII));
    }
  }

  private static int number(Map<String, String> values, String key, int fallback) {
    try {
      return Integer.parseInt(values.get(key));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  static Map<String, String> readSettings() throws Exception {
    Process process =
        new ProcessBuilder("/system/bin/settings", "list", "global")
            .redirectErrorStream(true)
            .start();
    Map<String, String> result = new HashMap<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        int split = line.indexOf('=');
        if (split > 0) result.put(line.substring(0, split), line.substring(split + 1));
      }
    }
    if (process.waitFor() != 0) throw new IOException("无法读取灯控设置");
    return result;
  }

  static void putSetting(String key, String value) throws Exception {
    Process process =
        new ProcessBuilder("/system/bin/settings", "put", "global", key, value)
            .redirectErrorStream(true)
            .start();
    try (InputStream input = process.getInputStream()) {
      while (input.read() != -1) {}
    }
    process.waitFor();
  }
}
