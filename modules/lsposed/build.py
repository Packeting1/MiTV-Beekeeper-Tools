"""Build the LSPosed/Vector (libxposed API 101) modules without Gradle (javac + D8 + aapt2 + zipalign + apksigner).

usage: python modules/lsposed/build.py [module-name ...]     (default: all modules in modules/lsposed/src/)

Environment variables (same as app/build.py, plus XPOSED_API):
  ANDROID_JAR   android.jar of API 28
  BUILD_TOOLS   Android build-tools directory (aapt2, zipalign, lib/apksigner.jar)
  R8_JAR        r8.jar (its bundled D8 is used)
  XPOSED_API    libxposed API 101: the api-101.0.0.aar (or its classes.jar) from
                https://repo.maven.apache.org/maven2/io/github/libxposed/api/101.0.0/
  JAVA_HOME     JDK 17+
  KEYSTORE, KS_PASS, KS_ALIAS   signing key
Each module dir under src/ holds AndroidManifest.xml, scope.txt (one target package per line) and
src/<package path>/HookEntry.java (the entry class; other .java files under src/ are compiled too). Output: modules/lsposed/dist/<label>.apk
"""
import os, re, shutil, subprocess, sys, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / 'src'
DIST = ROOT / 'dist'


def need(name):
    v = os.environ.get(name)
    if not v:
        sys.exit('set environment variable %s (see modules/lsposed/build.py)' % name)
    return v


ANDROID_JAR = Path(need('ANDROID_JAR'))
BT = Path(need('BUILD_TOOLS'))
R8 = Path(need('R8_JAR'))
API = Path(need('XPOSED_API'))
KS = need('KEYSTORE')
KS_PASS = need('KS_PASS')
KS_ALIAS = need('KS_ALIAS')
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


def build(mod):
    manifest_path = mod / 'AndroidManifest.xml'
    manifest = manifest_path.read_text(encoding='utf-8')
    pkg = re.search(r'package="([^"]+)"', manifest).group(1)
    label = re.search(r'android:label="([^"]+)"', manifest).group(1)
    scope = [s.strip() for s in (mod / 'scope.txt').read_text(encoding='utf-8').splitlines() if s.strip()]
    out = mod / 'out'
    if out.exists():
        shutil.rmtree(out)
    out.mkdir()

    if API.suffix == '.aar':
        with zipfile.ZipFile(API) as z:
            (out / 'api_classes.jar').write_bytes(z.read('classes.jar'))
        api_jar = out / 'api_classes.jar'
    else:
        api_jar = API

    cls = out / 'classes'
    cls.mkdir()
    sources = sorted((mod / 'src').rglob('*.java'))
    run([java('javac'), '--release', '17', '-encoding', 'UTF-8', '-Xlint:-options',
         '-cp', str(ANDROID_JAR) + os.pathsep + str(api_jar), '-d', cls] + sources)
    mod_jar = out / 'module.jar'
    with zipfile.ZipFile(mod_jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in cls.rglob('*.class'):
            z.write(p, p.relative_to(cls).as_posix())
    dexdir = out / 'dex'
    dexdir.mkdir()
    run([java('java'), '-cp', R8, 'com.android.tools.r8.D8', '--release', '--min-api', '26',
         '--lib', ANDROID_JAR, '--classpath', api_jar, '--output', dexdir, mod_jar])

    base = out / 'base.apk'
    run([exe(BT, 'aapt2'), 'link', '-o', base, '-I', ANDROID_JAR, '--manifest', manifest_path,
         '--min-sdk-version', '26', '--target-sdk-version', '28'])
    unsigned = out / 'unsigned.apk'
    xposed = {
        'META-INF/xposed/module.prop': 'minApiVersion=101\ntargetApiVersion=101\nstaticScope=true\n',
        'META-INF/xposed/scope.list': '\n'.join(scope) + '\n',
        'META-INF/xposed/java_init.list': pkg + '.HookEntry\n',
    }
    with zipfile.ZipFile(base) as zin, zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.write(dexdir / 'classes.dex', 'classes.dex')
        for k, v in xposed.items():
            zout.writestr(k, v)
    aligned = out / 'aligned.apk'
    run([exe(BT, 'zipalign'), '-f', '4', unsigned, aligned])
    DIST.mkdir(exist_ok=True)
    final = DIST / (label + '.apk')
    apksigner = BT / 'lib' / 'apksigner.jar'
    run([java('java'), '-jar', apksigner, 'sign', '--ks', KS, '--ks-pass', 'pass:' + KS_PASS,
         '--ks-key-alias', KS_ALIAS, '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
         '--out', final, aligned])
    run([java('java'), '-jar', apksigner, 'verify', '-v', final])
    shutil.rmtree(out)
    print('OK', final, final.stat().st_size)


mods = [SRC / m for m in sys.argv[1:]] or sorted(p for p in SRC.iterdir() if (p / 'AndroidManifest.xml').exists())
for m in mods:
    build(m)
