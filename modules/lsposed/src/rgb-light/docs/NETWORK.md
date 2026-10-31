# 网络灯控

入口：系统设置「炫彩灯」→「网络灯控」。页面按固件安装记录显示灯珠数量：原厂 MCU 为 1，自定义 MCU 为 24。该数量来自安装记录，并非每次读取 MCU 闪存识别得到。

## 协议

| 协议 | 端口 | 数据映射 |
| --- | --- | --- |
| DDP | UDP 4048 | RGB24，目标 ID 1，偏移 0 对应第一颗灯珠 |
| E1.31 / sACN | UDP 5568 | 起始码 0；从所选 Universe 的起始通道开始连续排列 RGB |

sACN 默认 Universe 1、起始通道 1。1 灯珠模式占 3 个通道，起始通道取值 1–510；24 灯珠模式占 72 个通道，起始通道取值 1–441。

## 在 OpenRGB 中使用

添加 DDP 设备，IP 填设置页显示的地址，端口 4048。原厂 MCU 设为 1 LED，自定义 MCU 设为 24 LEDs，保活间隔可设为 1000 ms。

- 支持 DDP RGB 数据类型 0、1、0x0B。
- 对来源相同、序号相同且非零、偏移为 0、覆盖当前灯珠范围、RGB 数据完全一致的 PUSH 包，视为保活，避免静态颜色超时。序号更旧且数据有变化的包会被丢弃。
- 原厂 MCU 也能接收 24 LED 的 DDP 数据，但只取第一组 RGB 控制整条灯。

## 接管与恢复

收到有效数据后，暂停原厂灯效定时器，关闭旧驱动的发送，由 root 专用进程通过 I2C 写入灯珠数据。原厂模式只允许 6 字节帧 `81 01 R G B XOR`，自定义模式只允许 75 字节帧 `81 0C RGB×24 XOR`。两种输出程序都不接受 MCU 升级命令。

- 输出上限约 24 帧/秒，中间帧会被合并。
- DDP 按偏移拼接，在收到 PUSH 时提交；sACN 按 CID、序号和优先级筛选。
- 约 3 秒无有效数据，恢复本地颜色和光效。
- 手动修改颜色、光效、屏幕同色或灯光开关，会退出网络接管。
- 存在未完成的 MCU 刷写记录时，网络输出暂停，需先按 [MCU 固件管理](MCU.md) 检查恢复记录。

## 测试工具

`send_frame.py` 向设备发送测试帧。它会改变灯光，请在能看到灯的位置使用，停止发送后可验证本地光效是否恢复。将 `DEVICE_IP` 换成设置页显示的地址：

```text
python modules/magisk/src/_shared/rgb_native/send_frame.py DEVICE_IP --count 1 --protocol ddp --colors 66FFCC --seconds 10
python modules/magisk/src/_shared/rgb_native/send_frame.py DEVICE_IP --count 24 --protocol sacn --universe 1 --channel 1 --colors FF00FF,00FFFF,FFFF00 --seconds 10
```

## 限制

- 建议使用单播。服务虽然订阅了 sACN 组播，但跨设备组播在测试网络中未验证通过。
- 未实现 sACN 多源 HTP、同步和发现、非零起始码、DDP 查询、RGBW。
- 未做重启恢复和断电故障注入的验证。
- UDP 接收没有身份认证，只在可信的局域网内使用，不要把端口转发到公网。
