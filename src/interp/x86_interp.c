/* x86_interp.c - see x86_interp.h.
 *
 * Coverage: every instruction form in ff7_en's code (148 mnemonics: integer, string, x87;
 * the exe has no MMX/SSE) plus what hand-written patches commonly add (pushad/popad, cmovcc,
 * xadd, cmpxchg, bswap, bsf/bsr). Not implemented: 16-bit addressing (0x67), segment
 * register moves, in/out, int, far transfers, MMX/SSE; those stop the game with the address.
 */
#define RECOMP_GENERATED_CODE
#include "recomp_types.h"
#include "portable_guest.h"     /* cpuid / rdtsc as the lifted code sees them */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "x86_interp.h"

/* ---- the runtime this runs in (src/host/ff7_overrides.c, the lift) ---------------------- */
extern uint32_t ff7_callsite_bits[];
extern uint32_t ff7_callsite_cur;
void ff7_run_callsite(void);
int ff7_override_install(uint32_t va, uint32_t target);
int ff7_override_active(uint32_t va);
#define FF7_INTERP_TARGET 0xFFFFFFFEu    /* the override target meaning "interpret" */
#define TEXT_LO 0x00401000u
#define TEXT_HI 0x007B6000u
#define CALLSITE_HOOKED(site) \
    ((uint32_t)((site) - TEXT_LO) < (TEXT_HI - TEXT_LO) && \
     (ff7_callsite_bits[((site) - TEXT_LO) >> 5] >> (((site) - TEXT_LO) & 31u) & 1u))

uint64_t ff7_interp_steps;

/* ---- marked functions ------------------------------------------------------------------- */
#define MAX_MARKED 4096
static uint32_t g_marked[MAX_MARKED];
static int g_nmarked;

int ff7_interp_marked(uint32_t va) {
    uint32_t h = (va * 2654435761u) & (MAX_MARKED - 1);
    for (int i = 0; i < MAX_MARKED; i++) {
        uint32_t k = (h + (uint32_t)i) & (MAX_MARKED - 1);
        if (g_marked[k] == va) return 1;
        if (!g_marked[k]) return 0;
    }
    return 0;
}

int ff7_interp_mark(uint32_t va) {
    if (ff7_interp_marked(va)) return 0;
    if (!recomp_lookup(va)) return -1;
    if (ff7_override_active(va)) return -2;
    if (g_nmarked >= MAX_MARKED / 2) return -3;
    if (ff7_override_install(va, FF7_INTERP_TARGET) != 0) return -3;
    uint32_t h = (va * 2654435761u) & (MAX_MARKED - 1);
    while (g_marked[h]) h = (h + 1) & (MAX_MARKED - 1);
    g_marked[h] = va;
    g_nmarked++;
    return 0;
}

uint32_t ff7_interp_function_of(uint32_t va) {
    uint32_t lo = 0, hi = recomp_dispatch_count;
    while (lo < hi) {                      /* first entry > va */
        uint32_t mid = lo + (hi - lo) / 2;
        if (recomp_dispatch_table[mid].address <= va) lo = mid + 1; else hi = mid;
    }
    return lo ? recomp_dispatch_table[lo - 1].address : 0;
}

/* ---- guest memory ---------------------------------------------------------------------- */
static inline uint8_t  rd8(uint32_t a)  { return MEM8(a); }
static inline uint16_t rd16(uint32_t a) { return MEM16(a); }
static inline uint32_t rd32(uint32_t a) { return MEM32(a); }
static inline void wr8(uint32_t a, uint8_t v)   { MEM8(a) = v; }
static inline void wr16(uint32_t a, uint16_t v) { MEM16(a) = v; }
static inline void wr32(uint32_t a, uint32_t v) { MEM32(a) = v; }
static inline float  rdf(uint32_t a) { return MEMF(a); }
static inline double rdd(uint32_t a) { return MEMD(a); }

/* ---- registers --------------------------------------------------------------------------- */
static uint32_t *const R[8] = { &g_eax, &g_ecx, &g_edx, &g_ebx, &g_esp, &g_ebp, &g_esi, &g_edi };

static inline uint32_t reg_get(int r, int sz) {
    if (sz == 4) return *R[r];
    if (sz == 2) return *R[r] & 0xFFFFu;
    return r < 4 ? (*R[r] & 0xFFu) : ((*R[r - 4] >> 8) & 0xFFu);
}
static inline void reg_set(int r, int sz, uint32_t v) {
    if (sz == 4) *R[r] = v;
    else if (sz == 2) *R[r] = (*R[r] & 0xFFFF0000u) | (v & 0xFFFFu);
    else if (r < 4) *R[r] = (*R[r] & 0xFFFFFF00u) | (v & 0xFFu);
    else *R[r - 4] = (*R[r - 4] & 0xFFFF00FFu) | ((v & 0xFFu) << 8);
}

/* ---- flags ------------------------------------------------------------------------------- */
enum { F_CF = 1u, F_PF = 4u, F_AF = 0x10u, F_ZF = 0x40u, F_SF = 0x80u, F_DF = 0x400u, F_OF = 0x800u };
#define ARITH (F_CF | F_PF | F_AF | F_ZF | F_SF | F_OF)

typedef struct {
    uint32_t eip, next;      /* this instruction, the one after it */
    uint32_t fl;             /* EFLAGS: the six arithmetic flags and DF */
    uint32_t func;           /* the activation's function */
} cpu_t;

static uint16_t g_fsw;       /* x87 status word: C0 C1 C2 C3 (the lift keeps none) */

static inline uint32_t mask_of(int sz) { return sz == 4 ? 0xFFFFFFFFu : sz == 2 ? 0xFFFFu : 0xFFu; }
static inline uint32_t msb_of(int sz)  { return sz == 4 ? 0x80000000u : sz == 2 ? 0x8000u : 0x80u; }
static inline uint32_t sx(uint32_t v, int sz) {
    return sz == 4 ? v : sz == 2 ? (uint32_t)(int32_t)(int16_t)v : (uint32_t)(int32_t)(int8_t)v;
}

static inline uint32_t szp(uint32_t r, int sz) {
    uint32_t f = 0;
    r &= mask_of(sz);
    if (!r) f |= F_ZF;
    if (r & msb_of(sz)) f |= F_SF;
    if (recomp_parity8((uint8_t)r)) f |= F_PF;
    return f;
}

static inline void set_arith(cpu_t *c, uint32_t f) { c->fl = (c->fl & ~ARITH) | (f & ARITH); }

static uint32_t do_add(cpu_t *c, uint32_t a, uint32_t b, uint32_t cin, int sz, int keep_cf) {
    uint32_t m = mask_of(sz);
    a &= m; b &= m;
    uint64_t wide = (uint64_t)a + b + cin;
    uint32_t r = (uint32_t)wide & m;
    uint32_t f = szp(r, sz);
    if (wide > m) f |= F_CF;
    if (((a ^ r) & (b ^ r)) & msb_of(sz)) f |= F_OF;
    if ((a ^ b ^ r) & 0x10u) f |= F_AF;
    if (keep_cf) f = (f & ~F_CF) | (c->fl & F_CF);
    set_arith(c, f);
    return r;
}

static uint32_t do_sub(cpu_t *c, uint32_t a, uint32_t b, uint32_t bin, int sz, int keep_cf) {
    uint32_t m = mask_of(sz);
    a &= m; b &= m;
    uint32_t r = (a - b - bin) & m;
    uint32_t f = szp(r, sz);
    if ((uint64_t)b + bin > a) f |= F_CF;
    if (((a ^ b) & (a ^ r)) & msb_of(sz)) f |= F_OF;
    if ((a ^ b ^ r) & 0x10u) f |= F_AF;
    if (keep_cf) f = (f & ~F_CF) | (c->fl & F_CF);
    set_arith(c, f);
    return r;
}

static uint32_t do_logic(cpu_t *c, uint32_t r, int sz) {
    set_arith(c, szp(r, sz));             /* CF = OF = 0; AF undefined (0) */
    return r & mask_of(sz);
}

/* add/or/adc/sbb/and/sub/xor/cmp by the opcode's reg field; returns the result (cmp: a). */
static uint32_t alu(cpu_t *c, int op, uint32_t a, uint32_t b, int sz) {
    switch (op) {
    case 0: return do_add(c, a, b, 0, sz, 0);
    case 1: return do_logic(c, a | b, sz);
    case 2: return do_add(c, a, b, c->fl & F_CF, sz, 0);
    case 3: return do_sub(c, a, b, c->fl & F_CF, sz, 0);
    case 4: return do_logic(c, a & b, sz);
    case 5: return do_sub(c, a, b, 0, sz, 0);
    case 6: return do_logic(c, a ^ b, sz);
    default: do_sub(c, a, b, 0, sz, 0); return a;
    }
}

static int cond(const cpu_t *c, int cc) {
    uint32_t f = c->fl;
    int r;
    switch (cc >> 1) {
    case 0: r = (f & F_OF) != 0; break;                                   /* o */
    case 1: r = (f & F_CF) != 0; break;                                   /* b */
    case 2: r = (f & F_ZF) != 0; break;                                   /* e */
    case 3: r = (f & (F_CF | F_ZF)) != 0; break;                          /* be */
    case 4: r = (f & F_SF) != 0; break;                                   /* s */
    case 5: r = (f & F_PF) != 0; break;                                   /* p */
    case 6: r = ((f & F_SF) != 0) != ((f & F_OF) != 0); break;            /* l */
    default: r = (f & F_ZF) || (((f & F_SF) != 0) != ((f & F_OF) != 0));  /* le */
    }
    return (cc & 1) ? !r : r;
}

/* The lifted code's lazy flags <-> an exact word. */
static void flags_in(cpu_t *c) {
    c->fl = recomp_eflags(g_flag_k, g_flag_a, g_flag_b, g_flag_cf, 1) & (ARITH);
}
static void flags_out(const cpu_t *c) {
    g_flag_k = FK_EFLAGS;
    g_flag_a = (c->fl & ARITH) | RECOMP_EFLAGS_FIXED;
    g_flag_b = 0;
    g_flag_cf = c->fl & F_CF;
}

/* ---- failure ----------------------------------------------------------------------------- */
#ifdef X86_INTERP_TEST
#include <setjmp.h>
jmp_buf x86_interp_test_jmp;
const char *x86_interp_test_why;
#endif
static void fail(const cpu_t *c, const char *what) {
#ifdef X86_INTERP_TEST
    (void)c;
    x86_interp_test_why = what;
    longjmp(x86_interp_test_jmp, 1);
#endif
    fprintf(stderr, "[interp] 0x%08X (function 0x%08X): %s; bytes", c->eip, c->func, what);
    for (int i = 0; i < 8; i++) fprintf(stderr, " %02X", rd8(c->eip + (uint32_t)i));
    fprintf(stderr, "\n");
    fflush(stderr);
    abort();
}

/* ---- decoding ----------------------------------------------------------------------------- */
typedef struct {
    int opsz;          /* 2 or 4 */
    int rep;           /* 0, 0xF2, 0xF3 */
    uint32_t seg;      /* segment base (fs/gs) */
    int mod, reg, rm;  /* ModRM fields */
    int is_reg;        /* rm is a register */
    uint32_t ea;       /* effective address when !is_reg */
} dec_t;

static inline uint8_t  f8(cpu_t *c)  { return rd8(c->next++); }
static inline uint16_t f16(cpu_t *c) { uint16_t v = rd16(c->next); c->next += 2; return v; }
static inline uint32_t f32(cpu_t *c) { uint32_t v = rd32(c->next); c->next += 4; return v; }
static inline uint32_t fimm(cpu_t *c, int sz) { return sz == 1 ? f8(c) : sz == 2 ? f16(c) : f32(c); }

static void modrm(cpu_t *c, dec_t *d) {
    uint8_t m = f8(c);
    d->mod = m >> 6; d->reg = (m >> 3) & 7; d->rm = m & 7;
    if (d->mod == 3) { d->is_reg = 1; return; }
    d->is_reg = 0;
    uint32_t a = 0;
    if (d->rm == 4) {
        uint8_t s = f8(c);
        int scale = s >> 6, idx = (s >> 3) & 7, base = s & 7;
        if (base == 5 && d->mod == 0) a = f32(c); else a = *R[base];
        if (idx != 4) a += *R[idx] << scale;
    } else if (d->rm == 5 && d->mod == 0) {
        a = f32(c);
    } else {
        a = *R[d->rm];
    }
    if (d->mod == 1) a += (uint32_t)(int32_t)(int8_t)f8(c);
    else if (d->mod == 2) a += f32(c);
    d->ea = a + d->seg;
}

static inline uint32_t rm_get(const dec_t *d, int sz) {
    if (d->is_reg) return reg_get(d->rm, sz);
    return sz == 4 ? rd32(d->ea) : sz == 2 ? rd16(d->ea) : rd8(d->ea);
}
static inline void rm_set(const dec_t *d, int sz, uint32_t v) {
    if (d->is_reg) { reg_set(d->rm, sz, v); return; }
    if (sz == 4) wr32(d->ea, v); else if (sz == 2) wr16(d->ea, (uint16_t)v); else wr8(d->ea, (uint8_t)v);
}

static inline void push32(uint32_t v) { g_esp -= 4; wr32(g_esp, v); }
static inline uint32_t pop32(void) { uint32_t v = rd32(g_esp); g_esp += 4; return v; }
static inline void pushv(uint32_t v, int sz) { if (sz == 4) push32(v); else { g_esp -= 2; wr16(g_esp, (uint16_t)v); } }
static inline uint32_t popv(int sz) { if (sz == 4) return pop32(); uint32_t v = rd16(g_esp); g_esp += 2; return v; }

/* ---- shifts and rotates (group 2) ------------------------------------------------------- */
static uint32_t shift(cpu_t *c, int op, uint32_t v, uint32_t cnt, int sz) {
    uint32_t m = mask_of(sz), msb = msb_of(sz);
    int bits = sz * 8;
    cnt &= 31;
    v &= m;
    if (!cnt) return v;
    uint32_t r = v, f = c->fl & ARITH, cf;
    switch (op) {
    case 0: {                                          /* rol */
        int n = (int)(cnt % (uint32_t)bits);
        r = n ? ((v << n) | (v >> (bits - n))) & m : v;
        cf = r & 1u;
        f = (f & ~(F_CF | F_OF)) | (cf ? F_CF : 0) | ((((r & msb) != 0) ^ cf) ? F_OF : 0);
        c->fl = (c->fl & ~ARITH) | f;
        return r;
    }
    case 1: {                                          /* ror */
        int n = (int)(cnt % (uint32_t)bits);
        r = n ? ((v >> n) | (v << (bits - n))) & m : v;
        cf = (r & msb) != 0;
        f = (f & ~(F_CF | F_OF)) | (cf ? F_CF : 0) | ((((r & msb) != 0) ^ ((r & (msb >> 1)) != 0)) ? F_OF : 0);
        c->fl = (c->fl & ~ARITH) | f;
        return r;
    }
    case 2: case 3: {                                  /* rcl / rcr through CF */
        int n = (int)(cnt % (uint32_t)(bits + 1));
        uint32_t carry = (c->fl & F_CF) ? 1u : 0u;
        for (int i = 0; i < n; i++) {
            if (op == 2) { uint32_t out = (r & msb) != 0; r = ((r << 1) | carry) & m; carry = out; }
            else { uint32_t out = r & 1u; r = (r >> 1) | (carry ? msb : 0); carry = out; }
        }
        uint32_t of = op == 2 ? (((r & msb) != 0) ^ carry) : (((r & msb) != 0) ^ ((r & (msb >> 1)) != 0));
        f = (f & ~(F_CF | F_OF)) | (carry ? F_CF : 0) | (of ? F_OF : 0);
        c->fl = (c->fl & ~ARITH) | f;
        return r;
    }
    case 4: case 6:                                    /* shl (sal) */
        cf = cnt <= (uint32_t)bits ? ((v >> (bits - (int)cnt)) & 1u) : 0;
        r = cnt < 32 ? (v << cnt) & m : 0;
        f = szp(r, sz) | (cf ? F_CF : 0) | ((((r & msb) != 0) ^ cf) ? F_OF : 0);
        break;
    case 5:                                            /* shr */
        cf = cnt <= (uint32_t)bits ? ((v >> (cnt - 1)) & 1u) : 0;
        r = cnt < 32 ? v >> cnt : 0;
        f = szp(r, sz) | (cf ? F_CF : 0) | ((v & msb) ? F_OF : 0);
        break;
    default: {                                         /* sar */
        int32_t s = (int32_t)sx(v, sz);
        uint32_t n = cnt < (uint32_t)bits ? cnt : (uint32_t)bits - 1;
        cf = cnt < (uint32_t)bits ? (uint32_t)((s >> (cnt - 1)) & 1) : (uint32_t)(s < 0);
        r = (uint32_t)(s >> n) & m;
        if (cnt >= (uint32_t)bits) r = s < 0 ? m : 0;
        f = szp(r, sz) | (cf ? F_CF : 0);
        break;
    }
    }
    c->fl = (c->fl & ~ARITH) | f;
    return r;
}

/* ---- x87 ----------------------------------------------------------------------------------- */
#define ST(i) g_st[(i)]
static void fpush(double v) { fp_push(v); }
static double fpop(void) { return fp_pop(); }

static void fcompare(double a, double b) {
    g_fsw &= (uint16_t)~0x4500u;
    if (isnan(a) || isnan(b)) g_fsw |= 0x4500u;
    else if (a < b) g_fsw |= 0x0100u;
    else if (a == b) g_fsw |= 0x4000u;
}

static double farith(int op, double a, double b) {     /* st0 op src (reg field order) */
    switch (op) {
    case 0: return a + b;
    case 1: return a * b;
    case 4: return a - b;
    case 5: return b - a;
    case 6: return a / b;
    default: return b / a;                             /* 7: fdivr */
    }
}

static uint16_t fsw_word(void) {
    return (uint16_t)((g_fsw & ~0x3800u) | (((unsigned)(-g_fp_top) & 7u) << 11));
}

static int32_t fist32(double v) {
    double r = fp_to_int(v);
    if (!(r >= -2147483648.0 && r <= 2147483647.0)) return INT32_MIN;
    return (int32_t)r;
}
static int16_t fist16(double v) {
    double r = fp_to_int(v);
    if (!(r >= -32768.0 && r <= 32767.0)) return INT16_MIN;
    return (int16_t)r;
}

static void x87(cpu_t *c, dec_t *d, uint8_t op) {
    modrm(c, d);
    int r = d->reg;
    if (!d->is_reg) {
        uint32_t a = d->ea;
        switch (op) {
        case 0xD8: { double s = rdf(a); if (r == 2 || r == 3) { fcompare(ST(0), s); if (r == 3) fpop(); } else ST(0) = farith(r, ST(0), s); return; }
        case 0xDC: { double s = rdd(a); if (r == 2 || r == 3) { fcompare(ST(0), s); if (r == 3) fpop(); } else ST(0) = farith(r, ST(0), s); return; }
        case 0xDA: { double s = (double)(int32_t)rd32(a); if (r == 2 || r == 3) { fcompare(ST(0), s); if (r == 3) fpop(); } else ST(0) = farith(r, ST(0), s); return; }
        case 0xDE: { double s = (double)(int16_t)rd16(a); if (r == 2 || r == 3) { fcompare(ST(0), s); if (r == 3) fpop(); } else ST(0) = farith(r, ST(0), s); return; }
        case 0xD9:
            switch (r) {
            case 0: fpush(rdf(a)); return;
            case 2: MEMF(a) = (float)ST(0); return;
            case 3: MEMF(a) = (float)ST(0); fpop(); return;
            case 4: return;                                            /* fldenv: control state kept */
            case 5: g_fpu_cw = rd16(a); return;                        /* fldcw */
            case 6: for (int i = 0; i < 28; i += 4) wr32(a + (uint32_t)i, 0);   /* fnstenv */
                    wr16(a, g_fpu_cw); wr16(a + 4, fsw_word()); wr16(a + 8, 0xFFFF); return;
            case 7: wr16(a, g_fpu_cw); return;                         /* fnstcw */
            }
            break;
        case 0xDB:
            switch (r) {
            case 0: fpush((double)(int32_t)rd32(a)); return;
            case 1: wr32(a, (uint32_t)(int32_t)trunc(ST(0))); fpop(); return;   /* fisttp */
            case 2: wr32(a, (uint32_t)fist32(ST(0))); return;
            case 3: wr32(a, (uint32_t)fist32(ST(0))); fpop(); return;
            case 5: { uint8_t b[10]; for (int i = 0; i < 10; i++) b[i] = rd8(a + (uint32_t)i); fpush(fp_ld80(b)); return; }
            case 7: { uint8_t b[10]; fp_st80(b, ST(0)); for (int i = 0; i < 10; i++) wr8(a + (uint32_t)i, b[i]); fpop(); return; }
            }
            break;
        case 0xDD:
            switch (r) {
            case 0: fpush(rdd(a)); return;
            case 2: MEMD(a) = ST(0); return;
            case 3: MEMD(a) = ST(0); fpop(); return;
            case 4: return;                                            /* frstor */
            case 6: for (int i = 0; i < 108; i += 4) wr32(a + (uint32_t)i, 0);  /* fnsave */
                    wr16(a, g_fpu_cw); wr16(a + 4, fsw_word()); return;
            case 7: wr16(a, fsw_word()); return;                       /* fnstsw m16 */
            }
            break;
        case 0xDF:
            switch (r) {
            case 0: fpush((double)(int16_t)rd16(a)); return;
            case 2: wr16(a, (uint16_t)fist16(ST(0))); return;
            case 3: wr16(a, (uint16_t)fist16(ST(0))); fpop(); return;
            case 5: { uint64_t v = MEM64(a); fp_push_i64((int64_t)v); return; }
            case 7: { int64_t v = fp_st0_to_i64(ST(0), g_st_i64[0], g_fpu_cw); MEM64(a) = (uint64_t)v; fpop(); return; }
            }
            break;
        }
        fail(c, "x87 memory form not implemented");
    }
    int i = d->rm;
    switch (op) {
    case 0xD8:
        if (r == 2) { fcompare(ST(0), ST(i)); return; }
        if (r == 3) { fcompare(ST(0), ST(i)); fpop(); return; }
        ST(0) = farith(r, ST(0), ST(i)); return;
    case 0xDC:                                                         /* st(i) op= st0 */
        if (r == 2 || r == 3) { fcompare(ST(0), ST(i)); if (r == 3) fpop(); return; }
        { static const int map[8] = { 0, 1, 2, 3, 5, 4, 7, 6 };       /* fsubr/fsub, fdivr/fdiv swap */
          ST(i) = farith(map[r], ST(i), ST(0)); }
        return;
    case 0xDE:
        if (r == 3 && i == 1) { fcompare(ST(0), ST(1)); fpop(); fpop(); return; }   /* fcompp */
        if (r == 2 || r == 3) break;
        if (r == 7 && ST(0) == 0.0) { fpop(); return; }               /* fdivp: the lift keeps the dividend */
        if (r == 6 && ST(i) == 0.0) { ST(i) = ST(0); fpop(); return; } /* fdivrp likewise */
        { static const int map[8] = { 0, 1, 2, 3, 5, 4, 7, 6 };
          ST(i) = farith(map[r], ST(i), ST(0)); }
        fpop();
        return;
    case 0xD9:
        if (r == 0) { double v = ST(i); fpush(v); return; }            /* fld st(i) */
        if (r == 1) { fp_xch(i); return; }                             /* fxch */
        if (r == 2 && i == 0) return;                                  /* fnop */
        if (r == 4) {
            switch (i) {
            case 0: ST(0) = -ST(0); return;
            case 1: ST(0) = fabs(ST(0)); return;
            case 4: fcompare(ST(0), 0.0); return;                      /* ftst */
            case 5: {                                                  /* fxam */
                double v = ST(0);
                g_fsw &= (uint16_t)~0x4700u;
                if (signbit(v)) g_fsw |= 0x0200u;
                if (isnan(v)) g_fsw |= 0x0100u;                        /* no "empty": the lift has no tags */
                else if (isinf(v)) g_fsw |= 0x0500u;
                else if (v == 0.0) g_fsw |= 0x4000u;
                else if (fabs(v) < 2.2250738585072014e-308) g_fsw |= 0x4400u;
                else g_fsw |= 0x0400u;
                return;
            }
            }
            break;
        }
        if (r == 5) {
            static const double k[7] = { 1.0, 3.32192809488736234787, 1.44269504088896340736, 3.14159265358979323846,
                                         0.30102999566398119521, 0.69314718055994530942, 0.0 };
            if (i < 7) { fpush(k[i]); return; }
            break;
        }
        if (r == 6) {
            switch (i) {
            case 0: ST(0) = pow(2.0, ST(0)) - 1.0; return;             /* f2xm1 (formulas as the lift has them) */
            case 1: { double x = fpop(); ST(0) *= log(x) / 0.6931471805599453; return; }   /* fyl2x */
            case 2: { double x = ST(0); ST(0) = tan(x); fpush(1.0); g_fsw &= (uint16_t)~0x0400u; return; }  /* fptan */
            case 3: { double x = ST(0); fpop(); ST(0) = atan2(ST(0), x); return; }  /* fpatan */
            case 4: { double x = ST(0), e = x == 0.0 ? -INFINITY : floor(log2(fabs(x)));   /* fxtract */
                      ST(0) = e; fpush(x == 0.0 ? x : x / exp2(e)); return; }
            case 5: ST(0) = fmod(ST(0), ST(1)); g_fsw &= (uint16_t)~0x0400u; return;   /* fprem1: as fprem, as lifted */
            case 6: g_fp_top--; return;                                /* fdecstp: not modelled */
            case 7: g_fp_top++; return;
            }
            break;
        }
        if (r == 7) {
            switch (i) {
            case 0: { ST(0) = fmod(ST(0), ST(1)); g_fsw &= (uint16_t)~0x0400u; return; }   /* fprem */
            case 1: { double x = fpop(); ST(0) *= log(x + 1.0) / 0.6931471805599453; return; }  /* fyl2xp1 */
            case 2: ST(0) = sqrt(ST(0)); return;
            case 3: { double x = ST(0); ST(0) = sin(x); fpush(cos(x)); g_fsw &= (uint16_t)~0x0400u; return; }  /* fsincos */
            case 4: ST(0) = fp_to_int(ST(0)); return;                  /* frndint */
            case 5: ST(0) *= pow(2.0, (double)(int)ST(1)); return;     /* fscale */
            case 6: ST(0) = sin(ST(0)); g_fsw &= (uint16_t)~0x0400u; return;
            case 7: ST(0) = cos(ST(0)); g_fsw &= (uint16_t)~0x0400u; return;
            }
        }
        break;
    case 0xDA:
        if (r == 5 && i == 1) { fcompare(ST(0), ST(1)); fpop(); fpop(); return; }   /* fucompp */
        break;
    case 0xDB:
        if (r == 4 && i == 2) { g_fsw &= 0x7F00u; return; }            /* fnclex */
        if (r == 4 && i == 3) { g_fsw = 0; g_fpu_cw = 0x037F; return; } /* fninit */
        if (r == 5 || r == 6) {                                        /* fucomi / fcomi */
            double a = ST(0), b = ST(i);
            uint32_t f = 0;
            if (isnan(a) || isnan(b)) f = F_ZF | F_PF | F_CF; else if (a < b) f = F_CF; else if (a == b) f = F_ZF;
            set_arith(c, f);
            return;
        }
        break;
    case 0xDD:
        if (r == 0) return;                                            /* ffree */
        if (r == 2) { ST(i) = ST(0); return; }                         /* fst st(i) */
        if (r == 3) { ST(i) = ST(0); fpop(); return; }                 /* fstp st(i) */
        if (r == 4 || r == 5) { fcompare(ST(0), ST(i)); if (r == 5) fpop(); return; }   /* fucom(p) */
        break;
    case 0xDF:
        if (r == 4 && i == 0) { reg_set(0, 2, fsw_word()); return; }   /* fnstsw ax */
        if (r == 5 || r == 6) {                                        /* fucomip / fcomip */
            double a = ST(0), b = ST(i);
            uint32_t f = 0;
            if (isnan(a) || isnan(b)) f = F_ZF | F_PF | F_CF; else if (a < b) f = F_CF; else if (a == b) f = F_ZF;
            set_arith(c, f);
            fpop();
            return;
        }
        break;
    }
    fail(c, "x87 register form not implemented");
}

/* ---- calls and jumps ---------------------------------------------------------------------- */
/* A lifted function or FFNx/host replacement to run natively, or NULL to interpret. */
static recomp_func_t native_of(uint32_t va) {
    if (ff7_interp_marked(va)) return NULL;
    recomp_func_t f = recomp_lookup_manual(va);
    if (!f) f = recomp_lookup(va);
    if (!f) f = recomp_lookup_import(va);
    return f;
}

static void call_native(cpu_t *c, recomp_func_t f) {
    flags_out(c);
    uint32_t caller = g_cur_func;
    f();
    g_cur_func = caller;
    flags_in(c);
}

static void do_call(cpu_t *c, uint32_t site, uint32_t target) {
    push32(c->next);
    if (CALLSITE_HOOKED(site)) {                     /* FFNx replace_call at this site */
        ff7_callsite_cur = site;
        call_native(c, ff7_run_callsite);
        return;
    }
    recomp_func_t f = native_of(target);
    if (f) { call_native(c, f); return; }
    flags_out(c);
    ff7_interp_run(target);                          /* code a patch added, or a marked function */
    flags_in(c);
}

/* A jump: continue here, or leave through a tail call. Returns 1 if the activation ended. */
static int do_jump(cpu_t *c, uint32_t target) {
    if (target != c->func) {
        recomp_func_t f = native_of(target);
        if (f && (recomp_lookup(target) || target >= 0x7F000000u)) {   /* a function entry: tail call */
            call_native(c, f);
            flags_out(c);
            return 1;
        }
    }
    c->next = target;
    return 0;
}

/* ---- string instructions ----------------------------------------------------------------- */
static void string_op(cpu_t *c, dec_t *d, uint8_t op) {
    int sz = (op & 1) ? d->opsz : 1;
    int step = (c->fl & F_DF) ? -sz : sz;
    int rep = d->rep;
    uint32_t src_seg = d->seg;                       /* a segment prefix applies to the source */
    for (;;) {
        if (rep && g_ecx == 0) break;
        switch (op) {
        case 0xA4: case 0xA5: {                      /* movs */
            uint32_t v = sz == 4 ? rd32(g_esi + src_seg) : sz == 2 ? rd16(g_esi + src_seg) : rd8(g_esi + src_seg);
            if (sz == 4) wr32(g_edi, v); else if (sz == 2) wr16(g_edi, (uint16_t)v); else wr8(g_edi, (uint8_t)v);
            g_esi += (uint32_t)step; g_edi += (uint32_t)step;
            break;
        }
        case 0xAA: case 0xAB:                        /* stos */
            if (sz == 4) wr32(g_edi, g_eax); else if (sz == 2) wr16(g_edi, (uint16_t)g_eax); else wr8(g_edi, (uint8_t)g_eax);
            g_edi += (uint32_t)step;
            break;
        case 0xAC: case 0xAD: {                      /* lods */
            uint32_t v = sz == 4 ? rd32(g_esi + src_seg) : sz == 2 ? rd16(g_esi + src_seg) : rd8(g_esi + src_seg);
            reg_set(0, sz, v);
            g_esi += (uint32_t)step;
            break;
        }
        case 0xA6: case 0xA7: {                      /* cmps */
            uint32_t a = sz == 4 ? rd32(g_esi + src_seg) : sz == 2 ? rd16(g_esi + src_seg) : rd8(g_esi + src_seg);
            uint32_t b = sz == 4 ? rd32(g_edi) : sz == 2 ? rd16(g_edi) : rd8(g_edi);
            do_sub(c, a, b, 0, sz, 0);
            g_esi += (uint32_t)step; g_edi += (uint32_t)step;
            break;
        }
        default: {                                   /* scas */
            uint32_t b = sz == 4 ? rd32(g_edi) : sz == 2 ? rd16(g_edi) : rd8(g_edi);
            do_sub(c, reg_get(0, sz), b, 0, sz, 0);
            g_edi += (uint32_t)step;
            break;
        }
        }
        if (!rep) break;
        g_ecx--;
        if (op == 0xA6 || op == 0xA7 || op == 0xAE || op == 0xAF) {
            int zf = (c->fl & F_ZF) != 0;
            if ((rep == 0xF3 && !zf) || (rep == 0xF2 && zf)) break;
        }
    }
}

/* ---- group 3: test/not/neg/mul/imul/div/idiv ------------------------------------------- */
static void group3(cpu_t *c, dec_t *d, int sz) {
    uint32_t v = rm_get(d, sz), m = mask_of(sz);
    switch (d->reg) {
    case 0: case 1: do_logic(c, v & fimm(c, sz), sz); return;
    case 2: rm_set(d, sz, ~v & m); return;
    case 3: { uint32_t r = do_sub(c, 0, v, 0, sz, 0); rm_set(d, sz, r); return; }
    case 4: case 5: {                                /* mul / imul (one operand) */
        uint32_t f;
        if (sz == 1) {
            if (d->reg == 4) { uint32_t r = (g_eax & 0xFF) * v; reg_set(0, 2, r); f = (r & 0xFF00u) != 0; }
            else { int32_t r = (int32_t)(int8_t)g_eax * (int32_t)(int8_t)v; reg_set(0, 2, (uint32_t)r); f = r != (int32_t)(int8_t)r; }
        } else if (sz == 2) {
            uint32_t r = d->reg == 4 ? (g_eax & 0xFFFF) * v : (uint32_t)((int32_t)(int16_t)g_eax * (int32_t)(int16_t)v);
            reg_set(0, 2, r); reg_set(2, 2, r >> 16);
            f = d->reg == 4 ? ((r >> 16) != 0) : (sx(r, 4) != sx(r & 0xFFFFu, 2));
        } else {
            if (d->reg == 4) { uint64_t r = (uint64_t)g_eax * v; g_eax = (uint32_t)r; g_edx = (uint32_t)(r >> 32); f = g_edx != 0; }
            else { int64_t r = (int64_t)(int32_t)g_eax * (int32_t)v; g_eax = (uint32_t)r; g_edx = (uint32_t)((uint64_t)r >> 32);
                   f = r != (int64_t)(int32_t)r; }
        }
        uint32_t lo = sz == 4 ? g_eax : reg_get(0, sz);
        set_arith(c, szp(lo, sz) | (f ? (F_CF | F_OF) : 0));
        return;
    }
    case 6: {                                        /* div: as the lift does it -- a zero divisor */
        if (sz == 1) {                               /* gives 0, an overflow truncates; no trap   */
            uint32_t n = g_eax & 0xFFFF;
            reg_set(0, 2, v ? ((n % v) << 8) | ((n / v) & 0xFFu) : 0u);
        } else if (sz == 2) {
            uint32_t n = (g_edx & 0xFFFF) << 16 | (g_eax & 0xFFFF);
            reg_set(0, 2, v ? n / v : 0u); reg_set(2, 2, v ? n % v : 0u);
        } else {
            uint64_t n = (uint64_t)g_edx << 32 | g_eax;
            if (v) { g_eax = (uint32_t)(n / v); g_edx = (uint32_t)(n % v); } else { g_eax = 0; g_edx = 0; }
        }
        return;
    }
    default: {                                       /* idiv (and INT_MIN / -1) likewise */
        if (sz == 1) {
            int32_t n = (int16_t)g_eax, dv = (int8_t)v;
            reg_set(0, 2, dv ? (((uint32_t)(n % dv) & 0xFFu) << 8) | ((uint32_t)(n / dv) & 0xFFu) : 0u);
        } else if (sz == 2) {
            int32_t n = (int32_t)((g_edx & 0xFFFF) << 16 | (g_eax & 0xFFFF)), dv = (int16_t)v;
            int ok = dv && !(dv == -1 && n == INT32_MIN);
            reg_set(0, 2, ok ? (uint32_t)(n / dv) : 0u); reg_set(2, 2, ok ? (uint32_t)(n % dv) : 0u);
        } else {
            int64_t n = (int64_t)((uint64_t)g_edx << 32 | g_eax), dv = (int32_t)v;
            if (dv && !(dv == -1 && n == INT64_MIN)) { g_eax = (uint32_t)(int32_t)(n / dv); g_edx = (uint32_t)(int32_t)(n % dv); }
            else { g_eax = 0; g_edx = 0; }
        }
        return;
    }
    }
}

/* ---- the last instructions run, for the crash report (ff7_crash.c) ------------------------- */
#define TRACE_N 48
static struct { uint32_t eip, func, eax, ecx, esp, ebp; } g_trace[TRACE_N];
static unsigned g_trace_i;

void ff7_interp_dump(void) {
    if (!g_trace_i) return;
    fprintf(stderr, "  interpreter, last %u instructions (eip func: eax ecx esp ebp):\n", g_trace_i < TRACE_N ? g_trace_i : TRACE_N);
    unsigned n = g_trace_i < TRACE_N ? g_trace_i : TRACE_N;
    for (unsigned k = 0; k < n; k++) {
        unsigned i = (g_trace_i - n + k) % TRACE_N;
        fprintf(stderr, "    %08X %08X: %08X %08X %08X %08X\n", g_trace[i].eip, g_trace[i].func,
                g_trace[i].eax, g_trace[i].ecx, g_trace[i].esp, g_trace[i].ebp);
    }
    /* the code bytes of the last two instructions, as they are in memory now */
    for (unsigned b = 2; b >= 1; b--) {
        uint32_t va = g_trace[(g_trace_i - b) % TRACE_N].eip;
        fprintf(stderr, "  bytes at %08X:", va);
        for (unsigned k = 0; k < 12; k++) fprintf(stderr, " %02X", MEM8(va + k));
        fprintf(stderr, "\n");
    }
}

/* ---- one instruction ---------------------------------------------------------------------- */
/* Returns 1 when the activation ends (ret, or a tail call out). */
static int step(cpu_t *c) {
    {
        unsigned i = g_trace_i++ % TRACE_N;
        g_trace[i].eip = c->eip; g_trace[i].func = c->func; g_trace[i].eax = g_eax;
        g_trace[i].ecx = g_ecx; g_trace[i].esp = g_esp; g_trace[i].ebp = g_ebp;
    }
    dec_t d;
    memset(&d, 0, sizeof d);
    d.opsz = 4;
    c->next = c->eip;
    uint32_t op;
    for (;;) {
        op = f8(c);
        if (op == 0x66) d.opsz = 2;
        else if (op == 0xF2 || op == 0xF3) d.rep = (int)op;
        else if (op == 0xF0 || op == 0x2E || op == 0x36 || op == 0x3E || op == 0x26) continue;
        else if (op == 0x64) d.seg = g_fs_base;
        else if (op == 0x65) d.seg = g_gs_base;
        else if (op == 0x67) fail(c, "16-bit addressing");
        else break;
    }
    if (op == 0x0F) op = 0x100u | f8(c);
    int sz = d.opsz;
    ff7_interp_steps++;

    switch (op) {
    /* ALU: 00-3F (except the prefix/BCD bytes) */
    case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05:
    case 0x08: case 0x09: case 0x0A: case 0x0B: case 0x0C: case 0x0D:
    case 0x10: case 0x11: case 0x12: case 0x13: case 0x14: case 0x15:
    case 0x18: case 0x19: case 0x1A: case 0x1B: case 0x1C: case 0x1D:
    case 0x20: case 0x21: case 0x22: case 0x23: case 0x24: case 0x25:
    case 0x28: case 0x29: case 0x2A: case 0x2B: case 0x2C: case 0x2D:
    case 0x30: case 0x31: case 0x32: case 0x33: case 0x34: case 0x35:
    case 0x38: case 0x39: case 0x3A: case 0x3B: case 0x3C: case 0x3D: {
        int aop = (int)(op >> 3) & 7, form = (int)op & 7;
        int s = (form & 1) ? sz : 1;
        if (form >= 4) {                                     /* AL/eAX, imm */
            uint32_t r = alu(c, aop, reg_get(0, s), fimm(c, s), s);
            if (aop != 7) reg_set(0, s, r);
        } else {
            modrm(c, &d);
            if (form < 2) {                                  /* E, G */
                uint32_t r = alu(c, aop, rm_get(&d, s), reg_get(d.reg, s), s);
                if (aop != 7) rm_set(&d, s, r);
            } else {                                         /* G, E */
                uint32_t r = alu(c, aop, reg_get(d.reg, s), rm_get(&d, s), s);
                if (aop != 7) reg_set(d.reg, s, r);
            }
        }
        break;
    }
    case 0x27: {                                             /* daa */
        uint32_t al = g_eax & 0xFF, cf = c->fl & F_CF, f = 0;
        if ((al & 0xF) > 9 || (c->fl & F_AF)) { al += 6; f |= F_AF; }
        if ((g_eax & 0xFF) > 0x99 || cf) { al += 0x60; f |= F_CF; }
        reg_set(0, 1, al);
        set_arith(c, szp(al, 1) | f);
        break;
    }
    case 0x37: {                                             /* aaa */
        if ((g_eax & 0xF) > 9 || (c->fl & F_AF)) { reg_set(0, 2, (g_eax + 0x106) & 0xFFFF); c->fl |= F_AF | F_CF; }
        else c->fl &= ~(F_AF | F_CF);
        reg_set(0, 1, g_eax & 0x0F);
        break;
    }
    case 0x40: case 0x41: case 0x42: case 0x43: case 0x44: case 0x45: case 0x46: case 0x47:
        reg_set((int)op & 7, sz, do_add(c, reg_get((int)op & 7, sz), 1, 0, sz, 1)); break;
    case 0x48: case 0x49: case 0x4A: case 0x4B: case 0x4C: case 0x4D: case 0x4E: case 0x4F:
        reg_set((int)op & 7, sz, do_sub(c, reg_get((int)op & 7, sz), 1, 0, sz, 1)); break;
    case 0x50: case 0x51: case 0x52: case 0x53: case 0x54: case 0x55: case 0x56: case 0x57:
        pushv(reg_get((int)op & 7, sz), sz); break;          /* push esp pushes the old value */
    case 0x58: case 0x59: case 0x5A: case 0x5B: case 0x5C: case 0x5D: case 0x5E: case 0x5F: {
        uint32_t v = popv(sz);
        reg_set((int)op & 7, sz, v);
        break;
    }
    case 0x60: {                                             /* pushad */
        uint32_t s0 = g_esp;
        for (int r = 0; r < 8; r++) pushv(r == 4 ? s0 : reg_get(r, sz), sz);
        break;
    }
    case 0x61:                                               /* popad (skips esp) */
        for (int r = 7; r >= 0; r--) { uint32_t v = popv(sz); if (r != 4) reg_set(r, sz, v); }
        break;
    case 0x68: pushv(fimm(c, sz), sz); break;
    case 0x6A: pushv(sx(f8(c), 1), sz); break;
    case 0x69: case 0x6B: case 0x1AF: {                      /* imul G, E(, imm) */
        modrm(c, &d);
        int32_t a = (int32_t)sx(rm_get(&d, sz), sz), b;
        if (op == 0x69) b = (int32_t)sx(fimm(c, sz), sz);
        else if (op == 0x6B) b = (int32_t)sx(f8(c), 1);
        else { b = a; a = (int32_t)sx(reg_get(d.reg, sz), sz); }
        int64_t r = (int64_t)a * b;
        uint32_t lo = (uint32_t)r & mask_of(sz);
        int ovf = r != (int64_t)(int32_t)sx(lo, sz);
        reg_set(d.reg, sz, lo);
        set_arith(c, szp(lo, sz) | (ovf ? (F_CF | F_OF) : 0));
        break;
    }
    case 0x70: case 0x71: case 0x72: case 0x73: case 0x74: case 0x75: case 0x76: case 0x77:
    case 0x78: case 0x79: case 0x7A: case 0x7B: case 0x7C: case 0x7D: case 0x7E: case 0x7F: {
        uint32_t rel = sx(f8(c), 1);
        if (cond(c, (int)op & 15)) return do_jump(c, c->next + rel);
        break;
    }
    case 0x180: case 0x181: case 0x182: case 0x183: case 0x184: case 0x185: case 0x186: case 0x187:
    case 0x188: case 0x189: case 0x18A: case 0x18B: case 0x18C: case 0x18D: case 0x18E: case 0x18F: {
        uint32_t rel = f32(c);
        if (cond(c, (int)op & 15)) return do_jump(c, c->next + rel);
        break;
    }
    case 0x80: case 0x81: case 0x82: case 0x83: {
        int s = (op == 0x81 || op == 0x83) ? sz : 1;
        modrm(c, &d);
        uint32_t b = op == 0x81 ? fimm(c, s) : (op == 0x83 ? sx(f8(c), 1) : f8(c));
        uint32_t r = alu(c, d.reg, rm_get(&d, s), b, s);
        if (d.reg != 7) rm_set(&d, s, r);
        break;
    }
    case 0x84: case 0x85: { int s = op == 0x85 ? sz : 1; modrm(c, &d); do_logic(c, rm_get(&d, s) & reg_get(d.reg, s), s); break; }
    case 0x86: case 0x87: {
        int s = op == 0x87 ? sz : 1;
        modrm(c, &d);
        uint32_t a = rm_get(&d, s), b = reg_get(d.reg, s);
        rm_set(&d, s, b); reg_set(d.reg, s, a);
        break;
    }
    case 0x88: case 0x89: { int s = op == 0x89 ? sz : 1; modrm(c, &d); rm_set(&d, s, reg_get(d.reg, s)); break; }
    case 0x8A: case 0x8B: { int s = op == 0x8B ? sz : 1; modrm(c, &d); reg_set(d.reg, s, rm_get(&d, s)); break; }
    case 0x8D: {
        d.seg = 0;                                           /* lea ignores segment bases */
        modrm(c, &d);
        if (d.is_reg) fail(c, "lea with a register operand");
        reg_set(d.reg, sz, d.ea);
        break;
    }
    case 0x8F: {                                             /* pop E: esp-relative after the pop */
        uint32_t v = popv(sz);
        modrm(c, &d);
        rm_set(&d, sz, v);
        break;
    }
    case 0x90: break;
    case 0x91: case 0x92: case 0x93: case 0x94: case 0x95: case 0x96: case 0x97: {
        uint32_t a = reg_get(0, sz), b = reg_get((int)op & 7, sz);
        reg_set(0, sz, b); reg_set((int)op & 7, sz, a);
        break;
    }
    case 0x98: if (sz == 4) g_eax = sx(g_eax & 0xFFFF, 2); else reg_set(0, 2, sx(g_eax & 0xFF, 1)); break;
    case 0x99: if (sz == 4) g_edx = (g_eax & 0x80000000u) ? 0xFFFFFFFFu : 0; else reg_set(2, 2, (g_eax & 0x8000u) ? 0xFFFF : 0); break;
    case 0x9B: break;                                        /* wait */
    case 0x9C: pushv((c->fl & (ARITH | F_DF)) | RECOMP_EFLAGS_FIXED, sz); break;
    case 0x9D: { uint32_t v = popv(sz); c->fl = (c->fl & ~(ARITH | F_DF)) | (v & (ARITH | F_DF)); break; }
    case 0x9E: c->fl = (c->fl & ~(F_CF | F_PF | F_AF | F_ZF | F_SF)) | ((g_eax >> 8) & (F_CF | F_PF | F_AF | F_ZF | F_SF)); break;
    case 0x9F: reg_set(4, 1, (c->fl & (F_CF | F_PF | F_AF | F_ZF | F_SF)) | 2u); break;
    case 0xA0: case 0xA1: { int s = op == 0xA1 ? sz : 1; uint32_t a = f32(c) + d.seg;
                            reg_set(0, s, s == 4 ? rd32(a) : s == 2 ? rd16(a) : rd8(a)); break; }
    case 0xA2: case 0xA3: { int s = op == 0xA3 ? sz : 1; uint32_t a = f32(c) + d.seg, v = reg_get(0, s);
                            if (s == 4) wr32(a, v); else if (s == 2) wr16(a, (uint16_t)v); else wr8(a, (uint8_t)v); break; }
    case 0xA4: case 0xA5: case 0xA6: case 0xA7: case 0xAA: case 0xAB: case 0xAC: case 0xAD: case 0xAE: case 0xAF:
        string_op(c, &d, (uint8_t)op); break;
    case 0xA8: do_logic(c, (g_eax & 0xFF) & f8(c), 1); break;
    case 0xA9: do_logic(c, reg_get(0, sz) & fimm(c, sz), sz); break;
    case 0xB0: case 0xB1: case 0xB2: case 0xB3: case 0xB4: case 0xB5: case 0xB6: case 0xB7:
        reg_set((int)op & 7, 1, f8(c)); break;
    case 0xB8: case 0xB9: case 0xBA: case 0xBB: case 0xBC: case 0xBD: case 0xBE: case 0xBF:
        reg_set((int)op & 7, sz, fimm(c, sz)); break;
    case 0xC0: case 0xC1: case 0xD0: case 0xD1: case 0xD2: case 0xD3: {
        int s = (op & 1) ? sz : 1;
        modrm(c, &d);
        uint32_t cnt = (op <= 0xC1) ? f8(c) : (op <= 0xD1 ? 1u : (g_ecx & 0xFF));
        rm_set(&d, s, shift(c, d.reg, rm_get(&d, s), cnt, s));
        break;
    }
    case 0xC2: { uint32_t n = f16(c); g_esp += 4 + n; flags_out(c); return 1; }
    case 0xC3: g_esp += 4; flags_out(c); return 1;
    case 0xC6: case 0xC7: { int s = op == 0xC7 ? sz : 1; modrm(c, &d); rm_set(&d, s, fimm(c, s)); break; }
    case 0xC8: {                                             /* enter n, 0 */
        uint32_t n = f16(c); uint8_t lvl = f8(c);
        if (lvl) fail(c, "enter with nesting");
        push32(g_ebp); g_ebp = g_esp; g_esp -= n;
        break;
    }
    case 0xC9: g_esp = g_ebp; g_ebp = pop32(); break;        /* leave */
    case 0xD7: reg_set(0, 1, rd8(g_ebx + (g_eax & 0xFF) + d.seg)); break;   /* xlat */
    case 0xD8: case 0xD9: case 0xDA: case 0xDB: case 0xDC: case 0xDD: case 0xDE: case 0xDF:
        x87(c, &d, (uint8_t)op); break;
    case 0xE0: case 0xE1: case 0xE2: {                       /* loopnz / loopz / loop */
        uint32_t rel = sx(f8(c), 1);
        g_ecx--;
        int z = (c->fl & F_ZF) != 0;
        int take = g_ecx != 0 && (op == 0xE2 || (op == 0xE1 ? z : !z));
        if (take) return do_jump(c, c->next + rel);
        break;
    }
    case 0xE3: { uint32_t rel = sx(f8(c), 1); if ((sz == 4 ? g_ecx : (g_ecx & 0xFFFF)) == 0) return do_jump(c, c->next + rel); break; }
    case 0xE8: { uint32_t rel = f32(c); do_call(c, c->eip, c->next + rel); break; }
    case 0xE9: { uint32_t rel = f32(c); return do_jump(c, c->next + rel); }
    case 0xEB: { uint32_t rel = sx(f8(c), 1); return do_jump(c, c->next + rel); }
    case 0xF5: c->fl ^= F_CF; break;
    case 0xF6: modrm(c, &d); group3(c, &d, 1); break;
    case 0xF7: modrm(c, &d); group3(c, &d, sz); break;
    case 0xF8: c->fl &= ~F_CF; break;
    case 0xF9: c->fl |= F_CF; break;
    case 0xFA: case 0xFB: break;                             /* cli / sti: nothing to mask */
    case 0xFC: c->fl &= ~F_DF; break;
    case 0xFD: c->fl |= F_DF; break;
    case 0xFE: {
        modrm(c, &d);
        if (d.reg == 0) rm_set(&d, 1, do_add(c, rm_get(&d, 1), 1, 0, 1, 1));
        else if (d.reg == 1) rm_set(&d, 1, do_sub(c, rm_get(&d, 1), 1, 0, 1, 1));
        else fail(c, "FE /r not implemented");
        break;
    }
    case 0xFF: {
        modrm(c, &d);
        switch (d.reg) {
        case 0: rm_set(&d, sz, do_add(c, rm_get(&d, sz), 1, 0, sz, 1)); break;
        case 1: rm_set(&d, sz, do_sub(c, rm_get(&d, sz), 1, 0, sz, 1)); break;
        case 2: { uint32_t t = rm_get(&d, 4); do_call(c, c->eip, t); break; }
        case 4: { uint32_t t = rm_get(&d, 4); return do_jump(c, t); }
        case 6: pushv(rm_get(&d, sz), sz); break;
        default: fail(c, "FF /r (far call/jmp) not implemented");
        }
        break;
    }
    /* ---- 0F xx ---- */
    case 0x131: { uint64_t t = __rdtsc(); g_eax = (uint32_t)t; g_edx = (uint32_t)(t >> 32); break; }
    case 0x140: case 0x141: case 0x142: case 0x143: case 0x144: case 0x145: case 0x146: case 0x147:
    case 0x148: case 0x149: case 0x14A: case 0x14B: case 0x14C: case 0x14D: case 0x14E: case 0x14F: {
        modrm(c, &d);
        uint32_t v = rm_get(&d, sz);
        if (cond(c, (int)op & 15)) reg_set(d.reg, sz, v);
        break;
    }
    case 0x190: case 0x191: case 0x192: case 0x193: case 0x194: case 0x195: case 0x196: case 0x197:
    case 0x198: case 0x199: case 0x19A: case 0x19B: case 0x19C: case 0x19D: case 0x19E: case 0x19F:
        modrm(c, &d); rm_set(&d, 1, (uint32_t)cond(c, (int)op & 15)); break;
    case 0x1A2: CPUID(g_eax, g_ebx, g_ecx, g_edx); break;
    case 0x1A3: case 0x1AB: case 0x1B3: case 0x1BB: case 0x1BA: {   /* bt/bts/btr/btc */
        modrm(c, &d);
        int kind;
        uint32_t bit;
        if (op == 0x1BA) { kind = d.reg - 4; bit = f8(c); if (kind < 0) fail(c, "0F BA /r not implemented"); }
        else { kind = op == 0x1A3 ? 0 : op == 0x1AB ? 1 : op == 0x1B3 ? 2 : 3; bit = reg_get(d.reg, sz); }
        uint32_t ea_adj = 0;
        if (!d.is_reg && op != 0x1BA) {                      /* a register bit offset reaches past the operand */
            int32_t sb = (int32_t)sx(bit, sz);
            ea_adj = (uint32_t)((sb >> (sz == 4 ? 5 : 4)) * sz);
            d.ea += ea_adj;
        }
        bit &= (uint32_t)(sz * 8 - 1);
        uint32_t v = rm_get(&d, sz), b = 1u << bit;
        c->fl = (c->fl & ~F_CF) | ((v & b) ? F_CF : 0);
        if (kind == 1) rm_set(&d, sz, v | b);
        else if (kind == 2) rm_set(&d, sz, v & ~b);
        else if (kind == 3) rm_set(&d, sz, v ^ b);
        break;
    }
    case 0x1A4: case 0x1A5: case 0x1AC: case 0x1AD: {        /* shld / shrd */
        modrm(c, &d);
        uint32_t cnt = (op == 0x1A4 || op == 0x1AC) ? f8(c) : (g_ecx & 0xFF);
        cnt &= 31;
        if (!cnt) break;
        int bits = sz * 8;
        uint32_t dst = rm_get(&d, sz), src = reg_get(d.reg, sz), m = mask_of(sz), r, cf;
        if (cnt > (uint32_t)bits) fail(c, "shld/shrd count beyond the operand");
        if (op <= 0x1A5) {
            uint64_t w = ((uint64_t)dst << bits) | src;
            w <<= cnt;
            r = (uint32_t)(w >> bits) & m;
            cf = (dst >> (bits - (int)cnt)) & 1u;
        } else {
            uint64_t w = ((uint64_t)src << bits) | dst;
            r = (uint32_t)(w >> cnt) & m;
            cf = (dst >> (cnt - 1)) & 1u;
        }
        rm_set(&d, sz, r);
        set_arith(c, szp(r, sz) | (cf ? F_CF : 0) | ((((r ^ dst) & msb_of(sz)) != 0) ? F_OF : 0));
        break;
    }
    case 0x1B0: case 0x1B1: {                                /* cmpxchg */
        int s = op == 0x1B1 ? sz : 1;
        modrm(c, &d);
        uint32_t v = rm_get(&d, s);
        do_sub(c, reg_get(0, s), v, 0, s, 0);
        if (c->fl & F_ZF) rm_set(&d, s, reg_get(d.reg, s)); else reg_set(0, s, v);
        break;
    }
    case 0x1B6: case 0x1B7: { int s = op == 0x1B7 ? 2 : 1; modrm(c, &d); reg_set(d.reg, sz, rm_get(&d, s)); break; }
    case 0x1BE: case 0x1BF: { int s = op == 0x1BF ? 2 : 1; modrm(c, &d); reg_set(d.reg, sz, sx(rm_get(&d, s), s)); break; }
    case 0x1BC: case 0x1BD: {                                /* bsf / bsr */
        modrm(c, &d);
        uint32_t v = rm_get(&d, sz);
        if (!v) { c->fl |= F_ZF; break; }
        c->fl &= ~F_ZF;
        int i = 0;
        if (op == 0x1BC) while (!((v >> i) & 1u)) i++;
        else { i = sz * 8 - 1; while (!((v >> i) & 1u)) i--; }
        reg_set(d.reg, sz, (uint32_t)i);
        break;
    }
    case 0x1C0: case 0x1C1: {                                /* xadd */
        int s = op == 0x1C1 ? sz : 1;
        modrm(c, &d);
        uint32_t a = rm_get(&d, s), b = reg_get(d.reg, s);
        uint32_t r = do_add(c, a, b, 0, s, 0);
        reg_set(d.reg, s, a);
        rm_set(&d, s, r);
        break;
    }
    case 0x1C8: case 0x1C9: case 0x1CA: case 0x1CB: case 0x1CC: case 0x1CD: case 0x1CE: case 0x1CF:
        *R[op & 7] = BSWAP32(*R[op & 7]); break;
    case 0x11F: modrm(c, &d); break;                         /* nop E (multi-byte nop) */
    default:
        fail(c, "instruction not implemented");
    }
    return 0;
}

void ff7_interp_run(uint32_t va) {
    cpu_t c;
    c.func = va;
    c.eip = va;
    flags_in(&c);
    uint32_t caller = g_cur_func;
    g_cur_func = va;
    for (;;) {
        if (step(&c)) break;
        c.eip = c.next;
    }
    g_cur_func = caller;
}

#ifdef X86_INTERP_TEST
/* tests/interp/difftest_main.c: run [start, end) of straight-line code. */
void x86_interp_test_range(uint32_t start, uint32_t end) {
    cpu_t c;
    c.func = start;
    c.eip = start;
    flags_in(&c);
    while (c.eip != end) {
        if (step(&c)) break;
        c.eip = c.next;
    }
    flags_out(&c);
}
void x86_interp_test_reset(void) { g_fsw = 0x4000; }   /* the lift starts each function at "equal" */
#endif
