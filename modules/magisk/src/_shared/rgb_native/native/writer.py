"""root 专用的定长帧写入器：从 stdin 读固定长度的帧，校验头和 XOR 后写给 /dev/i2c-3，不接受任何刷写命令。

om2-pixel-writer 接收 24 灯珠固件的 75 字节帧，om2-single-writer 只接收原版固件的 6 字节整条灯带帧。
"""

from elf import DATA, I2C_DEVICE, assemble, elf, i2c_msg, i2c_rdwr

# 数据段布局：+0 设备路径，+48 i2c_msg，+80 i2c_rdwr，+96 应答字节，+256 帧缓冲区。
# 带参数 d 运行时不打开 I2C，只校验并应答（离线测试用）。
PROGRAM = """
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
"""


def writer(program, size):
    data = bytearray(4096)
    data[: len(I2C_DEVICE)] = I2C_DEVICE
    data[48 : 48 + 16] = i2c_msg(size, DATA + 0x100)
    data[80 : 80 + 12] = i2c_rdwr(DATA + 0x30)
    return elf(assemble(program), data)


def build():
    single = (
        PROGRAM.replace("mov x23, #75", "mov x23, #6")
        .replace("cmp x23, #75", "cmp x23, #6")
        .replace("mov w1, #0xc81", "mov w1, #0x181")
    )
    return {"om2-pixel-writer": writer(PROGRAM, 75), "om2-single-writer": writer(single, 6)}
