package com.beekeeper.nosignalaudiomode;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.tv.TvInputInfo;
import android.media.tv.TvView;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 音响模式下保留框架的 TV 会话，信号恢复时才有视频回调可用。 */
final class SessionKeeper {
  private static final String TAG = "NoSignalSession";
  private static XposedModule module;
  private static final String EXTERNAL_SOURCE = "com.xiaomi.mitv.tvplayer.ExternalSourceActivity";
  private static Context context;
  private static TvView view;
  private static Activity owner;
  private static Activity keptActivity;
  private static boolean allowDestroy;
  private static boolean videoLost;
  private static String retainedInputId;
  private static ImageReader sink;
  private static HandlerThread sinkThread;

  // BaseActivity 的字段、TvView.mSession 和 mitv 中间件的方法，在 install() 里一次性解析。
  private static Field tvViewField;
  private static Field audioMgrField;
  private static Field focusListenerField;
  private static Field targetSourceField;
  private static Field sessionField;
  private static Method destroyMethod;
  private static Method tvContextInstance;
  private static Method tvContextSourceManager;
  private static Method sourceFromInputId;
  private static Method inputInfoForSource;

  static void setContext(Context value) {
    context = value;
  }

  static void error(String tag, String what, Throwable cause) {
    module.log(Log.ERROR, tag, what, cause);
  }

  private static Object sourceManager() throws ReflectiveOperationException {
    return tvContextSourceManager.invoke(tvContextInstance.invoke(null));
  }

  static void prepareInputWake(String inputId) {
    if (inputId == null || context == null) return;
    try {
      Object manager = sourceManager();
      int source = (Integer) sourceFromInputId.invoke(manager, inputId);
      TvInputInfo info = (TvInputInfo) inputInfoForSource.invoke(manager, Integer.valueOf(source));
      if (info == null || !info.isPassthroughInput() || !inputId.equals(info.getId())) {
        module.log(Log.WARN, TAG, "Recovery source mapping unavailable: " + inputId);
        return;
      }
      // 否则 handleIntent() 会回退到上次保存的信号源，可能是另一个端口。
      Settings.Global.putInt(
          context.getContentResolver(), "mitv.tvplayer.hdmi.last.source", source);
      if (owner != null && !owner.isFinishing()) {
        Intent original = owner.getIntent();
        Intent resume = original == null ? new Intent() : new Intent(original);
        resume.putExtra("input", source);
        resume.removeExtra("autosource");
        owner.setIntent(resume);
        targetSourceField.setInt(owner, source);
      }
      module.log(Log.INFO, TAG, "Recovery wake selects source=" + source + " input=" + inputId);
    } catch (Throwable error) {
      error(TAG, "Recovery source preparation failed", error);
    }
  }

  /** 选好信号源后请求亮屏（原因 8 = 音响模式）。 */
  static void wake(PowerManager power, String inputId, String reason)
      throws ReflectiveOperationException {
    prepareInputWake(inputId);
    HookEntry.wakeUp.invoke(power, SystemClock.uptimeMillis(), 8, reason);
  }

  static boolean audioMode() {
    return context != null
        && "xiaoai_mode"
            .equals(Settings.Global.getString(context.getContentResolver(), "mitv_power_state"));
  }

  private static void releaseSink() {
    if (sink != null) {
      sink.close();
      sink = null;
    }
    if (sinkThread != null) {
      sinkThread.quitSafely();
      sinkThread = null;
    }
  }

  private static Surface sinkSurface() {
    if (sink == null) {
      sinkThread = new HandlerThread("NoSignalDiscard");
      sinkThread.start();
      // PRIVATE 缓冲区直接丢弃，不读取像素，也不保存或导出画面。
      sink = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 2);
      sink.setOnImageAvailableListener(
          new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
              try {
                Image image = reader.acquireLatestImage();
                if (image != null) image.close();
              } catch (IllegalStateException ignored) {
              }
            }
          },
          new Handler(sinkThread.getLooper()));
    }
    return sink.getSurface();
  }

  /** 返回 true 表示要跳过原版 destoryPlayerAndSurface。 */
  private static boolean retainSession(Activity activity) throws ReflectiveOperationException {
    if (allowDestroy || !audioMode() || activity.isFinishing()) return false;
    if (!activity.getClass().getName().equals(EXTERNAL_SOURCE)) return false;
    TvView current = (TvView) tvViewField.get(activity);
    if (current == null) return false;
    try {
      sinkSurface();
    } catch (Throwable error) {
      releaseSink();
      error(TAG, "Session retention unavailable", error);
      return false;
    }
    keptActivity = activity;
    owner = activity;
    view = current;
    try {
      int source = targetSourceField.getInt(owner);
      TvInputInfo info =
          (TvInputInfo) inputInfoForSource.invoke(sourceManager(), Integer.valueOf(source));
      retainedInputId = info == null ? null : info.getId();
    } catch (Throwable error) {
      retainedInputId = null;
      module.log(Log.WARN, TAG, "Retained input mapping unavailable", error);
    }
    AudioManager audio = (AudioManager) audioMgrField.get(activity);
    if (audio != null)
      audio.abandonAudioFocus(
          (AudioManager.OnAudioFocusChangeListener) focusListenerField.get(activity));
    module.log(Log.INFO, TAG, "Retained input session for audio mode: " + retainedInputId);
    return true;
  }

  static Field field(Class<?> type, String name) throws NoSuchFieldException {
    Field f = type.getDeclaredField(name);
    f.setAccessible(true);
    return f;
  }

  private static void clearState() {
    keptActivity = null;
    view = null;
    owner = null;
    videoLost = false;
    retainedInputId = null;
    releaseSink();
  }

  /** 用原版逻辑释放被保留的会话。 */
  private static void releaseRetained(Object self) {
    allowDestroy = true;
    try {
      destroyMethod.invoke(self);
    } catch (Throwable error) {
      error(TAG, "Retained session release failed", error);
    } finally {
      allowDestroy = false;
      clearState();
    }
  }

  static void install(XposedModule m, ClassLoader loader) throws Throwable {
    module = m;
    Class<?> base = loader.loadClass("com.xiaomi.mitv.tvplayer.BaseActivity");
    Class<?> session = loader.loadClass("android.media.tv.TvInputManager$Session");
    Class<?> callback = loader.loadClass("android.media.tv.TvView$MySessionCallback");
    Class<?> tvContext = loader.loadClass("mitv.tv.TvContext");
    Class<?> sourceManagerImpl = loader.loadClass("mitv.impl.tv.SourceManagerImpl");

    tvViewField = field(base, "mTvView");
    audioMgrField = field(base, "mAudioMgr");
    focusListenerField = field(base, "mAudioFocusChangeListener");
    targetSourceField = field(base, "mTargetSource");
    sessionField = field(TvView.class, "mSession");
    destroyMethod = base.getDeclaredMethod("destoryPlayerAndSurface");
    destroyMethod.setAccessible(true);
    tvContextInstance = tvContext.getMethod("getInstance");
    tvContextSourceManager = tvContext.getMethod("getSourceManager");
    sourceFromInputId = sourceManagerImpl.getMethod("getMitvSourceFromTIFId", String.class);
    sourceFromInputId.setAccessible(true);
    inputInfoForSource = sourceManagerImpl.getMethod("getTvInputInfoForMitvSource", int.class);
    inputInfoForSource.setAccessible(true);

    module
        .hook(destroyMethod)
        .intercept(chain -> {
          Object self = chain.getThisObject();
          try {
            if (retainSession((Activity) self)) return null;
          } catch (Throwable error) {
            error(TAG, "Session retention check failed", error);
          }
          Object result = chain.proceed();
          try {
            if (owner == self && tvViewField.get(self) == null) clearState();
          } catch (Throwable error) {
            error(TAG, "Session cleanup failed", error);
          }
          return result;
        });
    module
        .hook(base.getDeclaredMethod("createPlayerAndSurface"))
        .intercept(chain -> {
          Object self = chain.getThisObject();
          if (keptActivity == self) releaseRetained(self);
          Object result = chain.proceed();
          try {
            if (self.getClass().getName().equals(EXTERNAL_SOURCE)) {
              view = (TvView) tvViewField.get(self);
              owner = (Activity) self;
              videoLost = false;
            }
          } catch (Throwable error) {
            error(TAG, "Session tracking failed", error);
          }
          return result;
        });
    module
        .hook(base.getDeclaredMethod("onDestroy"))
        .intercept(chain -> {
          Object self = chain.getThisObject();
          if (keptActivity == self) releaseRetained(self);
          return chain.proceed();
        });
    module
        .hook(session.getDeclaredMethod("setSurface", Surface.class))
        .intercept(chain -> {
          Surface replacement = null;
          try {
            if (chain.getArg(0) == null
                && view != null
                && audioMode()
                && !allowDestroy
                && sessionField.get(view) == chain.getThisObject()) {
              replacement = sinkSurface();
              module.log(Log.INFO, TAG, "Retained an offscreen signal surface");
            }
          } catch (Throwable error) {
            error(TAG, "Offscreen surface unavailable", error);
          }
          return replacement == null ? chain.proceed() : chain.proceed(new Object[] {replacement});
        });
    Method unavailable = callback.getDeclaredMethod("onVideoUnavailable", session, int.class);
    module
        .hook(unavailable)
        .intercept(chain -> {
          Object result = chain.proceed();
          try {
            if (view != null && sessionField.get(view) == chain.getArg(0)) {
              int reason = (Integer) chain.getArg(1);
              if (reason == 2) videoLost = true;
              module.log(Log.INFO, TAG, "Video unavailable reason=" + reason);
            }
          } catch (Throwable error) {
            error(TAG, "Video state tracking failed", error);
          }
          return result;
        });
    Method available = callback.getDeclaredMethod("onVideoAvailable", session);
    module
        .hook(available)
        .intercept(chain -> {
          Object result = chain.proceed();
          try {
            if (view != null && sessionField.get(view) == chain.getArg(0)) {
              boolean recovered = videoLost;
              videoLost = false;
              module.log(Log.INFO, TAG, "Video available; recovery=" + recovered);
              if (recovered && context != null) {
                PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (power != null && !power.isInteractive() && audioMode()) {
                  wake(power, retainedInputId, "NoSignalXiaoAi:video-event");
                  module.log(Log.INFO, TAG, "Requested wake from video event");
                }
              }
            }
          } catch (Throwable error) {
            error(TAG, "Video recovery handling failed", error);
          }
          return result;
        });
    module.log(Log.INFO, TAG, "Installed audio-mode session retention");
  }
}
