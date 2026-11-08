/*
 * ff7_crash.c - on a host fault (SIGSEGV/SIGBUS/SIGILL/SIGFPE), report where the lifted
 * program was: the faulting address, the lifted function (g_cur_func), the last shim,
 * the guest registers and the last indirect calls. Diagnostics only; not async-signal
 * safe, which is acceptable for a process that is about to exit.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <signal.h>
#include <ucontext.h>
#include <unwind.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "recomp_types.h"
#include "win32hle.h"

extern const char *g_cur_import;
void ff7_trace_dump(int last);

/* A host code address as "<library>+0x<offset>" (symbolize with llvm-symbolizer). */
static void host_where(const char *what, uintptr_t a) {
    Dl_info di;
    if (a && dladdr((void *)a, &di) && di.dli_fbase) {
        const char *lib = di.dli_fname ? strrchr(di.dli_fname, '/') : NULL;
        fprintf(stderr, "  %s %s+0x%lx%s%s\n", what, lib ? lib + 1 : "?", (unsigned long)(a - (uintptr_t)di.dli_fbase),
                di.dli_sname ? " " : "", di.dli_sname ? di.dli_sname : "");
    } else {
        fprintf(stderr, "  %s 0x%lx\n", what, (unsigned long)a);
    }
}

/* The native call stack at the fault (through the signal frame). */
static int g_bt_n;
static _Unwind_Reason_Code bt_frame(struct _Unwind_Context *ctx, void *arg) {
    (void)arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (!pc || g_bt_n++ >= 24) return _URC_END_OF_STACK;
    host_where("  at", pc);
    return _URC_NO_REASON;
}

static void on_fault(int sig, siginfo_t *si, void *uc) {
#if defined(__aarch64__)
    ucontext_t *u = (ucontext_t *)uc;
    host_where("host pc", (uintptr_t)u->uc_mcontext.pc);
    host_where("host lr", (uintptr_t)u->uc_mcontext.regs[30]);
#else
    (void)uc;
#endif
    fprintf(stderr, "  native backtrace:\n");
    g_bt_n = 0;
    _Unwind_Backtrace(bt_frame, NULL);
    fprintf(stderr, "[ff7] FAULT: signal %d (%s) at host address %p",
            sig, strsignal(sig), si->si_addr);
    uintptr_t a = (uintptr_t)si->si_addr;
    if (a <= 0xFFFFFFFFu) fprintf(stderr, " (guest VA 0x%08X)", (unsigned)a);
    fprintf(stderr, "\n  in lifted func 0x%08X, last import %s\n", g_cur_func, g_cur_import ? g_cur_import : "(none)");
    fprintf(stderr, "  eax=%08X ebx=%08X ecx=%08X edx=%08X esi=%08X edi=%08X ebp=%08X esp=%08X\n",
            g_eax, g_ebx, g_ecx, g_edx, g_esi, g_edi, g_ebp, g_esp);
    fprintf(stderr, "  recent indirect calls (target <- from):\n");
    for (uint32_t i = 0; i < 8 && i < g_icall_count; i++) {
        uint32_t k = (g_icall_trace_idx - 1 - i) & (ICALL_TRACE_SIZE - 1);
        const char *nm = hle_name(g_icall_trace[k]);
        fprintf(stderr, "    0x%08X <- 0x%08X%s%s\n", g_icall_trace[k], g_icall_from[k], nm ? "  " : "", nm ? nm : "");
    }
    ff7_trace_dump(64);
    fflush(stderr);
    usleep(400000);   /* the app pumps stderr to its log from another thread: let it drain */
    _exit(128 + sig);
}

void ff7_install_crash_handler(void) {
    static char altstack[64 * 1024];
    stack_t ss = { .ss_sp = altstack, .ss_size = sizeof altstack, .ss_flags = 0 };
    sigaltstack(&ss, NULL);
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = on_fault;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGFPE, &sa, NULL);
}

/* ---- watchdog (FF7_WATCHDOG=<seconds>): a sampling profile of the lifted program.
 * Every period it samples g_cur_func (the last lifted function entered) for one second
 * and prints the hottest functions and the last import, to see where a hung or slow
 * game is spending its time without a debugger. */
#include <pthread.h>
#include <stdlib.h>
#include <time.h>

/* FFNx's patch manifest (src/ffnx_port/patch_recomp.cpp), when FFNx is linked. */
int ffnx_recomp_write_manifest(const char *path) __attribute__((weak));
void ffnx_recomp_report(void) __attribute__((weak));

static void *watchdog(void *arg) {
    int period = (int)(intptr_t)arg;
    enum { NS = 1000, TOP = 8 };
    uint32_t va[NS];
    const char *manifest = getenv("FF7_FFNX_MANIFEST");
    for (;;) {
        sleep((unsigned)period);
        if (manifest && ffnx_recomp_write_manifest) {
            ffnx_recomp_write_manifest(manifest);
            if (ffnx_recomp_report) ffnx_recomp_report();
        }
        for (int i = 0; i < NS; i++) {
            va[i] = g_cur_func;
            struct timespec t = { 0, 1000000 };
            nanosleep(&t, NULL);
        }
        /* count distinct values (small n: quadratic is fine) */
        uint32_t uv[NS]; int cnt[NS]; int nu = 0;
        for (int i = 0; i < NS; i++) {
            int j = 0;
            while (j < nu && uv[j] != va[i]) j++;
            if (j == nu) { uv[nu] = va[i]; cnt[nu] = 0; nu++; }
            cnt[j]++;
        }
        fprintf(stderr, "[watchdog] %d distinct funcs in 1s; last import %s; top:", nu, g_cur_import ? g_cur_import : "-");
        for (int k = 0; k < TOP && k < nu; k++) {
            int best = 0;
            for (int j = 1; j < nu; j++) if (cnt[j] > cnt[best]) best = j;
            fprintf(stderr, " %08X:%d", uv[best], cnt[best]);
            cnt[best] = -1;
        }
        fprintf(stderr, "\n");
    }
    return NULL;
}

/* ---- FF7_MEMDIFF=1: a research aid for finding game variables. Every 2 s, the bytes of the
 * exe's .data/.bss that changed between two small values (0..3) since the last look, so a
 * menu cursor or a flag shows up as the input that moves it is pressed. */
#define MD_LO 0x007BA000u
#define MD_HI 0x00F51000u
static void *memdiff(void *arg) {
    (void)arg;
    static uint8_t prev[MD_HI - MD_LO];
    sleep(10);                       /* started at boot: the exe is mapped a moment later */
    memcpy(prev, (const void *)(uintptr_t)MD_LO, sizeof prev);
    for (int round = 0;; round++) {
        sleep(2);
        const uint8_t *cur = (const uint8_t *)(uintptr_t)MD_LO;
        int shown = 0;
        for (uint32_t i = 0; i < sizeof prev; i++) {
            uint8_t a = prev[i], b = cur[i];
            if (a != b && a <= 3 && b <= 3 && shown < 40) {
                fprintf(stderr, "[memdiff] %d 0x%08X %u -> %u\n", round, MD_LO + i, a, b);
                shown++;
            }
            prev[i] = b;
        }
    }
    return NULL;
}

/* FF7_MEMDIFF=2: counters. Every 2 s, the 16-bit words of .data/.bss that rose in at least 7 of
 * the last 8 quarter-second looks (never falling), with how much they rose: a gauge or timer
 * and its speed. */
static void *memdiff_counters(void *arg) {
    (void)arg;
    enum { N = (MD_HI - MD_LO) / 2 };
    static uint16_t prev[N], start[N];
    static uint8_t ups[N], downs[N];
    sleep(10);
    memcpy(prev, (const void *)(uintptr_t)MD_LO, sizeof prev);
    memcpy(start, prev, sizeof start);
    for (int round = 0;; round++) {
        for (int k = 0; k < 8; k++) {
            usleep(250000);
            const uint16_t *cur = (const uint16_t *)(uintptr_t)MD_LO;
            for (uint32_t i = 0; i < N; i++) {
                uint16_t a = prev[i], b = cur[i];
                if (b > a) ups[i]++; else if (b < a) downs[i]++;
                prev[i] = b;
            }
        }
        int shown = 0;
        for (uint32_t i = 0; i < N; i++) {
            if (ups[i] >= 7 && !downs[i] && shown < 60) {
                fprintf(stderr, "[counter] %d 0x%08X %u -> %u (+%u)\n", round, MD_LO + 2 * i, start[i], prev[i], (unsigned)(prev[i] - start[i]));
                shown++;
            }
            ups[i] = downs[i] = 0;
            start[i] = prev[i];
        }
    }
    return NULL;
}

/* FF7_WATCH=9A8B14,9A8C24: those 16-bit words every 50 ms, with the time in ms, whenever one
 * of them changed -- a gauge's timeline. */
static void *watch_words(void *arg) {
    (void)arg;
    /* each entry: ADDR, or *ADDR+OFF (the dword at ADDR, plus OFF: a field in a struct the
     * game points to) */
    uint32_t addr[8], off[8];
    int deref[8], n = 0;
    for (const char *p = getenv("FF7_WATCH"); p && *p && n < 8;) {
        char *end;
        deref[n] = *p == '*';
        if (deref[n]) p++;
        unsigned long v = strtoul(p, &end, 16);
        if (end == p) break;
        off[n] = 0;
        if (*end == '+') { const char *q = end + 1; off[n] = (uint32_t)strtoul(q, &end, 16); }
        addr[n++] = (uint32_t)v;
        p = *end ? end + 1 : end;
    }
    uint16_t last[8] = {0};
    struct timespec t0;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    sleep(10);
    for (;;) {
        usleep(50000);
        int changed = 0;
        uint16_t v[8];
        for (int i = 0; i < n; i++) {
            uint32_t a = addr[i];
            if (deref[i]) {
                a = *(volatile uint32_t *)(uintptr_t)a;
                if (a < 0x400000u) { v[i] = 0; continue; }
                a += off[i];
            }
            v[i] = *(volatile uint16_t *)(uintptr_t)a;
            changed |= v[i] != last[i];
            last[i] = v[i];
        }
        if (!changed) continue;
        struct timespec t;
        clock_gettime(CLOCK_MONOTONIC, &t);
        long ms = (long)(t.tv_sec - t0.tv_sec) * 1000 + (t.tv_nsec - t0.tv_nsec) / 1000000;
        char line[160];
        int k = snprintf(line, sizeof line, "[watch] %ld", ms);
        for (int i = 0; i < n && k < (int)sizeof line; i++) k += snprintf(line + k, sizeof line - k, " %u", v[i]);
        fprintf(stderr, "%s\n", line);
    }
    return NULL;
}

void ff7_start_watchdog(void) {
    if (getenv("FF7_WATCH")) {
        pthread_t w;
        pthread_create(&w, NULL, watch_words, NULL);
        pthread_detach(w);
    }
    if (getenv("FF7_MEMDIFF") && getenv("FF7_MEMDIFF")[0] == '2') {
        pthread_t m;
        pthread_create(&m, NULL, memdiff_counters, NULL);
        pthread_detach(m);
    }
    if (getenv("FF7_MEMDIFF") && getenv("FF7_MEMDIFF")[0] == '1') {
        pthread_t m;
        pthread_create(&m, NULL, memdiff, NULL);
        pthread_detach(m);
    }
    const char *e = getenv("FF7_WATCHDOG");
    int period = e ? atoi(e) : 0;
    if (period <= 0) return;
    pthread_t t;
    pthread_create(&t, NULL, watchdog, (void *)(intptr_t)period);
    pthread_detach(t);
}
