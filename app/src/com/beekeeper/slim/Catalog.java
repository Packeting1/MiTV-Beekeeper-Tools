package com.beekeeper.slim;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
    return new Group[] {
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
      new Group(KIND_DB, "Aerial 桌面", "安装 Aerial 并设为默认桌面。取消勾选将恢复系统桌面，重启后 Aerial 被移除。", null, DB_PKG)
          .confirm(
              "建议始终通过本工具更新 Aerial 桌面。\n"
                  + "在 Aerial 内部升级只是普通应用安装，不会成为系统应用，而系统不允许非系统应用作为默认桌面，升级后可能无法继续使用。"),
      new Group(
          KIND_NOHOME,
          "小米桌面 (占内存约 390MB)",
          "禁用小米桌面。需先启用「默认桌面引用」和「Aerial 桌面」并设为默认桌面。",
          null,
          STOCK_HOME_PKG),
      Group.apk(
          "语音键自定义",
          "自定义遥控器语音键的单击、双击、长按动作。需先禁用「语音助手」。",
          null,
          "com.beekeeper.voicekeymap",
          "apk_voicekeymap.apk"),
      Group.apk(
          "默认桌面引用", "将系统对小米桌面的引用替换为当前的默认桌面。", null, "com.beekeeper.homeref", "apk_homeref.apk"),
      Group.apk(
          "信号源设置不黑屏",
          "从信号源进入系统设置时不黑屏。",
          null,
          "com.beekeeper.tvplayerkeep",
          "apk_tvplayerkeep.apk"),
      Group.apk(
          "信号源快速切换",
          "减少信号源和电视系统之间切换的黑屏时长。",
          "代价：内存约占用165MB",
          "com.beekeeper.signalkeep",
          "apk_signalkeep.apk"),
      Group.apk(
          "无信号音响模式",
          "信号源在无信号倒计时结束后进入音响模式，信号恢复时自动亮屏。",
          null,
          "com.beekeeper.nosignalaudiomode",
          "apk_nosignalaudiomode.apk"),
      Group.apk(
          "投屏小窗", "允许投屏内容以小窗叠加在信号源画面之上，并支持混音。", null, "com.beekeeper.castpip", "apk_castpip.apk"),
      Group.apk(
          "更多图像设置",
          "快捷设置的图像页新增「更多图像设置」，可直接进入图像参数。",
          null,
          "com.beekeeper.moredisplaysettings",
          "apk_moredisplaysettings.apk"),
      Group.apk(
          "游戏设置合并",
          "游戏设置条的功能项合并到快捷设置的「游戏」页，并新增FPS 计数器、计时器、秒表。",
          null,
          "com.beekeeper.gamemenu",
          "apk_gamemenu.apk"),
      Group.apk(
          "杜比视界标题",
          "在杜比视界模式时，快捷设置的标题显示 Dolby Vision。",
          null,
          "com.beekeeper.dolbyvisiontitle",
          "apk_dolbyvisiontitle.apk"),
      Group.apk(
          "媒体角标",
          "根据播放内容显示 HDR10 / HLG / Dolby / DTS 等角标，可在快捷设置的「专业功能」页开关显示。",
          null,
          "com.beekeeper.mediabadge",
          "apk_mediabadge.apk"),
      Group.apk(
          "信号源角标",
          "简化信号源信息角标，可在快捷设置的「专业功能」页开关显示。",
          null,
          "com.beekeeper.signalinfobadge",
          "apk_signalinfobadge.apk"),
      Group.apk(
          "精密光控记忆",
          "按视频格式分别记忆精密光控档位，可在快捷设置的「专业功能」页开关。",
          null,
          "com.beekeeper.localdimmingmemory",
          "apk_localdimmingmemory.apk"),
      new Group(KIND_MOD, "NeoZygisk", "Zygisk 环境，作为 Magisk 内置 Zygisk 的强大替代品，Xposed 框架都依赖它。", null)
          .mod("neozygisk", "mod_neozygisk.zip")
          .dir("zygisksu")
          .replaces("zygisksu", "Zygisk Next")
          .base()
          .source("JingMatrix/NeoZygisk", "*-release.zip")
          .confirm("需关闭 Magisk 设置里自带的 Zygisk。\n已安装 Zygisk Next 时会将它卸载。")
          .confirmOff("关闭后 Vector 等基于 Zygisk 的模块都会失效。"),
      new Group(
              KIND_MOD,
              "Vector",
              "Xposed 兼容框架，它基于 LSPlant 进行工程化设计，以提供一个稳定、原生层级的插桩环境。LSPosed 的开源替代项目。",
              null)
          .mod("zygisk_vector", "mod_vector.zip")
          .base()
          .source("JingMatrix/Vector", "*-Release.zip")
          .requires("neozygisk", "需先安装 NeoZygisk")
          .replaces("zygisk_lsposed", "LSPosed")
          .confirm("已安装 LSPosed 时会将它卸载。")
          .confirmOff("关闭后所有 Xposed 模块都会失效。"),
      new Group(
              KIND_MOD,
              "FreeSync 保持图像模式",
              "开启 FreeSync 或杜比视界信号带有游戏标志时，不再自动切换到游戏图像模式。",
              "风险：在信号切换或重载时画面可能出现异常；仅适用于 OS 3.0.109。")
          .mod("beekeeper_freesync_keep", "mod_freesynckeep.zip")
          .fwOnly(),
      new Group(KIND_MOD, "IMAX 支持", "在图像模式中显示「IMAX模式」。", null)
          .mod("beekeeper_imax", "mod_imax.zip"),
      new Group(KIND_MOD, "HDR10+ 支持", "上报 HDR10+ 能力，实际效果未验证。", null)
          .mod("beekeeper_hdr10plus", "mod_hdr10plus.zip"),
      new Group(KIND_MOD, "AISR 强度调节", "在「图像参数 > 清晰度」中显示超分辨率强度调节。", null)
          .mod("beekeeper_aisr_level", "mod_aisr.zip"),
      new Group(
              KIND_MOD,
              "音量均衡",
              "在声音设置中显示“音量均衡”，并在快捷设置的声音页新增「音量均衡」开关。",
              null,
              "com.beekeeper.volumebalanceswitch")
          .mod("beekeeper_volume_balance", "mod_volbalance.zip")
          .companion("apk_volumebalanceswitch.apk"),
      new Group(KIND_MOD, "LLDV 支持", "声明支持低延迟杜比视界，使 Windows 能够识别杜比视界。", null)
          .mod("beekeeper_dv_edid", "mod_dvedid.zip"),
      new Group(KIND_MOD, "DP 音频格式支持", "使 DP 输入声明与 HDMI 相同的音频格式，支持通过 DP 输出比特流。", null)
          .mod("beekeeper_dp_audio", "mod_dpaudio.zip"),
      Group.apk(
          "安装拦截解除",
          "解除小米包安装器对部分应用的安装拦截。",
          null,
          "com.beekeeper.installunlock",
          "apk_installunlock.apk"),
      new Group(
              KIND_MOD,
              "装饰灯",
              "自定义装饰灯颜色，支持通过 DDP / sACN 网络控制灯光并提供 MCU 固件刷写功能。需先启用「ADB常开」。",
              "刷写 MCU 固件存在风险，卸载不会回滚 MCU 固件。",
              "com.beekeeper.decorativelight")
          .mod("beekeeper_decorative_light", "mod_decorative_light.zip")
          .requires("mitv_adb_keeper", "为降低风险，安装此模块前需启用ADB常开")
          .confirm(
              "该模块会为系统灯控库打补丁，并提供 MCU 固件刷写功能。\n"
                  + "仅安装模块不会刷写 MCU 固件，卸载模块不会回滚已刷入的 MCU 固件。\n\n"
                  + "注意：\n"
                  + "MCU 负责背光和装饰灯，刷写 MCU 固件可能背光异常（表现为黑屏）或灯光异常，主要救法是通过 ADB 操作界面，刷回原版进行恢复。前提是 MCU"
                  + " 的引导程序正常，还能接收刷写命令。")
          .fwOnly(),
      new Group(KIND_MOD, "ADB 常开", "保持 ADB 开关的开启状态。", null)
          .mod("mitv_adb_keeper", "mod_adbkeeper.zip"),
      new Group(KIND_MOD, "4K 界面开关", "在「系统设置 > 通用设置」中显示「UI 显示模式」选项。", null)
          .mod("mitv_4kui_switch", "mod_4kui.zip")
          .fwOnly(),
      new Group(KIND_MOD, "HGIG 修复", "修复 OS 3.0.109 中 HDR 色调映射的 HGIG 档位错位。", null)
          .mod("beekeeper_hgig_fix", "mod_hgig.zip")
          .fix()
          .fwOnly(),
      new Group(KIND_MOD, "LDM 局部调光修复", "调整 OS 3.0.109 的局部调光参数，减轻 HDR / 杜比视界过曝。", null)
          .mod("beekeeper_ldm_fix", "mod_ldm.zip")
          .fix()
          .fwOnly(),
      new Group(KIND_MOD, "Zygote64 修复", "修复 Xposed 模块在开机后未能稳定生效的问题。", null)
          .mod("zygote64_fix", "mod_zygote.zip")
          .confirmOff("关闭后 Xposed 模块可能不生效。")
          .fix(),
    };
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

  static final Map<String, String[]> LS_SCOPES = new HashMap<String, String[]>();

  static {
    LS_SCOPES.put(
        "com.beekeeper.decorativelight", new String[] {"com.xiaomi.mitv.settings", "mitv.service"});
    LS_SCOPES.put(
        "com.beekeeper.tvplayerkeep",
        new String[] {
          "com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.smartpanel", "com.xiaomi.mitv.settings"
        });
    LS_SCOPES.put(
        "com.beekeeper.signalkeep",
        new String[] {"com.xiaomi.mitv.tvplayer", "com.mediatek.tvinput"});
    LS_SCOPES.put(
        "com.beekeeper.castpip",
        new String[] {
          "com.xiaomi.mitv.smartshare", "com.xiaomi.mitv.tvplayer", "mitv.service", "system"
        });
    LS_SCOPES.put(
        "com.beekeeper.homeref",
        new String[] {"com.xiaomi.mitv.settings", "com.xiaomi.mitv.tvplayer", "mitv.service"});
    LS_SCOPES.put("com.beekeeper.installunlock", new String[] {"com.android.packageinstaller"});
    LS_SCOPES.put("com.beekeeper.nosignalaudiomode", new String[] {"com.xiaomi.mitv.tvplayer"});
    LS_SCOPES.put(
        "com.beekeeper.moredisplaysettings",
        new String[] {"com.xiaomi.mitv.settings", "com.xiaomi.mitv.tvplayer"});
    LS_SCOPES.put(
        "com.beekeeper.gamemenu",
        new String[] {"com.xiaomi.mitv.settings", "com.xiaomi.mitv.tvplayer"});
    LS_SCOPES.put("com.beekeeper.dolbyvisiontitle", new String[] {"com.xiaomi.mitv.settings"});
    LS_SCOPES.put(
        "com.beekeeper.mediabadge",
        new String[] {
          "mitv.service", "com.mitv.tvhome", "com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.settings"
        });
    LS_SCOPES.put(
        "com.beekeeper.signalinfobadge",
        new String[] {"com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.settings"});
    LS_SCOPES.put(
        "com.beekeeper.localdimmingmemory",
        new String[] {"mitv.service", "com.xiaomi.mitv.settings"});
    LS_SCOPES.put("com.beekeeper.volumebalanceswitch", new String[] {"com.xiaomi.mitv.settings"});
    LS_SCOPES.put("com.beekeeper.voicekeymap", new String[] {"system"});
  }

  /** 只改 mitv.config.xml 一个开关的模块：模块 id -> 开关 key。 */
  static final Map<String, String> FLAG_MODS = new LinkedHashMap<String, String>();

  static {
    FLAG_MODS.put("beekeeper_hdr10plus", "support_hdr10_plus");
    FLAG_MODS.put("beekeeper_imax", "support_display_IMAX");
    FLAG_MODS.put("beekeeper_aisr_level", "support_display_AISR_level");
    FLAG_MODS.put("beekeeper_volume_balance", "support_volume_balance");
  }

  static final int MISSING = -1, ENABLED = 0, DISABLED = 1;
  static final int DB_NONE = 0,
      DB_USERAPP = 1,
      DB_PENDING = 2,
      DB_SYSTEM = 3,
      DB_ACTIVE = 4,
      DB_REMOVING = 5;
}
