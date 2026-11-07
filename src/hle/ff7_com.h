/*
 * ff7_com.h - guest-visible COM objects whose methods are host shims.
 *
 * A COM object is a guest pointer to [vtable][refcount][state...]; the vtable is an
 * array of guest VAs. Each method is registered as a shim named "Interface::Method",
 * so lifted code calls it like any import (indirect call through a synthetic VA), and
 * a method with no implementation resolves to win32hle's self-naming stub: the first
 * call stops the run with its name.
 *
 * Methods are stdcall with `this` as argument 0, so A32(0) is the object.
 */
#ifndef FF7_COM_H
#define FF7_COM_H
#include <stdint.h>
#include "win32hle.h"

typedef struct { const char *name; recomp_func_t fn; } ff7_com_method;  /* fn NULL: not implemented */

/* Build a vtable (guest memory) from `count` methods. Names must outlive the run. */
uint32_t ff7_com_vtable(const ff7_com_method *methods, int count);
/* A new object: [vtable][refcount = 1][state_size bytes of zeroed state]. */
uint32_t ff7_com_new(uint32_t vtable, uint32_t state_size);
/* Object state: the bytes after vtable and refcount. */
#define FF7_COM_STATE(obj) ((uint8_t *)(uintptr_t)(obj) + 8)
#define FF7_COM_REFS(obj)  (((uint32_t *)(uintptr_t)(obj))[1])

/* A guest copy of a host string (for callbacks that receive strings). */
uint32_t ff7_guest_strdup(const char *s);

#endif
