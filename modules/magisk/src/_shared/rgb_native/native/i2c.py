"""MCU 刷写用的两个 root 专用小程序：om2-flash-transport 每次发送一个数据包，om2-flash-read 读回 32 字节应答。

两者都只做单包传输，升级流程（分块、校验、回读）由 Magisk 侧的 McuManager 负责。
"""

import struct

from elf import DATA, I2C_DEVICE, assemble, elf, i2c_msg, i2c_rdwr

# 数据段布局：+0 设备路径，+16 /dev/null，+32 版本查询包，+48/+112 写/读 i2c_msg，
# +80/+128 i2c_rdwr，+96 纳秒级等待时间，+160 应答，+256 待发送的包，+832 包文件路径。
# 带参数 d 运行时改为打开 /dev/null（离线测试用）。
HEAD = """
 mov x20, #0x2000
 movk x20, #0x40, lsl #16
"""
# 把包文件读进缓冲区，长度写入 i2c_msg（1..544 字节）。
LOAD_PACKET = """
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
"""
OPEN = """
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
"""
WRITE = """
 mov x1, #0x707
 add x2, x20, #80
 mov x8, #29
 svc #0
 cmp x0, #1
 b.ne fail_write
"""
# 带参数 w 时只写不读，直接以 0 退出。
WRITE_ONLY = """
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
"""
SETTLE = """
 add x0, x20, #96
 mov x1, #0
 mov x8, #101
 svc #0
"""
READ = """
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
"""
PACKET_ERRORS = """
bad_file:
 mov x0, #13
 b done
bad_size:
 mov x0, #14
"""
EXIT = """
done:
 mov x8, #93
 svc #0
"""


def data(msg_len, msg_buf):
    out = bytearray(4096)
    out[: len(I2C_DEVICE)] = I2C_DEVICE
    out[16:26] = b"/dev/null\0"
    out[32:36] = bytes([0xA5, 0x55, 0x00, 0x55])
    out[48:64] = i2c_msg(msg_len, msg_buf)
    out[80:92] = i2c_rdwr(DATA + 0x30)
    out[96:112] = struct.pack("<qq", 0, 100000000)
    out[112:128] = i2c_msg(32, DATA + 0xA0, flags=1)
    out[128:140] = i2c_rdwr(DATA + 0x70)
    return out


def transport():
    program = HEAD + LOAD_PACKET + OPEN + WRITE + WRITE_ONLY + SETTLE + READ + PACKET_ERRORS + EXIT
    packet = data(0, DATA + 0x100)
    name = b"/data/adb/beekeeper-rgb/mcu-packet.bin\0"
    packet[832 : 832 + len(name)] = name
    return elf(assemble(program), packet)


def read_only():
    # 跳过写入和等待，只读回上一次的应答。
    program = HEAD + OPEN + " b readit\n" + WRITE + SETTLE + READ + EXIT
    return elf(assemble(program), data(4, DATA + 0x20))


def build():
    return {"om2-flash-transport": transport(), "om2-flash-read": read_only()}
