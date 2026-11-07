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
}
TYPEDEF_MEMBER = re.compile(r'^(?P<ind>\s*)(?P<t>' + '|'.join(TYPEDEF_PTRS) + r')\s+(?P<name>\w+)(?P<arr>(?:\s*\[[^\]]*\])*)\s*;(?P<tail>.*)$')


def convert_line(line, host, fntypes, stats):
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
    if host:
        return line
    m = PTR_MEMBER.match(line)
    if not m:
        return line
    base = m.group('type').strip()
    if base in ('return', 'typedef', 'using', 'delete', 'else', 'case', 'goto') or base.startswith(('typedef', 'friend', 'static')):
        return line
    out = []
    for d in m.group('decls').split(','):
        dm = DECL.match(d)
        if not dm:
            return line                      # something unusual: leave it for the compiler to flag
        stars, name, arr = len(dm.group('stars')), dm.group('name'), dm.group('arr') or ''
        init = f" {dm.group('init').strip()}" if dm.group('init') else ''
        if base in fntypes and stars == 1:
            out.append(f'gfn<{base}> {name}{arr}{init};')
            stats['fntype'] += 1
        else:
            out.append(f'{nest(base, stars)} {name}{arr}{init};')
            stats['ptr'] += 1
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


if __name__ == '__main__':
    if len(sys.argv) >= 2 and sys.argv[1] == 'gptr':
        cmd_gptr('--check' in sys.argv)
    else:
        sys.exit(__doc__)
