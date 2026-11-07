"""Which of ff7_en.exe's imports does pcrecomp's win32hle already implement?

Writes analysis/import_coverage.md and prints a per-DLL summary.
usage: py -I tools/import_coverage.py
"""
import collections
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PCRECOMP = os.path.join(ROOT, 'external', 'pcrecomp')
sys.path.insert(0, os.path.join(PCRECOMP, 'tools', 'pe'))
from pe_analyze import analyze_pe, build_iat_map  # noqa: E402

HLE = os.path.join(PCRECOMP, 'runtime', 'win32hle')


def hle_names():
    """Every identifier that appears as a quoted import name in win32hle sources."""
    names = set()
    for dirpath, _, files in os.walk(HLE):
        for f in files:
            if f.endswith(('.c', '.h', '.inc')):
                text = open(os.path.join(dirpath, f), encoding='utf-8', errors='replace').read()
                names.update(re.findall(r'"([A-Za-z_][A-Za-z0-9_@]*)"', text))
    return names


def main():
    info = analyze_pe(os.path.join(ROOT, 'game', 'ff7_en.exe'))
    iat = build_iat_map(info)
    have = hle_names()
    by_dll = collections.defaultdict(list)
    for _, (dll, name) in sorted(iat.items()):
        by_dll[dll.upper()].append((name, name in have))
    lines = ['# ff7_en.exe imports vs win32hle', '']
    total = done = 0
    for dll in sorted(by_dll):
        rows = by_dll[dll]
        n_ok = sum(ok for _, ok in rows)
        total += len(rows)
        done += n_ok
        print(f'{dll:14s} {n_ok:3d}/{len(rows):3d}')
        lines.append(f'## {dll} ({n_ok}/{len(rows)})')
        lines += [f'- [{"x" if ok else " "}] {name}' for name, ok in rows]
        lines.append('')
    print(f'{"TOTAL":14s} {done:3d}/{total:3d}')
    os.makedirs(os.path.join(ROOT, 'analysis'), exist_ok=True)
    open(os.path.join(ROOT, 'analysis', 'import_coverage.md'), 'w', encoding='utf-8').write('\n'.join(lines))


if __name__ == '__main__':
    main()
