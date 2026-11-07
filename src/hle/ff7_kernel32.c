/*
 * ff7_kernel32.c - the KERNEL32 imports ff7_en.exe needs that pcrecomp's win32hle does
 * not provide, on libc/POSIX. Registered before win32hle, so a name here also overrides
 * win32hle's version (HeapSize).
 *
 * Threading model: one machine lock serialises lifted code (host_lite.c), so plain
 * read-modify-write on guest memory is atomic with respect to other guest threads.
 * Anything that would block (a critical section owned by another thread) gives up the
 * machine lock while it waits.
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fnmatch.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#include "guest_alloc.h"
#include "win32hle.h"
#include "ff7_hle.h"

#define INVALID_HANDLE 0xFFFFFFFFu
#define W16(p)  (*(uint16_t *)(uintptr_t)(p))
#define W32(p)  (*(uint32_t *)(uintptr_t)(p))

static uint32_t guest_tid(void) { return (uint32_t)gettid(); }

/* ------------------------------------------------------------------ heaps
 * Every guest heap shares the guest arena; the handle only has to be unique and
 * nonzero. HeapAlloc/HeapFree/HeapReAlloc ignore it (win32hle). */
static uint32_t g_next_heap = 0x00F00010u;
static void k_HeapCreate(void)  { uint32_t h = g_next_heap; g_next_heap += 0x10u; RET(h, 3); }
static void k_HeapDestroy(void) { RET(1, 1); }
static void k_HeapSize(void)    { void *p = APTR(2); RET(p ? (uint32_t)guest_msize(p) : 0xFFFFFFFFu, 3); }

/* -------------------------------------------------------------------- TLS
 * Slots live in the simulated TEB like on Windows (TlsSlots at fs:[0xE10]), so each
 * guest thread has its own values with no host bookkeeping. */
#define TLS_SLOTS 64
static uint64_t g_tls_used;
static void k_TlsAlloc(void) {
    for (uint32_t i = 0; i < TLS_SLOTS; i++)
        if (!(g_tls_used & (1ull << i))) { g_tls_used |= 1ull << i; RET(i, 0); }
    RET(0xFFFFFFFFu, 0);                                         /* TLS_OUT_OF_INDEXES */
}
static void k_TlsGetValue(void) {
    uint32_t i = A32(0);
    if (i >= TLS_SLOTS) RET(0, 1);
    RET(W32(g_fs_base + 0xE10u + 4u * i), 1);
}
static void k_TlsSetValue(void) {
    uint32_t i = A32(0);
    if (i >= TLS_SLOTS) RET(0, 2);
    W32(g_fs_base + 0xE10u + 4u * i) = A32(1);
    RET(1, 2);
}
static void k_TlsFree(void) { uint32_t i = A32(0); if (i < TLS_SLOTS) g_tls_used &= ~(1ull << i); RET(1, 1); }

/* --------------------------------------------------------- critical sections
 * CRITICAL_SECTION: DebugInfo +0, LockCount +4, RecursionCount +8, OwningThread +0xC.
 * Owned elsewhere -> release the machine so the owner can run, then retry. */
static void k_InitializeCriticalSection(void) {
    uint32_t cs = A32(0);
    memset((void *)(uintptr_t)cs, 0, 24);
    W32(cs + 4) = 0xFFFFFFFFu;
    RETV(1);
}
static void k_DeleteCriticalSection(void) { RETV(1); }
static void k_EnterCriticalSection(void) {
    uint32_t cs = A32(0), me = guest_tid();
    for (;;) {
        uint32_t owner = W32(cs + 0xC);
        if (owner == 0 || owner == me) {
            W32(cs + 0xC) = me;
            W32(cs + 8) += 1;
            W32(cs + 4) += 1;
            RETV(1);
        }
        hle_yield_machine();
    }
}
static void k_LeaveCriticalSection(void) {
    uint32_t cs = A32(0);
    if (W32(cs + 8) && --W32(cs + 8) == 0) W32(cs + 0xC) = 0;
    W32(cs + 4) -= 1;
    RETV(1);
}

/* ------------------------------------------------------------ interlocked */
static void k_InterlockedIncrement(void) { uint32_t p = A32(0); RET(++W32(p), 1); }
static void k_InterlockedDecrement(void) { uint32_t p = A32(0); RET(--W32(p), 1); }
static void k_InterlockedExchange(void)  { uint32_t p = A32(0), old = W32(p); W32(p) = A32(1); RET(old, 2); }

/* ------------------------------------------------- command line, environment
 * win32hle returns host statics here, which sit above 4 GB on a 64-bit host, and
 * answers the W variant with the narrow block. These live in guest memory, built once.
 * The environment is deliberately empty: nothing in the guest should depend on the
 * Android shell's variables. */
static uint32_t g_cmdline_va, g_env_a_va, g_env_w_va;
static void k_GetCommandLineA(void) {
    if (!g_cmdline_va) {
        static const char cmd[] = "ff7_en.exe";
        char *p = (char *)guest_malloc(sizeof cmd);
        memcpy(p, cmd, sizeof cmd);
        g_cmdline_va = (uint32_t)(uintptr_t)p;
    }
    RET(g_cmdline_va, 0);
}
static void k_GetEnvironmentStrings(void) {
    if (!g_env_a_va) g_env_a_va = (uint32_t)(uintptr_t)guest_calloc(1, 4);   /* "\0\0" */
    RET(g_env_a_va, 0);
}
static void k_GetEnvironmentStringsW(void) {
    if (!g_env_w_va) g_env_w_va = (uint32_t)(uintptr_t)guest_calloc(1, 8);   /* L"\0\0" */
    RET(g_env_w_va, 0);
}
static void k_FreeEnvironmentStrings(void) { RET(1, 1); }    /* the block is kept for reuse */

/* Module path and current directory: win32hle answers with the title it was first
 * written for ("C:\FURY3X"); FF7 builds its log and data paths from these. */
static void k_GetModuleFileNameA(void) {           /* (hModule, buf, size) */
    char *buf = ASTR(1);
    uint32_t size = A32(2);
    if (!buf || !size) RET(0, 3);
    int n = snprintf(buf, size, "%s\\ff7_en.exe", FF7_GUEST_ROOT);
    RET(n < (int)size ? (uint32_t)n : size, 3);
}
static void k_GetCurrentDirectoryA(void) {         /* (size, buf) */
    uint32_t size = A32(0);
    char *buf = ASTR(1);
    uint32_t need = (uint32_t)strlen(FF7_GUEST_ROOT);
    if (!buf || size <= need) RET(need + 1, 2);
    memcpy(buf, FF7_GUEST_ROOT, need + 1);
    RET(need, 2);
}
/* GetProcAddress(hModule, name): nothing is dynamically loadable yet; log what the
 * game probes for (a name, or an ordinal when the high word is 0) and answer "absent". */
static void k_GetProcAddress(void) {
    uint32_t name = A32(1);
    if (name > 0xFFFFu) fprintf(stderr, "[ff7] GetProcAddress(0x%08X, \"%s\") -> NULL\n", A32(0), (const char *)(uintptr_t)name);
    else fprintf(stderr, "[ff7] GetProcAddress(0x%08X, #%u) -> NULL\n", A32(0), name);
    RET(0, 2);
}

/* GlobalMemoryStatus(MEMORYSTATUS*): dwLength +0, dwMemoryLoad +4, TotalPhys +8,
 * AvailPhys +0xC, TotalPageFile +0x10, AvailPageFile +0x14, TotalVirtual +0x18,
 * AvailVirtual +0x1C. FF7 refuses to start without swap ("No Swap Space"); win32hle
 * leaves the page-file and virtual fields at 0. Values are what a 2 GB user address
 * space on a machine with plenty of RAM reports, all within 32 bits. */
static void k_GlobalMemoryStatus(void) {
    uint32_t m = A32(0);
    if (m) {
        W32(m + 0x00) = 32;
        W32(m + 0x04) = 25;
        W32(m + 0x08) = 0x40000000u;   /* 1 GB physical */
        W32(m + 0x0C) = 0x30000000u;
        W32(m + 0x10) = 0x7FFF0000u;   /* 2 GB page file */
        W32(m + 0x14) = 0x7F000000u;
        W32(m + 0x18) = 0x7FFE0000u;   /* 2 GB user virtual */
        W32(m + 0x1C) = 0x70000000u;
    }
    RETV(1);
}

/* -------------------------------------------------------------- process */
static uint32_t g_error_mode;
static void k_SetErrorMode(void) { uint32_t old = g_error_mode; g_error_mode = A32(0); RET(old, 1); }
static int plausible(uint32_t p, uint32_t n) { return p >= 0x10000u && (uint64_t)p + n <= 0x80000000ull; }
static void k_IsBadReadPtr(void)  { RET(!plausible(A32(0), A32(1)), 2); }
static void k_IsBadWritePtr(void) { RET(!plausible(A32(0), A32(1)), 2); }
static void k_IsBadCodePtr(void)  { RET(!plausible(A32(0), 1), 1); }
static void k_SetEnvironmentVariableA(void) {
    const char *n = ASTR(0), *v = ASTR(1);
    if (!n) RET(0, 2);
    if (v) setenv(n, v, 1); else unsetenv(n);
    RET(1, 2);
}
static void k_FormatMessageA(void) {            /* (flags, src, id, lang, buf, size, args) */
    uint32_t flags = A32(0), id = A32(2), buf = A32(4), size = A32(5);
    char msg[64];
    int n = snprintf(msg, sizeof msg, "Win32 error %u\r\n", id);
    if (flags & 0x100u) {                       /* FORMAT_MESSAGE_ALLOCATE_BUFFER */
        char *p = (char *)guest_malloc((size_t)n + 1);
        memcpy(p, msg, (size_t)n + 1);
        W32(buf) = (uint32_t)(uintptr_t)p;
        RET((uint32_t)n, 7);
    }
    if (!buf || size <= (uint32_t)n) RET(0, 7);
    memcpy((void *)(uintptr_t)buf, msg, (size_t)n + 1);
    RET((uint32_t)n, 7);
}
/* GetVolumeInformationA(root, name, nameSize, serial*, maxComp*, flags*, fs, fsSize).
 * FF7 reads the disc label in its CD check; FFNx removes that check, but answer as
 * disc 1 so an unpatched path still passes. */
static void k_GetVolumeInformationA(void) {
    uint32_t name = A32(1), nsz = A32(2), serial = A32(3), maxc = A32(4), fl = A32(5), fs = A32(6), fsz = A32(7);
    if (name && nsz) snprintf((char *)(uintptr_t)name, nsz, "FF7DISC1");
    if (serial) W32(serial) = 0x07071997u;
    if (maxc) W32(maxc) = 255;
    if (fl) W32(fl) = 0;
    if (fs && fsz) snprintf((char *)(uintptr_t)fs, fsz, "CDFS");
    RET(1, 8);
}

/* ------------------------------------------------------------------- time
 * SYSTEMTIME: wYear, wMonth, wDayOfWeek, wDay, wHour, wMinute, wSecond, wMilliseconds.
 * FILETIME: 100 ns ticks since 1601-01-01 UTC. */
#define EPOCH_DIFF_100NS 116444736000000000ull
static void fill_systemtime(uint32_t p, const struct tm *t, unsigned ms) {
    W16(p + 0) = (uint16_t)(t->tm_year + 1900);
    W16(p + 2) = (uint16_t)(t->tm_mon + 1);
    W16(p + 4) = (uint16_t)t->tm_wday;
    W16(p + 6) = (uint16_t)t->tm_mday;
    W16(p + 8) = (uint16_t)t->tm_hour;
    W16(p + 10) = (uint16_t)t->tm_min;
    W16(p + 12) = (uint16_t)t->tm_sec;
    W16(p + 14) = (uint16_t)ms;
}
static void k_GetLocalTime(void) {
    struct timeval tv; gettimeofday(&tv, NULL);
    struct tm t; time_t s = tv.tv_sec; localtime_r(&s, &t);
    fill_systemtime(A32(0), &t, (unsigned)(tv.tv_usec / 1000));
    RETV(1);
}
static void k_GetSystemTime(void) {
    struct timeval tv; gettimeofday(&tv, NULL);
    struct tm t; time_t s = tv.tv_sec; gmtime_r(&s, &t);
    fill_systemtime(A32(0), &t, (unsigned)(tv.tv_usec / 1000));
    RETV(1);
}
static uint64_t filetime_of(uint32_t p) { return (uint64_t)W32(p) | ((uint64_t)W32(p + 4) << 32); }
static void put_filetime(uint32_t p, uint64_t v) { W32(p) = (uint32_t)v; W32(p + 4) = (uint32_t)(v >> 32); }
static uint64_t unix_to_filetime(time_t t) { return (uint64_t)t * 10000000ull + EPOCH_DIFF_100NS; }
static void k_FileTimeToLocalFileTime(void) {
    uint64_t v = filetime_of(A32(0));
    time_t s = (time_t)((v - EPOCH_DIFF_100NS) / 10000000ull);
    struct tm lt; localtime_r(&s, &lt);
    put_filetime(A32(1), v + (uint64_t)((int64_t)lt.tm_gmtoff * 10000000ll));
    RET(1, 2);
}
static void k_FileTimeToSystemTime(void) {
    uint64_t v = filetime_of(A32(0));
    time_t s = (time_t)((v - EPOCH_DIFF_100NS) / 10000000ull);
    struct tm t; gmtime_r(&s, &t);
    fill_systemtime(A32(1), &t, (unsigned)((v / 10000ull) % 1000ull));
    RET(1, 2);
}

/* ------------------------------------------------------------- files/dirs */
static void k_CreateDirectoryA(void) {
    char hp[1024]; const char *p = hle_host_path(ASTR(0), hp, sizeof hp);
    RET(p && (mkdir(p, 0775) == 0 || errno == EEXIST) ? 1 : 0, 2);
}
static void k_DeleteFileA(void) {
    char hp[1024]; const char *p = hle_host_path(ASTR(0), hp, sizeof hp);
    RET(p && unlink(p) == 0, 1);
}
static void k_MoveFileA(void) {
    char a[1024], b[1024];
    const char *pa = hle_host_path(ASTR(0), a, sizeof a), *pb = hle_host_path(ASTR(1), b, sizeof b);
    RET(pa && pb && rename(pa, pb) == 0, 2);
}
/* GetFullPathNameA(name, size, buf, &filepart): the guest believes it is installed at
 * FF7_GUEST_ROOT; relative names are joined to it in Win32 form. */
static void k_GetFullPathNameA(void) {
    const char *name = ASTR(0);
    uint32_t size = A32(1), buf = A32(2), fp = A32(3);
    char full[1024];
    if (!name) RET(0, 4);
    if (isalpha((unsigned char)name[0]) && name[1] == ':') snprintf(full, sizeof full, "%s", name);
    else snprintf(full, sizeof full, "%s\\%s", FF7_GUEST_ROOT, name[0] == '.' && name[1] == '\\' ? name + 2 : name);
    uint32_t n = (uint32_t)strlen(full);
    if (!buf || size <= n) RET(n + 1, 4);
    memcpy((void *)(uintptr_t)buf, full, n + 1);
    if (fp) {
        char *last = strrchr((char *)(uintptr_t)buf, '\\');
        W32(fp) = last ? (uint32_t)(uintptr_t)(last + 1) : buf;
    }
    RET(n, 4);
}

/* FindFirstFileA/FindNextFileA over one directory with a wildcard pattern.
 * WIN32_FIND_DATAA: attrs +0, ftCreate +4, ftAccess +0xC, ftWrite +0x14, sizeHigh +0x1C,
 * sizeLow +0x20, reserved +0x24/+0x28, cFileName[260] +0x2C, cAlternate[14] +0x130. */
#define MAX_FINDS 16
static struct { DIR *d; char dir[1024]; char pat[256]; } g_find[MAX_FINDS];
#define FIND_BASE 0x00F1F000u
static int find_next(int i, uint32_t data) {
    struct dirent *e;
    while ((e = readdir(g_find[i].d)) != NULL) {
        if (fnmatch(g_find[i].pat, e->d_name, FNM_CASEFOLD) != 0) continue;
        char full[1300];
        snprintf(full, sizeof full, "%s/%s", g_find[i].dir, e->d_name);
        struct stat st;
        if (stat(full, &st) != 0) continue;
        memset((void *)(uintptr_t)data, 0, 0x140);
        W32(data) = S_ISDIR(st.st_mode) ? 0x10u : 0x80u;     /* DIRECTORY : NORMAL */
        uint64_t ft = unix_to_filetime(st.st_mtime);
        put_filetime(data + 0x04, ft);
        put_filetime(data + 0x0C, ft);
        put_filetime(data + 0x14, ft);
        W32(data + 0x1C) = (uint32_t)((uint64_t)st.st_size >> 32);
        W32(data + 0x20) = (uint32_t)st.st_size;
        snprintf((char *)(uintptr_t)(data + 0x2C), 260, "%s", e->d_name);
        return 1;
    }
    return 0;
}
static void k_FindFirstFileA(void) {
    const char *spec = ASTR(0);
    uint32_t data = A32(1);
    if (!spec) RET(INVALID_HANDLE, 2);
    char gdir[1024], hp[1024];
    snprintf(gdir, sizeof gdir, "%s", spec);
    char *slash = strrchr(gdir, '\\');
    if (!slash) slash = strrchr(gdir, '/');
    const char *pat = slash ? slash + 1 : gdir;
    char patbuf[256];
    snprintf(patbuf, sizeof patbuf, "%s", pat);
    if (slash) *slash = 0; else snprintf(gdir, sizeof gdir, ".");
    const char *hdir = hle_host_path(gdir, hp, sizeof hp);
    for (int i = 0; i < MAX_FINDS; i++) {
        if (g_find[i].d) continue;
        DIR *d = opendir(hdir);
        if (!d) RET(INVALID_HANDLE, 2);
        g_find[i].d = d;
        snprintf(g_find[i].dir, sizeof g_find[i].dir, "%s", hdir);
        snprintf(g_find[i].pat, sizeof g_find[i].pat, "%s", !strcmp(patbuf, "*.*") ? "*" : patbuf);
        if (find_next(i, data)) RET(FIND_BASE + (uint32_t)i, 2);
        closedir(d);
        g_find[i].d = NULL;
        RET(INVALID_HANDLE, 2);
    }
    RET(INVALID_HANDLE, 2);
}
static void k_FindNextFileA(void) {
    uint32_t i = A32(0) - FIND_BASE;
    if (i >= MAX_FINDS || !g_find[i].d) RET(0, 2);
    RET(find_next((int)i, A32(1)), 2);
}
static void k_FindClose(void) {
    uint32_t i = A32(0) - FIND_BASE;
    if (i < MAX_FINDS && g_find[i].d) { closedir(g_find[i].d); g_find[i].d = NULL; RET(1, 1); }
    RET(0, 1);
}

/* ------------------------------------------------------------------ locale
 * One locale: US English, code page 1252, compared as bytes (FF7's own strings are
 * ASCII). CompareString returns CSTR_LESS_THAN 1 / EQUAL 2 / GREATER 3. */
static int cmp3(int c) { return c < 0 ? 1 : c > 0 ? 3 : 2; }
static void k_CompareStringA(void) {        /* (lcid, flags, s1, n1, s2, n2) */
    uint32_t flags = A32(1);
    const char *a = ASTR(2), *b = ASTR(4);
    int na = (int)A32(3), nb = (int)A32(5);
    if (na < 0) na = (int)strlen(a);
    if (nb < 0) nb = (int)strlen(b);
    int n = na < nb ? na : nb;
    int c = (flags & 1u) ? strncasecmp(a, b, (size_t)n) : strncmp(a, b, (size_t)n);
    if (!c) c = na - nb;
    RET(cmp3(c), 6);
}
static int wlen(uint32_t p) { int n = 0; while (W16(p + 2u * (uint32_t)n)) n++; return n; }
static void k_CompareStringW(void) {
    uint32_t flags = A32(1), a = A32(2), b = A32(4);
    int na = (int)A32(3), nb = (int)A32(5);
    if (na < 0) na = wlen(a);
    if (nb < 0) nb = wlen(b);
    int n = na < nb ? na : nb, c = 0;
    for (int i = 0; i < n && !c; i++) {
        uint32_t x = W16(a + 2u * (uint32_t)i), y = W16(b + 2u * (uint32_t)i);
        if ((flags & 1u) && x < 128 && y < 128) { x = (uint32_t)tolower((int)x); y = (uint32_t)tolower((int)y); }
        c = (int)x - (int)y;
    }
    if (!c) c = na - nb;
    RET(cmp3(c), 6);
}
#define LCMAP_LOWERCASE 0x100u
#define LCMAP_UPPERCASE 0x200u
static void k_LCMapStringA(void) {          /* (lcid, flags, src, n, dst, dstn) */
    uint32_t flags = A32(1), src = A32(2), dst = A32(4);
    int n = (int)A32(3), dn = (int)A32(5);
    if (n < 0) n = (int)strlen((char *)(uintptr_t)src) + 1;
    if (!dn) RET((uint32_t)n, 6);
    if (dn < n) RET(0, 6);
    for (int i = 0; i < n; i++) {
        int c = ((uint8_t *)(uintptr_t)src)[i];
        if (flags & LCMAP_LOWERCASE) c = tolower(c);
        if (flags & LCMAP_UPPERCASE) c = toupper(c);
        ((uint8_t *)(uintptr_t)dst)[i] = (uint8_t)c;
    }
    RET((uint32_t)n, 6);
}
static void k_LCMapStringW(void) {
    uint32_t flags = A32(1), src = A32(2), dst = A32(4);
    int n = (int)A32(3), dn = (int)A32(5);
    if (n < 0) n = wlen(src) + 1;
    if (!dn) RET((uint32_t)n, 6);
    if (dn < n) RET(0, 6);
    for (int i = 0; i < n; i++) {
        uint32_t c = W16(src + 2u * (uint32_t)i);
        if (c < 128 && (flags & LCMAP_LOWERCASE)) c = (uint32_t)tolower((int)c);
        if (c < 128 && (flags & LCMAP_UPPERCASE)) c = (uint32_t)toupper((int)c);
        W16(dst + 2u * (uint32_t)i) = (uint16_t)c;
    }
    RET((uint32_t)n, 6);
}
static const char *locale_value(uint32_t type) {
    switch (type & 0xFFFFu) {
        case 0x0B: return "437";      /* LOCALE_IDEFAULTCODEPAGE (OEM) */
        case 0x1004: return "1252";   /* LOCALE_IDEFAULTANSICODEPAGE */
        case 0x01: return "0409";     /* LOCALE_ILANGUAGE */
        case 0x03: return "ENU";      /* LOCALE_SABBREVLANGNAME */
        case 0x07: return "USA";      /* LOCALE_SABBREVCTRYNAME */
        case 0x0E: return ".";        /* LOCALE_SDECIMAL */
        case 0x0F: return ",";        /* LOCALE_STHOUSAND */
        default: return "";
    }
}
static void k_GetLocaleInfoA(void) {        /* (lcid, type, buf, n) */
    const char *v = locale_value(A32(1));
    uint32_t buf = A32(2), n = A32(3), need = (uint32_t)strlen(v) + 1;
    if (!n) RET(need, 4);
    if (n < need) RET(0, 4);
    memcpy((void *)(uintptr_t)buf, v, need);
    RET(need, 4);
}
static void k_GetLocaleInfoW(void) {
    const char *v = locale_value(A32(1));
    uint32_t buf = A32(2), n = A32(3), need = (uint32_t)strlen(v) + 1;
    if (!n) RET(need, 4);
    if (n < need) RET(0, 4);
    for (uint32_t i = 0; i < need; i++) W16(buf + 2u * i) = (uint8_t)v[i];
    RET(need, 4);
}

const win32hle_shim ff7_kernel32[] = {
    { "HeapCreate", k_HeapCreate },
    { "HeapDestroy", k_HeapDestroy },
    { "HeapSize", k_HeapSize },
    { "TlsAlloc", k_TlsAlloc },
    { "TlsGetValue", k_TlsGetValue },
    { "TlsSetValue", k_TlsSetValue },
    { "TlsFree", k_TlsFree },
    { "InitializeCriticalSection", k_InitializeCriticalSection },
    { "DeleteCriticalSection", k_DeleteCriticalSection },
    { "EnterCriticalSection", k_EnterCriticalSection },
    { "LeaveCriticalSection", k_LeaveCriticalSection },
    { "InterlockedIncrement", k_InterlockedIncrement },
    { "InterlockedDecrement", k_InterlockedDecrement },
    { "InterlockedExchange", k_InterlockedExchange },
    { "GetCommandLineA", k_GetCommandLineA },
    { "GetEnvironmentStrings", k_GetEnvironmentStrings },
    { "GetEnvironmentStringsA", k_GetEnvironmentStrings },
    { "GetEnvironmentStringsW", k_GetEnvironmentStringsW },
    { "FreeEnvironmentStringsA", k_FreeEnvironmentStrings },
    { "FreeEnvironmentStringsW", k_FreeEnvironmentStrings },
    { "GlobalMemoryStatus", k_GlobalMemoryStatus },
    { "GetModuleFileNameA", k_GetModuleFileNameA },
    { "GetCurrentDirectoryA", k_GetCurrentDirectoryA },
    { "GetProcAddress", k_GetProcAddress },
    { "SetErrorMode", k_SetErrorMode },
    { "IsBadReadPtr", k_IsBadReadPtr },
    { "IsBadWritePtr", k_IsBadWritePtr },
    { "IsBadCodePtr", k_IsBadCodePtr },
    { "SetEnvironmentVariableA", k_SetEnvironmentVariableA },
    { "FormatMessageA", k_FormatMessageA },
    { "GetVolumeInformationA", k_GetVolumeInformationA },
    { "GetLocalTime", k_GetLocalTime },
    { "GetSystemTime", k_GetSystemTime },
    { "FileTimeToLocalFileTime", k_FileTimeToLocalFileTime },
    { "FileTimeToSystemTime", k_FileTimeToSystemTime },
    { "CreateDirectoryA", k_CreateDirectoryA },
    { "DeleteFileA", k_DeleteFileA },
    { "MoveFileA", k_MoveFileA },
    { "GetFullPathNameA", k_GetFullPathNameA },
    { "FindFirstFileA", k_FindFirstFileA },
    { "FindNextFileA", k_FindNextFileA },
    { "FindClose", k_FindClose },
    { "CompareStringA", k_CompareStringA },
    { "CompareStringW", k_CompareStringW },
    { "LCMapStringA", k_LCMapStringA },
    { "LCMapStringW", k_LCMapStringW },
    { "GetLocaleInfoA", k_GetLocaleInfoA },
    { "GetLocaleInfoW", k_GetLocaleInfoW },
    { NULL, NULL }
};
