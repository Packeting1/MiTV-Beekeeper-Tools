"""用系统自带的 csc.exe 编译 MiTvMic-Installer-x64.exe（.NET Framework 4.8 + WPF，不需要装任何 SDK）。

usage: python pc/build.py
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "MiTvMic"
# VB-CABLE 官方驱动包，原样内置进 exe（缺驱动时解压并运行原版安装程序）
VENDOR = ROOT / "vendor" / "VBCABLE_Driver_Pack46.zip"
DIST = ROOT / "dist"

CSC = Path(r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe")
WPF = CSC.parent / "WPF"
GAC = Path(r"C:\Windows\Microsoft.NET\assembly\GAC_MSIL")

# System.Xaml 等只在 GAC 里，其余在框架目录
REFS = (
    "PresentationFramework",
    "PresentationCore",
    "WindowsBase",
    "System.Xaml",
    "System.IO.Compression.FileSystem",
    "System.Core",
    "System",
)


def ref(name):
    for p in (CSC.parent / (name + ".dll"), WPF / (name + ".dll")):
        if p.exists():
            return str(p)
    hits = sorted(GAC.glob(name + "/*/*/" + name + ".dll"))
    if hits:
        return str(hits[-1])
    sys.exit("找不到 %s.dll" % name)


def main():
    if not CSC.exists():
        sys.exit("找不到 csc.exe：%s" % CSC)
    if not VENDOR.exists():
        sys.exit("找不到 VB-CABLE 驱动包：%s" % VENDOR)

    DIST.mkdir(exist_ok=True)
    build("x64")


def build(arch):
    out = DIST / ("MiTvMic-Installer-%s.exe" % arch)
    args = [
        str(CSC),
        "/nologo",
        "/target:winexe",
        "/platform:" + arch,
        "/optimize+",
        "/codepage:65001",
        "/out:" + str(out),
        "/win32manifest:" + str(SRC / "app.manifest"),
        "/win32icon:" + str(SRC / "MiTvMic.ico"),
        "/resource:" + str(SRC / "Ui.xaml"),
        "/resource:" + str(SRC / "Dialog.xaml"),
        "/resource:%s,VBCABLE_Driver_Pack.zip" % VENDOR,
    ]
    args += ["/r:" + ref(n) for n in REFS]
    args += sorted(str(p) for p in SRC.glob("*.cs"))

    r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    print((r.stdout or "") + (r.stderr or ""))
    if r.returncode:
        sys.exit("FAILED %s rc=%d" % (arch, r.returncode))
    print("OK", out, out.stat().st_size)


if __name__ == "__main__":
    main()
