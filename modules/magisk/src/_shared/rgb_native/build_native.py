#!/usr/bin/env python3
"""重新生成装饰灯 RGB 模块（modules/magisk/src/decorative_light）里的原生部分：bin/ 和 firmware/。

需要 keystone-engine（pip install -r requirements.txt）和对应系统的原版 MCU.bin：
  BEEKEEPER_MCU_ORIGINAL  /vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/MCU.bin，sha256 c78ea17c…145d
输入不匹配会拒绝构建。灯控库的补丁在 decorative_light/customize.sh 里，安装时在设备上生成。
生成后用 python modules/magisk/build.py decorative_light 打包。APK 由 modules/lsposed/build.py decorative-light 生成，放进 decorative_light/app/DecorativeLight.apk。
"""

import hashlib
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "native"))
import i2c  # noqa: E402
import om2_firmware  # noqa: E402
import writer  # noqa: E402

PAYLOAD = HERE.parent.parent / "decorative_light"
PIXELS_SHA256 = "69f4c469f391dabeef52489d01b3c41fbe7fe16297b4ebea144e0afdef793b5f"


def required(name):
    path = Path(os.environ.get(name, "")).expanduser()
    if not path.is_file():
        sys.exit("set %s to an existing file (see the docstring of this script)" % name)
    return path.resolve()


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


original = required("BEEKEEPER_MCU_ORIGINAL").read_bytes()
pixels = om2_firmware.pixel_firmware(original)
if hashlib.sha256(pixels).hexdigest() != PIXELS_SHA256:
    sys.exit("24 灯珠固件与已审查的 SHA-256 不一致")
write(PAYLOAD / "firmware/pixels.patch", om2_firmware.patch_text(original).encode("ascii"))
for name, binary in {**writer.build(), **i2c.build()}.items():
    write(PAYLOAD / "bin" / name, binary)
    if name.startswith("om2-flash"):
        write(PAYLOAD / "firmware" / (name + ".sha256"), (hashlib.sha256(binary).hexdigest() + "\n").encode("ascii"))
print("OK payload updated in", PAYLOAD)
