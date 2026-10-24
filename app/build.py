"""Build the APK without Gradle (javac + R8/D8 + aapt2 + zipalign + apksigner).

Configure with environment variables (see README.md):
  ANDROID_JAR   android.jar of API 28
  BUILD_TOOLS   Android build-tools directory (aapt2, zipalign, lib/apksigner.jar)
  R8_JAR        r8.jar (stable R8 release)
  JAVA_HOME     JDK 8+ (optional if java/javac are on PATH)
  KEYSTORE, KS_PASS, KS_ALIAS   signing key. Updates only install over an older build signed with the same key.
Extra inputs: modules/magisk/dist/*.zip, modules/lsposed/dist/*.apk, and app/bundled/aerial.apk (Aerial launcher, MIT, https://github.com/pengchujin/aerial-tv).
"""
import os, re, shutil, subprocess, sys, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
OUT = ROOT / 'out'


def need(name):
    v = os.environ.get(name)
    if not v:
        sys.exit('set environment variable %s (see README.md)' % name)
    return Path(v)


ANDROID_JAR = need('ANDROID_JAR')
BT = need('BUILD_TOOLS')
R8 = need('R8_JAR')
KS = need('KEYSTORE')
KS_PASS = os.environ.get('KS_PASS') or sys.exit('set KS_PASS')
KS_ALIAS = os.environ.get('KS_ALIAS') or sys.exit('set KS_ALIAS')
JBIN = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None


def exe(base, name):
    for n in (name + '.exe', name):
        if (base / n).exists():
            return base / n
    sys.exit('not found: %s in %s' % (name, base))


def java(name):
    return exe(JBIN, name) if JBIN else name


def run(cmd):
    print('>', ' '.join(str(c) for c in cmd))
    r = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
    print(r.stdout[-2000:], r.stderr[-3000:])
    if r.returncode:
        sys.exit('FAILED rc=%d' % r.returncode)


manifest = (ROOT / 'AndroidManifest.xml').read_text(encoding='utf-8')
version = re.search(r'versionName="([^"]+)"', manifest).group(1)

MODS = {  # asset suffix -> module zip glob
    'hgig': 'Beekeeper_HGIG_Fix_*.zip', 'ldm': 'Beekeeper_LDM_Fix_*.zip', 'zygote': 'Zygote64_Fix_*.zip',
    'adbkeeper': 'MiTV-ADB-Keeper-*.zip', '4kui': 'MiTV-4K-UI-Switch-*.zip',
    'hdr10plus': 'Beekeeper_HDR10Plus_*.zip', 'imax': 'Beekeeper_IMAX_*.zip',
    'aisr': 'Beekeeper_AISR_Level_*.zip', 'volbalance': 'Beekeeper_VolumeBalance_*.zip',
    'dvedid': 'Beekeeper_LLDV_*.zip',
}
aerial = ROOT / 'bundled' / 'aerial.apk'
if not aerial.exists():
    sys.exit('put the Aerial launcher APK at %s' % aerial)

if OUT.exists():
    shutil.rmtree(OUT)
cls = OUT / 'classes'
cls.mkdir(parents=True)
run([java('javac'), '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-Xlint:-options',
     '-bootclasspath', ANDROID_JAR, '-d', cls,
     *sorted((ROOT / 'src' / 'com' / 'beekeeper' / 'slim').glob('*.java'))])

jar = OUT / 'classes.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for p in cls.rglob('*.class'):
        z.write(p, p.relative_to(cls).as_posix())

dexdir = OUT / 'dex'
dexdir.mkdir()
run([java('java'), '-cp', R8, 'com.android.tools.r8.D8', '--release', '--min-api', '21',
     '--lib', ANDROID_JAR, '--output', dexdir, jar])

base = OUT / 'base.apk'
run([exe(BT, 'aapt2'), 'link', '-o', base, '-I', ANDROID_JAR, '--manifest', ROOT / 'AndroidManifest.xml',
     '--min-sdk-version', '21', '--target-sdk-version', '28'])

unsigned = OUT / 'unsigned.apk'
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(dexdir / 'classes.dex', 'classes.dex')
    zout.write(aerial, 'assets/aerial.apk', zipfile.ZIP_STORED)
    for name, apk in (('tvplayerkeep', 'TvPlayerKeep'), ('signalkeep', 'SignalKeep'), ('castpip', 'CastPip'), ('homeref', 'HomeRef')):
        zout.write(REPO / 'modules' / 'lsposed' / 'dist' / (apk + '.apk'), 'assets/apk_%s.apk' % name, zipfile.ZIP_STORED)
    for name, pattern in MODS.items():
        found = sorted((REPO / 'modules' / 'magisk' / 'dist').glob(pattern))
        if len(found) != 1:
            sys.exit('expected exactly one module zip for %s, found %d' % (pattern, len(found)))
        zout.write(found[0], 'assets/mod_%s.zip' % name, zipfile.ZIP_STORED)

aligned = OUT / 'aligned.apk'
run([exe(BT, 'zipalign'), '-f', '4', unsigned, aligned])
dist = REPO / 'dist'
dist.mkdir(exist_ok=True)
final = dist / ('MiTV-Beekeeper-Tools-v%s.apk' % version)
run([java('java'), '-jar', BT / 'lib' / 'apksigner.jar', 'sign', '--ks', KS, '--ks-pass', 'pass:' + KS_PASS,
     '--ks-key-alias', KS_ALIAS, '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
     '--out', final, aligned])
run([java('java'), '-jar', BT / 'lib' / 'apksigner.jar', 'verify', '-v', final])
print('OK', final, final.stat().st_size)
