"""interp_difftest.py - differential test of the x86 interpreter (src/interp) against the
lift's own semantics (lift32), on real ff7_en code.

Straight-line runs of FF7 instructions (each catalogued block up to its first control
transfer) are lifted with lift32 exactly as a function lift would, and written as C next to
their address. tests/interp/difftest_main.c maps the exe image at its VAs, runs each run
lifted and interpreted from the same random machine states, and compares registers, the
16 branch conditions (only those whose flags are defined after the run, from capstone's
per-instruction flag effects), the x87 stack and every memory page either wrote.

usage: py tools/interp_difftest.py <out dir> [max runs = 6000] [per form = 12]
"""
import json, os, random, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run_lift                                                   # noqa: E402  (sets sys.path for pcrecomp)
from run_lift import rebuild, analyze_pe, build_iat_map           # noqa: E402
from lift32 import Lifter                                         # noqa: E402
import pefile                                                     # noqa: E402
from capstone import Cs, CS_ARCH_X86, CS_MODE_32                  # noqa: E402
from capstone import x86_const as X                               # noqa: E402

ROOT = os.path.normpath(os.path.join(HERE, ".."))

STOP = ("j", "call", "ret", "loop", "int", "iret", "retf", "into")
EXCLUDE = {"in", "out", "insb", "insd", "insw", "outsb", "outsd", "cpuid", "rdtsc", "cli", "sti",
           "arpl", "hlt", "fnsave", "frstor", "fldenv", "fnstenv", "lock dec", "lock inc",
           "fprem", "fprem1"}

# capstone eflags bits per flag: (modify/set/reset/undefined)
FLAGS = {
    "CF": (X.X86_EFLAGS_MODIFY_CF, X.X86_EFLAGS_SET_CF, X.X86_EFLAGS_RESET_CF, X.X86_EFLAGS_UNDEFINED_CF),
    "PF": (X.X86_EFLAGS_MODIFY_PF, X.X86_EFLAGS_SET_PF, X.X86_EFLAGS_RESET_PF, X.X86_EFLAGS_UNDEFINED_PF),
    "ZF": (X.X86_EFLAGS_MODIFY_ZF, X.X86_EFLAGS_SET_ZF, X.X86_EFLAGS_RESET_ZF, X.X86_EFLAGS_UNDEFINED_ZF),
    "SF": (X.X86_EFLAGS_MODIFY_SF, X.X86_EFLAGS_SET_SF, X.X86_EFLAGS_RESET_SF, X.X86_EFLAGS_UNDEFINED_SF),
    "OF": (X.X86_EFLAGS_MODIFY_OF, X.X86_EFLAGS_SET_OF, X.X86_EFLAGS_RESET_OF, X.X86_EFLAGS_UNDEFINED_OF),
}
# condition code (o, no, b, ae, e, ne, be, a, s, ns, p, np, l, ge, le, g) -> flags it reads
CC_FLAGS = [{"OF"}, {"OF"}, {"CF"}, {"CF"}, {"ZF"}, {"ZF"}, {"CF", "ZF"}, {"CF", "ZF"},
            {"SF"}, {"SF"}, {"PF"}, {"PF"}, {"SF", "OF"}, {"SF", "OF"}, {"ZF", "SF", "OF"}, {"ZF", "SF", "OF"}]


def is_x87(i):
    """x87 instructions report FPU status flags through the same capstone field."""
    return i.mnemonic.startswith("f") and not i.mnemonic.startswith(("fcomi", "fucomi"))


def defined_after(insns):
    """Which flags are defined after the run (flags never written keep the input's)."""
    state = {f: True for f in FLAGS}
    for i in insns:
        if is_x87(i):
            continue
        ef = i.eflags or 0
        for f, (mod, st, rs, und) in FLAGS.items():
            if ef & und:
                state[f] = False
            elif ef & (mod | st | rs):
                state[f] = True
        # count-dependent: a shift/rotate by cl or by more than 1 leaves OF undefined
        if i.mnemonic in ("shl", "sal", "shr", "sar", "rol", "ror", "rcl", "rcr", "shld", "shrd"):
            ops = i.operands
            if not (ops and ops[-1].type == X.X86_OP_IMM and ops[-1].imm == 1):
                state["OF"] = False
        if i.mnemonic in ("mul", "imul"):
            for f in ("PF", "ZF", "SF"):
                state[f] = False
    return state


# The lift's documented approximations (pcrecomp lift32): after these as the LAST flag
# writer of a run, the lifted flags are not the hardware's, so those conditions are not
# compared. The interpreter computes them exactly; each was checked by hand (docs/NOTES.md).
#   inc/dec           CF: recomp_cond reports the add's carry, not the preserved one
#   shl/shr/sal       OF always 0; PF of a narrow operand taken from the left-aligned value
#   imul/mul (2/3-op) flags not written at all
#   bt/bts/btr/btc    ZF derived (hardware leaves it)
#   sahf              OF/SF: the lift reads fcom results as unsigned only (as MSVC branches)
#   fcom/ftst/fucom   the lift keeps the compare in the integer flags (for a later sahf)
#   cmps (narrow)     compared as 32-bit words: SF/OF of a byte compare
#   sbb/adc (narrow)  SF/OF from the 32-bit result
LIFT_APPROX = {
    "inc": {"CF"}, "dec": {"CF"},
    "shl": {"OF", "PF"}, "sal": {"OF", "PF"}, "shr": {"OF", "PF"}, "sar": {"PF"},
    "imul": {"CF", "OF", "SF", "ZF", "PF"}, "mul": {"CF", "OF", "SF", "ZF", "PF"},
    "bt": {"ZF", "SF", "OF", "PF"}, "bts": {"ZF", "SF", "OF", "PF"}, "btr": {"ZF", "SF", "OF", "PF"}, "btc": {"ZF", "SF", "OF", "PF"},
    "sahf": {"OF", "SF"},
    "repe cmpsb": {"SF", "OF"}, "repne scasb": {"SF", "OF"}, "cmpsb": {"SF", "OF"},
}
P_FORMS = ("faddp", "fsubp", "fsubrp", "fmulp", "fdivp", "fdivrp")
X87_COMPARES = ("fcom", "fcomp", "fcompp", "ftst", "fucom", "fucomp", "fucompp", "fxam", "ficom", "ficomp")


def lift_approx(insns):
    """Flags the lift does not model after this run (see LIFT_APPROX)."""
    for i in reversed(insns):
        m = i.mnemonic
        if m in X87_COMPARES:
            return set(FLAGS)
        if is_x87(i):
            continue
        if m in ("sbb", "adc") and i.operands and i.operands[0].size < 4:
            return {"SF", "OF"}
        if (i.eflags or 0) & sum(sum(v[:3]) for v in FLAGS.values()):
            return LIFT_APPROX.get(m, set())
    return set()


def form_of(i):
    t = {X.X86_OP_REG: "r", X.X86_OP_MEM: "m", X.X86_OP_IMM: "i"}
    return i.mnemonic + " " + ",".join(t.get(o.type, "?") + str(o.size * 8) for o in i.operands)


def main():
    out = sys.argv[1]
    max_runs = int(sys.argv[2]) if len(sys.argv) > 2 else 6000
    per_form = int(sys.argv[3]) if len(sys.argv) > 3 else 12
    os.makedirs(out, exist_ok=True)
    exe = os.path.join(ROOT, "game", "ff7_en.exe")
    pe = pefile.PE(exe, fast_load=True)
    base = pe.OPTIONAL_HEADER.ImageBase
    img = pe.get_memory_mapped_image()
    open(os.path.join(out, "image.bin"), "wb").write(img)
    info = analyze_pe(exe)
    iat = build_iat_map(info)
    fds = json.load(open(os.path.join(ROOT, "analysis", "catalog.json")))["functions"]
    md = Cs(CS_ARCH_X86, CS_MODE_32)
    md.detail = True
    lifter = Lifter(iat_map=iat, func_names={}, lifted={fd["address"] for fd in fds}, precise_carry=True)

    runs, seen = [], set()
    for fd in fds:
        for s, e, _succ, _ex in fd["blocks"]:
            if (s, e) in seen:
                continue
            seen.add((s, e))
            cur = []
            for ins in md.disasm(img[s - base:e - base], s):
                m = ins.mnemonic
                if m.startswith(STOP) or m in EXCLUDE:
                    break
                cur.append(ins)
                if len(cur) >= 24:
                    break
            # skip segment-register pushes (data disassembled as code)
            if cur and not any(i.mnemonic in ("push", "pop") and i.op_str in ("es", "ds", "ss", "cs", "fs", "gs")
                               for i in cur):
                first_cmp = next((k for k, i in enumerate(cur) if i.mnemonic in X87_COMPARES), len(cur))
                first_sw = next((k for k, i in enumerate(cur) if i.mnemonic == "fnstsw"), len(cur))
                if first_sw >= first_cmp:      # fnstsw with no compare before it reads the lift's per-function default
                    runs.append(cur)
    rnd = random.Random(7)
    rnd.shuffle(runs)
    count, chosen, taken = {}, [], set()
    for k, r in enumerate(runs):                     # every form at least per_form times
        rare = any(i.mnemonic in P_FORMS and i.op_str == "st(0)" for i in r)   # pcrecomp patch 0019
        if rare or any(count.get(form_of(i), 0) < per_form for i in r):
            chosen.append(r)
            taken.add(k)
            for i in r:
                count[form_of(i)] = count.get(form_of(i), 0) + 1
    for k, r in enumerate(runs):                     # then random runs up to max_runs
        if len(chosen) >= max_runs:
            break
        if k not in taken:
            chosen.append(r)

    from run_lift import Instruction
    body, table, skipped = [], [], 0
    for n, r in enumerate(chosen):
        lifter._flag_state = None
        lifter._labels = set()
        lifter._jump_targets = set()
        lifter._fp_depth = 0
        try:
            lines = []
            for ins in r:
                ii = Instruction(ins.address, ins.size, ins.mnemonic, ins.op_str, bytes(ins.bytes), list(ins.operands))
                lines += lifter.lift_instruction(ii)
        except Exception as ex:                      # noqa: BLE001
            skipped += 1
            continue
        text = "\n".join("    " + l for l in lines)
        if "RECOMP_UNIMPL" in text or "goto " in text or "RECOMP_ICALL" in text:
            skipped += 1
            continue
        d = defined_after(r)
        for f in lift_approx(r):
            d[f] = False
        ccmask = sum(1 << k for k, need in enumerate(CC_FLAGS) if all(d[f] for f in need))
        name = f"t{len(table)}"
        body.append(f"static void {name}(void) {{\n"
                    f"    int _fpu_cmp = 0; uint32_t _cf = 0; int _df = 1; uint32_t _flag_a = 0, _flag_b = 0, _flag_k = FK_NONE;\n"
                    f"    (void)_fpu_cmp; (void)_df;\n"
                    f"    RECOMP_FLAGS_IN();\n{text}\n    RECOMP_FLAGS_OUT();\n}}\n")
        asm = " ; ".join(f"{i.mnemonic} {i.op_str}".strip() for i in r).replace("\\", "/").replace('"', "'")
        has_rep = int(any(i.mnemonic.startswith("rep") for i in r))
        table.append(f'    {{ 0x{r[0].address:08X}u, 0x{r[-1].address + r[-1].size:08X}u, {name}, 0x{ccmask:04X}u, {has_rep}, "{asm[:200]}" }},')
    with open(os.path.join(out, "difftest_cases.c"), "w", newline="\n") as fh:
        fh.write("/* AUTO-GENERATED by tools/interp_difftest.py from ff7_en: do not commit. */\n")
        fh.write('#include "difftest.h"\n\n')
        fh.write("\n".join(body))
        fh.write("\nconst difftest_case_t difftest_cases[] = {\n" + "\n".join(table) + "\n};\n")
        fh.write(f"const unsigned difftest_case_count = {len(table)};\n")
    forms = len(count)
    print(f"{len(table)} runs ({sum(len(r) for r in chosen)} instructions, {forms} forms), {skipped} skipped (lifter refuses)")


if __name__ == "__main__":
    main()
