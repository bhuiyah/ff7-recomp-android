/*
 * ff7_crash.c - on a host fault (SIGSEGV/SIGBUS/SIGILL/SIGFPE), report where the lifted
 * program was: the faulting address, the lifted function (g_cur_func), the last shim,
 * the guest registers and the last indirect calls. Diagnostics only; not async-signal
 * safe, which is acceptable for a process that is about to exit.
 */
#define _GNU_SOURCE
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include "recomp_types.h"
#include "win32hle.h"

extern const char *g_cur_import;
void ff7_trace_dump(int last);

static void on_fault(int sig, siginfo_t *si, void *uc) {
    (void)uc;
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
