"""List the system/third-party headers an FFNx translation unit needs that the Android
build cannot find yet (walks quoted includes through FFNx's sources; checks <...> ones
against our include dirs, the deps checkouts and the NDK sysroot).

usage: py -I tools/ffnx_missing_headers.py common.cpp [more.cpp ...]
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FFNX = os.path.join(ROOT, 'external', 'FFNx', 'src')
SYSROOT = os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk', 'ndk', '28.2.13676358',
                       'toolchains', 'llvm', 'prebuilt', 'windows-x86_64', 'sysroot', 'usr', 'include')
LIBCXX = os.path.join(SYSROOT, 'c++', 'v1')
SEARCH = [
    os.path.join(ROOT, 'src', 'ffnx_port', 'include'),
    os.path.join(ROOT, 'src', 'ffnx_port', 'include_stub_ffmpeg'),
    os.path.join(ROOT, 'src', 'ffnx_bridge'),
    os.path.join(ROOT, 'external', 'deps', 'tomlplusplus', 'include'),
    os.path.join(ROOT, 'external', 'deps', 'imgui'),
    os.path.join(ROOT, 'external', 'deps', 'xxhash'),
    os.path.join(ROOT, 'external', 'bgfx', 'include'),
    os.path.join(ROOT, 'external', 'bx', 'include'),
    os.path.join(ROOT, 'external', 'bimg', 'include'),
    os.path.join(ROOT, 'external', 'pcrecomp', 'runtime', 'recomp32'),
    os.path.join(ROOT, 'external', 'pcrecomp', 'runtime', 'win32hle'),
    os.path.join(ROOT, 'src', 'host'),
    FFNX, LIBCXX, SYSROOT, os.path.join(SYSROOT, 'aarch64-linux-android'),
]
INC = re.compile(r'^\s*#\s*include\s*([<"])([^>"]+)[>"]', re.M)


def find(name, here):
    for d in [here] + SEARCH:
        p = os.path.join(d, name)
        if os.path.isfile(p):
            return p
    return None


def main(units):
    seen, missing = set(), {}
    stack = [os.path.join(FFNX, u) for u in units]
    while stack:
        path = stack.pop()
        if path in seen:
            continue
        seen.add(path)
        text = open(path, encoding='utf-8', errors='replace').read()
        for kind, name in INC.findall(text):
            p = find(name, os.path.dirname(path))
            if p:
                if p.startswith(FFNX) or kind == '"':
                    stack.append(p)
            else:
                missing.setdefault(name, set()).add(os.path.relpath(path, FFNX))
    # The cmrc header is generated at configure time.
    missing.pop('cmrc/cmrc.hpp', None)
    for name in sorted(missing):
        print(f'{name:40s} <- {", ".join(sorted(missing[name])[:4])}')
    print(f'{len(missing)} missing headers, {len(seen)} files walked')


if __name__ == '__main__':
    main(sys.argv[1:] or ['common.cpp'])
