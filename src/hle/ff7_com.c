/*
 * ff7_com.c - guest-visible COM objects backed by host shims. See ff7_com.h.
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "guest_alloc.h"
#include "ff7_com.h"

uint32_t ff7_com_vtable(const ff7_com_method *methods, int count) {
    uint32_t *vt = (uint32_t *)guest_calloc((size_t)count, 4);
    for (int i = 0; i < count; i++) {
        const ff7_com_method *m = &methods[i];
        uint32_t va = 0;
        if (m->fn) {
            win32hle_shim one[2] = { { m->name, m->fn }, { NULL, NULL } };
            va = hle_resolve(m->name);
            if (!va) { win32hle_register(one); va = hle_resolve(m->name); }
        } else {
            va = hle_resolve_or_stub(m->name);          /* stops naming the method if called */
        }
        vt[i] = va;
    }
    return (uint32_t)(uintptr_t)vt;
}

uint32_t ff7_com_new(uint32_t vtable, uint32_t state_size) {
    uint32_t *obj = (uint32_t *)guest_calloc(1, 8u + state_size);
    obj[0] = vtable;
    obj[1] = 1;                                         /* reference count */
    return (uint32_t)(uintptr_t)obj;
}

uint32_t ff7_guest_strdup(const char *s) {
    size_t n = strlen(s) + 1;
    char *p = (char *)guest_malloc(n);
    memcpy(p, s, n);
    return (uint32_t)(uintptr_t)p;
}
