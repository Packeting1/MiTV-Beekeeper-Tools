"""Build a minimal static arm64 Linux I2C_RDWR diagnostic; no libraries."""
import struct, sys
from pathlib import Path
from keystone import Ks, KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN

# Data addresses are fixed within the RW PT_LOAD segment at 0x402000.
code = '''
 mov x20, #0x2000
 movk x20, #0x40, lsl #16
 mov x0, #-100
 mov x1, x20
 ldr x3, [sp]
 cmp x3, #2
 b.lt openit
 ldr x3, [sp, #16]
 ldrb w3, [x3]
 cmp w3, #100
 b.ne openit
 add x1, x20, #16
openit:
 mov x2, #2
 mov x3, #0
 mov x8, #56
 svc #0
 cmp x0, #0
 b.lt fail_open
 mov x19, x0
 mov x1, #0x707
 add x2, x20, #80
 mov x8, #29
 svc #0
 cmp x0, #1
 b.ne fail_write
 add x0, x20, #96
 mov x1, #0
 mov x8, #101
 svc #0
readit:
 mov x0, x19
 mov x1, #0x707
 add x2, x20, #128
 mov x8, #29
 svc #0
 cmp x0, #1
 b.ne fail_read
 mov x0, #1
 add x1, x20, #160
 mov x2, #32
 mov x8, #64
 svc #0
 mov x0, #0
 b done
fail_open:
 mov x0, #10
 b done
fail_write:
 mov x0, #11
 b done
fail_read:
 mov x0, #12
done:
 mov x8, #93
 svc #0
'''
read_only = '--read-only' in sys.argv
if read_only:
    code = code.replace(' mov x19, x0\n', ' mov x19, x0\n b readit\n')
enc, _ = Ks(KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN).asm(code, addr=0x401000)
assert len(enc) < 4096
data = bytearray(4096)
data[:11] = b'/dev/i2c-3\0'
data[16:26] = b'/dev/null\0'
crc = 0
for value in bytes.fromhex('55 00'):
    crc ^= value
    for _ in range(8):
        crc = ((crc << 1) ^ (7 if crc & 128 else 0)) & 255
assert crc == 0x4d
use_crc = '--crc8' in sys.argv
om2_version = '--om2-version' in sys.argv
data[32:36] = bytes.fromhex('02 00 00 02') if om2_version else bytes([0xa5, 0x55, 0, crc if use_crc else 0x55])
struct.pack_into('<HHHHQ', data, 48, 0x50, 0, 4, 0, 0x402020)
struct.pack_into('<QI', data, 80, 0x402030, 1)
struct.pack_into('<qq', data, 96, 0, 100000000)
struct.pack_into('<HHHHQ', data, 112, 0x50, 1, 32, 0, 0x4020a0)
struct.pack_into('<QI', data, 128, 0x402070, 1)
ident = b'\x7fELF' + bytes([2,1,1,0]) + bytes(8)
header = struct.pack('<16sHHIQQQIHHHHHH', ident, 2, 183, 1, 0x401000, 64, 0, 0, 64, 56, 2, 0, 0, 0)
ph1 = struct.pack('<IIQQQQQQ', 1, 5, 0, 0x400000, 0x400000, 8192, 8192, 4096)
ph2 = struct.pack('<IIQQQQQQ', 1, 6, 8192, 0x402000, 0x402000, 4096, 4096, 4096)
out = bytearray(12288)
out[:64] = header
out[64:176] = ph1 + ph2
out[4096:4096+len(enc)] = bytes(enc)
out[8192:] = data
folder=Path(__file__).resolve().parents[1]/'build/native';folder.mkdir(parents=True,exist_ok=True)
path = folder/('i2c-om2-version-arm64' if om2_version else ('i2c-read-arm64' if read_only else ('i2c-query-crc8-arm64' if use_crc else 'i2c-query-arm64')))
path.write_bytes(out)
print(path, 'code bytes', len(enc))
