"""Checks tools/ffnx_port.py's function-pointer member conversion.

A game function that FFNx stores in a host table must become a gfn (a guest call). A
member left as a native function pointer makes FFNx jump to the 32-bit guest address as
host code (issue #23: `int (_stdcall *world_submit_draw_effects_75C283)(...)`, spelled with
one underscore, stayed native and crashed the world map with Cosmos Gaia's external mesh).

The second check scans the pristine FFNx headers for any member the converter would leave
as a native function pointer.

usage: py -I tests/ffnx_port/test_fn_members.py
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import ffnx_port  # noqa: E402

CASES = [
    ('\tint (_stdcall *world_submit_draw_effects_75C283)(world_texture_data*, int, vector3<short>*, short);',
     '\tgfn_stdcall<int(world_texture_data*, int, vector3<short>*, short)> world_submit_draw_effects_75C283;'),
    ('\tint (__stdcall *f)(int);', '\tgfn_stdcall<int(int)> f;'),
    ('\tvoid (__thiscall *g)(void *);', '\tgfn_thiscall<void(void *)> g;'),
    ('\tvoid (_thiscall *g)(void *);', '\tgfn_thiscall<void(void *)> g;'),
    ('\tvoid (__cdecl *h)();', '\tgfn<void()> h;'),
    ('\tvoid (_cdecl *h)();', '\tgfn<void()> h;'),
    ('\tvoid (*h)();', '\tgfn<void()> h;'),
]

RAW_FN = re.compile(r'\(\s*(?:_{1,2}\w+\s+)?\*\s*\w+\s*\)\s*\(')

fails = 0
for host in (True, False):
    for src, want in CASES:
        stats = {'ptr': 0, 'fn': 0, 'fntype': 0}
        got = ffnx_port.convert_line(src, host, set(), stats)
        if got != want:
            print(f'FAIL host={host}: {src!r}\n  got  {got!r}\n  want {want!r}')
            fails += 1

# The pristine headers, converted in memory: no struct member may stay a native fn pointer.
ffnx = os.path.join(ROOT, 'external', 'FFNx')
fntypes = ffnx_port.fn_typedefs()
for h in ffnx_port.HEADERS:
    try:
        text = subprocess.run(['git', '-C', ffnx, 'show', f'HEAD:src/{h}'], capture_output=True,
                              text=True, encoding='utf-8', errors='replace', check=True).stdout
    except subprocess.CalledProcessError:
        continue
    stats = {'ptr': 0, 'fn': 0, 'fntype': 0}
    out = ffnx_port.convert_text(text, fntypes, stats)
    for before, after in zip(text.split('\n'), out.split('\n')):
        code = after.split('//', 1)[0]
        if before == after and RAW_FN.search(code) and code.strip().endswith(';') \
                and not code.lstrip().startswith(('typedef', 'gfn', 'static', 'extern', 'using')) \
                and '{' not in code:
            # Only lines the converter looked at (struct members) matter; a function
            # declaration taking a callback is not a member.
            if ffnx_port.FN_MEMBER.match(after) is None and re.match(r'^\s*[^(;]+\(\s*_{1,2}\w+\s+\*', after):
                print(f'FAIL {h}: native fn-pointer member left: {after.strip()}')
                fails += 1

print('ok' if not fails else f'{fails} failure(s)')
sys.exit(1 if fails else 0)
