"""Build the APK and complete Magisk module using explicit local toolchains and vendor inputs."""
from pathlib import Path
import hashlib,os,re,shutil,subprocess,sys,zipfile
import xml.etree.ElementTree as ET
if hasattr(sys.stdout,'reconfigure'):sys.stdout.reconfigure(encoding='utf8',errors='replace')
ROOT=Path(__file__).resolve().parent
BUILD=ROOT/'build';OUTPUT=ROOT/'dist'
VERSION=ET.parse(ROOT/'AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')

def required(name):
    value=os.environ.get(name)
    if not value:raise RuntimeError('Set '+name+'; see BUILDING.md')
    path=Path(value).expanduser().resolve()
    if not path.exists():raise FileNotFoundError(name+' does not exist')
    return path

sdk_name='ANDROID_SDK_ROOT' if os.environ.get('ANDROID_SDK_ROOT') else 'ANDROID_HOME'
SDK=required(sdk_name)
JDK=Path(os.environ['JAVA_HOME']).expanduser().resolve() if os.environ.get('JAVA_HOME') else Path(shutil.which('javac') or '').resolve().parent.parent
suffix='.exe' if os.name=='nt' else ''
JAVA=JDK/'bin'/('java'+suffix);JAVAC=JDK/'bin'/('javac'+suffix)
if not JAVA.is_file() or not JAVAC.is_file():raise RuntimeError('Install a JDK and set JAVA_HOME')
version=os.environ.get('ANDROID_BUILD_TOOLS_VERSION')
BUILD_TOOLS=SDK/'build-tools'/version if version else max((SDK/'build-tools').iterdir(),key=lambda p:tuple(int(n) for n in re.findall(r'\d+',p.name)))
ANDROID=SDK/'platforms'/('android-'+os.environ.get('ANDROID_API','36'))/'android.jar'
XPOSED=required('XPOSED_API_JAR')
VENDOR=required('BEEKEEPER_VENDOR_LIB')
os.environ['BEEKEEPER_MCU_ORIGINAL']=str(required('BEEKEEPER_MCU_ORIGINAL'))
if not ANDROID.is_file():raise RuntimeError('Install the requested Android SDK platform')

def run(args):
    if Path(str(args[0])).name.lower() in ('javac','javac.exe'):args=[args[0],'-J-Duser.language=en','-J-Duser.country=US',*args[1:]]
    result=subprocess.run([str(x) for x in args],cwd=ROOT,capture_output=True)
    output=(result.stdout+result.stderr).decode('utf8',errors='replace')
    if output:print(output,flush=True)
    if result.returncode:raise RuntimeError('Build tool failed: '+Path(str(args[0])).name)

# The only recursive cleanup is the fixed build directory inside this source checkout.
if BUILD.exists():
    resolved=BUILD.resolve()
    if BUILD.is_symlink() or resolved.parent!=ROOT or resolved.name!='build':raise RuntimeError('Unsafe build path')
    shutil.rmtree(resolved)
for name in ('classes','dex','test-classes','native'):(BUILD/name).mkdir(parents=True,exist_ok=True)
OUTPUT.mkdir(exist_ok=True)
module=BUILD/'module';shutil.copytree(ROOT/'magisk',module)
run([sys.executable,ROOT/'native/build_writer.py'])
run([sys.executable,ROOT/'native/build_flash.py'])
sys.path.insert(0,str(ROOT/'native'))
from patch_vendor import patch
patch(VENDOR,module/'system/vendor/lib64/libbspmserver_config.so')
shutil.copy2(ROOT/'native/native-patch.json',module/'native-patch.json')

src=ROOT/'src/io/github/rin/beekeeper/rgb'
for test,units in [
    ('RgbColorTest',['RgbColor']),('NetworkPacketTest',['NetworkPacket']),
    ('NetworkLimitsTest',['NetworkLimits']),('Om2FrameTest',['NetworkLimits','Om2Frame']),
    ('SingleFrameTest',['SingleFrame']),('FramePacerTest',['FramePacer']),
    ('McuTest',['McuPolicy','McuProtocol'])]:
    run([JAVAC,'--release','8','-encoding','UTF-8','-d',BUILD/'test-classes',*[src/(n+'.java') for n in units],ROOT/'tests'/(test+'.java')])
    args=[JAVA,'-cp',BUILD/'test-classes',test]
    if test=='McuTest':args.extend([module/'firmware/original.bin',module/'firmware/pixels.bin'])
    run(args)
run([JAVAC,'--release','8','-encoding','UTF-8','-cp',str(ANDROID)+os.pathsep+str(XPOSED),'-d',BUILD/'classes',*sorted(ROOT.glob('src/**/*.java'))])
jar=BUILD/'classes.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as archive:
    for p in sorted((BUILD/'classes').rglob('*.class')):archive.write(p,p.relative_to(BUILD/'classes').as_posix())
run([JAVA,'-cp',BUILD_TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--min-api','28','--lib',ANDROID,'--classpath',XPOSED,'--output',BUILD/'dex',jar])
run([BUILD_TOOLS/('aapt2'+suffix),'compile','--dir',ROOT/'res','-o',BUILD/'resources.zip'])
run([BUILD_TOOLS/('aapt2'+suffix),'link','-I',ANDROID,'--manifest',ROOT/'AndroidManifest.xml','-o',BUILD/'unsigned.apk',BUILD/'resources.zip'])
with zipfile.ZipFile(BUILD/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as archive:
    archive.write(BUILD/'dex/classes.dex','classes.dex')
    for p in sorted((ROOT/'assets').rglob('*')):
        if p.is_file():archive.write(p,'assets/'+p.relative_to(ROOT/'assets').as_posix())
os.environ.setdefault('BEEKEEPER_SIGNING_PASSWORD','android')
key=Path(os.environ['BEEKEEPER_KEYSTORE']).expanduser().resolve() if os.environ.get('BEEKEEPER_KEYSTORE') else ROOT/'.signing/debug.p12'
alias=os.environ.get('BEEKEEPER_KEY_ALIAS','beekeeper-rgb')
if not key.exists():
    if os.environ.get('BEEKEEPER_KEYSTORE'):raise FileNotFoundError('Signing keystore does not exist')
    key.parent.mkdir(exist_ok=True)
    run([JDK/'bin'/('keytool'+suffix),'-genkeypair','-keystore',key,'-storetype','PKCS12','-storepass:env','BEEKEEPER_SIGNING_PASSWORD','-keypass:env','BEEKEEPER_SIGNING_PASSWORD','-alias',alias,'-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Beekeeper RGB'])
run([BUILD_TOOLS/('zipalign'+suffix),'-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk'])
apk=OUTPUT/('Beekeeper-RGB-v'+VERSION+'.apk')
run([JAVA,'-jar',BUILD_TOOLS/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias',alias,'--ks-pass','env:BEEKEEPER_SIGNING_PASSWORD','--key-pass','env:BEEKEEPER_SIGNING_PASSWORD','--out',apk,BUILD/'aligned.apk'])
run([JAVA,'-jar',BUILD_TOOLS/'lib/apksigner.jar','verify','--verbose',apk])
(module/'app').mkdir(exist_ok=True);shutil.copy2(apk,module/'app/BeekeeperRGB.apk')
zip_path=OUTPUT/('Beekeeper-RGB-v'+VERSION+'-magisk.zip')
with zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED) as archive:
    for p in sorted(module.rglob('*')):
        if p.is_file():archive.write(p,p.relative_to(module).as_posix())
(OUTPUT/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in (apk,zip_path)),encoding='ascii')
print('Built release '+VERSION+' in dist/',flush=True)

# Export the compiled payload for the repository's deterministic Magisk packer.
payload=ROOT.parent/'magisk/src/rgb'
expected={p.relative_to(module) for p in module.rglob('*') if p.is_file()}
if payload.exists():
    unexpected={p.relative_to(payload) for p in payload.rglob('*') if p.is_file()}-expected
    if unexpected:raise RuntimeError('Unexpected files in RGB payload; inspect before rebuilding')
for rel in expected:
    target=payload/rel;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(module/rel,target)
run([sys.executable,ROOT.parent/'magisk/build.py','rgb'])
