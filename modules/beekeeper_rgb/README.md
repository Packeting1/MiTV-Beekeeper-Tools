# 装饰灯 RGB 1.4.1

支持 beekeeper / otfcn1n、OS3.0.109.0.UFFMAMN（Android 14）。

- 输入 `#RRGGBB`，保留常亮／呼吸光效。
- DDP（UDP 4048）、E1.31 / sACN（UDP 5568）；原厂 MCU 整灯控制，自定义 MCU 为 24 颗独立控制。
- 约 3 秒断流后恢复本地光效。

在工具应用勾选本模块并应用，配套 APK 自动安装。首次在 LSPosed 启用并勾选 `com.xiaomi.mitv.settings`、`mitv.service`，重启后进入系统设置的「炫彩灯」。

普通安装不刷写 MCU。需要逐灯控制时，在「网络灯控 → MCU 固件管理」执行；页面强制版本／镜像校验、风险确认及 10 秒等待。MCU 参与显示和背光，刷写可能导致黑屏，暂无已验证的软件救砖方案。卸载不回滚 MCU。

详见 [网络设置](NETWORK.md)、[MCU 固件管理](MCU.md)、[构建](BUILDING.md)。

```cpp
#include <std_disclaimer.h>

/*
 * Your warranty is now void.
 *
 * We are not responsible for bricked devices, dead SD cards,
 * thermonuclear war, or you getting fired because the alarm app failed. Please
 * do some research if you have any concerns about features included in this ROM
 * before flashing it! YOU are choosing to make these modifications, and if
 * you point the finger at us for messing up your device, we will laugh at you.
 *
 */
```
