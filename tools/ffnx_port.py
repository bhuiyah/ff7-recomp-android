"""Source transforms that make FFNx's headers describe the *guest's* 32-bit memory on a
64-bit host. Run on a pristine external/FFNx (tools/setup.ps1 does it after patches);
idempotent per file (a marker line records that a file is converted).

  gptr   in every struct that lives in game memory, rewrite members:
           T *x;            -> gptr<T> x;
           T **x;           -> gptr<gptr<T>> x;
           T *x[N];         -> gptr<T> x[N];
           R (*f)(A);       -> gfn<R(A)> f;            (__thiscall: gfn_thiscall<...>)
           fntype *f;       -> gfn<fntype> f;          (fntype = a function typedef)
         Host-side structs (FFNx's own address tables) keep native data pointers but
         their function-pointer members still become gfn, because FFNx calls the game
         through them.

usage: py -I tools/ffnx_port.py gptr [--check]
  --check  report what would change without writing
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FFNX = os.path.join(ROOT, 'external', 'FFNx', 'src')
MARKER = '// FF7Recomp: guest-layout members (tools/ffnx_port.py gptr)'

# Structs whose instances are FFNx host memory, not game memory.
HOST_STRUCTS = {
    'common_externals', 'ff7_externals', 'ff8_externals', 'driver_stats', 'game_mode', 'light_data',
}

# The headers that describe game memory. The ff7/ and ff8/ subfolders hold FFNx's own
# classes (effect decorators, texture packer, mod loader...): host objects that merely
# point into game memory, so their native pointers are right and they are not touched.
HEADERS = ['common_imports.h', 'common.h', 'ff7.h', 'ff8.h']

FN_TYPEDEF = re.compile(r'^\s*typedef\s+[^;()]+?\(\s*(\w+)\s*\)\s*\(.*\)\s*;|^\s*typedef\s+[^;()]*?[\s*](\w+)\s*\([^()]*\)\s*;', re.M)
FN_MEMBER = re.compile(r'^(?P<ind>\s*)(?P<ret>[^;()]+?)\s*\(\s*(?P<cc>__\w+\s+)?\*\s*(?P<name>\w+)\s*\)\s*\((?P<args>.*)\)\s*;(?P<tail>.*)$')
PTR_MEMBER = re.compile(r'^(?P<ind>\s*)(?P<type>(?:(?:const|volatile|struct|union|enum|unsigned|signed)\s+)*[\w:]+(?:\s*<[^;]*?>)?(?:\s+(?:const|int|char|long|short))*)\s*(?P<decls>\*[^;]*);(?P<tail>.*)$')
DECL = re.compile(r'^\s*(?P<stars>\*+)\s*(?:const\s+)?(?P<name>\w+)(?P<arr>(?:\s*\[[^\]]*\])*)\s*(?P<init>=\s*[^,]+?)?\s*$')


def fn_typedefs():
    names = set()
    for h in ['common_imports.h', 'common.h', 'ff7.h', 'ff8.h']:
        p = os.path.join(FFNX, h)
        if os.path.exists(p):
            for m in FN_TYPEDEF.finditer(open(p, encoding='utf-8', errors='replace').read()):
                names.add(m.group(1) or m.group(2))
    return names


def nest(t, k):
    for _ in range(k):
        t = f'gptr<{t}>'
    return t


# Windows pointer typedefs used as game-struct members: they hide their '*'.
TYPEDEF_PTRS = {
    'LPVOID': 'void', 'PVOID': 'void', 'LPSTR': 'char', 'LPCSTR': 'const char',
    'LPWAVEFORMATEX': 'WAVEFORMATEX', 'LPDIRECTDRAWSURFACE': 'IDirectDrawSurface',
    'LPDIRECTDRAW': 'IDirectDraw', 'LPDIRECTSOUNDBUFFER': 'IDirectSoundBuffer',
    'LPDIRECTSOUND': 'IDirectSound',
}
# Pointer-to-pointer typedefs in host tables: a host pointer to a guest pointer cell.
HOST_PTRPTR = {'LPLPDIRECTSOUND': 'IDirectSound', 'LPLPDIRECTDRAW': 'IDirectDraw'}
HOST_PTRPTR_TYPEDEF = re.compile(r'^(?P<ind>\s*)(?P<t>' + '|'.join(HOST_PTRPTR) + r')\s+(?P<name>\w+)\s*;(?P<tail>.*)$')
TYPEDEF_MEMBER = re.compile(r'^(?P<ind>\s*)(?P<t>' + '|'.join(TYPEDEF_PTRS) + r')\s+(?P<name>\w+)(?P<arr>(?:\s*\[[^\]]*\])*)\s*;(?P<tail>.*)$')


SPAN_OF_PTRS = re.compile(r'std::span<\s*((?:struct\s+)?[\w:]+)\s*\*\s*>')


def convert_line(line, host, fntypes, stats):
    if host and SPAN_OF_PTRS.search(line):
        # A span over guest memory holding pointers: its elements are 4-byte cells.
        stats['ptr'] += 1
        return SPAN_OF_PTRS.sub(lambda m: f'std::span<gptr<{m.group(1)}>>', line)
    m = HOST_PTRPTR_TYPEDEF.match(line) if host else None
    if m:
        stats['ptr'] += 1
        return f"{m.group('ind')}gptr<{HOST_PTRPTR[m.group('t')]}> *{m.group('name')};{m.group('tail')}"
    if not host:
        m = TYPEDEF_MEMBER.match(line)
        if m:
            stats['ptr'] += 1
            return f"{m.group('ind')}gptr<{TYPEDEF_PTRS[m.group('t')]}> {m.group('name')}{m.group('arr') or ''};{m.group('tail')}"
    m = FN_MEMBER.match(line)
    if m and '=' not in m.group('ret'):
        cc = (m.group('cc') or '').strip()
        tmpl = {'__thiscall': 'gfn_thiscall', '__stdcall': 'gfn_stdcall'}.get(cc, 'gfn')
        stats['fn'] += 1
        return f"{m.group('ind')}{tmpl}<{m.group('ret').strip()}({m.group('args').strip()})> {m.group('name')};{m.group('tail')}"
    m = PTR_MEMBER.match(line)
    if not m:
        return line
    base = m.group('type').strip()
    if base in ('return', 'typedef', 'using', 'delete', 'else', 'case', 'goto') or base.startswith(('typedef', 'friend', 'static')):
        return line
    out = []
    changed = False
    for d in m.group('decls').split(','):
        dm = DECL.match(d)
        if not dm:
            return line                      # something unusual: leave it for the compiler to flag
        stars, name, arr = len(dm.group('stars')), dm.group('name'), dm.group('arr') or ''
        init = f" {dm.group('init').strip()}" if dm.group('init') else ''
        if base in TYPEDEF_PTRS:             # LPDIRECTSOUND *x == IDirectSound **x
            base, stars = TYPEDEF_PTRS[base], stars + 1
        if base in fntypes and stars == 1:
            # A game function, wherever the pointer is stored.
            out.append(f'gfn<{base}> {name}{arr}{init};')
            stats['fntype'] += 1
            changed = True
        elif host:
            # Host storage: the outer pointer stays native, but any further level points at
            # a 4-byte guest pointer cell (T **p on Windows = host pointer to a guest slot).
            if stars >= 2:
                out.append(f'{nest(base, stars - 1)} *{name}{arr}{init};')
                stats['ptr'] += 1
                changed = True
            else:
                out.append(f'{base} *{name}{arr}{init};')
        else:
            out.append(f'{nest(base, stars)} {name}{arr}{init};')
            stats['ptr'] += 1
            changed = True
    if not changed:
        return line
    return m.group('ind') + ' '.join(out) + m.group('tail')


def convert_text(text, fntypes, stats):
    lines = text.split('\n')
    out = []
    depth = 0            # brace depth inside the current top-level struct/union
    host = False
    in_struct = False
    for line in lines:
        code = line.split('//', 1)[0]
        if not in_struct:
            m = re.match(r'^\s*(?:typedef\s+)?(struct|union|class)\s+(\w+)?\s*(?::[^{]*)?(\{)?\s*$', code)
            if m:
                kind, name = m.group(1), m.group(2) or ''
                # A struct definition starts here if this line or the next opens a brace;
                # forward declarations end in ';' and do not match the pattern above.
                # Classes are FFNx host objects.
                in_struct = True
                host = name in HOST_STRUCTS or kind == 'class'
                depth = code.count('{') - code.count('}')
                out.append(line)
                if depth == 0 and '{' in code:
                    in_struct = False
                continue
            out.append(line)
            continue
        if depth >= 1 and ';' in code and '{' not in code and '}' not in code:
            new = convert_line(line, host, fntypes, stats)
            out.append(new)
        else:
            out.append(line)
        depth += code.count('{') - code.count('}')
        if depth <= 0 and '}' in code:
            in_struct = False
            depth = 0
    return '\n'.join(out)


def cmd_gptr(check):
    fntypes = fn_typedefs()
    total = {'ptr': 0, 'fn': 0, 'fntype': 0}
    for rel in HEADERS:
        path = os.path.join(FFNX, rel)
        raw = open(path, 'rb').read()
        text = raw.decode('utf-8', errors='surrogateescape')
        if MARKER in text:
            print(f'  {rel}: already converted')
            continue
        crlf = '\r\n' in text
        if crlf:
            text = text.replace('\r\n', '\n')
        stats = {'ptr': 0, 'fn': 0, 'fntype': 0}
        new = convert_text(text, fntypes, stats)
        if not any(stats.values()):
            continue
        for k in total:
            total[k] += stats[k]
        print(f'  {rel}: {stats["ptr"]} pointers, {stats["fn"]} fn pointers, {stats["fntype"]} fn-typedef pointers')
        if check:
            continue
        idx = new.find('#pragma once')
        if idx >= 0:
            eol = new.index('\n', idx)
            new = new[:eol + 1] + MARKER + '\n#include "ffnx_guest.h"\n' + new[eol + 1:]
        else:
            new = MARKER + '\n#include "ffnx_guest.h"\n' + new
        if crlf:
            new = new.replace('\n', '\r\n')
        open(path, 'wb').write(new.encode('utf-8', errors='surrogateescape'))
    print(f'total: {total["ptr"]} pointers, {total["fn"]} fn pointers, {total["fntype"]} fn-typedef pointers'
          + (' (check only)' if check else ''))


# Files that fill the host address tables: `(T **)get_absolute_value(...)` must become
# `(gptr<T> *)...`, matching the members converted above (a host pointer to a 4-byte
# guest pointer cell).
CAST_FILES = ['ff7_data.h', 'ff8_data.cpp']
CAST_MARKER = '// FF7Recomp: guest pointer-cell casts (tools/ffnx_port.py casts)'
# Not preceded by "(*)": that would be a function-pointer parameter list, not a cast.
PTRPTR_CAST = re.compile(r'(?<!\(\*\))\(\s*((?:const\s+)?(?:struct\s+)?[\w:]+(?:<[^()]*?>)?)\s*\*\s*\*\s*\)')
# A local `T **x = (gptr<T> *)...` must hold the cell type too.
PTRPTR_LOCAL = re.compile(r'\b((?:struct\s+)?[\w:]+)\s*\*\*\s*(\w+)\s*=\s*\(gptr<\1> \*\)')


def cmd_casts():
    for rel in CAST_FILES:
        path = os.path.join(FFNX, rel)
        if not os.path.exists(path):
            continue
        text = open(path, 'rb').read().decode('utf-8', errors='surrogateescape')
        if CAST_MARKER in text:
            print(f'  {rel}: already converted')
            continue
        new, n = PTRPTR_CAST.subn(lambda m: f'(gptr<{m.group(1)}> *)', text)
        new = PTRPTR_LOCAL.sub(lambda m: f'gptr<{m.group(1)}> *{m.group(2)} = (gptr<{m.group(1)}> *)', new)
        nl = '\r\n' if '\r\n' in new else '\n'
        new = CAST_MARKER + nl + new
        open(path, 'wb').write(new.encode('utf-8', errors='surrogateescape'))
        print(f'  {rel}: {n} pointer-cell casts')


OVERRIDES = os.path.join(ROOT, 'src', 'ffnx_port', 'override')


def cmd_overrides():
    """Copy src/ffnx_port/override/** over external/FFNx/src/** (same relative paths).
    FFNx includes these with quotes from its own directory, so they have to be in-tree."""
    n = 0
    for dirpath, _, files in os.walk(OVERRIDES):
        for name in files:
            src = os.path.join(dirpath, name)
            rel = os.path.relpath(src, OVERRIDES)
            dst = os.path.join(FFNX, rel)
            data = open(src, 'rb').read()
            if not os.path.exists(dst) or open(dst, 'rb').read() != data:
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                open(dst, 'wb').write(data)
                print(f'  override {rel}')
            n += 1
    print(f'{n} override file(s) in place')


if __name__ == '__main__':
    if len(sys.argv) >= 2 and sys.argv[1] == 'gptr':
        cmd_gptr('--check' in sys.argv)
    elif len(sys.argv) >= 2 and sys.argv[1] == 'overrides':
        cmd_overrides()
    elif len(sys.argv) >= 2 and sys.argv[1] == 'casts':
        cmd_casts()
    else:
        sys.exit(__doc__)
