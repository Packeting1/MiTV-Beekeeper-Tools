# 构建

需要 Python 3.9+、支持 `--release 8` 的 JDK、Android SDK 平台 36 与 Build Tools（含 aapt2、d8、zipalign、apksigner），以及 Xposed API 82 JAR。

```text
python -m pip install -r requirements-build.txt
```

通过环境变量指定本地依赖，不需要修改源码：

| 环境变量 | 内容 |
| --- | --- |
| `JAVA_HOME` | JDK 根目录；未设置时尝试 PATH 中的 javac |
| `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` | Android SDK 根目录 |
| `ANDROID_API` | 可选，默认 36 |
| `ANDROID_BUILD_TOOLS_VERSION` | 可选，默认选取已安装的最高版本 |
| `XPOSED_API_JAR` | 本地 Xposed API 82 JAR 路径 |
| `BEEKEEPER_VENDOR_LIB` | 对应系统的原版 `libbspmserver_config.so` |
| `BEEKEEPER_MCU_ORIGINAL` | 对应系统的原版 MCU 应用镜像 |
| `BEEKEEPER_KEYSTORE` | 可选，私有签名密钥库路径 |
| `BEEKEEPER_SIGNING_PASSWORD` | 可选，密钥库及私钥密码；本地调试默认为 android |
| `BEEKEEPER_KEY_ALIAS` | 可选，默认 beekeeper-rgb |

厂商输入文件需从对应原版系统镜像或设备获取，不包含个人数据。若已启用本模块，挂载中的灯控库已经修改，不能将它当作原版输入。

| 输入 | SHA-256 | 原版系统中的位置 |
| --- | --- | --- |
| 灯控库 | `986aa78c1761d22142463183b69db729f40be10ba45485e9aca68e2d5972a992` | `/vendor/lib64/libbspmserver_config.so` |
| MCU 应用镜像 | `c78ea17cfe2c5278964e4cb57232db19fca1414b4fc5dcdb1df89faf8376145d` | `/vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/MCU.bin` |

输入不匹配时会拒绝构建。原版二进制文件不存入源码仓库，发布安装包包含匹配的运行时镜像与修改后的灯控库。

```text
python build.py
```

在模块目录运行上述命令，或从仓库根目录运行 `python modules/beekeeper_rgb/build.py`。生成模块内 `dist/` 中的 APK 和 ZIP，并同步 `modules/magisk/src/rgb/` 的编译文件、打包为 `modules/magisk/dist/Beekeeper_RGB_v1.4.1.zip`，供工具应用内置。构建会清理模块内固定的 `build/` 目录，运行协议单元测试并检查 APK 签名。

不指定私有密钥时会在被 Git 忽略的 `.signing/` 生成本地调试密钥。使用不同密钥签名的 APK 不能直接覆盖已安装版本。不要提交签名密钥或密码文件。

`native/native-patch.json` 描述灯控库的固定字节替换，构建时同时校验原始字节和目标 SHA-256。MCU 补丁生成器仅生成镜像，不连接设备；刷写逻辑在 Magisk 后台服务中。

`tests/` 保留颜色解析、DDP/sACN、固定帧长度、24 地址编码、MCU 版本与镜像校验、等待时间和逐块失败停止测试，不包含设备日志或实机记录。
