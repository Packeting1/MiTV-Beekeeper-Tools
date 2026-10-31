# 装饰灯 RGB

适用于 beekeeper / otfcn1n，固件 OS3.0.109.0.UFFMAMN（Android 14）。作者：[@Rin010](https://github.com/Rin010)。

## 功能

- **自定义颜色**：在系统设置的「炫彩灯」中输入 `#RRGGBB`，保留常亮／呼吸光效，也可切回预设颜色。
- **网络灯控**：接收 DDP（UDP 4048）和 E1.31 / sACN（UDP 5568）数据，约 3 秒无数据后恢复本地光效。原厂 MCU 整条灯同色，自定义 MCU 24 颗灯珠独立控制。详见 [网络灯控](docs/NETWORK.md)。
- **MCU 固件管理**（可选）：刷入 24 灯珠的自定义固件，或刷回原版。详见 [MCU 固件管理](docs/MCU.md)。

## 组成与安装

| 部分 | 源码位置 | 内容 |
| --- | --- | --- |
| Magisk 模块 `beekeeper_rgb` | `modules/magisk/src/rgb` | 修改后的灯控库、原生输出程序、MCU 固件镜像、网络灯控的启动脚本 |
| LSPosed 模块 RgbLight（`com.beekeeper.rgblight`） | `modules/lsposed/src/rgb-light` | 设置页里的「自定义颜色」「网络灯控」界面，以及网络灯控守护进程 |

在工具应用中勾选「装饰灯 RGB」并应用，RgbLight 随 Magisk 模块一起安装。随后在 LSPosed 中启用 RgbLight，勾选作用域 `com.xiaomi.mitv.settings` 和 `mitv.service`，重启电视，在系统设置的「炫彩灯」中使用。

安装本身不会刷写 MCU。

## 风险

MCU 同时参与显示和背光，刷写可能导致黑屏，目前没有经过验证的纯软件救砖方法。卸载模块不会回滚已刷入的 MCU 固件，需要单独刷回原版。

## 构建

- RgbLight：`python modules/lsposed/build.py rgb-light`，将生成的 APK 复制为 `modules/magisk/src/rgb/app/RgbLight.apk`。
- Magisk 模块：`python modules/magisk/build.py rgb`。
- `modules/magisk/src/rgb` 中的 `bin/`、`firmware/` 和打过补丁的 `system/vendor/lib64/libbspmserver_config.so` 是预编译产物，生成脚本在 `modules/magisk/src/_shared/rgb_native/`。`build_native.py` 需要 keystone-engine，以及对应系统的原版 `libbspmserver_config.so` 和 `MCU.bin`（SHA-256 见脚本开头），输入不匹配时拒绝构建。原版厂商文件不在仓库中。`native/native-patch.json` 记录灯控库的固定字节替换，构建时同时校验替换前的字节和结果的 SHA-256。

## 第三方声明

本项目与 Xiaomi、Redmi、Magisk、LSPosed、Xposed 和 OpenRGB 无关联，也未获其背书，名称和商标归各自权利人所有。

模块包含的厂商 MCU 原版应用镜像、由其生成的自定义镜像和修改后的灯控库含有厂商代码，相关权利归原权利人所有。
