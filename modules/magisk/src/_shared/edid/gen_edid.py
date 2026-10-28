"""Build patched copies of the TV's GPro EDID files (Windows Dolby Vision, DP audio formats).

HDMI: Windows only enables DV when the Dolby VSVDB advertises the low-latency (LLDV) HDMI
interface: payload byte 2 bit 0 (9e->9f, 9a->9b). Nothing else changes.
DP: the stock DP EDID has no Dolby / HDR10+ block at all, so both are inserted before the DTDs.

States per file (list columns): o = stock, n = LLDV, a = DP audio (HDMI's audio descriptors, DP files only),
b = both. The two Magisk modules move files between these states, see edid_lib.sh.
"""
import hashlib
import json
import os
import sys

OUT = os.path.dirname(os.path.abspath(__file__))

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


def sad_block(d):
    """The Short Audio Descriptor data block (tag 1) of the first CTA block, header byte included."""
    blk = d[128:256]
    i = 4
    while i < blk[2]:
        ln = blk[i] & 31
        if blk[i] >> 5 == 1:
            return bytes(blk[i:i + 1 + ln])
        i += 1 + ln
    raise AssertionError('no SAD block')


def patch_audio(d, sads):
    """Replace the SAD block with `sads`, shifting everything after it; the freed padding absorbs the growth."""
    d = bytearray(d)
    blk = d[128:256]
    assert blk[0] == 0x02
    off = blk[2]
    ndtd = blk[3] & 0x0F
    blocks, i = [], 4
    while i < off:
        ln = blk[i] & 31
        b = bytes(blk[i:i + 1 + ln])
        blocks.append(sads if b[0] >> 5 == 1 else b)
        i += 1 + ln
    data = b''.join(blocks)
    dtds = bytes(blk[off:off + 18 * ndtd])
    assert not any(blk[off + 18 * ndtd:127]), 'unexpected data after the DTDs'
    body = bytes(blk[:4]) + data + dtds
    assert len(body) <= 127, 'not enough free space'
    new = bytearray(body.ljust(128, b'\x00'))
    new[2] = 4 + len(data)
    fix_sum(new)
    d[128:256] = new
    return bytes(d)


def main():
    if len(sys.argv) != 2:
        sys.exit('usage: gen_edid.py <directory with the TV\'s stock GPro_HDMI*/GPro_DP* EDID files>')
    SRC = sys.argv[1]
    files = sorted(f for f in os.listdir(SRC) if f.startswith(('GPro_HDMI', 'GPro_DP')))
    # DP gets the audio capabilities of the HDMI 2.1 ports (ver6.0, identical in all their files);
    # HDMI 3/4 are HDMI 2.0 ports with stereo LPCM only
    hdmi_sads = {sad_block(open(os.path.join(SRC, f), 'rb').read()) for f in files if f.startswith(('GPro_HDMI1', 'GPro_HDMI2'))}
    assert len(hdmi_sads) == 1
    sads = hdmi_sads.pop()
    rows = []
    for f in files:
        d = open(os.path.join(SRC, f), 'rb').read()
        dp = f.startswith('GPro_DP')
        states = {'o': d, 'n': patch_dp(d) if dp else patch_hdmi(d)}
        if dp:
            states['a'] = patch_audio(d, sads)
            states['b'] = patch_audio(states['n'], sads)
        for k, v in states.items():
            assert len(v) == len(d)
            for blk in range(len(v) // 128):
                assert sum(v[blk * 128:(blk + 1) * 128]) % 256 == 0, (f, k, blk)
            open(os.path.join(OUT, '%s_%s' % (k, f)), 'wb').write(v)
        rows.append([f] + [hashlib.sha256(states[k]).hexdigest() if k in states else '-' for k in 'onab'])
    with open(os.path.join(OUT, 'list'), 'w', newline='\n') as fh:
        for r in rows:
            fh.write(' '.join(r) + '\n')
    print(len(rows), 'files')


if __name__ == '__main__':
    main()
