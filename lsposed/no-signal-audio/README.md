# 无信号进入音响模式

MiTV beekeeper 的 LSPosed 模块：输入源无信号倒计时结束后，进入小爱音响模式。

在已验证的固件上，输入源应用的倒计时调用 `PowerManager.goToSleep(..., 9, 0)`，进入 STR 待机。模块只在 `com.xiaomi.mitv.tvplayer.widget.views.SearchingView$1.run` 调用链中把原因 `9` 改为 `8`，复用系统原生小爱音响模式流程。保留原有倒计时和 HDMI 唤醒设置；手动待机、关机及其他调用链不做修改。

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
- 需要 LSPosed 已注入目标进程。冷启动加载情况和其他固件版本未单独验证。
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

输出：`dist/TV-NoSignal-AudioMode-1.0.1.apk`。升级安装需要使用与上一版本相同的签名密钥。现有安装包的包名保持为 `io.github.rin.tvnosignalxiaoai`。

## 隐私

模块不申请 Android 权限，不联网、不读取文件或设备标识。仅向本地 Android / LSPosed 日志写入安装状态和固定的原因转换提示。发布内容不含本机路径、局域网地址、设备序列号、现场截图、原始设备日志、私钥或系统应用 APK。

项目采用 [GPL-3.0](../../LICENSE)。与 [MiTV-Beekeeper-Tools](https://github.com/Packeting1/MiTV-Beekeeper-Tools) 的其他优化模块互补。
