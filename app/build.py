"""Build the APK without Gradle (javac + R8/D8 + aapt2 + zipalign + apksigner).

Configure with environment variables (see README.md):
  ANDROID_JAR   android.jar of API 28
  BUILD_TOOLS   Android build-tools directory (aapt2, zipalign, lib/apksigner.jar)
  R8_JAR        r8.jar (stable R8 release)
  JAVA_HOME     JDK 8+ (optional if java/javac are on PATH)
  KEYSTORE, KS_PASS, KS_ALIAS   signing key. Updates only install over an older build signed with the same key.
Extra inputs: modules/magisk/dist/*.zip, modules/xposed/dist/*.apk, app/bundled/aerial.apk (Aerial launcher, MIT, https://github.com/pengchujin/aerial-tv) and app/bundled/vector.zip (Vector v2.2, GPL-3.0, https://github.com/JingMatrix/Vector); both are downloaded and verified automatically if missing.
"""

import os, re, shutil, subprocess, sys, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
OUT = ROOT / "out"
DIST = REPO / "dist"
BUNDLED = ROOT / "bundled"
XPOSED_DIST = REPO / "modules/xposed/dist"
MAGISK_DIST = REPO / "modules/magisk/dist"

sys.path.insert(0, str(REPO))
from modules.catalog import app_assets, load, write_java


def need(name):
    v = os.environ.get(name)
    if not v:
        sys.exit("set environment variable %s (see README.md)" % name)
    return Path(v)


ANDROID_JAR = need("ANDROID_JAR")
BT = need("BUILD_TOOLS")
R8 = need("R8_JAR")
KS = need("KEYSTORE")
KS_PASS = os.environ.get("KS_PASS") or sys.exit("set KS_PASS")
KS_ALIAS = os.environ.get("KS_ALIAS") or sys.exit("set KS_ALIAS")
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


AERIAL_REPO = "pengchujin/aerial-tv"
AERIAL_ASSET = "aerial-standard.apk"
GH_MIRRORS = [
    "https://fastgit.cc/",
    "https://ghfast.top/",
    "https://gh-proxy.com/",
    "https://ghproxy.net/",
]


def fetch_asset(dest, repo, asset_name, tag=None, sha256=None):
    """Download a release asset into app/bundled/ (latest release unless tag is given) and verify its SHA-256 (GitHub's digest unless sha256 is given)."""
    import hashlib, json, urllib.request

    def get(url):
        req = urllib.request.Request(url, headers={"User-Agent": "MiTV-Beekeeper-Tools-build"})
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()

    api = "https://api.github.com/repos/%s/releases/%s" % (repo, "tags/" + tag if tag else "latest")
    try:
        rel = json.loads(get(api))
        asset = next(a for a in rel["assets"] if a["name"] == asset_name)
        digest = sha256 or asset["digest"].split(":", 1)[1].lower()
        url = asset["browser_download_url"]
    except Exception as e:
        sys.exit(
            "could not query %s (%s); download %s from the %s releases page and put it at %s"
            % (api, e, asset_name, repo, dest)
        )
    print(repo, rel["tag_name"])
    dest.parent.mkdir(exist_ok=True)
    last = None
    for cand in [url] + [m + url for m in GH_MIRRORS]:
        print("downloading", cand)
        try:
            data = get(cand)
            if hashlib.sha256(data).hexdigest() != digest:
                raise ValueError("SHA-256 mismatch")
            tmp = dest.with_suffix(".part")
            tmp.write_bytes(data)
            tmp.replace(dest)
            return
        except Exception as e:
            last = e
            print("  failed:", e)
    sys.exit("could not download %s (%s); download %s manually and put it at %s" % (repo, last, url, dest))


def main():
    entries = load()
    aerial = BUNDLED / "aerial.apk"
    if not aerial.exists():
        fetch_asset(aerial, AERIAL_REPO, AERIAL_ASSET)
    for entry in entries:
        if entry.get("download"):
            dest = BUNDLED / entry["bundled"]
            if not dest.exists():
                fetch_asset(dest, entry["download"]["repo"], entry["download"]["asset"], entry["download"].get("tag"))
    assets = app_assets(entries, XPOSED_DIST, MAGISK_DIST, BUNDLED)
    manifest = (ROOT / "AndroidManifest.xml").read_text(encoding="utf-8")
    version = re.search(r'versionName="([^"]+)"', manifest).group(1)

    if OUT.exists():
        shutil.rmtree(OUT)
    cls = OUT / "classes"
    cls.mkdir(parents=True)
    generated = write_java(OUT / "generated", entries)
    run(
        [
            java("javac"),
            "-source",
            "8",
            "-target",
            "8",
            "-encoding",
            "UTF-8",
            "-Xlint:-options",
            "-bootclasspath",
            ANDROID_JAR,
            "-d",
            cls,
            *sorted((ROOT / "src" / "com" / "beekeeper" / "slim").glob("*.java")),
            generated,
        ]
    )

    jar = OUT / "classes.jar"
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
        for p in cls.rglob("*.class"):
            z.write(p, p.relative_to(cls).as_posix())

    dexdir = OUT / "dex"
    dexdir.mkdir()
    run(
        [
            java("java"),
            "-cp",
            R8,
            "com.android.tools.r8.D8",
            "--release",
            "--min-api",
            "21",
            "--lib",
            ANDROID_JAR,
            "--output",
            dexdir,
            jar,
        ]
    )

    base = OUT / "base.apk"
    run(
        [
            exe(BT, "aapt2"),
            "link",
            "-o",
            base,
            "-I",
            ANDROID_JAR,
            "--manifest",
            ROOT / "AndroidManifest.xml",
            "--min-sdk-version",
            "21",
            "--target-sdk-version",
            "28",
        ]
    )

    unsigned = OUT / "unsigned.apk"
    with zipfile.ZipFile(base) as zin, zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.write(dexdir / "classes.dex", "classes.dex")
        zout.write(aerial, "assets/aerial.apk", zipfile.ZIP_STORED)
        for path, asset in assets:
            zout.write(path, "assets/" + asset, zipfile.ZIP_STORED)

    aligned = OUT / "aligned.apk"
    run([exe(BT, "zipalign"), "-f", "4", unsigned, aligned])
    DIST.mkdir(exist_ok=True)
    final = DIST / ("MiTV-Beekeeper-Tools-v%s.apk" % version)
    run(
        [
            java("java"),
            "-jar",
            BT / "lib" / "apksigner.jar",
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
            "--out",
            final,
            aligned,
        ]
    )
    run([java("java"), "-jar", BT / "lib" / "apksigner.jar", "verify", "-v", final])
    print("OK", final, final.stat().st_size)


if __name__ == "__main__":
    main()
