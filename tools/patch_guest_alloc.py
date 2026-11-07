"""Route win32hle's guest-visible allocations through guest_alloc (one-off edit of
external/pcrecomp; the result is captured as patches/pcrecomp/0015-*.patch).

usage: py -I tools/patch_guest_alloc.py
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HLE = os.path.join(ROOT, 'external', 'pcrecomp', 'runtime', 'win32hle')

EDITS = {
    'kernel32.c': [
        ('void *p = (flags & HEAP_ZERO_MEMORY) ? calloc(1, size) : malloc(size);',
         'void *p = (flags & HEAP_ZERO_MEMORY) ? guest_calloc(1, size) : guest_malloc(size);'),
        ('static void k_HeapFree(void)   { free(APTR(2)); RET(1, 3); }',
         'static void k_HeapFree(void)   { guest_free(APTR(2)); RET(1, 3); }'),
        ('static void k_HeapReAlloc(void){ RETP(realloc(APTR(2), A32(3)), 4); }',
         'static void k_HeapReAlloc(void){ RETP(guest_realloc(APTR(2), A32(3)), 4); }'),
        ('RETP(flags&0x40?calloc(1,n):malloc(n), 2); }',
         'RETP(flags&0x40?guest_calloc(1,n):guest_malloc(n), 2); }'),
        ('static void k_LocalFree(void)  { free(APTR(0)); RET(0, 1); }',
         'static void k_LocalFree(void)  { guest_free(APTR(0)); RET(0, 1); }'),
        ('RETP(addr ? (void *)(uintptr_t)addr : calloc(1, size), 4);',
         'RETP(addr ? (void *)(uintptr_t)addr : guest_calloc(1, size), 4);'),
    ],
    'kernel32_ext.c': [
        ('RETP(fl & GMEM_ZEROINIT ? calloc(1, n) : malloc(n), 2); }',
         'RETP(fl & GMEM_ZEROINIT ? guest_calloc(1, n) : guest_malloc(n), 2); }'),
        ('static void k_GlobalFree(void)  { free(APTR(0)); RET(0, 1); }',
         'static void k_GlobalFree(void)  { guest_free(APTR(0)); RET(0, 1); }'),
        ('RETP(realloc(APTR(0), A32(1)), 3); }',
         'RETP(guest_realloc(APTR(0), A32(1)), 3); }'),
    ],
    'host_lite.c': [
        ('uint32_t *t = (uint32_t *)calloc(1, 0x1000);',
         'uint32_t *t = (uint32_t *)guest_calloc(1, 0x1000);'),
        ('uint32_t lo = (uint32_t)(uintptr_t)calloc(1, GUEST_STACK);',
         'uint32_t lo = (uint32_t)(uintptr_t)guest_calloc(1, GUEST_STACK);'),
    ],
    'gdi32.c': [
        ('uint32_t *bits = (uint32_t *)calloc((size_t)w * hab, 4);',
         'uint32_t *bits = (uint32_t *)guest_calloc((size_t)w * hab, 4);'),
        ('free(g_dibs[i].bits); g_dibs[i].bits = NULL;',
         'guest_free(g_dibs[i].bits); g_dibs[i].bits = NULL;'),
    ],
}


def main():
    for name, edits in EDITS.items():
        path = os.path.join(HLE, name)
        raw = open(path, 'rb').read()
        text = raw.decode('utf-8')
        for old, new in edits:
            if text.count(old) != 1:
                sys.exit(f'{name}: expected exactly one match for: {old!r} (found {text.count(old)})')
            text = text.replace(old, new)
        include = '#include "guest_alloc.h"\n'
        if include not in text:
            idx = text.index('#include "win32hle.h"')
            text = text[:idx] + include + text[idx:]
        open(path, 'wb').write(text.encode('utf-8'))
        print(f'patched {name} ({len(edits)} edits)')


if __name__ == '__main__':
    main()
