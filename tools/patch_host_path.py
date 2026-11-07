"""Add a host-path hook to win32hle's file shims (captured as patches/pcrecomp/0016-*.patch).

hle_host_path(guest, buf, n) turns a guest Win32 path into the host path to open. The weak
default returns the path unchanged; a title supplies a strong one (drive letters,
backslashes, case-insensitive lookup on case-sensitive filesystems).

usage: py -I tools/patch_host_path.py
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HLE = os.path.join(ROOT, 'external', 'pcrecomp', 'runtime', 'win32hle')


def edit(name, pairs):
    path = os.path.join(HLE, name)
    text = open(path, 'rb').read().decode('utf-8')
    # These files mix CRLF and LF lines; match each edit in whichever style it is
    # written in, and never normalise the whole file.
    for old, new in pairs:
        variants = ((old, new), (old.replace('\n', '\r\n'), new.replace('\n', '\r\n')))
        for o, n in variants:
            if n in text:
                break                    # already applied
            if text.count(o) == 1:
                text = text.replace(o, n)
                break
        else:
            sys.exit(f'{name}: expected exactly one match for {old!r}')
    open(path, 'wb').write(text.encode('utf-8'))
    print(f'patched {name} ({len(pairs)} edits)')


HOOK_DECL = (
    '/* ---- host paths ----\n'
    ' * The path a file shim should open for a guest Win32 path. The weak default in\n'
    ' * kernel32_ext.c returns `guest` unchanged; a title may define it to map drive\n'
    ' * letters, backslashes and case. `buf` (n bytes) is scratch the result may use. */\n'
    'const char *hle_host_path(const char *guest, char *buf, unsigned n);\n'
    '\n'
)

edit('win32hle.h', [
    ('/* ---- native -> guest ----', HOOK_DECL + '/* ---- native -> guest ----'),
])

edit('kernel32_ext.c', [
    ('#define INVALID_HANDLE  0xFFFFFFFFu\n',
     '#define INVALID_HANDLE  0xFFFFFFFFu\n\n'
     '__attribute__((weak)) const char *hle_host_path(const char *guest, char *buf, unsigned n) {\n'
     '    (void)buf; (void)n; return guest;\n'
     '}\n'),
    ('    FILE *f = name ? fopen(name, mode) : NULL;\n'
     '    if (!f && disp == 4 && name) f = fopen(name, "wb+");  /* open-always: make it */',
     '    char hp[1024];\n'
     '    const char *host = name ? hle_host_path(name, hp, sizeof hp) : NULL;\n'
     '    FILE *f = host ? fopen(host, mode) : NULL;\n'
     '    if (!f && disp == 4 && host) f = fopen(host, "wb+");  /* open-always: make it */'),
])
