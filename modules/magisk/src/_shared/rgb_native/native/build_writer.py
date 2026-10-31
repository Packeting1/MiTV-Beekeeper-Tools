"""Build a root-only arm64 fixed-frame writer; no MCU flash commands accepted."""
from pathlib import Path
import struct,sys,hashlib
ROOT=Path(__file__).resolve().parents[1]
from keystone import Ks,KS_ARCH_ARM64,KS_MODE_LITTLE_ENDIAN
code='''
 mov x20, #0x2000
 movk x20, #0x40, lsl #16
 mov x24, #0
 ldr x0, [sp]
 cmp x0, #2
 b.lt openit
 ldr x0, [sp, #16]
 ldrb w0, [x0]
 cmp w0, #100
 b.ne openit
 mov x24, #1
 b frame
openit:
 mov x0, #-100
 mov x1, x20
 mov x2, #2
 mov x3, #0
 mov x8, #56
 svc #0
 cmp x0, #0
 b.lt open_error
 mov x19, x0
frame:
 add x22, x20, #256
 mov x23, #75
receive:
 mov x0, #0
 mov x1, x22
 mov x2, x23
 mov x8, #63
 svc #0
 cmp x0, #-4
 b.eq receive
 cmp x0, #0
 b.lt read_error
 b.eq eof
 add x22, x22, x0
 sub x23, x23, x0
 cbnz x23, receive
 add x22, x20, #256
 ldrh w0, [x22]
 mov w1, #0xc81
 cmp w0, w1
 b.ne invalid
 mov x23, #75
 mov w1, #0
checksum:
 ldrb w0, [x22], #1
 eor w1, w1, w0
 subs x23, x23, #1
 b.ne checksum
 cbnz w1, invalid
 cbnz x24, acknowledge
 mov x0, x19
 mov x1, #0x707
 add x2, x20, #80
 mov x8, #29
 svc #0
 cmp x0, #1
 b.ne write_error
acknowledge:
 mov x0, #1
 add x1, x20, #96
 mov x2, #1
 mov x8, #64
 svc #0
 cmp x0, #1
 b.ne ack_error
 b frame
eof:
 cmp x23, #75
 b.ne truncated
 mov x0, #0
 b done
open_error:
 mov x0, #10
 b done
write_error:
 mov x0, #11
 b done
read_error:
 mov x0, #13
 b done
truncated:
 mov x0, #14
 b done
invalid:
 mov x0, #15
 b done
ack_error:
 mov x0, #16
done:
 mov x8, #93
 svc #0
'''
enc,_=Ks(KS_ARCH_ARM64,KS_MODE_LITTLE_ENDIAN).asm(code,addr=0x401000)
out=bytearray(12288)
ident=b'\x7fELF'+bytes([2,1,1,0])+bytes(8)
out[:64]=struct.pack('<16sHHIQQQIHHHHHH',ident,2,183,1,0x401000,64,0,0,64,56,2,0,0,0)
out[64:120]=struct.pack('<IIQQQQQQ',1,5,0,0x400000,0x400000,8192,8192,4096)
out[120:176]=struct.pack('<IIQQQQQQ',1,6,8192,0x402000,0x402000,4096,4096,4096)
out[4096:4096+len(enc)]=bytes(enc)
out[8192:8203]=b'/dev/i2c-3\0'
struct.pack_into('<HHHHQ',out,8192+48,0x50,0,75,0,0x402100)
struct.pack_into('<QI',out,8192+80,0x402030,1)
target=ROOT/'build/module/bin/om2-pixel-writer';target.parent.mkdir(parents=True,exist_ok=True)
target.write_bytes(out)
print(target,hashlib.sha256(out).hexdigest())

# Separate executable accepts only the six-byte original whole-strip protocol.
single=code.replace('mov x23, #75','mov x23, #6').replace('cmp x23, #75','cmp x23, #6').replace('mov w1, #0xc81','mov w1, #0x181')
enc,_=Ks(KS_ARCH_ARM64,KS_MODE_LITTLE_ENDIAN).asm(single,addr=0x401000)
out[4096:8192]=bytes(4096);out[4096:4096+len(enc)]=bytes(enc)
struct.pack_into('<HHHHQ',out,8192+48,0x50,0,6,0,0x402100)
target=ROOT/'build/module/bin/om2-single-writer';target.write_bytes(out)
print(target,hashlib.sha256(out).hexdigest())
