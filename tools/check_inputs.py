"""Check game/ against what the pipeline expects and record hashes in analysis/inputs.json.

The exe must be the 32-bit 1.02 US build FFNx knows (entry 0x40B6E0, AF3DN.P import).
Every data file is hashed so later runs can tell if the inputs changed.

usage: py -I tools/check_inputs.py [--quick]   (--quick skips hashing the data folder)
"""
import hashlib
import json
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GAME = os.path.join(ROOT, 'game')
OUT = os.path.join(ROOT, 'analysis', 'inputs.json')

EXPECT_ENTRY_VA = 0x0040B6E0      # FFNx externals_102_us.h: start
EXPECT_IMAGE_BASE = 0x00400000
# Subfolders of data/ that the game reads; missing ones mean an incomplete install.
# The 2026 release keeps per-language kernel/battle text and movies under lang-xx/.
EXPECT_DATA_DIRS = ['battle', 'field', 'menu', 'minigame', 'movies', 'music', 'sound', 'wm',
                    'lang-en/kernel', 'lang-en/battle']


def sha1(path):
    h = hashlib.sha1()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def check_exe(path, errors):
    b = open(path, 'rb').read(4096)
    pe = struct.unpack_from('<I', b, 0x3C)[0]
    machine = struct.unpack_from('<H', b, pe + 4)[0]
    entry, = struct.unpack_from('<I', b, pe + 24 + 16)
    base, = struct.unpack_from('<I', b, pe + 24 + 28)
    if machine != 0x14C:
        errors.append(f'ff7_en.exe is not x86-32 (machine 0x{machine:x})')
    if base != EXPECT_IMAGE_BASE or base + entry != EXPECT_ENTRY_VA:
        errors.append(f'ff7_en.exe entry 0x{base + entry:08x} is not the 1.02 US build (want 0x{EXPECT_ENTRY_VA:08x})')
    if b'AF3DN.P' not in open(path, 'rb').read():
        errors.append('ff7_en.exe does not import AF3DN.P')


def main():
    quick = '--quick' in sys.argv
    errors = []
    exe = os.path.join(GAME, 'ff7_en.exe')
    data = os.path.join(GAME, 'data')
    if not os.path.isfile(exe):
        errors.append('game/ff7_en.exe missing (run tools/import_game.py)')
    if not os.path.isdir(data):
        errors.append('game/data missing (run tools/import_game.py)')
    if errors:
        sys.exit('\n'.join('FAIL ' + e for e in errors))

    check_exe(exe, errors)
    for d in EXPECT_DATA_DIRS:
        if not os.path.isdir(os.path.join(data, d)):
            errors.append(f'game/data/{d} missing')

    result = {'ff7_en.exe': {'sha1': sha1(exe), 'size': os.path.getsize(exe)}, 'data': {}}
    if not quick:
        n = 0
        for dirpath, _, files in os.walk(data):
            for name in sorted(files):
                p = os.path.join(dirpath, name)
                rel = os.path.relpath(p, data).replace('\\', '/')
                result['data'][rel] = {'sha1': sha1(p), 'size': os.path.getsize(p)}
                n += 1
                if n % 250 == 0:
                    print(f'  hashed {n} data files', flush=True)

    if os.path.exists(OUT):
        old = json.load(open(OUT, encoding='utf-8'))
        if old.get('ff7_en.exe') != result['ff7_en.exe']:
            errors.append('ff7_en.exe differs from the one recorded in analysis/inputs.json')

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if not errors:
        if quick and os.path.exists(OUT):
            result['data'] = json.load(open(OUT, encoding='utf-8')).get('data', {})
        json.dump(result, open(OUT, 'w', encoding='utf-8'), indent=1)

    print(f"ff7_en.exe sha1 {result['ff7_en.exe']['sha1']}")
    print(f"data files hashed: {len(result['data'])}")
    if errors:
        sys.exit('\n'.join('FAIL ' + e for e in errors))
    print('PASS')


if __name__ == '__main__':
    main()
