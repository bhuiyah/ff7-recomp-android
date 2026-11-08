/* difftest_main.c - the x86 interpreter against the lift, on real ff7_en code (Windows host).
 *
 * The exe image is mapped at its own VAs (0x400000..) and a scratch region (pointer targets,
 * stack) at 0x01000000..0x03000000, both with write-watch so every page a run writes is
 * found and restored. Each case runs lifted and interpreted from the same random state; a
 * case where either faults is counted apart (an invalid random pointer). See
 * tools/interp_difftest.py and tools/interp_difftest.sh.
 */
#include <windows.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "difftest.h"

/* ---- the runtime the lifted snippets and the interpreter link against ------------------- */
uint32_t g_eax, g_ecx, g_edx, g_esp, g_ebx, g_esi, g_edi, g_ebp;
double g_st[8];
int g_fp_top;
uint16_t g_fpu_cw = 0x027F;
ptrdiff_t g_mem_base;
uint32_t g_fs_base, g_gs_base, g_cur_func;
uint16_t g_seg_cs, g_seg_ds, g_seg_es, g_seg_fs, g_seg_gs, g_seg_ss;
uint64_t g_mm[8];
uint32_t ff7_callsite_bits[(0x007B6000u - 0x00401000u + 31u) / 32u];
uint32_t ff7_callsite_cur;
const recomp_dispatch_entry_t recomp_dispatch_table[1];
const uint32_t recomp_dispatch_count = 0;
/* Two stand-in "lifted functions" for the control-flow tests (each pops its return address,
 * as a lifted function's `ret` does). */
#define NATIVE1 0x01002000u
#define NATIVE2 0x01002100u
static void native1(void) { g_ecx += 100; g_esp += 4; }
static void native2(void) { g_eax = 42; g_esp += 4; }
recomp_func_t recomp_lookup(uint32_t va) { return va == NATIVE1 ? native1 : va == NATIVE2 ? native2 : NULL; }
recomp_func_t recomp_lookup_manual(uint32_t va) { (void)va; return NULL; }
recomp_func_t recomp_lookup_import(uint32_t va) { (void)va; return NULL; }
int ff7_override_install(uint32_t va, uint32_t target) { (void)va; (void)target; return -1; }
int ff7_override_active(uint32_t va) { (void)va; return 0; }
void ff7_run_callsite(void) {}

extern jmp_buf x86_interp_test_jmp;
extern const char *x86_interp_test_why;
void x86_interp_test_range(uint32_t start, uint32_t end);
void x86_interp_test_reset(void);
#include "x86_interp.h"

/* ---- memory -------------------------------------------------------------------------------- */
#define IMG_LO 0x00400000u
#define IMG_SIZE 0x00B6F000u
#define SCR_LO 0x01000000u
#define SCR_SIZE 0x02000000u
static uint8_t *g_img_pristine, *g_scr_pristine;

static uint64_t g_rng;
static uint32_t rnd(void) { g_rng ^= g_rng << 13; g_rng ^= g_rng >> 7; g_rng ^= g_rng << 17; return (uint32_t)(g_rng >> 11); }

typedef struct { uint32_t addr; uint8_t data[4096]; } page_t;
#define MAX_PAGES 64
typedef struct {
    uint32_t r[8];
    uint32_t k, a, b, cf;
    double st[8];
    int top;
    uint16_t cw;
    int npages;
    page_t pages[MAX_PAGES];
    int faulted;
    const char *why;
} snap_t;

static void collect_pages(snap_t *s) {
    s->npages = 0;
    const uint32_t lo[2] = { IMG_LO, SCR_LO }, size[2] = { IMG_SIZE, SCR_SIZE };
    const uint8_t *pristine[2] = { g_img_pristine, g_scr_pristine };
    static PVOID all[16384];
    for (int ri = 0; ri < 2; ri++) {
        ULONG_PTR n = 0;
        for (;;) {                                       /* every page written since the last reset */
            ULONG_PTR count = 16384 - n;
            ULONG gran;
            if (!count || GetWriteWatch(WRITE_WATCH_FLAG_RESET, (PVOID)(uintptr_t)lo[ri], size[ri], all + n, &count, &gran) != 0 || !count) break;
            n += count;
        }
        for (ULONG_PTR i = 0; i < n; i++) {
            uint32_t a = (uint32_t)(uintptr_t)all[i];
            if (s->npages < MAX_PAGES) {
                s->pages[s->npages].addr = a;
                memcpy(s->pages[s->npages].data, (void *)(uintptr_t)a, 4096);
                s->npages++;
            }
            memcpy((void *)(uintptr_t)a, pristine[ri] + (a - lo[ri]), 4096);   /* restore */
        }
        ResetWriteWatch((PVOID)(uintptr_t)lo[ri], size[ri]);           /* forget the restores */
    }
}

static const uint8_t *page_of(const snap_t *s, uint32_t a) {
    for (int i = 0; i < s->npages; i++) if (s->pages[i].addr == a) return s->pages[i].data;
    return a >= SCR_LO ? g_scr_pristine + (a - SCR_LO) : g_img_pristine + (a - IMG_LO);
}

/* ---- state --------------------------------------------------------------------------------- */
typedef struct {
    uint32_t r[8], k, a, b, cf, fs;
    double st[8];
    int top;
    uint16_t cw;
} state_t;

static uint32_t rval(void) {
    static const uint32_t special[] = { 0, 1, 2, 0xFFFFFFFFu, 0x7FFFFFFFu, 0x80000000u, 0x80, 0xFF, 0x7F, 0x8000, 0xFFFF, 0x10, 31, 32 };
    uint32_t c = rnd() % 10;
    if (c < 6) return SCR_LO + ((rnd() % (SCR_SIZE - 0x20000u)) & ~3u);   /* a pointer into scratch */
    if (c < 8) return special[rnd() % (sizeof special / sizeof special[0])];
    return rnd() ^ (rnd() << 16);
}

static double fval(void) {
    switch (rnd() % 6) {
    case 0: return (double)(int32_t)(rnd() % 2001) - 1000.0;
    case 1: return ((double)(rnd() % 100000) - 50000.0) / 256.0;
    case 2: return (double)(rnd() % 7) * 0.5;
    case 3: return 0.5;   /* not 0.0: the lift guards x87 division by zero (keeps the dividend) */
    case 4: return ((double)rnd() / 4294967296.0) * 6.283185307179586;
    default: return (double)(int32_t)rnd() * 1e-3;
    }
}

static void make_state(state_t *s, int has_rep) {
    for (int i = 0; i < 8; i++) s->r[i] = rval();
    s->r[4] = 0x02FF0000u - (rnd() % 256) * 4;                      /* esp */
    s->r[5] = s->r[4] + (rnd() % 64) * 4;                           /* ebp */
    if (has_rep) {
        s->r[1] = rnd() % 40;
        s->r[6] = SCR_LO + 0x100000u + (rnd() % 0x100000u);
        s->r[7] = SCR_LO + 0x400000u + (rnd() % 0x100000u);
    }
    if (rnd() & 1) { s->k = FK_EFLAGS; s->a = rnd() & 0x8D5u; s->b = 0; s->cf = s->a & 1u; }
    else { s->k = FK_CMP; s->a = rval(); s->b = rval(); s->cf = s->a < s->b; }
    for (int i = 0; i < 8; i++) s->st[i] = fval();
    s->top = (int)(rnd() % 9);
    static const uint16_t cws[] = { 0x027F, 0x037F, 0x0F7F, 0x077F, 0x0B7F, 0x0E7F };
    s->cw = cws[rnd() % 6];
    s->fs = 0x02800000u;
}

static void load_state(const state_t *s) {
    uint32_t *R[8] = { &g_eax, &g_ecx, &g_edx, &g_ebx, &g_esp, &g_ebp, &g_esi, &g_edi };
    for (int i = 0; i < 8; i++) *R[i] = s->r[i];
    g_flag_k = s->k; g_flag_a = s->a; g_flag_b = s->b; g_flag_cf = s->cf;
    memcpy(g_st, s->st, sizeof g_st);
    for (int i = 0; i < 8; i++) g_st_i64[i] = 0;
    g_fp_top = s->top;
    g_fpu_cw = s->cw;
    g_fs_base = s->fs;
    x86_interp_test_reset();
}

static void save(snap_t *o) {
    uint32_t *R[8] = { &g_eax, &g_ecx, &g_edx, &g_ebx, &g_esp, &g_ebp, &g_esi, &g_edi };
    for (int i = 0; i < 8; i++) o->r[i] = *R[i];
    o->k = g_flag_k; o->a = g_flag_a; o->b = g_flag_b; o->cf = g_flag_cf;
    memcpy(o->st, g_st, sizeof g_st);
    o->top = g_fp_top;
    o->cw = g_fpu_cw;
    collect_pages(o);
}

static int run_lifted(void (*fn)(void)) {
    __try { fn(); } __except (EXCEPTION_EXECUTE_HANDLER) { return 1; }
    return 0;
}

static int run_interp_seh(uint32_t a, uint32_t b) {
    __try { x86_interp_test_range(a, b); } __except (EXCEPTION_EXECUTE_HANDLER) { return 1; }
    return 0;
}

static int run_interp(uint32_t a, uint32_t b, const char **why) {
    *why = NULL;
    if (setjmp(x86_interp_test_jmp)) { *why = x86_interp_test_why; return 2; }
    return run_interp_seh(a, b);
}

static int same_double(double x, double y) {
    if (x != x && y != y) return 1;
    return memcmp(&x, &y, sizeof x) == 0;
}

static const char *const REG[8] = { "eax", "ecx", "edx", "ebx", "esp", "ebp", "esi", "edi" };
static const char *const CC[16] = { "o", "no", "b", "ae", "e", "ne", "be", "a", "s", "ns", "p", "np", "l", "ge", "le", "g" };

static snap_t g_A, g_B;

int main(int argc, char **argv) {
    const char *image = argc > 1 ? argv[1] : "image.bin";
    int seeds = argc > 2 ? atoi(argv[2]) : 4;
    int verbose_max = argc > 3 ? atoi(argv[3]) : 60;
    void *img = VirtualAlloc((LPVOID)(uintptr_t)IMG_LO, IMG_SIZE, MEM_RESERVE | MEM_COMMIT | MEM_WRITE_WATCH, PAGE_EXECUTE_READWRITE);
    void *scr = VirtualAlloc((LPVOID)(uintptr_t)SCR_LO, SCR_SIZE, MEM_RESERVE | MEM_COMMIT | MEM_WRITE_WATCH, PAGE_READWRITE);
    if (img != (void *)(uintptr_t)IMG_LO || scr != (void *)(uintptr_t)SCR_LO) { printf("cannot map guest memory at its VAs\n"); return 2; }
    FILE *f = fopen(image, "rb");
    if (!f) { printf("cannot read %s\n", image); return 2; }
    size_t got = fread(img, 1, IMG_SIZE, f);
    fclose(f);
    if (got < 0x3B6000u) { printf("short image\n"); return 2; }
    g_img_pristine = malloc(IMG_SIZE);
    memcpy(g_img_pristine, img, IMG_SIZE);
    g_scr_pristine = malloc(SCR_SIZE);
    g_rng = 0x9E3779B97F4A7C15ull;
    for (uint32_t i = 0; i < SCR_SIZE; i += 4) {
        uint32_t v = (rnd() % 4 == 0) ? SCR_LO + ((rnd() % (SCR_SIZE - 0x20000u)) & ~3u) : rnd();
        memcpy(g_scr_pristine + i, &v, 4);
    }
    memcpy(scr, g_scr_pristine, SCR_SIZE);
    ResetWriteWatch(img, IMG_SIZE);
    ResetWriteWatch(scr, SCR_SIZE);

    unsigned pass = 0, fail = 0, faults = 0, refused = 0, shown = 0;
    for (unsigned ci = 0; ci < difftest_case_count; ci++) {
        const difftest_case_t *tc = &difftest_cases[ci];
        int case_bad = 0;
        for (int sd = 0; sd < seeds && !case_bad; sd++) {
            g_rng = 0x1234567ull + ci * 7919ull + (uint64_t)sd * 104729ull;
            state_t st;
            make_state(&st, tc->has_rep);
            load_state(&st);
            int fa = run_lifted(tc->lifted);
            save(&g_A);
            load_state(&st);
            const char *why;
            int fb = run_interp(tc->start, tc->end, &why);
            save(&g_B);
            if (fb == 2 && fa && why && !strncmp(why, "divide", 6)) { faults++; continue; }   /* both trap */
            if (fb == 2) {
                refused++;
                if (shown < (unsigned)verbose_max) { printf("REFUSED #%u %s: %s\n", ci, tc->text, why); shown++; }
                case_bad = 1;
                break;
            }
            if (fa || fb) {
                if (fa != fb) {
                    fail++; case_bad = 1;
                    if (shown < (unsigned)verbose_max) { printf("FAULT #%u (lifted %d, interp %d) %s\n", ci, fa, fb, tc->text); shown++; }
                } else faults++;
                continue;
            }
            char diff[512] = "";
            for (int r = 0; r < 8; r++)
                if (g_A.r[r] != g_B.r[r]) snprintf(diff + strlen(diff), sizeof diff - strlen(diff), " %s %08X/%08X", REG[r], g_A.r[r], g_B.r[r]);
            for (int cc = 0; cc < 16; cc++) {
                if (!((tc->ccmask >> cc) & 1)) continue;
                /* x86 condition order -> recomp_cond's CC_* numbering */
                static const int RC[16] = { CC_O, CC_NO, CC_B, CC_AE, CC_E, CC_NE, CC_BE, CC_A,
                                            CC_S, CC_NS, CC_P, CC_NP, CC_L, CC_GE, CC_LE, CC_G };
                int x = recomp_cond(g_A.k, g_A.a, g_A.b, RC[cc]), y = recomp_cond(g_B.k, g_B.a, g_B.b, RC[cc]);
                if (x != y) snprintf(diff + strlen(diff), sizeof diff - strlen(diff), " cc_%s %d/%d", CC[cc], x, y);
            }
            if (g_A.top != g_B.top) snprintf(diff + strlen(diff), sizeof diff - strlen(diff), " fp_top %d/%d", g_A.top, g_B.top);
            if (g_A.cw != g_B.cw) snprintf(diff + strlen(diff), sizeof diff - strlen(diff), " cw %04X/%04X", g_A.cw, g_B.cw);
            for (int i = 0; i < 8; i++)
                if (!same_double(g_A.st[i], g_B.st[i])) { snprintf(diff + strlen(diff), sizeof diff - strlen(diff), " st%d %.17g/%.17g", i, g_A.st[i], g_B.st[i]); break; }
            for (int pass2 = 0; pass2 < 2; pass2++) {
                const snap_t *S = pass2 ? &g_B : &g_A;
                for (int p = 0; p < S->npages; p++) {
                    uint32_t a = S->pages[p].addr;
                    const uint8_t *x = page_of(&g_A, a), *y = page_of(&g_B, a);
                    if (memcmp(x, y, 4096)) {
                        int o = 0; while (x[o] == y[o]) o++;
                        snprintf(diff + strlen(diff), sizeof diff - strlen(diff), " mem[%08X] %02X/%02X", a + (uint32_t)o, x[o], y[o]);
                        pass2 = 2; break;
                    }
                }
            }
            if (diff[0]) {
                fail++; case_bad = 1;
                if (shown < (unsigned)verbose_max) {
                    printf("DIFF #%u seed %d %s:%s\n", ci, sd, tc->text, diff);
                    printf("     in k=%X a=%08X b=%08X cf=%u | lifted k=%X a=%08X b=%08X cf=%u | interp k=%X a=%08X\n",
                           st.k, st.a, st.b, st.cf, g_A.k, g_A.a, g_A.b, g_A.cf, g_B.k, g_B.a);
                    shown++;
                }
            } else pass++;
        }
    }
    /* ---- control flow: loops, calls into lifted and into uninterpreted code, ret n, tail jumps */
    {
        static const uint8_t fn1[] = {
            0x55, 0x8B, 0xEC, 0x8B, 0x45, 0x08,             /* push ebp; mov ebp, esp; mov eax, [ebp+8] */
            0x33, 0xC9, 0x41, 0x3B, 0xC8, 0x7C, 0xFB,       /* xor ecx, ecx; L: inc ecx; cmp ecx, eax; jl L */
            0xE8, 0, 0, 0, 0,                               /* call NATIVE1 (rel32 patched below) */
            0xE8, 0, 0, 0, 0,                               /* call FN2 (not lifted: interpreted) */
            0x5D, 0xC2, 0x04, 0x00 };                       /* pop ebp; ret 4 */
        static const uint8_t fn2[] = { 0x81, 0xC1, 0xE8, 0x03, 0x00, 0x00, 0xC3 };   /* add ecx, 1000; ret */
        static const uint8_t fn3[] = { 0xB9, 0x05, 0, 0, 0, 0xE2, 0xFE, 0xE9, 0, 0, 0, 0 };   /* mov ecx,5; loop $; jmp NATIVE2 */
        const uint32_t F1 = 0x01001000u, F2 = 0x01001100u, F3 = 0x01001200u;
        memcpy((void *)(uintptr_t)F1, fn1, sizeof fn1);
        memcpy((void *)(uintptr_t)F2, fn2, sizeof fn2);
        memcpy((void *)(uintptr_t)F3, fn3, sizeof fn3);
        uint32_t r1 = NATIVE1 - (F1 + 18), r2 = F2 - (F1 + 23), r3 = NATIVE2 - (F3 + 12);
        memcpy((void *)(uintptr_t)(F1 + 14), &r1, 4);
        memcpy((void *)(uintptr_t)(F1 + 19), &r2, 4);
        memcpy((void *)(uintptr_t)(F3 + 8), &r3, 4);
        int ok = 1;
        if (setjmp(x86_interp_test_jmp)) { printf("control flow: interpreter refused: %s\n", x86_interp_test_why); ok = 0; }
        else {
            g_esp = 0x02FF0000u; g_ebp = 0x11111111u;
            g_esp -= 4; MEM32(g_esp) = 7;                   /* the argument */
            g_esp -= 4; MEM32(g_esp) = RECOMP_RETADDR;      /* as a lifted caller pushes */
            ff7_interp_run(F1);
            if (g_ecx != 1107 || g_eax != 7 || g_esp != 0x02FF0000u || g_ebp != 0x11111111u) {
                printf("control flow FAIL: ecx %u (1107) eax %u esp %08X ebp %08X\n", g_ecx, g_eax, g_esp, g_ebp); ok = 0;
            }
            g_esp = 0x02FF0000u - 4; MEM32(g_esp) = RECOMP_RETADDR; g_eax = 0;
            ff7_interp_run(F3);
            if (g_eax != 42 || g_ecx != 0 || g_esp != 0x02FF0000u) {
                printf("tail jump FAIL: eax %u ecx %u esp %08X\n", g_eax, g_ecx, g_esp); ok = 0;
            }
        }
        { snap_t tmp; collect_pages(&tmp); }
        printf("control flow: %s\n", ok ? "pass" : "FAIL");
        if (!ok) fail++;
    }
    printf("%u runs x %d states: %u pass, %u differ, %u both faulted, %u refused by the interpreter\n",
           difftest_case_count, seeds, pass, fail, faults, refused);
    return fail || refused ? 1 : 0;
}
