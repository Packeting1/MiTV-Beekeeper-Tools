#!/usr/bin/env python3
"""把 modules/magisk/src 下的模块源码打成 modules/magisk/dist 里的 zip。

用法：python modules/magisk/build.py [模块目录名 ...]（不带参数＝全部）

- 版本号在各模块 module.prop 的 version / versionCode，zip 文件名跟着它走，旧版本的同名前缀 zip 会被删掉。
- 有 install.sh（legacy）或 customize.sh 的模块会自动加上共用的 META-INF（modules/magisk/src/_shared/META-INF）。
- SHARED 表里的模块自动加上 src/_shared 下对应目录的文件（install.sh、uninstall.sh 放 zip 根，其余放 common/）：
  EDID 类（dv_edid、dp_audio）用 edid_mod 和 edid，配置开关类（hgig_fix 等五个）用 flag_mod。
- 打出来的 zip 内容固定（排序、固定时间戳、固定为 Unix 属性），源码不变则 zip 不变。
"""
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
SRC = ROOT / 'src'
DIST = ROOT / 'dist'
META = SRC / '_shared' / 'META-INF'
STAMP = (1980, 1, 1, 0, 0, 0)
SKIP_DIRS = {'original'}  # 只在重新生成覆盖文件时用，不进 zip
TEXT_SUFFIXES = ('.sh', '.prop', '.conf', 'update-binary')

# 源码目录 -> zip 文件名前缀（后面接版本号）
PLAIN = {
    'adb_keeper': 'Beekeeper_ADB_Keeper_v',
    '4kui_switch': 'Beekeeper_4KUI_Switch_v',
    'dv_edid': 'Beekeeper_LLDV_v',
    'dp_audio': 'Beekeeper_DPAudio_v',
    'freesync_keep': 'Beekeeper_FreeSyncKeep_v',
    'hgig_fix': 'Beekeeper_HGIG_Fix_v',
    'ldm_fix': 'Beekeeper_LDM_Fix_v',
    'zygote64_fix': 'Zygote64_Fix_v',
    'hdr10plus': 'Beekeeper_HDR10Plus_v',
    'imax': 'Beekeeper_IMAX_v',
    'aisr_level': 'Beekeeper_AISR_Level_v',
    'volume_balance': 'Beekeeper_VolumeBalance_v',
    'decorative_light': 'Beekeeper_DecorativeLight_v',
}

# 模块目录 -> 共用的 src/_shared 子目录
SHARED = {
    'dv_edid': ('edid_mod', 'edid'),
    'dp_audio': ('edid_mod', 'edid'),
    'hgig_fix': ('flag_mod',),
    'hdr10plus': ('flag_mod',),
    'imax': ('flag_mod',),
    'aisr_level': ('flag_mod',),
    'volume_balance': ('flag_mod',),
}

# 模块目录 -> {zip 内路径: modules/lsposed/dist 里的 APK}
EXTRA_APK = {
    'decorative_light': {'app/DecorativeLight.apk': ROOT.parent / 'lsposed' / 'dist' / 'DecorativeLight.apk'},
}


def collect(directory):
    """目录里要打进 zip 的文件：{zip 内路径: 字节}。"""
    files = {}
    for path in sorted(directory.rglob('*')):
        rel = path.relative_to(directory)
        if not path.is_file() or path.suffix == '.py' or '__pycache__' in rel.parts or SKIP_DIRS & set(rel.parts):
            continue
        files[rel.as_posix()] = path.read_bytes()
    for shared in SHARED.get(directory.name, ()):
        for path in sorted((SRC / '_shared' / shared).iterdir()):
            if path.is_file() and path.suffix != '.py':
                files[('' if path.name in ('install.sh', 'uninstall.sh') else 'common/') + path.name] = path.read_bytes()
    for name, apk in EXTRA_APK.get(directory.name, {}).items():
        if not apk.is_file():
            sys.exit('missing %s, build it first: python modules/lsposed/build.py' % apk)
        files[name] = apk.read_bytes()
    if 'install.sh' in files or 'customize.sh' in files:
        for path in sorted(META.rglob('*')):
            if path.is_file():
                files['META-INF/' + path.relative_to(META).as_posix()] = path.read_bytes()
    return files


def pack(prefix, files):
    version = re.search(rb'(?m)^version=(.+)$', files['module.prop']).group(1).decode()
    for name, data in files.items():
        if name.endswith(TEXT_SUFFIXES) and b'\r' in data:
            sys.exit('%s: CRLF in %s' % (prefix, name))
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
            info.create_system = 3  # 固定为 Unix，不同主机打出的 zip 才一致
            z.writestr(info, files[name])
    print('OK', out, out.stat().st_size)


def main():
    wanted = set(sys.argv[1:])
    unknown = wanted - set(PLAIN)
    if unknown:
        sys.exit('unknown module: %s (available: %s)' % (', '.join(sorted(unknown)), ', '.join(PLAIN)))
    for directory, prefix in PLAIN.items():
        if not wanted or directory in wanted:
            pack(prefix, collect(SRC / directory))


if __name__ == '__main__':
    main()
