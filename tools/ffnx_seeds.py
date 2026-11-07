"""Function seeds for the catalog from FFNx's 1.02 US address table.

FFNx names ~80 game functions by address in src/externals_102_us.h. Entries cast to a data
pointer type (char*, DWORD*, struct*) or outside .text are data and are skipped.
Writes analysis/seeds.json: [{"address": "0x...", "name": "...", "source": "ffnx"}].

usage: py -I tools/ffnx_seeds.py
"""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'external', 'FFNx', 'src', 'externals_102_us.h')
OUT = os.path.join(ROOT, 'analysis', 'seeds.json')
TEXT_START, TEXT_END = 0x401000, 0x401000 + 0x3B5000   # ff7_en .text

LINE = re.compile(r'^\s*(\w+)\.(\w+)\s*=\s*(\([^;]*\))?\s*(0x[0-9A-Fa-f]+)\s*;')


def main():
    seeds = []
    for line in open(SRC, encoding='utf-8'):
        m = LINE.match(line)
        if not m:
            continue
        table, name, cast, addr = m.group(1), m.group(2), m.group(3) or '', int(m.group(4), 16)
        is_data_cast = cast and '(*)' not in cast and cast.strip() not in ('(WNDPROC)',)
        if is_data_cast or not (TEXT_START <= addr < TEXT_END):
            continue
        seeds.append({'address': f'0x{addr:08X}', 'name': f'{table}.{name}', 'source': 'ffnx'})
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(seeds, open(OUT, 'w', encoding='utf-8'), indent=1)
    print(f'{len(seeds)} seeds -> {os.path.relpath(OUT, ROOT)}')


if __name__ == '__main__':
    main()
