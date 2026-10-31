# 装饰灯 RGB

支持 beekeeper / otfcn1n、OS3.0.109.0.UFFMAMN（Android 14）。作者：[@Rin010](https://github.com/Rin010)。

- 输入 `#RRGGBB`，保留常亮／呼吸光效。
- DDP（UDP 4048）、E1.31 / sACN（UDP 5568）；原厂 MCU 整灯控制，自定义 MCU 为 24 颗独立控制。
- 约 3 秒断流后恢复本地光效。

由两部分组成，在工具应用里勾选「装饰灯 RGB」即可一起安装：

| 部分 | 位置 | 说明 |
| --- | --- | --- |
| Magisk 模块 `beekeeper_rgb` | `modules/magisk/src/rgb` | 修改灯控库、原生输出程序、MCU 固件镜像、网络灯控守护进程 |
| LSPosed 模块 `RgbLight`（`com.beekeeper.rgblight`） | `modules/lsposed/src/rgb-light` | 系统设置里的「自定义颜色」「网络灯控」页面，网络灯控守护进程也运行在这个 APK 中；随 Magisk 模块自动安装 |

首次使用需在 LSPosed 启用 RgbLight，并勾选 `com.xiaomi.mitv.settings`、`mitv.service`，重启后进入系统设置的「炫彩灯」。

普通安装不刷写 MCU。需要逐灯控制时，在「网络灯控 → MCU 固件管理」执行；页面强制版本／镜像校验、风险确认及 10 秒等待。MCU 参与显示和背光，刷写可能导致黑屏，暂无已验证的软件救砖方案。卸载不回滚 MCU。

详见 [网络灯控](network.md)、[MCU 固件管理](mcu.md)。

## 构建

APK 与其它 LSPosed 模块一样，用 `python modules/lsposed/build.py rgb-light` 生成，Magisk 模块用 `python modules/magisk/build.py rgb` 打包（先把 APK 复制为 `modules/magisk/src/rgb/app/RgbLight.apk`）。

`modules/magisk/src/rgb` 里的 `bin/`、`firmware/` 和打过补丁的 `system/vendor/lib64/libbspmserver_config.so` 是预编译产物，生成方法在 `modules/magisk/src/_shared/rgb_native/`：`build_native.py` 需要 keystone-engine 和对应系统的原版 `libbspmserver_config.so`、`MCU.bin`（见脚本开头的 SHA-256），输入不匹配会拒绝构建。原版厂商文件不在仓库里。`native/native-patch.json` 描述灯控库的固定字节替换，构建时同时校验原始字节和目标 SHA-256。

## 第三方声明

本项目与 Xiaomi、Redmi、Magisk、LSPosed、Xposed 或 OpenRGB 没有关联或背书关系，名称和商标归各自权利人所有。

模块中的厂商 MCU 原版应用镜像、由其生成的实验镜像，以及修改后的厂商灯控库包含厂商代码，相关权利仍归原权利人所有。
