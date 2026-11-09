#!/usr/bin/env python3
"""FF7Recomp lift driver (pcrecomp lift32, global-register model).

Adapted from codepdbh/nfsu2-recompiled scripts/run_lift.py (MIT License).

  run_lift.py catalog  <analysis.exe> <seeds.json> <catalog.json>
      Recursive descent (pcrecomp disasm32) to a catalog that keeps every
      function's BASIC BLOCK ranges, so lifting can re-decode without paying
      for the whole discovery again (it takes ~12 minutes on SPEED2.EXE).

  run_lift.py lift  <analysis.exe> <catalog.json> <outdir> [--split N] [--names names.json] [--prefix P]
                    [--manifests a.json,b.json]   FFNx patch manifests (see manifest_patches)
      Lift every catalogued function with lift32.Lifter and write:
        recomp_NNNN.c          the functions, N per file
        recomp_funcs.h         declarations
        recomp_dispatch.c      sorted VA -> function table (binary search)
        recomp_imports.c       IAT slot VA -> "dll!name" (for crash trails)
        recomp_variants.c      patched-code variants lifted (checked by ff7_hext.cpp)
        recomp_extents.c       guest ranges each function's body covers (tools/gen_extents.py)
        lift_stats.json        counts for docs/LIFT_REPORT.md
      --prefix P names a second module's output (P + every file and table
      above, e.g. server_recomp_dispatch_table for server.dll) so it links
      next to SPEED2.EXE's.

Everything written here is derived from the game binary and is gitignored.
"""
import gzip, json, os, sys, time, collections

HERE = os.path.dirname(os.path.abspath(__file__))
PCRECOMP = os.environ.get("PCRECOMP", os.path.normpath(os.path.join(HERE, "..", "external", "pcrecomp")))
for sub in ("pe", "disasm", "lift"):
    sys.path.insert(0, os.path.join(PCRECOMP, "tools", sub))

from capstone import Cs, CS_ARCH_X86, CS_MODE_32  # noqa: E402
from pe_analyze import analyze_pe, build_iat_map   # noqa: E402
import disasm32                                     # noqa: E402
from disasm32 import Disassembler, Function, BasicBlock, Instruction  # noqa: E402


def cmd_catalog(exe, seeds_path, out):
    info = analyze_pe(exe)
    iat = build_iat_map(info)
    data = open(exe, "rb").read()
    seeds = {info.image_base + info.entry_point_rva}
    for e in json.load(open(seeds_path)):
        a = e.get("address") if isinstance(e, dict) else e
        seeds.add(int(a, 0) if isinstance(a, str) else a)
    dis = Disassembler(data, info.image_base, info.sections)
    t = time.time()
    funcs = dis.find_functions(info.code_start, info.code_end, iat, seeds=seeds,
                               release_operands=True)
    print(f"[*] {len(funcs)} functions in {time.time() - t:.0f}s")
    doc = {"exe": os.path.basename(exe), "image_base": info.image_base,
           "code_start": info.code_start, "code_end": info.code_end,
           "functions": []}
    for f in sorted(funcs.values(), key=lambda f: f.address):
        doc["functions"].append({
            "address": f.address, "end": f.end, "size": f.size,
            "entry_kind": f.entry_kind, "is_thunk": f.is_thunk,
            "jump_targets": sorted(f.jump_targets),
            "calls_to": sorted(f.calls_to),
            "blocks": [[b.start, b.end, sorted(set(b.successors)), int(b.is_exit)]
                       for b in sorted(f.blocks.values(), key=lambda b: b.start)],
        })
    json.dump(doc, open(out, "w"))
    print(f"[*] wrote {out}")


def rebuild(md, img, base, fd):
    """A disasm32.Function with full capstone operands, from a catalog entry."""
    f = Function(address=fd["address"], end=fd["end"], size=fd["size"],
                 name=f"sub_{fd['address']:08X}", is_thunk=fd["is_thunk"],
                 entry_kind=fd["entry_kind"])
    f.jump_targets = set(fd["jump_targets"])
    f.calls_to = set(fd["calls_to"])
    for s, e, succ, ex in fd["blocks"]:
        b = BasicBlock(start=s, end=e, successors=list(succ), is_exit=bool(ex))
        for ins in md.disasm(img[s - base:e - base], s):
            b.instructions.append(Instruction(ins.address, ins.size, ins.mnemonic,
                                              ins.op_str, bytes(ins.bytes),
                                              list(ins.operands)))
        f.blocks[s] = b
    return f


def read_va_list(path):
    out = {}
    if path and os.path.exists(path):
        for line in open(path):
            line = line.split("#", 1)[0].strip()
            if line:
                parts = line.split(None, 1)
                out[int(parts[0], 16)] = parts[1] if len(parts) > 1 else ""
    return out


DWORD_VALUE_KINDS = {"patch_code_uint", "patch_code_dword", "patch_code_int"}


def manifest_patches(manifest_paths, img, base, md, fds):
    """FFNx's code patches (src/ffnx_port/patch_recomp.cpp manifests, one per FFNx config)
    -> (patch_sites, code_variants) for lift32.Lifter.

    dword value writes become patch_sites: the instruction reads that operand from memory,
    so whatever FFNx writes at run time takes effect. Every other write (opcodes, NOPs,
    copied instruction sequences, a call written over a non-call) becomes a variant: the
    patched instructions, lifted next to the original ones and chosen at run time by
    comparing the bytes in memory (dword values masked out)."""
    patch_sites, variants = set(), {}
    if not manifest_paths:
        return patch_sites, variants
    # Original instruction boundaries of every catalogued function.
    starts, ends = set(), set()
    for fd in fds:
        for s, e, _succ, _ex in fd["blocks"]:
            for ins in md.disasm(img[s - base:e - base], s):
                starts.add(ins.address)
                ends.add(ins.address + ins.size)
    sorted_starts = sorted(starts)
    import bisect

    def insn_start_containing(a):
        i = bisect.bisect_right(sorted_starts, a) - 1
        return sorted_starts[i] if i >= 0 else None

    def decode_one(buf_get, a):
        chunk = bytes(buf_get(a + k) for k in range(16))
        for ins in md.disasm(chunk, a):
            return ins
        return None

    def add_regions(overlay, struct, dyn):
        orig = lambda a: img[a - base]
        patched = lambda a: overlay.get(a, img[a - base])
        todo = sorted(struct)
        done = set()
        for a in todo:
            if a in done:
                continue
            s = insn_start_containing(a)
            if s is None:
                print(f"[!] patch at 0x{a:08X}: not inside lifted code, skipped")
                continue
            hi = a                                   # last patched byte the region must cover
            po = pp = s
            ok = True
            while not (po == pp and po > hi):
                if po <= pp:
                    i = decode_one(orig, po)
                    if not i: ok = False; break
                    po += i.size
                else:
                    i = decode_one(patched, pp)
                    if not i: ok = False; break
                    pp += i.size
                # patched bytes further on within the region extend it
                hi = max([hi] + [b for b in struct if s <= b < max(po, pp)])
                if max(po, pp) - s > 256:
                    ok = False; break
            if not ok:
                print(f"[!] patch at 0x{a:08X}: original and patched code do not resynchronise, skipped")
                continue
            e = po
            done.update(b for b in struct if s <= b < e)
            if e not in starts and e not in ends:
                print(f"[!] patch region 0x{s:08X}-0x{e:08X} does not end on an instruction, skipped")
                continue
            pbytes = bytes(patched(x) for x in range(s, e))
            mask = bytes(0 if x in dyn else 0xFF for x in range(s, e))
            insns = [Instruction(i.address, i.size, i.mnemonic, i.op_str, bytes(i.bytes), list(i.operands))
                     for i in md.disasm(pbytes, s)]
            v = {"end": e, "insns": insns, "bytes": pbytes, "mask": mask}
            lst = variants.setdefault(s, [])
            if not any(x["bytes"] == pbytes and x["mask"] == mask and x["end"] == e for x in lst):
                lst.append(v)

    for path in manifest_paths:
        m = json.load(open(path))
        overlay, dyn, struct = {}, set(), set()
        for w in m.get("code_writes", []):
            off = int(w["offset"], 16)
            data = bytes.fromhex(w["bytes"])
            for k, b in enumerate(data):
                overlay[off + k] = b
            if w["kind"] in DWORD_VALUE_KINDS and len(data) == 4:
                patch_sites.add(off)
                dyn.update(range(off, off + 4))
            elif w["kind"] == "replace_call_function" and len(data) == 5:
                struct.add(off)                      # the E8; its rel32 is the hook's business
                dyn.update(range(off + 1, off + 5))
            else:
                struct.update(range(off, off + len(data)))
        add_regions(overlay, struct, dyn)
        # Per-frame stepped values (patch_recomp.cpp step_fraction / step_alternate): each
        # value a site can hold, lifted as the manifest's patched code plus that one write.
        for cv in m.get("code_variants", []):
            off = int(cv["offset"], 16)
            data = bytes.fromhex(cv["bytes"])
            ov = dict(overlay)
            for k, b in enumerate(data):
                ov[off + k] = b
            add_regions(ov, set(range(off, off + len(data))), dyn)

    nv = sum(len(v) for v in variants.values())
    print(f"[*] FFNx manifests: {len(patch_sites)} live dword operands, {nv} patched-code variants at {len(variants)} sites")
    return patch_sites, variants


def cmd_lift(exe, catalog, outdir, split=400, names_path=None, prefix="", manifests=None):
    import pefile
    from lift32 import Lifter
    info = analyze_pe(exe)
    iat = build_iat_map(info)
    module = os.path.basename(exe)
    pe = pefile.PE(exe, fast_load=True)
    base = pe.OPTIONAL_HEADER.ImageBase
    img = pe.get_memory_mapped_image()
    cat = json.load(gzip.open(catalog, "rt") if catalog.endswith(".gz") else open(catalog))
    fds = cat["functions"]
    lifted = {fd["address"] for fd in fds}
    names = {}
    if names_path and os.path.exists(names_path):
        names = {int(k, 16): v for k, v in json.load(open(names_path)).items()}
    cfg = os.path.join(HERE, "..", "config")
    overrides = read_va_list(os.path.join(cfg, "overrides.txt"))
    excluded = read_va_list(os.path.join(cfg, "exclude.txt"))
    hooks = read_va_list(os.path.join(cfg, "hooks.txt"))
    md = Cs(CS_ARCH_X86, CS_MODE_32)
    md.detail = True
    patch_sites, code_variants = manifest_patches(manifests, img, base, md, fds)
    lifter = Lifter(iat_map=iat, func_names={}, lifted=lifted, precise_carry=True,
                    hook_sites=set(hooks), patch_sites=patch_sites, code_variants=code_variants)
    os.makedirs(outdir, exist_ok=True)
    for fn in os.listdir(outdir):
        if fn.startswith(prefix + "recomp_") and fn.endswith((".c", ".h")):
            os.remove(os.path.join(outdir, fn))

    stats = collections.Counter()
    unimpl = collections.Counter()
    unimpl_sites = collections.defaultdict(list)
    failed = []
    entries = []
    t0 = time.time()
    chunk, idx, lines_total = [], 0, 0

    def flush():
        nonlocal chunk, idx, lines_total
        if not chunk:
            return
        p = os.path.join(outdir, f"{prefix}recomp_{idx:04d}.c")
        with open(p, "w", newline="\n") as fh:
            fh.write(f"/* AUTO-GENERATED from {module} by run_lift.py (pcrecomp lift32). DO NOT EDIT, DO NOT COMMIT. */\n")
            fh.write(f'#define RECOMP_GENERATED_CODE\n#include "recomp_types.h"\n#include "{prefix}recomp_funcs.h"\n#include <math.h>\n#include <string.h>\n\n')
            body = "\n\n".join(chunk)
            fh.write(body)
            lines_total += body.count("\n")
        idx += 1
        chunk = []

    for n, fd in enumerate(fds):
        a = fd["address"]
        try:
            if a in overrides:
                code = (f"/* OVERRIDE: {overrides[a]} */\n"
                        f"void sub_{a:08X}(void) {{ recomp_func_t _f = recomp_lookup_manual(0x{a:08X}u);"
                        f" if (_f) {{ _f(); return; }} RECOMP_UNIMPL(0x{a:08X}u, \"override missing\"); }}")
                stats["overridden"] += 1
            elif a in excluded:
                code = (f"/* EXCLUDED: {excluded[a]} */\n"
                        f"void sub_{a:08X}(void) {{ RECOMP_UNIMPL(0x{a:08X}u, \"excluded: not code\"); }}")
                stats["excluded"] += 1
            else:
                f = rebuild(md, img, base, fd)
                code = lifter.lift_function(f)
        except Exception as ex:  # noqa: BLE001
            failed.append((a, repr(ex)))
            code = (f"/* LIFT FAILED: {ex!r} */\n"
                    f"void sub_{a:08X}(void) {{ RECOMP_UNIMPL(0x{a:08X}u, \"lift failed\"); }}")
        for line in code.splitlines():
            if "RECOMP_UNIMPL(" in line:
                key = line.split('RECOMP_UNIMPL(', 1)[1].split('"')[1].split()[0] if '"' in line else "?"
                unimpl[key] += 1
                if len(unimpl_sites[key]) < 10:
                    unimpl_sites[key].append(f"{a:08X}")
            if "RECOMP_ICALL(" in line: stats["icall_sites"] += 1
            if "RECOMP_ITAIL(" in line: stats["itail_sites"] += 1
        if names.get(a):
            code = f"/* {names[a]} */\n" + code
        chunk.append(code)
        entries.append(a)
        if len(chunk) >= split:
            flush()
            el = time.time() - t0
            print(f"[*] {n + 1}/{len(fds)} lifted ({el:.0f}s)", flush=True)
    flush()

    with open(os.path.join(outdir, f"{prefix}recomp_funcs.h"), "w", newline="\n") as fh:
        fh.write("/* AUTO-GENERATED. DO NOT EDIT. */\n#pragma once\n#include <stdint.h>\n")
        for a in entries:
            fh.write(f"void sub_{a:08X}(void);\n")
    with open(os.path.join(outdir, f"{prefix}recomp_dispatch.c"), "w", newline="\n") as fh:
        fh.write(f'/* AUTO-GENERATED. DO NOT EDIT. */\n#include "recomp_types.h"\n#include "{prefix}recomp_funcs.h"\n\n')
        fh.write(f"const recomp_dispatch_entry_t {prefix}recomp_dispatch_table[] = {{\n")
        for a in sorted(entries):
            fh.write(f"    {{ 0x{a:08X}u, sub_{a:08X} }},\n")
        fh.write("};\n")
        fh.write(f"const uint32_t {prefix}recomp_dispatch_count = {len(entries)};\n")
    with open(os.path.join(outdir, f"{prefix}recomp_imports.c"), "w", newline="\n") as fh:
        fh.write("/* AUTO-GENERATED: IAT slot -> import name, for diagnostics. */\n#include <stdint.h>\n")
        fh.write("typedef struct { uint32_t slot; const char* name; } ff7_import_name_t;\n")
        fh.write(f"const ff7_import_name_t {prefix}ff7_import_names[] = {{\n")
        for slot, (dll, nm) in sorted(iat.items()):
            fh.write(f'    {{ 0x{slot:08X}u, "{dll}!{nm}" }},\n')
        fh.write("    { 0, 0 } };\n")
    # Patched-code variants the lift has (src/host/ff7_hext.cpp checks mod hext code writes
    # against these: a write with no matching variant cannot take effect).
    def cstr(b):
        return '"' + "".join(f"\\x{x:02X}" for x in b) + '"'
    with open(os.path.join(outdir, f"{prefix}recomp_variants.c"), "w", newline="\n") as fh:
        fh.write("/* AUTO-GENERATED: lifted patched-code variants (start, end, bytes, mask). */\n#include <stdint.h>\n")
        fh.write("typedef struct { uint32_t start, end; const char *bytes, *mask; } ff7_code_variant_t;\n")
        rows = [(s, v) for s, vs in sorted(code_variants.items()) for v in vs]
        fh.write(f"const ff7_code_variant_t {prefix}ff7_code_variants[] = {{\n")
        for s, v in rows:
            fh.write(f"    {{ 0x{s:08X}u, 0x{v['end']:08X}u, {cstr(v['bytes'])}, {cstr(v['mask'])} }},\n")
        if not rows:
            fh.write("    { 0, 0, 0, 0 },\n")
        fh.write("};\n")
        fh.write(f"const uint32_t {prefix}ff7_code_variant_count = {len(rows)};\n")
    # Which functions hold each guest address (inline copies included): ff7_hext.cpp switches
    # every one of them to the interpreter for a patch the lift has no variant for.
    import gen_extents
    gen_extents.main(outdir, prefix)

    out = {"functions": len(entries), "files": idx, "c_lines": lines_total,
           "lift_failures": len(failed), "failures": failed[:200],
           "unimplemented_total": sum(unimpl.values()),
           "unimplemented": dict(unimpl.most_common()),
           "unimplemented_sites": dict(unimpl_sites),
           "overridden": stats["overridden"], "excluded": stats["excluded"],
           "icall_sites": stats["icall_sites"], "itail_sites": stats["itail_sites"],
           "seconds": round(time.time() - t0)}
    json.dump(out, open(os.path.join(outdir, f"{prefix}lift_stats.json"), "w"), indent=1)
    print(json.dumps({k: v for k, v in out.items() if k not in ("failures", "unimplemented_sites")}, indent=1))


if __name__ == "__main__":
    a = sys.argv[1:]
    if a and a[0] == "catalog":
        cmd_catalog(a[1], a[2], a[3])
    elif a and a[0] == "lift":
        split = int(a[a.index("--split") + 1]) if "--split" in a else 400
        names = a[a.index("--names") + 1] if "--names" in a else None
        prefix = a[a.index("--prefix") + 1] if "--prefix" in a else ""
        manifests = a[a.index("--manifests") + 1].split(",") if "--manifests" in a else None
        cmd_lift(a[1], a[2], a[3], split, names, prefix, manifests)
    else:
        sys.exit(__doc__)
