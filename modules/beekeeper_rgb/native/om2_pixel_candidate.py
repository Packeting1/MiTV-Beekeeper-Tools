"""Offline-only OM2 patch candidate. Deliberately contains no device/flashing code."""
from pathlib import Path
import struct, hashlib, json

HERE = Path(__file__).resolve().parent
FLASH = 0x80002000
HOOK = 0x812
CAVE = 0x7840
RAM_SOURCE = 0x5c4
NORMAL_RETURN = 0x20000000 + 0x81e - RAM_SOURCE
CLEANUP = 0x20000000 + 0x8b2 - RAM_SOURCE
BUFFER = 0x20012e20
MODE = 0x20014d9c
OUTPUT = FLASH + 0x5418

class Assembler:
    def __init__(self, base):
        self.base, self.items, self.labels = base, [], {}
    def label(self, name): self.labels[name] = self.base + len(self.items)*4
    def emit(self, op, *args): self.items.append((op, args))
    def li(self, rd, value):
        value &= 0xffffffff
        hi = ((value + 0x800) >> 12) & 0xfffff
        lo = (value & 0xfff) - (0x1000 if value & 0x800 else 0)
        self.emit('lui', rd, hi)
        self.emit('addi', rd, rd, lo)
    def jump(self, address):
        self.li(5, address)
        self.emit('jalr', 0, 5, 0)
    def build(self):
        words = []
        for n,(op,a) in enumerate(self.items):
            if op == 'lui': rd,imm=a; word=imm<<12|rd<<7|0x37
            elif op in ('addi','xori','lbu','lhu','jalr'):
                rd,rs,imm=a
                f,opcode={'addi':(0,0x13),'xori':(4,0x13),'lbu':(4,3),'lhu':(5,3),'jalr':(0,0x67)}[op]
                word=(imm&4095)<<20|rs<<15|f<<12|rd<<7|opcode
            elif op in ('add','xor','mul'):
                rd,r1,r2=a; f={'add':0,'xor':4,'mul':0}[op]
                word=(1 if op=='mul' else 0)<<25|r2<<20|r1<<15|f<<12|rd<<7|0x33
            elif op == 'sb':
                rs,base,imm=a; word=((imm>>5)&127)<<25|rs<<20|base<<15|(imm&31)<<7|0x23
            elif op in ('beq','bne'):
                r1,r2,label=a; d=self.labels[label]-(self.base+n*4)
                assert d%2==0 and -4096<=d<4096
                word=((d>>12)&1)<<31|((d>>5)&63)<<25|r2<<20|r1<<15|(op=='bne')<<12|((d>>1)&15)<<8|((d>>11)&1)<<7|0x63
            else: raise ValueError(op)
            words.append(word)
        return struct.pack('<'+'I'*len(words), *words)

def build_body():
    a=Assembler(FLASH+CAVE); e=a.emit
    # s0 -> active receive queue index, s1 -> queue base, s3 -> stride 0x220.
    e('lbu',5,8,0); e('mul',5,5,19); e('add',5,5,9)
    e('lbu',6,5,1); e('addi',7,0,12); e('bne',6,7,'normal')
    e('lhu',6,21,0); e('addi',7,0,75); e('bne',6,7,'reject')
    e('addi',28,5,0); e('addi',29,0,0); e('addi',30,0,74)
    a.label('crc')
    e('lbu',31,28,0); e('xor',29,29,31); e('addi',28,28,1)
    e('addi',30,30,-1); e('bne',30,0,'crc')
    e('lbu',31,28,0); e('bne',31,29,'reject')
    e('addi',5,5,2); a.li(6,BUFFER); e('addi',7,0,0); e('addi',30,0,24)
    a.label('pixel')
    # Serialize G,R,B into MSB-first 32-bit peripheral words (byte index xor 3).
    for src in (1,0,2):
        e('lbu',28,5,src); e('xori',29,7,3); e('add',29,6,29)
        e('sb',28,29,0); e('addi',7,7,1)
    e('addi',5,5,3); e('addi',30,30,-1); e('bne',30,0,'pixel')
    a.li(5,MODE); e('addi',6,0,12); e('sb',6,5,0)
    a.li(10,BUFFER); e('addi',11,0,18); a.li(5,OUTPUT); e('jalr',1,5,0)
    a.label('reject'); a.jump(CLEANUP)
    a.label('normal')
    # Replay the three displaced instructions, then resume the RAM overlay.
    e('lui',15,0x20015); e('sb',0,15,-0x277); e('lui',15,0x20015)
    a.jump(NORMAL_RETURN)
    return a.build(), a

def candidate(original):
    assert hashlib.sha256(original).hexdigest()=='c78ea17cfe2c5278964e4cb57232db19fca1414b4fc5dcdb1df89faf8376145d'
    image=bytearray(original[32:])
    body,asm=build_body()
    assert CAVE+len(body)<len(image)-1
    assert all(x==255 for x in image[CAVE:CAVE+len(body)])
    hook=Assembler(0x20000000+HOOK-RAM_SOURCE); hook.jump(FLASH+CAVE)
    assert len(hook.build())==12
    image[HOOK:HOOK+12]=hook.build()
    image[CAVE:CAVE+len(body)]=body
    checksum=255
    for value in image[4:-1]:checksum^=value
    image[-1]=checksum
    header=bytearray(original[:32]); header[28]=checksum
    return bytes(header+image), {'hook_image_offset':hex(HOOK),'body_image_offset':hex(CAVE),'body_bytes':len(body),'checksum':checksum,'status':'EXPERIMENTAL - exact target validation required'}

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('original',type=Path)
    parser.add_argument('--output',type=Path,default=HERE.parent/'build/firmware/pixels.bin')
    args=parser.parse_args()
    original=args.original.read_bytes()
    output,manifest=candidate(original)
    dest=args.output;dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_bytes(output)
    manifest['sha256']=hashlib.sha256(output).hexdigest()
    manifest['limitations']=['No proven brick recovery path','Exact target validation required','Main MCU also controls display functions','Network idle timeout is managed by the host backend']
    dest.with_suffix('.json').write_text(json.dumps(manifest,indent=2),encoding='utf8')
    print(json.dumps(manifest,indent=2))
