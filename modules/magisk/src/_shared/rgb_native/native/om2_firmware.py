"""24 灯珠固件相对原版 MCU.bin 的补丁：在 RISC-V 接收路径上挂一个钩子，收到 75 字节的 0x81 0x0c 帧时
校验 XOR，再把 24 个 RGB 重排成外设需要的 GRB 字序并输出；其它帧原样交还给原固件。

这里只做离线生成，不含任何与设备通信或刷写的代码。
"""

import hashlib
import struct

HEADER = 32
ORIGINAL_SHA256 = "c78ea17cfe2c5278964e4cb57232db19fca1414b4fc5dcdb1df89faf8376145d"
FLASH = 0x80002000
HOOK = 0x812
CAVE = 0x7840
RAM_SOURCE = 0x5C4
NORMAL_RETURN = 0x20000000 + 0x81E - RAM_SOURCE
CLEANUP = 0x20000000 + 0x8B2 - RAM_SOURCE
BUFFER = 0x20012E20
MODE = 0x20014D9C
OUTPUT = FLASH + 0x5418


class Assembler:
    """只实现本补丁用到的 RV32IM 指令。"""

    def __init__(self, base):
        self.base = base
        self.items = []
        self.labels = {}

    def label(self, name):
        self.labels[name] = self.base + len(self.items) * 4

    def emit(self, op, *args):
        self.items.append((op, args))

    def li(self, rd, value):
        value &= 0xFFFFFFFF
        hi = ((value + 0x800) >> 12) & 0xFFFFF
        lo = (value & 0xFFF) - (0x1000 if value & 0x800 else 0)
        self.emit("lui", rd, hi)
        self.emit("addi", rd, rd, lo)

    def jump(self, address):
        self.li(5, address)
        self.emit("jalr", 0, 5, 0)

    def build(self):
        words = []
        for n, (op, a) in enumerate(self.items):
            if op == "lui":
                rd, imm = a
                word = imm << 12 | rd << 7 | 0x37
            elif op in ("addi", "xori", "lbu", "lhu", "jalr"):
                rd, rs, imm = a
                funct, opcode = {"addi": (0, 0x13), "xori": (4, 0x13), "lbu": (4, 3), "lhu": (5, 3), "jalr": (0, 0x67)}[
                    op
                ]
                word = (imm & 4095) << 20 | rs << 15 | funct << 12 | rd << 7 | opcode
            elif op in ("add", "xor", "mul"):
                rd, r1, r2 = a
                funct = 4 if op == "xor" else 0
                word = (1 if op == "mul" else 0) << 25 | r2 << 20 | r1 << 15 | funct << 12 | rd << 7 | 0x33
            elif op == "sb":
                rs, base, imm = a
                word = ((imm >> 5) & 127) << 25 | rs << 20 | base << 15 | (imm & 31) << 7 | 0x23
            elif op in ("beq", "bne"):
                r1, r2, label = a
                d = self.labels[label] - (self.base + n * 4)
                assert d % 2 == 0 and -4096 <= d < 4096
                word = (
                    ((d >> 12) & 1) << 31
                    | ((d >> 5) & 63) << 25
                    | r2 << 20
                    | r1 << 15
                    | (op == "bne") << 12
                    | ((d >> 1) & 15) << 8
                    | ((d >> 11) & 1) << 7
                    | 0x63
                )
            else:
                raise ValueError(op)
            words.append(word)
        return struct.pack("<" + "I" * len(words), *words)


def body():
    a = Assembler(FLASH + CAVE)
    e = a.emit
    # s0 指向当前接收队列序号，s1 为队列基址，s3 为步长 0x220。
    e("lbu", 5, 8, 0)
    e("mul", 5, 5, 19)
    e("add", 5, 5, 9)
    e("lbu", 6, 5, 1)
    e("addi", 7, 0, 12)
    e("bne", 6, 7, "normal")
    e("lhu", 6, 21, 0)
    e("addi", 7, 0, 75)
    e("bne", 6, 7, "reject")
    e("addi", 28, 5, 0)
    e("addi", 29, 0, 0)
    e("addi", 30, 0, 74)
    a.label("crc")
    e("lbu", 31, 28, 0)
    e("xor", 29, 29, 31)
    e("addi", 28, 28, 1)
    e("addi", 30, 30, -1)
    e("bne", 30, 0, "crc")
    e("lbu", 31, 28, 0)
    e("bne", 31, 29, "reject")
    e("addi", 5, 5, 2)
    a.li(6, BUFFER)
    e("addi", 7, 0, 0)
    e("addi", 30, 0, 24)
    a.label("pixel")
    # 按 G、R、B 写入高位在前的 32 位外设字（字节下标异或 3）。
    for src in (1, 0, 2):
        e("lbu", 28, 5, src)
        e("xori", 29, 7, 3)
        e("add", 29, 6, 29)
        e("sb", 28, 29, 0)
        e("addi", 7, 7, 1)
    e("addi", 5, 5, 3)
    e("addi", 30, 30, -1)
    e("bne", 30, 0, "pixel")
    a.li(5, MODE)
    e("addi", 6, 0, 12)
    e("sb", 6, 5, 0)
    a.li(10, BUFFER)
    e("addi", 11, 0, 18)
    a.li(5, OUTPUT)
    e("jalr", 1, 5, 0)
    a.label("reject")
    a.jump(CLEANUP)
    a.label("normal")
    # 补回被钩子覆盖的三条指令，再回到 RAM 覆盖段继续执行。
    e("lui", 15, 0x20015)
    e("sb", 0, 15, -0x277)
    e("lui", 15, 0x20015)
    a.jump(NORMAL_RETURN)
    return a.build()


def hunks(original):
    """返回 [(文件偏移, 数据)]：只含新增的钩子和补丁代码，不带厂商固件的任何字节。"""
    if hashlib.sha256(original).hexdigest() != ORIGINAL_SHA256:
        raise ValueError("Unsupported original MCU image")
    code = body()
    assert CAVE + len(code) < len(original) - HEADER - 1
    assert all(x == 255 for x in original[HEADER + CAVE : HEADER + CAVE + len(code)])
    hook = Assembler(0x20000000 + HOOK - RAM_SOURCE)
    hook.jump(FLASH + CAVE)
    return [(HEADER + HOOK, hook.build()), (HEADER + CAVE, code)]


def pixel_firmware(original):
    raw = bytearray(original)
    for offset, data in hunks(original):
        raw[offset : offset + len(data)] = data
    checksum = 255
    for value in raw[HEADER + 4 : -1]:
        checksum ^= value
    raw[-1] = checksum
    raw[28] = checksum
    return bytes(raw)


def patch_text(original):
    """补丁文件格式：每行 `十六进制偏移 十六进制数据`，由设备上的 McuImage 套用并重算校验和。"""
    return "".join("%x %s\n" % (offset, data.hex()) for offset, data in hunks(original))
