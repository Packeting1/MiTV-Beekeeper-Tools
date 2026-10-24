# MiTV Beekeeper Tools

小米电视 Mini LED 机型 `beekeeper`（MiTV-MFFU1，G Pro 27U，HyperOS 3.0.109）的系统优化工具集。作者：酷安 @pkkka

> 仅适用于已获取 root（Magisk）的 beekeeper 机型，App 在其他机型上会拒绝运行。改动系统有风险，使用前请自行评估，后果自负。

## 系统优化 App

需要 root。分页：应用类 / 优化类 / 修复类 / 隐藏项 / 关于。

- 禁用后台应用（语音助手、统计诊断等），随时可恢复
- 安装 Aerial 桌面并设为默认桌面，可选禁用系统桌面，并检测 Aerial 更新
- 一键安装/关闭下面的 Magisk 模块，并显示模块是否真正生效
- 一键安装 LSPosed 模块（TvPlayerKeep / CastPip / HomeRef / 无信号进入音响模式）
- 隐藏的系统页面入口（调试信息、色彩校准、MTK 工厂菜单等）

## Magisk 模块（`modules/`，也在每个 Release 里）

| 模块 | 作用 |
|---|---|
| HGIG Fix | 补上缺失的 HGIG 支持标志，修复 HDR 色调映射菜单错位 |
| LDM Fix | 把 ldm.ini 的局部调光参数恢复成 OS2 的值，减轻 HDR/杜比视界过曝 |
| Zygote64 Fix | 修复 Zygisk Next 漏注入 zygote64，让 LSPosed 钩子开机后生效 |
| MiTV ADB Keeper | 开机和系统重置后保持 ADB 与 TCP 端口开启 |
| MiTV 4K UI Switch | 通过无系统改动的 XML overlay 显示原生 4K UI 分辨率开关 |
| Support HDR10+ | 上报 HDR10+ 能力（实际效果未验证） |
| Support IMAX | 在图像模式里显示 IMAX |
| Support AISR Level | 在“图像参数 > 清晰度”里显示超分辨率强度调节 |
| Support Volume Balance | 在声音设置里显示音量均衡 |
| Support LLDV | 声明支持低延迟杜比视界（LLDV），让 Windows 能识别杜比视界PC支持 |

开关类模块会在开机时把对应项写入 `/data/system/mitv.config.xml`，并用 `chattr +i` 锁定，防止云端配置覆盖；卸载模块时会解锁并还原。

## 无信号进入音响模式（LSPosed）

输入源无信号倒计时结束后进入小爱音响模式，保留原有倒计时。倒计时页面新增第二个“息屏”按钮，可提前进入音响模式，原有“主页、待机、关机”按钮保持原行为。`1.0.3-test4` 预发布版补充电脑不拔线睡眠/熄屏恢复检测，并在唤醒时选择实际恢复的输入源，避免错误切到其他端口后再次息屏。安装后在 LSPosed 中启用，作用域只勾选“模拟电视” (`com.xiaomi.mitv.tvplayer`)，然后重启输入源应用。

已在 beekeeper / Android 14 / HyperOS OS3.0.109.0.UFFMAMN、输入源应用 3.0.38（260325）上完成 HDMI 断输出 75 秒、音响模式和恢复信号测试。新方案会保留当前输入会话并丢弃离屏缓冲，不读取像素或保存画面；额外功耗、其他端口不拔线恢复及长期稳定性尚未完整验证。需要 LSPosed 正常注入；其他固件和整机冷启动未单独验证。

源码和构建说明见 [`lsposed/no-signal-audio/`](lsposed/no-signal-audio/)，独立项目见 [MiTV-NoSignal-AudioMode](https://github.com/Rin010/MiTV-NoSignal-AudioMode)。

## 自行构建 App

没有用 Gradle。需要：JDK 8+、API 28 的 `android.jar`、build-tools（aapt2 / zipalign / apksigner）、稳定版 R8，以及 Aerial 桌面的 APK（放到 `app/bundled/aerial.apk`，来源 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)，MIT 许可）。

```
set ANDROID_JAR=...\android.jar
set BUILD_TOOLS=...\build-tools\34.0.0
set R8_JAR=...\r8.jar
set KEYSTORE=...\my.keystore
set KS_PASS=...
set KS_ALIAS=...
python app\build.py
```

输出在 `dist/`。

## 许可证
 [GNU GPL v3.0](LICENSE) 授权。随 App 附带的 Aerial 桌面仍遵循其原有的 MIT 许可。

## 致谢

- [Aerial](https://github.com/pengchujin/aerial-tv)（MIT）：随 App 附带的桌面
- [Magisk](https://github.com/topjohnwu/Magisk)、[LSPosed](https://github.com/LSPosed/LSPosed)、[Zygisk Next](https://github.com/Dr-TSNG/ZygiskNext)
