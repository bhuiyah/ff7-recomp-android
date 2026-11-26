/* -Pasan=ON only. ART resets the PAC keys at start-up (prctl PR_PAC_RESET_KEYS); ASan's prctl
 * interceptor signs its return address before the call and checks it after, with the new key:
 * SIGILL on CPUs with FPAC. Preloaded before ASan, this prctl is a plain syscall, unsigned. */
#include <errno.h>
#include <stdarg.h>
__attribute__((visibility("default"))) int prctl(int op, ...) {
    va_list ap; va_start(ap, op);
    unsigned long a = va_arg(ap, unsigned long), b = va_arg(ap, unsigned long),
                  c = va_arg(ap, unsigned long), d = va_arg(ap, unsigned long);
    va_end(ap);
    register long x0 __asm__("x0") = op; register long x1 __asm__("x1") = a;
    register long x2 __asm__("x2") = b; register long x3 __asm__("x3") = c;
    register long x4 __asm__("x4") = d; register long x8 __asm__("x8") = 167;   /* __NR_prctl */
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x8) : "memory");
    if (x0 < 0 && x0 > -4096) { errno = (int)-x0; return -1; }
    return (int)x0;
}
