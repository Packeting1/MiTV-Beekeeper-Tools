# 无信号进入音响模式

MiTV beekeeper 的 LSPosed 模块：输入源无信号倒计时结束后，进入小爱音响模式；任意外部输入源恢复时自动亮屏。

下载已签名 APK：[Releases](https://github.com/Rin010/MiTV-NoSignal-AudioMode/releases)。

在已验证的固件上，输入源应用的倒计时调用 `PowerManager.goToSleep(..., 9, 0)`，进入 STR 待机。模块只在 `com.xiaomi.mitv.tvplayer.widget.views.SearchingView$1.run` 调用链中把原因 `9` 改为 `8`，复用系统原生小爱音响模式流程。保留原有倒计时和 HDMI 唤醒设置。

从 1.0.2 起，通过系统 `TvInputManager` 监听所有外部输入的连接状态。任意 HDMI、DP 等外部输入从断开/待机恢复到连接状态时，如果屏幕处于休眠，就请求系统唤醒；不限定原输入端口，也不限定是否由本模块进入音响模式。启动时已有的连接和重复连接通知不触发唤醒。模块不修改手动电源动作，但恢复输入也会唤醒手动进入的音响模式。

预发布版本 `1.0.3-test4` 补充处理电脑不拔线的睡眠/熄屏恢复。原生音响模式会销毁输入会话，导致视频恢复通知停止；模块在音响模式下保留现有 `TvView` 会话，并在窗口 Surface 被关闭时提供一个离屏 Surface。视频缓冲仅丢弃，不读取像素、不保存画面。系统的 `onVideoUnavailable(reason=2)` → `onVideoAvailable` 事件触发系统唤醒，随后释放离屏资源，恢复正常输入画面。普通退出输入源或销毁 Activity 时照常释放会话。

无信号倒计时页面显示“您的设备即将进入音响模式”。按钮顺序为“主页、息屏、待机、关机”；点击新增的“息屏”会立即进入原生小爱音响模式并取消当前倒计时。其余三个按钮的原有点击逻辑保持不变。

恢复唤醒前，通过固件提供的输入映射，把恢复事件对应的 TIF 输入 ID 转成系统输入源编号，同时更新保存源及现有输入页面的恢复 Intent。视频事件使用进入音响模式时记录的当前输入 ID。这样可以避免唤醒后读取到其他端口的旧记录，再次进入无信号倒计时；没有写死 HDMI 端口编号。

此方案全部在输入源应用的 LSPosed 钩子中完成，不使用 Magisk 辅助模块、不读取原始端口时钟、不启动 root 常驻进程。旧时钟辅助模块已经从测试设备移除。代价是黑屏期间保留输入会话，功耗尚未测量。

视频事件只能来自保留的输入会话；其他端口仍使用上述全局连接事件，没有设置端口白名单。未选中端口不拔线睡眠恢复的行为尚未验证，不能把当前 HDMI 测试结果视为所有端口的保证。

## 安装

1. 安装已签名的模块 APK。
2. 在已正常工作的 LSPosed 中启用“无信号进入音响模式”。
3. 作用域只勾选“模拟电视” (`com.xiaomi.mitv.tvplayer`)。
4. 重启输入源应用或重启设备。

恢复原行为：在 LSPosed 中关闭模块，再重启输入源应用；也可以直接卸载模块。

## 验证范围

- 设备：MiTV-MFFU1 / beekeeper（G Pro 27U）。
- 系统：Android 14，HyperOS OS3.0.109.0.UFFMAMN。
- 输入源应用：3.0.38，versionCode 260325。
- 已实测无信号倒计时结束后，系统收到 `reason:8`，发送 `mitv.goto.xiaoai_mode`，并设置 `mitv_power_state=xiaoai_mode`。
- 会话保留方案已通过断输出 75 秒后恢复的 HDMI 测试，先前测试版由用户确认自动亮屏并显示电脑画面。新增“息屏”按钮的顺序、提示文字和点击进入音响模式已通过设备界面结构及运行日志验证。
- `1.0.3-test4` 额外验证了保存源错误指向 DP 的场景：恢复 HDMI 后选择正确输入，系统报告 `Awake`、`usermode` 和有视频信号，持续超过一分钟未再次误入音响模式。最新一轮修复后的实屏画面尚未单独确认，因此保留预发布标记。
- 需要 LSPosed 已注入目标进程。1.0.2 已验证重启注入，本次会话保留补丁的重启行为和其他固件未单独验证。
- 输入源内部类名改变时需要适配；找不到目标类时不会安装钩子，原有行为保留。

## 构建

不使用 Gradle。需要 JDK、Android SDK（含 aapt2、D8、zipalign、apksigner）及 [Xposed API 82](https://api.xposed.info/) 编译依赖。依赖和私钥不包含在仓库中。

```powershell
$env:JAVA_HOME = '<JDK>'
$env:ANDROID_JAR = '<Android SDK>/platforms/android-36/android.jar'
$env:BUILD_TOOLS = '<Android SDK>/build-tools/36.0.0'
$env:XPOSED_API_JAR = '<Xposed API jar>'
$env:KEYSTORE = '<private signing keystore>'
$env:KS_ALIAS = '<key alias>'
# 通过本机安全方式设置 KS_PASS；不要提交私钥或密码。
./build.ps1
```

构建会先运行输入恢复状态转换检查。输出：`dist/TV-NoSignal-AudioMode-1.0.3-test4.apk`。升级安装需要使用与上一版本相同的签名密钥。现有安装包的包名保持为 `io.github.rin.tvnosignalxiaoai`。

## 隐私

模块不申请 Android 权限，不联网、不主动读取文件或设备标识。为检测恢复，使用系统外部输入连接状态、视频可用事件和输入源映射；恢复时会更新系统保存的输入源。离屏视频缓冲仅获取并立即释放，不读取像素、不保存或传输画面。

本地 Android / LSPosed 日志包含模式切换、视频状态、系统输入服务/端口 ID 和源编号；这些 ID 是系统服务与端口标识，不是设备序列号。发布内容不含本机路径、局域网地址、设备序列号、现场截图、原始设备日志、私钥或系统应用 APK。

项目采用 [GPL-3.0](LICENSE)。与 [MiTV-Beekeeper-Tools](https://github.com/Packeting1/MiTV-Beekeeper-Tools) 的其他优化模块互补。
