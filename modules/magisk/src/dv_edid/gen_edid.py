"""Build patched copies of the TV's GPro EDID files for Windows Dolby Vision.

HDMI: Windows only enables DV when the Dolby VSVDB advertises the low-latency (LLDV) HDMI
interface: payload byte 2 bit 0 (9e->9f, 9a->9b). Nothing else changes.
DP: the stock DP EDID has no Dolby / HDR10+ block at all, so both are inserted before the DTDs.
"""
import hashlib
import json
import os
import sys

SRC = r'E:\Data\27U EDID\image\research\edid\tv_edid_bin'
OUT = r'E:\Data\27U EDID\image\research\edid\dv_edid_src\files'

DV_HDR = bytes([0xEB, 0x01, 0x46, 0xD0, 0x00])
DV_BLOCK = bytes.fromhex('eb0146d0004d0a9f5898aa5c')
HDR10P_BLOCK = bytes.fromhex('e5018b849001')


def fix_sum(blk):
    blk[127] = (-sum(blk[:127])) & 0xFF


def cta_blocks(d):
    for b in range(1, len(d) // 128):
        if d[b * 128] == 0x02:
            yield b


def patch_hdmi(d):
    d = bytearray(d)
    hits = 0
    for b in cta_blocks(d):
        blk = d[b * 128:(b + 1) * 128]
        i = 4
        while i < blk[2]:
            ln = blk[i] & 31
            if bytes(blk[i:i + 5]) == DV_HDR:
                assert ln == 11
                p = i + 5 + 2
                assert blk[p] in (0x9E, 0x9A), hex(blk[p])
                blk[p] |= 1
                fix_sum(blk)
                d[b * 128:(b + 1) * 128] = blk
                hits += 1
            i += 1 + ln
    assert hits == 1, hits
    return bytes(d)


def patch_dp(d):
    d = bytearray(d)
    b = 1
    blk = d[b * 128:(b + 1) * 128]
    assert blk[0] == 0x02
    off = blk[2]
    add = DV_BLOCK + HDR10P_BLOCK
    n = len(add)
    # no existing Dolby block, and the region we push out must be padding
    i = 4
    while i < off:
        ln = blk[i] & 31
        assert bytes(blk[i:i + 5]) != DV_HDR
        i += 1 + ln
    assert not any(blk[127 - n:127]), 'not enough free space'
    new = bytearray(blk[:off]) + add + blk[off:127 - n] + b'\x00'
    new[2] = off + n
    fix_sum(new)
    assert len(new) == 128 and sum(new) % 256 == 0
    d[b * 128:(b + 1) * 128] = new
    return bytes(d)


def main():
    os.makedirs(OUT, exist_ok=True)
    rows = []
    for f in sorted(os.listdir(SRC)):
        if not (f.startswith('GPro_HDMI') or f.startswith('GPro_DP')):
            continue
        d = open(os.path.join(SRC, f), 'rb').read()
        nd = patch_dp(d) if f.startswith('GPro_DP') else patch_hdmi(d)
        assert len(nd) >= len(d)
        # DP insert keeps the size; HDMI patch changes 2 bytes
        assert len(nd) == len(d)
        diff = [i for i in range(len(d)) if d[i] != nd[i]]
        for blk in range(len(nd) // 128):
            assert sum(nd[blk * 128:(blk + 1) * 128]) % 256 == 0, (f, blk)
        open(os.path.join(OUT, 'o_' + f), 'wb').write(d)
        open(os.path.join(OUT, 'n_' + f), 'wb').write(nd)
        rows.append((f, hashlib.sha256(d).hexdigest(), hashlib.sha256(nd).hexdigest(), len(diff)))
    with open(os.path.join(OUT, 'list'), 'w', newline='\n') as fh:
        for f, o, n, _ in rows:
            fh.write('%s %s %s\n' % (f, o, n))
    for f, o, n, k in rows:
        print('%-42s bytes changed: %3d' % (f, k))
    print(len(rows), 'files')


if __name__ == '__main__':
    main()
