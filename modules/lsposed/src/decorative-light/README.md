# Decorative Light

适用于 beekeeper / otfcn1n，固件 OS3.0.109.0.UFFMAMN。作者：[@Rin010](https://github.com/Rin010)。

## 功能

- **自定义颜色**：在系统设置「炫彩灯」中输入 `#RRGGBB`，保留常亮／呼吸光效，可切回预设颜色。
- **网络灯控**：接收 DDP / sACN，原厂 MCU 整条灯同色，自定义 MCU 24 颗灯珠独立控制。见 [网络灯控](docs/NETWORK.md)。
- **MCU 固件管理**（可选）：刷入 24 灯珠固件，或刷回原版。见 [MCU 固件管理](docs/MCU.md)。

## 组成与安装

| 部分 | 源码 | 内容 |
| --- | --- | --- |
| Magisk 模块 `beekeeper_decorative_light` | `modules/magisk/src/decorative_light` | 灯控库补丁（安装时生成）、输出程序、MCU 固件补丁、启动脚本 |
| LSPosed 模块 DecorativeLight | `modules/lsposed/src/decorative-light` | 设置页界面和网络灯控守护进程 |

在工具应用勾选「Decorative Light」并应用，DecorativeLight 随之安装。再到 LSPosed 启用 DecorativeLight，作用域 `com.xiaomi.mitv.settings`、`mitv.service`，重启后在「炫彩灯」中使用。安装不会刷写 MCU。

## 风险

该 MCU 负责背光（局部调光）和装饰灯，刷写可能导致背光异常，表现为黑屏。卸载模块不会回滚已刷入的固件。

恢复：通过 ADB 操作界面（因此安装前需启用 ADB 常开），在「MCU 固件管理」中使用「刷回原版」。前提是 MCU 的引导程序正常，还能接收刷写命令。该流程尚未在真机上验证。

## 构建

- `python modules/lsposed/build.py decorative-light`。
- `python modules/magisk/build.py decorative_light`，自动把上一步的 APK 打进模块。
- `bin/` 和 `firmware/` 是预编译产物，由 `modules/magisk/src/_shared/rgb_native/build_native.py` 生成，需要 keystone-engine 和原版 `MCU.bin`（SHA-256 见脚本开头）。灯控库的补丁写在 `customize.sh`，安装时在设备上生成。

## 第三方声明

本项目与 Xiaomi、Redmi、Magisk、LSPosed、Xposed 和 OpenRGB 无关联，也未获其背书，名称和商标归各自权利人所有。模块不带厂商文件，MCU 固件和灯控库都在设备上由电视自带的原件套用补丁生成，相关厂商代码的权利归原权利人所有。
