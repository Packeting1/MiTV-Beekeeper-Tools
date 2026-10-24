package com.beekeeper.slim;

/** 勾选项清单、隐藏页清单和各种常量。 */
final class Catalog {

    private Catalog() { }

    static final String DB_PKG = "app.orchard.tv";

    static final String DB_COMP = DB_PKG + "/app.orchard.tv.MainActivity";

    static final String DB_MODULE = "beekeeper_aerial";

    static final String OLD_DB_MODULE = "beekeeper_dangbei";

    static final String STOCK_HOME_PKG = "com.mitv.tvhome";

    static final String STOCK_HOME_COMP = STOCK_HOME_PKG + "/.MainActivityUserMode";

    static final int KIND_PKGS = 0, KIND_DB = 1, KIND_NOHOME = 2, KIND_MOD = 3, KIND_APK = 4;

    static Group[] groups() {
        return new Group[] {
            new Group("语音助手 (占内存约 245MB)",
                "语音控制和唤醒。禁用后语音键和小爱同学不可用。",
                null,
                "com.xiaomi.voicecontrol", "com.xiaomi.wakeupservice"),
            new Group("小米互联 (占内存约 420MB)",
                "手机互联、MiPlay、HyperConnect。禁用后手机投屏和多设备协同不可用。",
                "风险：可能影响其它功能",
                "com.xiaomi.mi_connect_service", "com.xiaomi.miplay", "com.xiaomi.hyper_connect.tv"),
            new Group("智能家居 (占内存约 90MB)",
                "小米智能家居 TV 版。",
                null,
                "com.xiaomi.smarthome.tv"),
            new Group("消息推送 (占内存约 87MB)",
                "系统推送服务。禁用后收不到推送。",
                null,
                "com.xiaomi.mitv.tvpush.tvpushservice"),
            new Group("应用商店 (占内存约 109MB)",
                "小米应用商店。禁用后不能安装和更新应用。",
                null,
                "com.xiaomi.mitv.appstore"),
            new Group("电视管家 (占内存约 22MB)",
                "清理加速等。",
                "风险：可能影响其它功能",
                "com.xiaomi.mitv.tvmanager"),
            new Group("健康与诊断 (占内存约 9MB)",
                "健康服务和健康诊断。",
                null,
                "com.xiaomi.mitv.health", "com.xiaomi.mitv.healthdiagnosis"),
            new Group("截屏与录屏",
                "禁用后截屏和录屏不可用。",
                null,
                "com.xiaomi.screenrecorder"),
            new Group("统计与诊断 (占内存约 270MB)",
                "数据统计、MiSight 诊断、质量上报。",
                null,
                "com.miui.tv.analytics", "com.miui.misightservice", "com.xiaomi.tvqs"),
            new Group("云控中心",
                "拉取云端配置、上传诊断日志。",
                "风险：云端功能开关不再更新",
                "com.mitv.cloudcontrol"),
            new Group("屏保 (占内存约 92MB)",
                "超级屏保。",
                null,
                "com.xiaomi.mitv.hyper.screensaver"),
            new Group("AOD 息屏显示",
                "息屏显示图案和签名。",
                "风险：可能影响息屏相关功能",
                "com.xiaomi.mitv.aod.screensaver"),
            new Group("视频头条",
                "短视频信息流应用。",
                null,
                "com.duokan.videodaily"),
            new Group("相册 (占内存约 32MB)",
                "电视相册及其推送。",
                null,
                "com.mitv.gallery"),
            new Group("快应用 (占内存约 13MB)",
                "小米快应用平台。",
                null,
                "com.miui.hybrid.tv"),
            new Group("卡拉 OK",
                "卡拉 OK 服务。",
                null,
                "com.xiaomi.mitv.karaoke.service"),
            new Group("工厂检测工具箱",
                "小米电视的内部调试和工厂检测工具。",
                null,
                "com.mitv.toolhouse"),
            new Group(KIND_DB, "Aerial 桌面",
                "安装 Aerial 并设为默认桌面。取消勾选会恢复系统桌面，重启后 Aerial 消失。",
                null,
                DB_PKG),
            new Group(KIND_NOHOME, "系统桌面 (占内存约 390MB)",
                "禁用小米系统桌面。仅已安装“Aerial 桌面”和 HomeRef，且 Aerial 为默认桌面后才能禁用此项。",
                "风险：部分系统入口（如信号源列表的“主页”）可能没反应",
                STOCK_HOME_PKG),
            new Group(KIND_APK, "TvPlayerKeep（LSPosed模块）",
                "从信号源进入系统设置不黑屏。",
                null, "com.beekeeper.tvplayerkeep").mod("com.beekeeper.tvplayerkeep", "apk_tvplayerkeep.apk"),
            new Group(KIND_APK, "SignalKeep（LSPosed模块）",
                "减少信号源和电视系统之间切换的黑屏时长。",
                "代价：内存约占用165MB",
                "com.beekeeper.signalkeep").mod("com.beekeeper.signalkeep", "apk_signalkeep.apk"),
            new Group(KIND_APK, "CastPip（LSPosed模块）",
                "投屏小窗叠加信号源，并支持混音。",
                null, "com.beekeeper.castpip").mod("com.beekeeper.castpip", "apk_castpip.apk"),
            new Group(KIND_APK, "HomeRef（LSPosed模块）",
                "把系统桌面的引用替换为当前的默认桌面。",
                null, "com.beekeeper.homeref").mod("com.beekeeper.homeref", "apk_homeref.apk"),
            new Group(KIND_APK, "InstallUnlock（LSPosed模块）",
                "解除应用安装器对部分应用安装拦截弹窗。",
                null,
                "com.beekeeper.installunlock").mod("com.beekeeper.installunlock", "apk_installunlock.apk"),
            new Group(KIND_MOD, "ADB 常开（Magisk模块）",
                "开机后自动保持 ADB 开关和 5555 端口开启。",
                null).mod("mitv_adb_keeper", "mod_adbkeeper.zip"),
            new Group(KIND_MOD, "4K 界面开关（Magisk模块）",
                "在系统设置里显示 4K 界面开关。开关位于“设置>通用设置>UI显示模式>极清模式”",
                null).mod("mitv_4kui_switch", "mod_4kui.zip"),
            new Group(KIND_MOD, "HGIG 修复（Magisk模块）",
                "修复 OS 3.0.109 的 HDR 色调映射里 HGIG 档位错位。",
                null).mod("beekeeper_hgig_fix", "mod_hgig.zip"),
            new Group(KIND_MOD, "LDM 局部调光修复（Magisk模块）",
                "调整 OS 3.0.109 的局部调光参数，减轻 HDR/杜比视界过曝。",
                null).mod("beekeeper_ldm_fix", "mod_ldm.zip"),
            new Group(KIND_MOD, "Zygote64 修复（Magisk模块）",
                "让 LSPosed 模块开机后稳定生效。",
                "风险：关闭后 LSPosed 的模块可能不生效").mod("zygote64_fix", "mod_zygote.zip"),
            new Group(KIND_MOD, "HDR10+支持（Magisk模块）",
                "上报HDR10+能力支持，实际效果未知。",
                null).mod("beekeeper_hdr10plus", "mod_hdr10plus.zip"),
            new Group(KIND_MOD, "IMAX支持（Magisk模块）",
                "在图像模式中显示IMAX。",
                null).mod("beekeeper_imax", "mod_imax.zip"),
            new Group(KIND_MOD, "AISR 强度调节（Magisk模块）",
                "“图像参数>清晰度”中显示超分辨率强度调节。",
                null).mod("beekeeper_aisr_level", "mod_aisr.zip"),
            new Group(KIND_MOD, "音量均衡（Magisk模块）",
                "在声音设置里显示“音量均衡”。",
                null).mod("beekeeper_volume_balance", "mod_volbalance.zip"),
            new Group(KIND_MOD, "LLDV支持（Magisk模块）",
                "声明支持低延迟杜比视界（LLDV），让 Windows 能识别杜比视界PC支持。",
                null).mod("beekeeper_dv_edid", "mod_dvedid.zip"),
        };
    }

    static final String SET_MAIN = "com.xiaomi.mitv.settings/com.xiaomi.mitv.settings.entry.MainActivity";

    static String viaSettings(String name) {
        return "-n " + SET_MAIN + " --es activity_name " + name;
    }

    /** 设置菜单里没有入口、但能直接打开的页面。 */
    static Hidden[] hidden() {
        return new Hidden[] {
            new Hidden("调试信息", "设备和系统的调试信息页。", null, viaSettings("DebugInfoActivity")),
            new Hidden("调试页", "设置里的调试页面。", "风险：内有调试开关，不建议改动", viaSettings("DebugActivity")),
            new Hidden("开机视频", "开机视频设置页。", null, viaSettings("BootVideoActivity")),
            new Hidden("色彩校准", "图像色彩校准页。", null, viaSettings("ColorCalibrationActivity")),
            new Hidden("息屏样式", "息屏显示的图案选择页。", null, viaSettings("AodStyle")),
            new Hidden("环绕声测试", "环绕音箱声道测试页。", null, viaSettings("surroundTest")),
            new Hidden("MTK 工厂菜单", "联发科工厂/设计菜单，能改面板、画质等底层参数。",
                "风险：高风险，改错可能会让画面异常",
                "-n mediatek.factorymenu.ui/mediatek.tvsetting.factory.ui.designmenu.DesignMenuActivity"),
        };
    }

    static final java.util.Map<String, String[]> LS_SCOPES = new java.util.HashMap<String, String[]>();

    static {
        LS_SCOPES.put("com.beekeeper.tvplayerkeep", new String[] {
            "com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.smartpanel", "com.xiaomi.mitv.settings" });
        LS_SCOPES.put("com.beekeeper.signalkeep", new String[] {
            "com.xiaomi.mitv.tvplayer", "com.mediatek.tvinput" });
        LS_SCOPES.put("com.beekeeper.castpip", new String[] {
            "com.xiaomi.mitv.smartshare", "com.xiaomi.mitv.tvplayer", "mitv.service" });
        LS_SCOPES.put("com.beekeeper.homeref", new String[] {
            "com.xiaomi.mitv.settings", "com.xiaomi.mitv.tvplayer", "mitv.service" });
        LS_SCOPES.put("com.beekeeper.installunlock", new String[] { "com.android.packageinstaller" });
    }

    /** 只改 mitv.config.xml 一个开关的模块：模块 id -> 开关 key。 */
    static final java.util.Map<String, String> FLAG_MODS = new java.util.LinkedHashMap<String, String>();

    static {
        FLAG_MODS.put("beekeeper_hdr10plus", "support_hdr10_plus");
        FLAG_MODS.put("beekeeper_imax", "support_display_IMAX");
        FLAG_MODS.put("beekeeper_aisr_level", "support_display_AISR_level");
        FLAG_MODS.put("beekeeper_volume_balance", "support_volume_balance");
    }

    static final int MISSING = -1, ENABLED = 0, DISABLED = 1;

    static final int DB_NONE = 0, DB_USERAPP = 1, DB_PENDING = 2, DB_SYSTEM = 3, DB_ACTIVE = 4, DB_REMOVING = 5;
}
