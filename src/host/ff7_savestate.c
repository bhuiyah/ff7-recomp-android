/*
 * ff7_savestate.c - save states: the whole running game, saved and loaded at a frame
 * boundary (docs/SAVESTATES.md).
 *
 * The safe point is the top of FFNx's per-frame core loop (ff7_core_game_loop calls
 * ff7_savestate_frame first): no game-mode code is on the host stack there, so the C call
 * chain is the same whenever a state is saved or loaded and the guest state alone
 * describes the game. A state holds:
 *   - the guest registers, flags and x87 stack;
 *   - guest memory: the exe's .data/.bss, FFNx's static block, every allocated range of the
 *     heap arena (the guest stack included) and the VirtualAlloc regions;
 *   - the host-side bookkeeping that memory depends on: the heap's free list, the
 *     VirtualAlloc table, the DirectSound voices, the open file handles, the guest cwd;
 *   - whatever the FFNx port registers (ff7_savestate_hooks: its graphics/audio state).
 * Saving copies all of it at the safe point and compresses/writes it on a worker thread;
 * loading reads/decompresses on a worker and applies it at the next safe point.
 *
 * Buttons: Select+L1 quick save, Select+R1 quick load (src/android keeps those from the game).
 * Files: <files>/savestates/quick.ff7s (+ .bak of the previous one).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <zlib.h>
#include "recomp_types.h"
#include "guest_alloc.h"
#include "win32hle.h"
#include "ff7_hle.h"
#include "ff7_savestate.h"

#define SS_MAGIC   "FF7RSS\x1a\x01"
#define SS_VERSION 3   /* 3: records the graphics settings (R_CONFIG) */
#define IMG_DATA_LO 0x007BA000u          /* ff7_en .data/.bss */
#define IMG_DATA_HI 0x00F51000u
#define FFNX_STATIC_LO 0x6B000000u       /* src/ffnx_port/ffnx_guest_static.cpp */
#define FFNX_STATIC_HI 0x6B010000u

enum { R_CPU = 1, R_MEM, R_FREE, R_VREGIONS, R_DSOUND, R_FILES, R_CWD, R_HOOK, R_CONFIG, R_END = 0xFFFF };

typedef struct {
    uint32_t eax, ecx, edx, ebx, esp, ebp, esi, edi;
    uint32_t flag_k, flag_a, flag_b, flag_cf;
    double st[8];
    int64_t st_i64[8];
    int32_t fp_top;
    uint16_t fpu_cw, pad;
    uint32_t fs_base, gs_base;
    uint64_t host_depth;                 /* bytes of host stack in use at the safe point */
} ss_cpu;

/* How deep the host stack is here: the same call chain gives the same depth, so a state is
 * only applied where it was taken from (the flip of the game's own frame loop, not a flip
 * some nested loading loop made). */
static uint64_t g_depth_now;            /* measured at the top of ff7_savestate_frame */
static uint64_t host_depth_at(void *frame) {
    static uintptr_t top;
    if (!top) {
        pthread_attr_t a;
        void *base; size_t size;
        if (pthread_getattr_np(pthread_self(), &a) == 0) {
            if (pthread_attr_getstack(&a, &base, &size) == 0) top = (uintptr_t)base + size;
            pthread_attr_destroy(&a);
        }
    }
    return top ? (uint64_t)(top - (uintptr_t)frame) : 0;
}
static uint64_t host_depth(void) { return g_depth_now; }

typedef struct {
    char magic[8];
    uint32_t version, header_size;
    char build[64];
    int64_t saved_at;
    int32_t mode;
    uint32_t raw_size;                   /* the uncompressed record stream */
    char label[64];                      /* the slot ("slot3") */
    char location[48];                   /* where in the game, for the slot lists */
    uint32_t play_seconds;               /* the game's play-time counter */
} ss_header;

/* ---- a growing byte buffer ---------------------------------------------------------------- */
typedef struct { uint8_t *p; size_t n, cap; } buf_t;
static int buf_put(buf_t *b, const void *d, size_t n) {
    if (b->n + n > b->cap) {
        size_t c = b->cap ? b->cap : (64u << 20);
        while (c < b->n + n) c *= 2;
        uint8_t *q = realloc(b->p, c);
        if (!q) return -1;
        b->p = q; b->cap = c;
    }
    memcpy(b->p + b->n, d, n);
    b->n += n;
    return 0;
}
static int rec(buf_t *b, uint32_t id, const void *d, uint32_t n) {
    uint32_t h[2] = { id, n };
    return buf_put(b, h, sizeof h) || (n && buf_put(b, d, n));
}
static int rec_mem(buf_t *b, uint32_t lo, uint32_t hi) {
    if (hi <= lo) return 0;
    uint32_t h[3] = { R_MEM, (hi - lo) + 4, lo };
    return buf_put(b, h, sizeof h) || buf_put(b, (const void *)(uintptr_t)lo, hi - lo);
}

/* ---- hooks (the FFNx port's state) ------------------------------------------------------- */
#define MAX_HOOKS 8
static const ff7_savestate_hook *g_hooks[MAX_HOOKS];
static int g_nhooks;
void ff7_savestate_register(const ff7_savestate_hook *h) { if (g_nhooks < MAX_HOOKS) g_hooks[g_nhooks++] = h; }

/* Optional, from the FFNx port (weak: the headless runner has none). */
__attribute__((weak)) int ff7_ss_driver_mode(void) { return -1; }
__attribute__((weak)) int ff7_ss_can_save(const char **why) { (void)why; return 1; }
__attribute__((weak)) void ff7_ss_notify(const char *msg) { (void)msg; }
/* The settings a state depends on (FFNx's frame-rate and widescreen patches change code and
 * data layouts): a state is only loaded under the same ones. */
__attribute__((weak)) void ff7_ss_config(char *out, unsigned n) { if (n) out[0] = 0; }
__attribute__((weak)) void ff7_ss_location(char *out, unsigned n, uint32_t *play_seconds) { if (n) out[0] = 0; *play_seconds = 0; }
static void notify(const char *msg) { fprintf(stderr, "[savestate] %s\n", msg); fflush(stderr); ff7_ss_notify(msg); }

/* ---- paths --------------------------------------------------------------------------------- */
static char g_dir[1024];
static void ss_paths(void) {
    if (g_dir[0]) return;
    char cwd[900];
    if (!getcwd(cwd, sizeof cwd)) { snprintf(g_dir, sizeof g_dir, "savestates"); return; }
    char *slash = strrchr(cwd, '/');
    if (slash) *slash = 0;                    /* the process runs in <files>/game */
    snprintf(g_dir, sizeof g_dir, "%s/savestates", cwd);
    mkdir(g_dir, 0775);
}
#define SS_SLOTS 10
static int g_cur = 1;                     /* the slot the buttons use (savestates/current.txt) */
static int slot_number(const char *slot) {
    if (!slot || !*slot || !strcmp(slot, "quick") || !strcmp(slot, "current")) return g_cur;
    if (!strncmp(slot, "slot", 4)) slot += 4;
    int n = atoi(slot);
    return n >= 1 && n <= SS_SLOTS ? n : 0;
}
static void slot_path(const char *slot, char *out, size_t n) {
    ss_paths();
    int k = slot_number(slot);
    if (k) snprintf(out, n, "%s/slot%02d.ff7s", g_dir, k);
    else snprintf(out, n, "%s/%s.ff7s", g_dir, slot);   /* a named slot (scripts) */
}
static void save_current(void) {
    char p[1100];
    ss_paths();
    snprintf(p, sizeof p, "%s/current.txt", g_dir);
    FILE *f = fopen(p, "wb");
    if (f) { fprintf(f, "%d\n", g_cur); fclose(f); }
}
/* Once: the current slot, and the old single quick state becomes slot 1. */
static void slots_init(void) {
    static int done;
    if (done) return;
    done = 1;
    ss_paths();
    char p[1100], q[1100];
    snprintf(p, sizeof p, "%s/current.txt", g_dir);
    FILE *f = fopen(p, "rb");
    if (f) { int k = 0; if (fscanf(f, "%d", &k) == 1 && k >= 1 && k <= SS_SLOTS) g_cur = k; fclose(f); }
    snprintf(p, sizeof p, "%s/quick.ff7s", g_dir);
    snprintf(q, sizeof q, "%s/slot01.ff7s", g_dir);
    if (access(p, F_OK) == 0 && access(q, F_OK) != 0) rename(p, q);
}

/* ---- capture -------------------------------------------------------------------------------- */
static int capture(buf_t *b) {
    ss_cpu c;
    memset(&c, 0, sizeof c);
    c.eax = g_eax; c.ecx = g_ecx; c.edx = g_edx; c.ebx = g_ebx; c.esp = g_esp; c.ebp = g_ebp; c.esi = g_esi; c.edi = g_edi;
    c.flag_k = g_flag_k; c.flag_a = g_flag_a; c.flag_b = g_flag_b; c.flag_cf = g_flag_cf;
    for (int i = 0; i < 8; i++) { c.st[i] = g_st[i]; c.st_i64[i] = g_st_i64[i]; }
    c.fp_top = g_fp_top; c.fpu_cw = g_fpu_cw; c.fs_base = g_fs_base; c.gs_base = g_gs_base;
    c.host_depth = host_depth();
    if (rec(b, R_CPU, &c, sizeof c)) return -1;
    char cfg[64];
    ff7_ss_config(cfg, sizeof cfg);
    if (rec(b, R_CONFIG, cfg, (uint32_t)strlen(cfg))) return -1;

    if (rec_mem(b, IMG_DATA_LO, IMG_DATA_HI) || rec_mem(b, FFNX_STATIC_LO, FFNX_STATIC_HI)) return -1;

    /* the heap: everything between the free ranges (under the allocator lock) */
    uint32_t lo, hi;
    guest_alloc_arena(&lo, &hi);
    uint32_t nf = guest_alloc_free_ranges(NULL, 0);
    uint32_t *fr = malloc(sizeof(uint32_t) * 2 * (nf + 1) + 16);
    if (!fr) return -1;
    nf = guest_alloc_free_ranges(fr, nf);
    uint32_t at = lo;
    for (uint32_t i = 0; i < nf; i++) {
        if (rec_mem(b, at, fr[2 * i])) { free(fr); return -1; }
        at = fr[2 * i] + fr[2 * i + 1];
    }
    if (rec_mem(b, at, hi)) { free(fr); return -1; }
    {   /* the free list itself, and the live byte count */
        buf_t t = { 0 };
        uint64_t live = guest_alloc_live_bytes();
        if (buf_put(&t, &live, 8) || buf_put(&t, fr, sizeof(uint32_t) * 2 * nf) || rec(b, R_FREE, t.p, (uint32_t)t.n)) { free(t.p); free(fr); return -1; }
        free(t.p);
    }
    free(fr);

    uint32_t vr[2 * 1024];
    int nv = ff7_virtual_regions(vr, 1024);
    if (rec(b, R_VREGIONS, vr, (uint32_t)(sizeof(uint32_t) * 2 * nv))) return -1;
    for (int i = 0; i < nv; i++) if (rec_mem(b, vr[2 * i], vr[2 * i] + vr[2 * i + 1])) return -1;

    {
        unsigned n = ff7_dsound_state_size();
        void *v = malloc(n);
        if (!v) return -1;
        ff7_dsound_state_save(v);
        int r = rec(b, R_DSOUND, v, n);
        free(v);
        if (r) return -1;
    }
    {   /* open files: slot, mode, position, path */
        buf_t t = { 0 };
        for (int i = 0; i < hle_file_slots(); i++) {
            char path[512], mode[8];
            long pos;
            if (!hle_file_info(i, path, sizeof path, mode, &pos)) continue;
            int32_t slot = i;
            int64_t p64 = pos;
            uint32_t len = (uint32_t)strlen(path);
            if (buf_put(&t, &slot, 4) || buf_put(&t, mode, 8) || buf_put(&t, &p64, 8) || buf_put(&t, &len, 4) || buf_put(&t, path, len)) { free(t.p); return -1; }
        }
        int r = rec(b, R_FILES, t.p, (uint32_t)t.n);
        free(t.p);
        if (r) return -1;
    }
    {
        const char *cwd = ff7_guest_cwd();
        if (rec(b, R_CWD, cwd ? cwd : "", cwd ? (uint32_t)strlen(cwd) : 0)) return -1;
    }
    for (int i = 0; i < g_nhooks; i++) {
        buf_t t = { 0 };
        uint32_t nlen = (uint32_t)strlen(g_hooks[i]->name);
        if (buf_put(&t, &nlen, 4) || buf_put(&t, g_hooks[i]->name, nlen)) { free(t.p); return -1; }
        if (g_hooks[i]->save && g_hooks[i]->save(g_hooks[i]->ctx, (ff7_ss_writer)buf_put, &t)) { free(t.p); return -1; }
        int r = rec(b, R_HOOK, t.p, (uint32_t)t.n);
        free(t.p);
        if (r) return -1;
    }
    return rec(b, R_END, NULL, 0);
}

/* ---- restore ---------------------------------------------------------------------------- */
static int check(const uint8_t *p, size_t n, const char **why) {
    /* everything this build must accept before anything is touched */
    size_t at = 0;
    int have_config = 0;
    while (at + 8 <= n) {
        uint32_t id, len;
        memcpy(&id, p + at, 4); memcpy(&len, p + at + 4, 4);
        if (at + 8 + len > n) { *why = "damaged (truncated)"; return -1; }
        if (id == R_CPU) {
            ss_cpu c;
            if (len != sizeof c) { *why = "another build (layout changed)"; return -1; }
            memcpy(&c, p + at + 8, sizeof c);
            if (c.esp != g_esp) { *why = "the game is somewhere a state cannot be loaded (guest stack differs)"; return -1; }
            if (c.host_depth != host_depth()) { *why = "the game is somewhere a state cannot be loaded (host stack differs)"; return -1; }
        }
        if (id == R_DSOUND && len != ff7_dsound_state_size()) { *why = "another build (sound layout changed)"; return -1; }
        if (id == R_CONFIG) {
            have_config = 1;
            /* loading under other frame-rate / widescreen settings crashes the game */
            static char msg[200];
            char now[64];
            ff7_ss_config(now, sizeof now);
            if (len != strlen(now) || memcmp(p + at + 8, now, len)) {
                snprintf(msg, sizeof msg, "saved with other graphics settings (%.*s; now %s)", (int)(len < 64 ? len : 64), (const char *)(p + at + 8), now);
                *why = msg;
                return -1;
            }
        }
        if (id == R_END) {
            /* states from before the settings were recorded: their settings are unknown */
            if (!have_config) { *why = "from an older version (save it again)"; return -1; }
            return 0;
        }
        at += 8 + len;
    }
    *why = "damaged (no end)";
    return -1;
}

static void apply(const uint8_t *p, size_t n) {
    for (int i = 0; i < g_nhooks; i++) if (g_hooks[i]->before_load) g_hooks[i]->before_load(g_hooks[i]->ctx);
    guest_alloc_lock();
    size_t at = 0;
    while (at + 8 <= n) {
        uint32_t id, len;
        memcpy(&id, p + at, 4); memcpy(&len, p + at + 4, 4);
        const uint8_t *d = p + at + 8;
        switch (id) {
        case R_CPU: {
            ss_cpu c;
            memcpy(&c, d, sizeof c);
            g_eax = c.eax; g_ecx = c.ecx; g_edx = c.edx; g_ebx = c.ebx; g_esp = c.esp; g_ebp = c.ebp; g_esi = c.esi; g_edi = c.edi;
            g_flag_k = c.flag_k; g_flag_a = c.flag_a; g_flag_b = c.flag_b; g_flag_cf = c.flag_cf;
            for (int i = 0; i < 8; i++) { g_st[i] = c.st[i]; g_st_i64[i] = c.st_i64[i]; }
            g_fp_top = c.fp_top; g_fpu_cw = c.fpu_cw; g_fs_base = c.fs_base; g_gs_base = c.gs_base;
            break;
        }
        case R_MEM: {
            uint32_t va;
            memcpy(&va, d, 4);
            memcpy((void *)(uintptr_t)va, d + 4, len - 4);
            break;
        }
        case R_FREE: {
            uint64_t live;
            {   /* diagnostics: is this the heap layout the running game has? */
                uint32_t ncur = guest_alloc_free_ranges(NULL, 0);
                uint32_t *cur = malloc(sizeof(uint32_t) * 2 * (ncur + 1));
                if (cur) {
                    ncur = guest_alloc_free_ranges(cur, ncur);
                    uint32_t nsav = (len - 8) / 8, same = 0;
                    const uint32_t *sav = (const uint32_t *)(d + 8);
                    for (uint32_t i = 0; i < nsav && i < ncur; i++) { if (sav[2*i] != cur[2*i] || sav[2*i+1] != cur[2*i+1]) break; same++; }
                    fprintf(stderr, "[savestate] heap: %u free ranges saved, %u now, first %u identical", nsav, ncur, same);
                    if (same < nsav && same < ncur)
                        fprintf(stderr, " (first difference: saved 0x%08X+0x%X, now 0x%08X+0x%X)", sav[2*same], sav[2*same+1], cur[2*same], cur[2*same+1]);
                    fprintf(stderr, "\n");
                    free(cur);
                }
            }
            memcpy(&live, d, 8);
            uint32_t nf = (len - 8) / 8;
            uint32_t *fr = malloc(sizeof(uint32_t) * 2 * (nf + 1));
            if (fr) { memcpy(fr, d + 8, sizeof(uint32_t) * 2 * nf); guest_alloc_set_free_ranges(fr, nf, live); free(fr); }
            break;
        }
        case R_VREGIONS: ff7_virtual_set_regions((const uint32_t *)d, (int)(len / 8)); break;
        case R_DSOUND: ff7_dsound_state_restore(d); break;
        case R_FILES: {
            int keep[256] = { 0 };
            size_t q = 0;
            while (q + 24 <= len) {
                int32_t slot; char mode[8]; int64_t pos; uint32_t plen;
                memcpy(&slot, d + q, 4); memcpy(mode, d + q + 4, 8); memcpy(&pos, d + q + 12, 8); memcpy(&plen, d + q + 20, 4);
                char path[512];
                uint32_t c = plen < sizeof path - 1 ? plen : (uint32_t)sizeof path - 1;
                memcpy(path, d + q + 24, c);
                path[c] = 0;
                if (!hle_file_reopen(slot, path, mode, (long)pos))
                    fprintf(stderr, "[savestate] could not reopen %s (handle slot %d)\n", path, slot);
                if (slot >= 0 && slot < 256) keep[slot] = 1;
                q += 24 + plen;
            }
            for (int i = 0; i < hle_file_slots() && i < 256; i++)
                if (!keep[i] && hle_file_info(i, NULL, 0, NULL, NULL)) hle_file_reopen(i, NULL, NULL, 0);
            break;
        }
        case R_CWD: {
            char cwd[512];
            uint32_t c = len < sizeof cwd - 1 ? len : (uint32_t)sizeof cwd - 1;
            memcpy(cwd, d, c);
            cwd[c] = 0;
            ff7_restore_guest_cwd(cwd);
            break;
        }
        case R_HOOK: {
            uint32_t nlen;
            memcpy(&nlen, d, 4);
            for (int i = 0; i < g_nhooks; i++)
                if (strlen(g_hooks[i]->name) == nlen && !memcmp(g_hooks[i]->name, d + 4, nlen) && g_hooks[i]->restore)
                    g_hooks[i]->restore(g_hooks[i]->ctx, d + 4 + nlen, len - 4 - nlen);
            break;
        }
        }
        if (id == R_END) break;
        at += 8 + len;
    }
    guest_alloc_unlock();
    for (int i = 0; i < g_nhooks; i++) if (g_hooks[i]->after_load) g_hooks[i]->after_load(g_hooks[i]->ctx);
}

/* ---- files ----------------------------------------------------------------------------------- */
static const char *build_id(void) { return __DATE__ " " __TIME__; }

static int write_file(const char *path, const ss_header *h, const uint8_t *raw, size_t n, const char **why) {
    char tmp[1100], bak[1100];
    snprintf(tmp, sizeof tmp, "%s.tmp", path);
    snprintf(bak, sizeof bak, "%s.bak", path);
    FILE *f = fopen(tmp, "wb");
    if (!f) { *why = "cannot create the file"; return -1; }
    int ok = fwrite(h, sizeof *h, 1, f) == 1;
    z_stream z;
    memset(&z, 0, sizeof z);
    ok = ok && deflateInit(&z, 1) == Z_OK;
    static uint8_t out[1 << 20];
    z.next_in = (Bytef *)raw;
    z.avail_in = 0;
    size_t left = n;
    int flush = Z_NO_FLUSH;
    while (ok) {
        if (!z.avail_in && left) { uInt c = left > (1u << 30) ? (1u << 30) : (uInt)left; z.avail_in = c; left -= c; }
        flush = left || z.avail_in ? Z_NO_FLUSH : Z_FINISH;
        z.next_out = out; z.avail_out = sizeof out;
        int r = deflate(&z, flush);
        if (r == Z_STREAM_ERROR) { ok = 0; break; }
        size_t have = sizeof out - z.avail_out;
        if (have && fwrite(out, 1, have, f) != have) { ok = 0; break; }
        if (r == Z_STREAM_END) break;
    }
    deflateEnd(&z);
    ok = (fclose(f) == 0) && ok;
    if (!ok) { remove(tmp); *why = "cannot write the file (disk full?)"; return -1; }
    rename(path, bak);
    if (rename(tmp, path) != 0) { *why = "cannot replace the file"; return -1; }
    return 0;
}

static uint8_t *read_file(const char *path, ss_header *h, size_t *n, const char **why) {
    FILE *f = fopen(path, "rb");
    if (!f) { *why = "empty slot"; return NULL; }
    if (fread(h, sizeof *h, 1, f) != 1 || memcmp(h->magic, SS_MAGIC, 8) || h->version != SS_VERSION || h->header_size != sizeof *h) {
        fclose(f); *why = "not a save state of this version"; return NULL;
    }
    uint8_t *raw = malloc(h->raw_size ? h->raw_size : 1);
    if (!raw) { fclose(f); *why = "not enough memory"; return NULL; }
    z_stream z;
    memset(&z, 0, sizeof z);
    inflateInit(&z);
    static uint8_t in[1 << 20];
    z.next_out = raw; z.avail_out = h->raw_size;
    int r = Z_OK;
    while (r != Z_STREAM_END) {
        if (!z.avail_in) { z.avail_in = (uInt)fread(in, 1, sizeof in, f); z.next_in = in; if (!z.avail_in) break; }
        r = inflate(&z, Z_NO_FLUSH);
        if (r != Z_OK && r != Z_STREAM_END) break;
    }
    inflateEnd(&z);
    fclose(f);
    if (r != Z_STREAM_END || z.total_out != h->raw_size) { free(raw); *why = "damaged"; return NULL; }
    *n = h->raw_size;
    return raw;
}

/* ---- the worker and the frame hook ----------------------------------------------------------- */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_busy;                        /* a save or load is in flight */
static int g_want_save, g_want_load;      /* requests for the next safe point */
static char g_slot[32] = "quick";
static uint8_t *g_loaded;                 /* a read state waiting for the safe point */
static struct timespec g_wait_since;      /* a load waiting for the game's own frame loop */
static size_t g_loaded_n;
static ss_header g_loaded_h;

typedef struct { char path[1100]; ss_header h; uint8_t *raw; size_t n; } job_t;

static void *save_worker(void *arg) {
    job_t *j = arg;
    const char *why = NULL;
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int rc = write_file(j->path, &j->h, j->raw, j->n, &why);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    char msg[256];
    if (rc) snprintf(msg, sizeof msg, "Could not save the state: %s", why);
    else snprintf(msg, sizeof msg, "State saved (%.0f MB, %.1f s)", j->n / 1048576.0,
                  (t1.tv_sec - t0.tv_sec) + (t1.tv_nsec - t0.tv_nsec) / 1e9);
    notify(msg);
    free(j->raw);
    free(j);
    pthread_mutex_lock(&g_lock); g_busy = 0; pthread_mutex_unlock(&g_lock);
    return NULL;
}

static void *load_worker(void *arg) {
    job_t *j = arg;
    const char *why = NULL;
    size_t n = 0;
    ss_header h;
    uint8_t *raw = read_file(j->path, &h, &n, &why);
    if (!raw) {
        char msg[256];
        snprintf(msg, sizeof msg, "Cannot load the state: %s", why);
        notify(msg);
        pthread_mutex_lock(&g_lock); g_busy = 0; pthread_mutex_unlock(&g_lock);
    } else {
        pthread_mutex_lock(&g_lock);
        g_loaded = raw; g_loaded_n = n; g_loaded_h = h; g_want_load = 1;
        pthread_mutex_unlock(&g_lock);
    }
    free(j);
    return NULL;
}

static void start(void *(*fn)(void *), job_t *j) {
    pthread_t t;
    pthread_create(&t, NULL, fn, j);
    pthread_detach(t);
}

static void slot_note(int k) {
    char path[1100], msg[256], when[32] = "";
    snprintf(path, sizeof path, "%s/slot%02d.ff7s", g_dir, k);
    ss_header h;
    FILE *f = fopen(path, "rb");
    int ok = f && fread(&h, sizeof h, 1, f) == 1 && !memcmp(h.magic, SS_MAGIC, 8) && h.version == SS_VERSION;
    if (f) fclose(f);
    if (!ok) { snprintf(msg, sizeof msg, "Slot %d: %s", k, f ? "from an older version" : "empty"); notify(msg); return; }
    time_t t = (time_t)h.saved_at;
    struct tm tm;
    localtime_r(&t, &tm);
    strftime(when, sizeof when, "%d %b %H:%M", &tm);
    snprintf(msg, sizeof msg, "Slot %d: %s - %u:%02u:%02u - saved %s", k, h.location[0] ? h.location : "?",
             h.play_seconds / 3600, h.play_seconds / 60 % 60, h.play_seconds % 60, when);
    notify(msg);
}

void ff7_savestate_request(int load, const char *slot) {
    pthread_mutex_lock(&g_lock);
    if (g_busy) { pthread_mutex_unlock(&g_lock); notify("A save or load is still working"); return; }
    g_busy = 1;
    slots_init();
    snprintf(g_slot, sizeof g_slot, "%s", slot ? slot : "quick");
    if (!load) { g_want_save = 1; pthread_mutex_unlock(&g_lock); return; }
    pthread_mutex_unlock(&g_lock);
    job_t *j = calloc(1, sizeof *j);
    slot_path(g_slot, j->path, sizeof j->path);
    notify("Loading state...");
    start(load_worker, j);
}

/* Buttons: Select+L1 save, Select+R1 load, Select+Left / Select+Right the previous / next slot
 * (XInput BACK 0x20, LB 0x100, RB 0x200, DPAD_LEFT 0x4, DPAD_RIGHT 0x8). */
extern int ff7_platform_pad_buttons(void);
static void poll_buttons(void) {
    static int prev;
    int b = ff7_platform_pad_buttons();
    int pressed = b & ~prev;
    prev = b;
    if (!(b & 0x0020)) return;
    slots_init();
    if (pressed & 0x0100) ff7_savestate_request(0, "current");
    else if (pressed & 0x0200) ff7_savestate_request(1, "current");
    else if (pressed & (0x0004 | 0x0008)) {
        g_cur += (pressed & 0x0008) ? 1 : -1;
        if (g_cur < 1) g_cur = SS_SLOTS;
        if (g_cur > SS_SLOTS) g_cur = 1;
        save_current();
        slot_note(g_cur);
    }
}

/* Tests and scripts: <files>/savestates/cmd holding "save <slot>" or "load <slot>" is done
 * and removed (checked twice a second). */
static void poll_cmd_file(void) {
    static struct timespec last;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if ((now.tv_sec - last.tv_sec) * 1000 + (now.tv_nsec - last.tv_nsec) / 1000000 < 500) return;
    last = now;
    char path[1100];
    ss_paths();
    snprintf(path, sizeof path, "%s/cmd", g_dir);
    FILE *f = fopen(path, "rb");
    if (!f) return;
    char line[64] = "", what[16] = "", slot[32] = "quick";
    if (fgets(line, sizeof line, f)) sscanf(line, "%15s %31s", what, slot);
    fclose(f);
    remove(path);
    if (!strcmp(what, "save")) ff7_savestate_request(0, slot);
    else if (!strcmp(what, "load")) ff7_savestate_request(1, slot);
}

void ff7_savestate_frame(void) {
    g_depth_now = host_depth_at(__builtin_frame_address(0));
    poll_buttons();
    poll_cmd_file();
    pthread_mutex_lock(&g_lock);
    int want_save = g_want_save, want_load = g_want_load;
    g_want_save = 0;
    pthread_mutex_unlock(&g_lock);

    if (want_save) {
        const char *why = NULL;
        if (!ff7_ss_can_save(&why)) {
            char msg[256]; snprintf(msg, sizeof msg, "Cannot save now: %s", why ? why : "busy");
            notify(msg);
            pthread_mutex_lock(&g_lock); g_busy = 0; pthread_mutex_unlock(&g_lock);
            return;
        }
        job_t *j = calloc(1, sizeof *j);
        buf_t b = { 0 };
        guest_alloc_lock();
        int rc = capture(&b);
        guest_alloc_unlock();
        if (rc || !j) {
            free(b.p); free(j);
            notify("Could not save the state: not enough memory");
            pthread_mutex_lock(&g_lock); g_busy = 0; pthread_mutex_unlock(&g_lock);
            return;
        }
        memcpy(j->h.magic, SS_MAGIC, 8);
        j->h.version = SS_VERSION;
        j->h.header_size = sizeof j->h;
        snprintf(j->h.build, sizeof j->h.build, "%s", build_id());
        j->h.saved_at = (int64_t)time(NULL);
        j->h.mode = ff7_ss_driver_mode();
        j->h.raw_size = (uint32_t)b.n;
        snprintf(j->h.label, sizeof j->h.label, "slot%d", slot_number(g_slot));
        ff7_ss_location(j->h.location, sizeof j->h.location, &j->h.play_seconds);
        slot_path(g_slot, j->path, sizeof j->path);
        j->raw = b.p; j->n = b.n;
        notify("Saving state...");
        start(save_worker, j);
        return;
    }
    if (want_load) {
        pthread_mutex_lock(&g_lock);
        uint8_t *raw = g_loaded; size_t n = g_loaded_n; ss_header h = g_loaded_h;
        g_loaded = NULL; g_want_load = 0;
        pthread_mutex_unlock(&g_lock);
        const char *why = NULL;
        char msg[256];
        /* any mode: the safe point is the same flip in every mode (the stack checks in check()
           hold the rest), and the FFNx hooks rebuild what scene entry would have set up */
        if (!why) check(raw, n, &why);
        if (why && strstr(why, "somewhere a state cannot be loaded")) {
            /* a nested loop (a loading screen, a transition) is flipping: wait for the game's
               own frame loop, up to 10 s */
            struct timespec now;
            clock_gettime(CLOCK_MONOTONIC, &now);
            if (!g_wait_since.tv_sec) g_wait_since = now;
            if (now.tv_sec - g_wait_since.tv_sec < 10) {
                pthread_mutex_lock(&g_lock);
                g_loaded = raw; g_loaded_n = n; g_loaded_h = h; g_want_load = 1;
                pthread_mutex_unlock(&g_lock);
                return;
            }
        }
        g_wait_since.tv_sec = 0;
        if (why) snprintf(msg, sizeof msg, "Cannot load the state: %s", why);
        else {
            apply(raw, n);
            snprintf(msg, sizeof msg, "State loaded%s", strcmp(h.build, build_id()) ? " (made by another build)" : "");
        }
        notify(msg);
        free(raw);
        pthread_mutex_lock(&g_lock); g_busy = 0; pthread_mutex_unlock(&g_lock);
    }
}
