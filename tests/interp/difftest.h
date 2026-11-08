/* difftest.h - shared by the generated cases (tools/interp_difftest.py) and the harness. */
#ifndef FF7_INTERP_DIFFTEST_H
#define FF7_INTERP_DIFFTEST_H
#define RECOMP_GENERATED_CODE
#include "recomp_types.h"

typedef struct {
    uint32_t start, end;        /* the run: [start, end) of straight-line ff7_en code */
    void (*lifted)(void);       /* the run as lift32 lifts it */
    uint32_t ccmask;            /* conditions whose flags are defined after the run */
    int has_rep;                /* contains a rep string op: start with a small ecx */
    const char *text;           /* the instructions, for reports */
} difftest_case_t;

extern const difftest_case_t difftest_cases[];
extern const unsigned difftest_case_count;
#endif
