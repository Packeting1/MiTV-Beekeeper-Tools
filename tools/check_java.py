"""编译应用和每个 Xposed 模块的 Java 源码，在正式构建前发现错误。

需要 ANDROID_JAR（API 28 的 android.jar）和 XPOSED_API（libxposed api-101.0.0.aar 或其 classes.jar），
与构建脚本用的变量相同。未设置时跳过，本地贡献者无需安装 SDK，CI 会设置。
JAVA_HOME 可选，未设置时用 PATH 里的 javac。
"""

import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from modules.catalog import load, write_java


def main():
    entries = load()
    print("ok   metadata (%d records)" % len(entries))
    android = os.environ.get("ANDROID_JAR")
    xposed = os.environ.get("XPOSED_API")
    if not (android and xposed):
        print("skipped: set ANDROID_JAR and XPOSED_API to compile the Java sources")
        return 0
    home = os.environ.get("JAVA_HOME")
    javac = str(Path(home) / "bin" / "javac") if home else "javac"
    with tempfile.TemporaryDirectory(prefix="check_java_") as tmp:
        api = Path(xposed)
        if api.suffix == ".aar":
            with zipfile.ZipFile(api) as z:
                api = Path(tmp) / "api_classes.jar"
                api.write_bytes(z.read("classes.jar"))
        common = [javac, "-encoding", "UTF-8", "-Xlint:-options"]
        jobs = [
            (
                "app",
                common + ["-source", "8", "-target", "8", "-bootclasspath", android],
                sorted((ROOT / "app" / "src").rglob("*.java")) + [write_java(Path(tmp) / "generated", entries)],
            )
        ]
        for entry in sorted((e for e in entries if e["kind"] == "xposed"), key=lambda e: e["key"]):
            mod = entry["source"]
            cp = android + os.pathsep + str(api)
            jobs.append((mod.name, common + ["--release", "17", "-cp", cp], sorted((mod / "src").rglob("*.java"))))
        failed = False
        for name, args, sources in jobs:
            out = Path(tmp) / name
            out.mkdir()
            r = subprocess.run(args + ["-d", str(out)] + [str(s) for s in sources], capture_output=True, text=True)
            print("%s %s (%d files)" % ("FAIL" if r.returncode else "ok  ", name, len(sources)))
            if r.returncode:
                failed = True
                print(r.stdout + r.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
