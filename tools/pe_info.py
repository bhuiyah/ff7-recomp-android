"""Print PE header facts for a 32-bit exe: machine, sections, entry, imports, and signs of
a SteamStub / packer wrapper (a .bind section, high-entropy code, tiny import table).

usage: py -I tools/pe_info.py <path-to-exe>
"""
import math
import struct
import sys


def entropy(b):
    if not b:
        return 0.0
    counts = [0] * 256
    for x in b:
        counts[x] += 1
    n = len(b)
    return -sum(c / n * math.log2(c / n) for c in counts if c)


def main(path):
    data = open(path, 'rb').read()
    file_len = len(data)
    # Sections can be shorter on disk than in memory (the loader zero-fills the rest);
    # pad so tables that run past the raw data read as zeros, like they do at runtime.
    data += b'\0' * 0x10000
    if data[:2] != b'MZ':
        sys.exit('not an MZ file')
    pe = struct.unpack_from('<I', data, 0x3C)[0]
    if data[pe:pe + 4] != b'PE\0\0':
        sys.exit('no PE signature')
    machine, nsec, timestamp = struct.unpack_from('<HHI', data, pe + 4)
    opt_size, chars = struct.unpack_from('<HH', data, pe + 20)
    opt = pe + 24
    magic = struct.unpack_from('<H', data, opt)[0]
    entry, = struct.unpack_from('<I', data, opt + 16)
    base, = struct.unpack_from('<I', data, opt + 28)
    subsystem, = struct.unpack_from('<H', data, opt + 68)
    ndirs, = struct.unpack_from('<I', data, opt + 92)
    dirs = [struct.unpack_from('<II', data, opt + 96 + 8 * i) for i in range(min(ndirs, 16))]
    print(f'file        {path}  ({file_len} bytes)')
    print(f'machine     0x{machine:04x}  magic 0x{magic:x}  subsystem {subsystem}  timestamp 0x{timestamp:08x}')
    print(f'image base  0x{base:08x}  entry RVA 0x{entry:08x}  (VA 0x{base + entry:08x})')

    secs = []
    sh = opt + opt_size
    print('sections:')
    for i in range(nsec):
        name = data[sh:sh + 8].rstrip(b'\0').decode('latin1')
        vsize, va, rsize, rptr = struct.unpack_from('<IIII', data, sh + 8)
        ch, = struct.unpack_from('<I', data, sh + 36)
        raw = data[rptr:rptr + rsize]
        secs.append((name, va, vsize, rptr, rsize))
        print(f'  {name:8s} VA 0x{va:08x} vsize 0x{vsize:08x} raw 0x{rsize:08x} chars 0x{ch:08x} entropy {entropy(raw):.2f}')
        sh += 40

    def rva2off(rva):
        for _, va, vs, rp, rs in secs:
            if va <= rva < va + max(vs, rs):
                return rp + rva - va
        return None

    entry_sec = next((s[0] for s in secs if s[1] <= entry < s[1] + s[2]), '?')
    print(f'entry is in section {entry_sec}')

    imp_rva = dirs[1][0] if len(dirs) > 1 else 0
    dlls = []
    off = rva2off(imp_rva) if imp_rva else None
    while off is not None:
        oft, _, _, name_rva, ft = struct.unpack_from('<IIIII', data, off)
        if not name_rva:
            break
        noff = rva2off(name_rva)
        dll = data[noff:data.index(b'\0', noff)].decode('latin1')
        count = 0
        t = rva2off(oft or ft)
        while t is not None and struct.unpack_from('<I', data, t)[0]:
            count += 1
            t += 4
        dlls.append((dll, count))
        off += 20
    print('imports:')
    for dll, count in dlls:
        print(f'  {dll:24s} {count} functions')

    flags = []
    if any(s[0].lower() == '.bind' for s in secs):
        flags.append('.bind section (SteamStub)')
    if any(d.lower().startswith('steam_api') for d, _ in dlls):
        flags.append('imports steam_api')
    if entry_sec.lower() not in ('.text', 'code', '.code'):
        flags.append(f'entry point outside .text ({entry_sec})')
    print('wrapper/DRM signs:', ', '.join(flags) if flags else 'none found')


if __name__ == '__main__':
    main(sys.argv[1])
