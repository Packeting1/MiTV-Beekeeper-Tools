# MiTV Beekeeper Tools

小米电视 Mini LED 机型 `beekeeper`（MiTV-MFFU1，G Pro 27U，HyperOS 3.0.109）的系统优化工具集。

> 仅适用于已 root 的 beekeeper 机型，其他机型上 App 会拒绝运行。改动系统有风险，使用前请自行评估，后果自负。

## 系统优化 App

需要 root；模块功能还需要安装 Magisk / LSPosed（Vector）。

- 禁用后台应用（语音助手、统计诊断等），随时可恢复
- 安装 Aerial 桌面并设为默认桌面，可选禁用系统桌面，并检测 Aerial 更新
- 一键安装/卸载下面的 Magisk、LSPosed 模块，并显示是否真正生效
- 隐藏的系统页面入口（调试信息、色彩校准、MTK 工厂菜单等）

## Magisk 模块

| 模块 | 作用 |
|---|---|
| HGIG Fix | 补上缺失的 HGIG 支持标志，修复 HDR 色调映射菜单错位 |
| LDM Fix | 把 ldm.ini 的局部调光参数恢复成 OS2 的值，减轻 HDR/杜比视界过曝 |
| Zygote64 Fix | 修复 Zygisk Next 漏注入 zygote64，让 LSPosed 钩子开机后生效 |
| ADB Keeper | 开机和系统重置后保持 ADB 与 TCP 端口开启 |
| 4K UI Switch | 系统设置中显示 4K UI 分辨率开关 |
| Support HDR10+ | 上报 HDR10+ 能力（实际效果未验证） |
| Support IMAX | 在图像模式里显示 IMAX |
| Support AISR Level | 在“图像参数 > 清晰度”里显示超分辨率强度调节 |
| Support Volume Balance | 在声音设置里显示音量均衡 |
| Support LLDV | 声明支持低延迟杜比视界（LLDV），让 Windows 能识别杜比视界 PC 支持 |
| FreeSync Keep Mode | 开启 FreeSync、或 PC 杜比视界信号带游戏标志时不再自动切到游戏模式（开机时把打过补丁的 libmtkapp.so 绑定挂载上去，不改分区，仅适用于 OS 3.0.109） |

开关类模块开机时把对应项写入 `/data/system/mitv.config.xml` 并 `chattr +i` 锁定，防止云端配置覆盖；卸载时解锁并还原。

## LSPosed 模块

App 里勾选只负责安装，安装好后需在 LSPosed / Vector 里手动启用并勾选作用域。

| 模块 | 作用 |
|---|---|
| TvPlayerKeep | 从信号源进入系统设置（遥控器设置键、图像参数页）时不黑屏 |
| SignalKeep | 减少信号源与电视系统切换的黑屏时长 |
| CastPip | 投屏以小窗叠加在信号源画面上 |
| HomeRef | 默认桌面不是系统桌面时，让系统的“回桌面”判断和跳转指向当前默认桌面 |
| InstallUnlock | 解除小米包安装器依据云端黑名单对部分应用的安装拦截 |
| NoSignalAudioMode | 信号源在无信号倒计时结束后进入音响模式，信号恢复时自动亮屏 |
| MoreDisplaySettings | 菜单键悬浮窗的图像页新增“更多图像设置”，可直接进入图像参数；打开图像页时自动收起底部游戏条 |
| DolbyVisionTitle | 菜单键设置界面的标题在所有杜比视界图像模式下都显示 Dolby Vision |

## 自行构建

不使用 Gradle。需要 JDK 17+、API 28 的 `android.jar`、build-tools（aapt2 / zipalign / apksigner）和 R8。

```
set JAVA_HOME=...
set ANDROID_JAR=...\android.jar
set BUILD_TOOLS=...\build-tools\34.0.0
set R8_JAR=...\r8.jar
set KEYSTORE=...\my.keystore
set KS_PASS=...
set KS_ALIAS=...
set XPOSED_API=...\api-101.0.0.aar
python modules\lsposed\build.py [模块名]
python modules\magisk\build.py [模块目录名]
python app\build.py
```

- `XPOSED_API` 是 [libxposed API 101](https://github.com/libxposed/api)，Maven Central 上有。
- LSPosed 模块输出到 `modules/lsposed/dist/`；Magisk 模块（纯脚本，只需要 Python）从 `modules/magisk/src/` 打成 `modules/magisk/dist/` 里的 zip，版本号在各模块的 `module.prop`。App 构建时从这两个 `dist` 取；App 输出到 `dist/`。
- 内置的 Aerial APK（[pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)，MIT）在 `app/bundled/aerial.apk` 不存在时，会自动下载最新 Release 并按 GitHub 给出的 SHA-256 校验。

## 许可证

[GNU GPL v3.0](LICENSE)。随 App 附带的 Aerial 仍遵循其原有的 MIT 许可。

## 致谢

- [Aerial](https://github.com/pengchujin/aerial-tv)（MIT）：随 App 附带的桌面
- [Magisk](https://github.com/topjohnwu/Magisk)、[LSPosed](https://github.com/LSPosed/LSPosed)、[Zygisk Next](https://github.com/Dr-TSNG/ZygiskNext)
