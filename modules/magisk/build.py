#!/usr/bin/env python3
"""把 modules/magisk/src 下的模块源码打成 modules/magisk/dist 里的 zip。

用法：python modules/magisk/build.py [模块目录名 ...]（不带参数＝全部）

- 版本号在各模块 module.prop 的 version / versionCode，zip 文件名跟着它走，旧版本的同名前缀 zip 会被删掉。
- 有 install.sh（legacy）或 customize.sh 的模块会自动加上共用的 META-INF（modules/magisk/src/_shared/META-INF）。
- 共用文件和内置 APK 的关系见 modules/catalog.json。
- 打出来的 zip 内容固定（排序、固定时间戳、固定为 Unix 属性），源码不变则 zip 不变。
"""

import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent
SRC = ROOT / "src"
DIST = ROOT / "dist"
META = SRC / "_shared" / "META-INF"
STAMP = (1980, 1, 1, 0, 0, 0)
SKIP_DIRS = {"original"}  # 只在重新生成覆盖文件时用，不进 zip
TEXT_SUFFIXES = (".sh", ".prop", ".conf", "update-binary")

sys.path.insert(0, str(ROOT.parent.parent))
from modules.catalog import load

MODULES = {entry["key"]: entry for entry in load()}
XPOSED_DIST = ROOT.parent / "xposed" / "dist"


def collect(directory):
    """目录里要打进 zip 的文件：{zip 内路径: 字节}。"""
    files = {}
    module = MODULES[directory.name]
    for path in sorted(directory.rglob("*")):
        rel = path.relative_to(directory)
        if not path.is_file() or path.suffix == ".py" or "__pycache__" in rel.parts or SKIP_DIRS & set(rel.parts):
            continue
        files[rel.as_posix()] = path.read_bytes()
    for shared in module.get("shared", ()):
        for path in sorted((SRC / "_shared" / shared).iterdir()):
            if path.is_file() and path.suffix != ".py":
                files[("" if path.name in ("install.sh", "uninstall.sh") else "common/") + path.name] = (
                    path.read_bytes()
                )
    for name, key in module.get("embed", {}).items():
        apk = XPOSED_DIST / (MODULES[key]["label"] + ".apk")
        if not apk.is_file():
            sys.exit("missing %s, build it first: python modules/xposed/build.py" % apk)
        files[name] = apk.read_bytes()
    if "install.sh" in files or "customize.sh" in files:
        for path in sorted(META.rglob("*")):
            if path.is_file():
                files["META-INF/" + path.relative_to(META).as_posix()] = path.read_bytes()
    return files


def pack(prefix, files):
    version = re.search(rb"(?m)^version=(.+)$", files["module.prop"]).group(1).decode()
    for name, data in files.items():
        if name.endswith(TEXT_SUFFIXES) and b"\r" in data:
            sys.exit("%s: CRLF in %s" % (prefix, name))
    out = DIST / ("%s%s.zip" % (prefix, version))
    DIST.mkdir(exist_ok=True)
    for old in DIST.glob(prefix + "*.zip"):
        old.unlink()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(files, key=lambda n: (n != "module.prop", n)):
            info = zipfile.ZipInfo(name, STAMP)
            executable = name.endswith((".sh", "update-binary"))
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3  # 固定为 Unix，不同主机打出的 zip 才一致
            z.writestr(info, files[name])
    print("OK", out, out.stat().st_size)


def main(names=None):
    wanted = set(sys.argv[1:] if names is None else names)
    modules = {key: entry for key, entry in MODULES.items() if entry["kind"] == "magisk"}
    unknown = wanted - set(modules)
    if unknown:
        sys.exit("unknown module: %s (available: %s)" % (", ".join(sorted(unknown)), ", ".join(modules)))
    for directory, entry in modules.items():
        if not wanted or directory in wanted:
            pack(entry["archive_prefix"], collect(SRC / directory))


if __name__ == "__main__":
    main()
