package com.beekeeper.slim;

import java.util.Arrays;
import java.util.Map;

/** 勾选项清单、隐藏页清单和各种常量。 */
final class Catalog {

  private Catalog() {}

  /** 固件相关的模块（fwOnly）只在这个版本上开放。 */
  static final String FIRMWARE = "OS3.0.109.0.UFFMAMN";

  static final String DB_PKG = "app.orchard.tv";

  static final String DB_COMP = DB_PKG + "/app.orchard.tv.MainActivity";

  static final String DB_MODULE = "beekeeper_aerial";

  static final String OLD_DB_MODULE = "beekeeper_dangbei";

  static final String STOCK_HOME_PKG = "com.mitv.tvhome";

  static final String STOCK_HOME_COMP = STOCK_HOME_PKG + "/.MainActivityUserMode";

  static final int KIND_PKGS = 0, KIND_DB = 1, KIND_NOHOME = 2, KIND_MOD = 3, KIND_APK = 4;

  static Group[] groups() {
    Group[] base =
        new Group[] {
          new Group(
              "语音助手 (占内存约 245MB)",
              "语音控制和唤醒。",
              null,
              "com.xiaomi.voicecontrol",
              "com.xiaomi.wakeupservice"),
          new Group(
                  "小米互联 (占内存约 420MB)",
                  "手机互联、MiPlay、HyperConnect。",
                  "风险：可能影响其它功能",
                  "com.xiaomi.mi_connect_service",
                  "com.xiaomi.miplay",
                  "com.xiaomi.hyper_connect.tv")
              .confirm("禁用后手机投屏和多设备协同不可用。"),
          new Group("智能家居 (占内存约 90MB)", "小米智能家居 TV 版。", null, "com.xiaomi.smarthome.tv"),
          new Group("消息推送 (占内存约 87MB)", "系统推送服务。", null, "com.xiaomi.mitv.tvpush.tvpushservice"),
          new Group("应用商店 (占内存约 109MB)", "小米应用商店。", null, "com.xiaomi.mitv.appstore"),
          new Group("电视管家 (占内存约 22MB)", "系统清理与加速功能。", null, "com.xiaomi.mitv.tvmanager"),
          new Group(
              "健康与诊断 (占内存约 9MB)",
              "健康服务和健康诊断。",
              null,
              "com.xiaomi.mitv.health",
              "com.xiaomi.mitv.healthdiagnosis"),
          new Group("截屏与录屏", "禁用后截屏和录屏不可用。", null, "com.xiaomi.screenrecorder"),
          new Group(
              "统计与诊断 (占内存约 270MB)",
              "数据统计、MiSight 诊断、质量上报。",
              null,
              "com.miui.tv.analytics",
              "com.miui.misightservice",
              "com.xiaomi.tvqs"),
          new Group("云控中心", "拉取云端配置、上传诊断日志。", "风险：云端功能开关不再更新", "com.mitv.cloudcontrol"),
          new Group("系统更新", "检查和推送系统固件更新。", null, "com.xiaomi.mitv.upgrade"),
          new Group("屏保 (占内存约 92MB)", "系统超级屏保。", null, "com.xiaomi.mitv.hyper.screensaver"),
          new Group("AOD 息屏显示", "息屏显示图案和签名。", null, "com.xiaomi.mitv.aod.screensaver"),
          new Group("视频头条", "短视频信息流应用。", null, "com.duokan.videodaily"),
          new Group("相册 (占内存约 32MB)", "电视相册及其消息推送。", null, "com.mitv.gallery"),
          new Group("快应用 (占内存约 13MB)", "小米快应用平台。", null, "com.miui.hybrid.tv"),
          new Group("卡拉 OK", "卡拉 OK 服务。", null, "com.xiaomi.mitv.karaoke.service"),
          new Group("工厂检测工具箱", "小米电视的内部调试和工厂检测工具。", null, "com.mitv.toolhouse"),
          new Group(
                  KIND_DB,
                  "Aerial 桌面",
                  "安装 Aerial 并设为默认桌面。取消勾选将恢复系统桌面，重启后 Aerial 被移除。",
                  null,
                  DB_PKG)
              .confirm(
                  "建议始终通过本工具更新 Aerial 桌面。\n"
                      + "在 Aerial 内部升级只是普通应用安装，不会成为系统应用，而系统不允许非系统应用作为默认桌面，升级后可能无法继续使用。"),
          new Group(
              KIND_NOHOME,
              "小米桌面 (占内存约 390MB)",
              "禁用小米桌面。需先启用「默认桌面引用」和「Aerial 桌面」并设为默认桌面。",
              null,
              STOCK_HOME_PKG),
        };
    Group[] modules = ModuleCatalog.groups();
    Group[] all = Arrays.copyOf(base, base.length + modules.length);
    System.arraycopy(modules, 0, all, base.length, modules.length);
    return all;
  }

  static final String SET_MAIN =
      "com.xiaomi.mitv.settings/com.xiaomi.mitv.settings.entry.MainActivity";

  static String viaSettings(String name) {
    return "-n " + SET_MAIN + " --es activity_name " + name;
  }

  /** 设置菜单里没有入口、但能直接打开的页面。 */
  static Hidden[] hidden() {
    return new Hidden[] {
      new Hidden("调试信息", "设备和系统的调试信息页。", null, viaSettings("DebugInfoActivity")),
      new Hidden("调试页", "设置里的调试页面。", "风险：包含调试开关，不建议修改", viaSettings("DebugActivity")),
      new Hidden("开机视频", "开机视频设置页。", null, viaSettings("BootVideoActivity")),
      new Hidden("色彩校准", "图像色彩校准页。", null, viaSettings("ColorCalibrationActivity")),
      new Hidden("息屏样式", "息屏显示的图案选择页。", null, viaSettings("AodStyle")),
      new Hidden("环绕声测试", "环绕音箱声道测试页。", null, viaSettings("surroundTest")),
      new Hidden(
          "MTK 工厂菜单",
          "联发科工厂/设计菜单，可修改面板、画质等底层参数。",
          "风险：高风险，设置错误可能导致画面异常",
          "-n mediatek.factorymenu.ui/mediatek.tvsetting.factory.ui.designmenu.DesignMenuActivity"),
    };
  }

  static final Map<String, String[]> LS_SCOPES = ModuleCatalog.scopes();

  /** 配置开关的 KEY 来自各模块 common/flag.conf。 */
  static final Map<String, String> FLAG_MODS = ModuleCatalog.flags();

  static final int MISSING = -1, ENABLED = 0, DISABLED = 1;
  static final int DB_NONE = 0,
      DB_USERAPP = 1,
      DB_PENDING = 2,
      DB_SYSTEM = 3,
      DB_ACTIVE = 4,
      DB_REMOVING = 5;
}
