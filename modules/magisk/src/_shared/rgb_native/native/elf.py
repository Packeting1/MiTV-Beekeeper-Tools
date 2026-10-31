"""arm64 静态可执行文件的最小 ELF 封装：代码段 0x401000（RX），数据段 0x402000（RW），不依赖任何库。"""
import struct

from keystone import Ks, KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN

CODE = 0x401000
DATA = 0x402000
I2C_DEVICE = b'/dev/i2c-3\0'


def assemble(source):
    code, _ = Ks(KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN).asm(source, addr=CODE)
    return bytes(code)


def i2c_msg(length, buf, flags=0):
    """Linux struct i2c_msg，从机地址固定为 0x50（灯控 MCU）。"""
    return struct.pack('<HHHHQ', 0x50, flags, length, 0, buf)


def i2c_rdwr(msgs):
    """Linux struct i2c_rdwr_ioctl_data，只带一条消息。"""
    return struct.pack('<QI', msgs, 1)


def elf(code, data):
    assert len(code) < 4096 and len(data) <= 4096
    out = bytearray(12288)
    ident = b'\x7fELF' + bytes([2, 1, 1, 0]) + bytes(8)
    out[:64] = struct.pack('<16sHHIQQQIHHHHHH', ident, 2, 183, 1, CODE, 64, 0, 0, 64, 56, 2, 0, 0, 0)
    out[64:120] = struct.pack('<IIQQQQQQ', 1, 5, 0, 0x400000, 0x400000, 8192, 8192, 4096)
    out[120:176] = struct.pack('<IIQQQQQQ', 1, 6, 8192, DATA, DATA, 4096, 4096, 4096)
    out[4096:4096 + len(code)] = code
    out[8192:8192 + len(data)] = data
    return bytes(out)
