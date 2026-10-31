"""Build bounded single-packet arm64 I2C transport; no upgrade orchestration."""
from pathlib import Path
import runpy, sys, struct
sys.argv=[sys.argv[0]]
base=runpy.run_path(str(Path(__file__).with_name('build_i2c_query.py')))
code=base['code']
insertion='''
 mov x0, #-100
 add x1, x20, #832
 mov x2, #0
 mov x3, #0
 mov x8, #56
 svc #0
 cmp x0, #0
 b.lt bad_file
 mov x22, x0
 add x1, x20, #256
 mov x2, #545
 mov x8, #63
 svc #0
 mov x23, x0
 mov x0, x22
 mov x8, #57
 svc #0
 cmp x23, #1
 b.lt bad_size
 cmp x23, #544
 b.gt bad_size
 strh w23, [x20, #52]
'''
code=code.replace(' mov x0, #-100\n', insertion+' mov x0, #-100\n',1)
code=code.replace(' add x0, x20, #96\n', '''
 ldr x3, [sp]
 cmp x3, #2
 b.lt doread
 ldr x3, [sp, #16]
 ldrb w3, [x3]
 cmp w3, #119
 b.ne doread
 mov x0, #0
 b done
doread:
 add x0, x20, #96
''',1)
code=code.replace('done:\n', 'bad_file:\n mov x0, #13\n b done\nbad_size:\n mov x0, #14\ndone:\n')
enc,_=base['Ks'](base['KS_ARCH_ARM64'],base['KS_MODE_LITTLE_ENDIAN']).asm(code,addr=0x401000)
out=bytearray(base['out'])
out[4096:8192]=bytes(4096);out[4096:4096+len(enc)]=bytes(enc)
data=bytearray(base['data'])
struct.pack_into('<HHHHQ',data,48,0x50,0,0,0,0x402100)
name=b'/data/adb/beekeeper-rgb/mcu-packet.bin\0'
data[832:832+len(name)]=name
out[8192:]=data
path=Path(__file__).resolve().parents[1]/'build/native/om2-transport-arm64';path.write_bytes(out)
print(path,'bytes',len(out),'code',len(enc))
