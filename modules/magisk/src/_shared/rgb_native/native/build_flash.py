"""Package the previously tested bounded root-only I2C upgrade transport."""
from pathlib import Path
import subprocess,sys,hashlib,shutil,os
here=Path(__file__).resolve().parent
module=here.parent/'build/module'
(module/'bin').mkdir(parents=True,exist_ok=True)
(module/'firmware').mkdir(parents=True,exist_ok=True)
original=Path(os.environ['BEEKEEPER_MCU_ORIGINAL']).read_bytes()
if hashlib.sha256(original).hexdigest()!='c78ea17cfe2c5278964e4cb57232db19fca1414b4fc5dcdb1df89faf8376145d':raise ValueError('Unsupported original MCU image')
from om2_pixel_candidate import candidate
(module/'firmware/original.bin').write_bytes(original)
(module/'firmware/pixels.bin').write_bytes(candidate(original)[0])
for script,args,source,target in [
    ('build_i2c_query.py',['--read-only'],'i2c-read-arm64','om2-flash-read'),
    ('build_om2_transport.py',[],'om2-transport-arm64','om2-flash-transport')]:
    subprocess.run([sys.executable,str(here/script),*args],check=True)
    binary=(here.parent/'build/native'/source).read_bytes()
    (module/'bin'/target).write_bytes(binary)
    (module/'firmware'/(target+'.sha256')).write_bytes((hashlib.sha256(binary).hexdigest()+'\n').encode('ascii'))
for name,expected in [('original.bin','c78ea17cfe2c5278964e4cb57232db19fca1414b4fc5dcdb1df89faf8376145d'),('pixels.bin','69f4c469f391dabeef52489d01b3c41fbe7fe16297b4ebea144e0afdef793b5f')]:
    assert hashlib.sha256((module/'firmware'/name).read_bytes()).hexdigest()==expected,name
assert candidate((module/'firmware/original.bin').read_bytes())[0]==(module/'firmware/pixels.bin').read_bytes()
print('MCU transport and fixed firmware images verified')
