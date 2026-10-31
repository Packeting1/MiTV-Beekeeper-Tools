"""Compile every Java source tree (app and each LSPosed module) to catch errors before a real build.

Needs ANDROID_JAR (android.jar of API 28) and XPOSED_API (libxposed api-101.0.0.aar or its classes.jar), the same
variables as the build scripts. Without them the check is skipped so contributors need no SDK locally; CI sets both.
JAVA_HOME is optional (javac on PATH is used otherwise).
"""

import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
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
                sorted((ROOT / "app" / "src").rglob("*.java")),
            )
        ]
        for mod in sorted((ROOT / "modules" / "lsposed" / "src").iterdir()):
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
