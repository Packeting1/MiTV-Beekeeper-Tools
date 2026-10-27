#!/usr/bin/env python3
"""把 modules/magisk/src 下的模块源码打成 modules/magisk/dist 里的 zip。

用法：python modules/magisk/build.py [模块目录名 ...]（不带参数＝全部）

- 版本号在各模块 module.prop 的 version / versionCode，zip 文件名跟着它走，旧版本的同名前缀 zip 会被删掉。
- 有 install.sh 的 legacy 模块会自动加上共用的 META-INF（modules/magisk/META-INF）。
- 打出来的 zip 内容固定（排序、固定时间戳），源码不变则 zip 不变。
"""
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
SRC = ROOT / 'src'
DIST = ROOT / 'dist'
META = ROOT / 'META-INF'
STAMP = (1980, 1, 1, 0, 0, 0)

# 源码目录 -> zip 文件名前缀（后面接版本号）
PLAIN = {
    'adb_keeper': 'Beekeeper_ADB_Keeper_v',
    '4kui_switch': 'Beekeeper_4KUI_Switch_v',
    'dv_edid': 'Beekeeper_LLDV_v',
    'freesync_keep': 'Beekeeper_FreeSyncKeep_v',
    'hgig_fix': 'Beekeeper_HGIG_Fix_v',
    'ldm_fix': 'Beekeeper_LDM_Fix_v',
    'zygote64_fix': 'Zygote64_Fix_v',
    'hdr10plus': 'Beekeeper_HDR10Plus_v',
    'imax': 'Beekeeper_IMAX_v',
    'aisr_level': 'Beekeeper_AISR_Level_v',
    'volume_balance': 'Beekeeper_VolumeBalance_v',
}


def collect(directory):
    """目录里要打进 zip 的文件：{zip 内路径: 字节}。"""
    files = {}
    for path in sorted(directory.rglob('*')):
        if not path.is_file() or path.suffix == '.py' or '__pycache__' in path.parts:
            continue
        files[path.relative_to(directory).as_posix()] = path.read_bytes()
    if 'install.sh' in files:
        for path in sorted(META.rglob('*')):
            if path.is_file():
                files['META-INF/' + path.relative_to(META).as_posix()] = path.read_bytes()
    return files


def pack(prefix, files):
    version = re.search(rb'(?m)^version=(.+?)\r?$', files['module.prop']).group(1).decode()
    out = DIST / ('%s%s.zip' % (prefix, version))
    DIST.mkdir(exist_ok=True)
    for old in DIST.glob(prefix + '*.zip'):
        old.unlink()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in sorted(files, key=lambda n: (n != 'module.prop', n)):
            info = zipfile.ZipInfo(name, STAMP)
            executable = name.endswith('.sh') or name.endswith('update-binary')
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, files[name])
    print('OK', out, out.stat().st_size)


def main():
    wanted = set(sys.argv[1:])
    for directory, prefix in PLAIN.items():
        if not wanted or directory in wanted:
            pack(prefix, collect(SRC / directory))


if __name__ == '__main__':
    main()
