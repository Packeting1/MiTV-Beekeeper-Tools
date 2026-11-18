"""Build the Xposed (libxposed API 101) modules without Gradle (javac + D8 + aapt2 + zipalign + apksigner).

usage: python modules/xposed/build.py [module-name ...]     (default: all modules in modules/xposed/src/)

Environment variables (same as app/build.py, plus XPOSED_API):
  ANDROID_JAR   android.jar of API 28
  BUILD_TOOLS   Android build-tools directory (aapt2, zipalign, lib/apksigner.jar)
  R8_JAR        r8.jar (its bundled D8 is used)
  XPOSED_API    libxposed API 101: the api-101.0.0.aar (or its classes.jar) from
                https://repo.maven.apache.org/maven2/io/github/libxposed/api/101.0.0/
  JAVA_HOME     JDK 17+ (sources are compiled with --release 17 and dexed with min-api 26)
  KEYSTORE, KS_PASS, KS_ALIAS   signing key
Each module dir under src/ holds AndroidManifest.xml, scope.txt (one target package per line) and
src/<package path>/HookEntry.java (the entry class; other .java files under src/ are compiled too). Output: modules/xposed/dist/<label>.apk
"""

import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
DIST = ROOT / "dist"

sys.path.insert(0, str(ROOT.parent.parent))
from modules.catalog import load


def need(name):
    v = os.environ.get(name)
    if not v:
        sys.exit("set environment variable %s (see modules/xposed/build.py)" % name)
    return v


ANDROID_JAR = Path(need("ANDROID_JAR"))
BT = Path(need("BUILD_TOOLS"))
R8 = Path(need("R8_JAR"))
API = Path(need("XPOSED_API"))
KS = need("KEYSTORE")
KS_PASS = need("KS_PASS")
KS_ALIAS = need("KS_ALIAS")
JBIN = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None


def exe(base, name):
    for n in (name + ".exe", name):
        if (base / n).exists():
            return base / n
    sys.exit("not found: %s in %s" % (name, base))


def java(name):
    return exe(JBIN, name) if JBIN else name


def run(cmd):
    cmd = [str(c) for c in cmd]
    logged = cmd.copy()
    secrets = [KS_PASS]
    for i, arg in enumerate(cmd[:-1]):
        if arg in ("--ks-pass", "--key-pass"):
            logged[i + 1] = "<redacted>"
            if cmd[i + 1].startswith("pass:"):
                secrets.append(cmd[i + 1][5:])
    print(">", " ".join(logged))
    r = subprocess.run(cmd, capture_output=True, text=True)
    stdout, stderr = r.stdout, r.stderr
    for secret in secrets:
        if secret:
            stdout = stdout.replace(secret, "<redacted>")
            stderr = stderr.replace(secret, "<redacted>")
    print(stdout[-2000:] + "\n" + stderr[-3000:])
    if r.returncode:
        sys.exit("FAILED rc=%d" % r.returncode)


def build(module):
    mod = module["source"]
    manifest_path = mod / "AndroidManifest.xml"
    pkg, label, scope = module["package"], module["label"], module["scopes"]
    entry = mod / "src" / Path(*pkg.split(".")) / "HookEntry.java"
    if not entry.exists():
        sys.exit("missing entry class source: %s" % entry)
    # temp dir outside the source tree; removed even when the build fails
    out = Path(tempfile.mkdtemp(prefix="xposed_"))
    try:
        compile_and_sign(mod, out, manifest_path, pkg, label, scope)
    finally:
        shutil.rmtree(out, ignore_errors=True)


def compile_and_sign(mod, out, manifest_path, pkg, label, scope):
    if API.suffix == ".aar":
        with zipfile.ZipFile(API) as z:
            (out / "api_classes.jar").write_bytes(z.read("classes.jar"))
        api_jar = out / "api_classes.jar"
    else:
        api_jar = API

    cls = out / "classes"
    cls.mkdir()
    sources = sorted((mod / "src").rglob("*.java"))
    run(
        [
            java("javac"),
            "--release",
            "17",
            "-encoding",
            "UTF-8",
            "-Xlint:-options",
            "-cp",
            str(ANDROID_JAR) + os.pathsep + str(api_jar),
            "-d",
            cls,
        ]
        + sources
    )
    mod_jar = out / "module.jar"
    with zipfile.ZipFile(mod_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for p in cls.rglob("*.class"):
            z.write(p, p.relative_to(cls).as_posix())
    dexdir = out / "dex"
    dexdir.mkdir()
    run(
        [
            java("java"),
            "-cp",
            R8,
            "com.android.tools.r8.D8",
            "--release",
            "--min-api",
            "26",
            "--lib",
            ANDROID_JAR,
            "--classpath",
            api_jar,
            "--output",
            dexdir,
            mod_jar,
        ]
    )

    base = out / "base.apk"
    run(
        [
            exe(BT, "aapt2"),
            "link",
            "-o",
            base,
            "-I",
            ANDROID_JAR,
            "--manifest",
            manifest_path,
            "--min-sdk-version",
            "26",
            "--target-sdk-version",
            "28",
        ]
    )
    unsigned = out / "unsigned.apk"
    xposed = {
        "META-INF/xposed/module.prop": "minApiVersion=101\ntargetApiVersion=101\nstaticScope=true\n",
        "META-INF/xposed/scope.list": "\n".join(scope) + "\n",
        "META-INF/xposed/java_init.list": pkg + ".HookEntry\n",
    }
    with zipfile.ZipFile(base) as zin, zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.write(dexdir / "classes.dex", "classes.dex")
        for k, v in xposed.items():
            zout.writestr(k, v)
    aligned = out / "aligned.apk"
    run([exe(BT, "zipalign"), "-f", "4", unsigned, aligned])
    DIST.mkdir(exist_ok=True)
    final = DIST / (label + ".apk")
    apksigner = BT / "lib" / "apksigner.jar"
    run(
        [
            java("java"),
            "-jar",
            apksigner,
            "sign",
            "--ks",
            KS,
            "--ks-pass",
            "env:KS_PASS",
            "--ks-key-alias",
            KS_ALIAS,
            "--v1-signing-enabled",
            "true",
            "--v2-signing-enabled",
            "true",
            "--v4-signing-enabled",
            "false",
            "--out",
            final,
            aligned,
        ]
    )
    run([java("java"), "-jar", apksigner, "verify", "-v", final])
    print("OK", final, final.stat().st_size)


def main(names=None):
    modules = {entry["key"]: entry for entry in load() if entry["kind"] == "xposed"}
    wanted = sys.argv[1:] if names is None else names
    unknown = set(wanted) - set(modules)
    if unknown:
        sys.exit("unknown module: " + ", ".join(sorted(unknown)))
    for key in wanted or sorted(modules):
        build(modules[key])


if __name__ == "__main__":
    main()
