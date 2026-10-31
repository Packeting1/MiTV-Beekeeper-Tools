#!/usr/bin/env python3
"""重新生成装饰灯 RGB 模块（modules/magisk/src/rgb）里的原生部分：bin/、firmware/ 和打补丁后的灯控库。

需要 keystone-engine（pip install -r requirements.txt）和对应系统的原版文件：
  BEEKEEPER_VENDOR_LIB    /vendor/lib64/libbspmserver_config.so，sha256 986aa78c…a992
  BEEKEEPER_MCU_ORIGINAL  /vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/MCU.bin，sha256 c78ea17c…145d
已启用本模块时挂载的灯控库是改过的，不能当作原版输入。输入不匹配会拒绝构建。
生成后用 python modules/magisk/build.py rgb 打包。APK 由 modules/lsposed/build.py rgb-light 生成，放进 rgb/app/RgbLight.apk。
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
BUILD = HERE / 'build'
PAYLOAD = HERE.parent / 'rgb'


def required(name):
    path = Path(os.environ.get(name, '')).expanduser()
    if not path.is_file():
        sys.exit('set %s to an existing file (see the docstring of this script)' % name)
    return path.resolve()


vendor = required('BEEKEEPER_VENDOR_LIB')
os.environ['BEEKEEPER_MCU_ORIGINAL'] = str(required('BEEKEEPER_MCU_ORIGINAL'))
if BUILD.exists():
    shutil.rmtree(BUILD)
for script in ('build_writer.py', 'build_flash.py'):
    subprocess.run([sys.executable, str(HERE / 'native' / script)], check=True)
sys.path.insert(0, str(HERE / 'native'))
from patch_vendor import patch  # noqa: E402

module = BUILD / 'module'
patch(vendor, module / 'system/vendor/lib64/libbspmserver_config.so')
for part in ('bin', 'firmware', 'system'):
    shutil.copytree(module / part, PAYLOAD / part, dirs_exist_ok=True)
print('OK payload updated in', PAYLOAD)
