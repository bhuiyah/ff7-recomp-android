// ff7_hext.cpp - 7th Heaven hext patches from the enabled mods, applied to guest memory at
// boot (after the exe is mapped, before FFNx attaches: on Windows 7th Heaven patches the
// process before the driver loads). Delayed files are applied from a background thread
// after their delay, as 7th Heaven does.
//
// Writes into data take effect as on Windows. Writes into .text take effect where the lift
// has the patched code (a code variant from a hext manifest, tools/hext_manifest.sh); each
// code write is checked against the lift's variant table (recomp_variants.c, emitted by
// run_lift.py), and the function holding a write with no lifted counterpart is switched to
// the x86 interpreter (src/interp), which runs the bytes in memory -- the patch included.
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <unistd.h>
#include <string>
#include <thread>
#include <vector>
#include "hext.hpp"
#include "mods.h"
#include "x86_interp.h"

extern "C" {
typedef struct { uint32_t start, end; const char *bytes, *mask; } ff7_code_variant_t;
// generated/recomp/recomp_variants.c (run_lift.py). A strong reference on purpose: the lift
// sits in a static library, whose members a weak reference never pulls in.
extern const ff7_code_variant_t ff7_code_variants[];
extern const uint32_t ff7_code_variant_count;
}

namespace {

constexpr uint32_t IMAGE_LO = 0x00401000u, IMAGE_HI = 0x00F6F000u;   // ff7_en sections
constexpr uint32_t TEXT_LO = 0x00401000u, TEXT_HI = 0x007B6000u;
constexpr uint32_t ARENA_LO = 0x33000000u, ARENA_HI = 0x6B010000u;   // heap, VirtualAlloc, FFNx block

bool readable(uint32_t va, uint32_t n) {
    uint64_t e = (uint64_t)va + n;
    return (va >= IMAGE_LO && e <= IMAGE_HI) || (va >= ARENA_LO && e <= ARENA_HI);
}

uint32_t read32(uint32_t va) {
    if (!readable(va, 4)) throw std::runtime_error("pointer read outside game memory");
    uint32_t v;
    std::memcpy(&v, (const void *)(uintptr_t)va, 4);
    return v;
}

// Is the patched code at [va, va+n) (now in memory) something the lift can run?
const char *code_status(uint32_t va, const std::vector<uint8_t> &before, uint32_t n) {
    if (std::memcmp(before.data(), (const void *)(uintptr_t)va, n) == 0) return nullptr;   // unchanged
    bool covered = false;
    for (uint32_t i = 0; i < ff7_code_variant_count; i++) {
        const ff7_code_variant_t &v = ff7_code_variants[i];
        if (v.end <= va || v.start >= va + n) continue;
        covered = true;
        const uint8_t *m = (const uint8_t *)(uintptr_t)v.start;
        bool match = true;
        for (uint32_t k = 0; k < v.end - v.start && match; k++)
            match = !(uint8_t)v.mask[k] || m[k] == (uint8_t)v.bytes[k];
        if (match) return nullptr;
    }
    return covered ? "patched code differs from every lifted variant" : "patched code not lifted";
}

struct Ctx { int files = 0, writes = 0, data_writes = 0, code_ok = 0, code_bad = 0, errors = 0, delayed = 0, interpreted = 0; };

// The function holding [va, va+n) runs interpreted from now on.
void interpret_at(const std::string &mod, const std::string &file, uint32_t va, uint32_t n, const char *why, Ctx &c) {
    uint32_t fn = ff7_interp_function_of(va);
    int rc = fn ? ff7_interp_mark(fn) : -1;
    if (rc == 0) {
        c.interpreted++;
        std::fprintf(stderr, "[hext] %s %s: code write 0x%08X (%u bytes), %s: function 0x%08X runs interpreted\n",
                     mod.c_str(), file.c_str(), va, n, why, fn);
        return;
    }
    c.code_bad++;
    std::fprintf(stderr, "[hext] %s %s: code write 0x%08X (%u bytes), %s: %s -- the patch has no effect\n",
                 mod.c_str(), file.c_str(), va, n, why,
                 rc == -2 ? "its function is replaced by FFNx (as on Windows, the replacement runs)"
                          : rc == -3 ? "interpreter table full" : "not inside a lifted function");
}

// ---- the launcher's view: mods/hext_status.txt, one row per hext file of the last run
// (mod file, hext file, state, data writes, code lifted, code interpreted, code without effect)
struct Row { std::string mod, file, state; int data, lifted, interpreted, none; };
std::mutex g_rows_lock;
std::vector<Row> g_rows;
std::string g_status_path;

void write_status() {
    if (g_status_path.empty()) return;
    std::string tmp = g_status_path + ".part";
    std::FILE *f = std::fopen(tmp.c_str(), "wb");
    if (!f) return;
    for (const Row &r : g_rows)
        std::fprintf(f, "%s\t%s\t%s\t%d\t%d\t%d\t%d\n", r.mod.c_str(), r.file.c_str(), r.state.c_str(),
                     r.data, r.lifted, r.interpreted, r.none);
    std::fclose(f);
    std::rename(tmp.c_str(), g_status_path.c_str());
}

void record(const std::string &mod, const std::string &file, const char *state, const Ctx &before, const Ctx &after) {
    std::lock_guard<std::mutex> lk(g_rows_lock);
    Row row{ mod, file, state, after.data_writes - before.data_writes, after.code_ok - before.code_ok,
             after.interpreted - before.interpreted, after.code_bad - before.code_bad };
    for (Row &r : g_rows)
        if (r.mod == mod && r.file == file) { r = row; write_status(); return; }
    g_rows.push_back(row);
    write_status();
}

void apply_one(const std::string &mod, const std::string &file, const std::string &text, bool honour_delay, Ctx &c) {
    const Ctx before = c;
    struct Done {                                   // the row for this file, however it ends
        const std::string &mod, &file; const Ctx &before, &c; const char *state = "applied";
        ~Done() { record(mod, file, state, before, c); }
    } done{ mod, file, before, c };
    ff7mods::HextResult r = ff7mods::hext_run(text, honour_delay, read32,
        [&](uint32_t va, const uint8_t *b, uint32_t n) {
            if (!readable(va, n)) throw std::runtime_error("write outside game memory");
            bool code = va < TEXT_HI && va + n > TEXT_LO;
            std::vector<uint8_t> before((const uint8_t *)(uintptr_t)va, (const uint8_t *)(uintptr_t)va + n);
            std::memcpy((void *)(uintptr_t)va, b, n);
            c.writes++;
            if (!code) { c.data_writes++; return; }
            if (const char *why = code_status(va, before, n)) {
                interpret_at(mod, file, va, n, why, c);
            } else {
                c.code_ok++;
            }
        });
    if (r.skipped) {
        done.state = "skipped";
        std::fprintf(stderr, "[hext] %s %s: Delay is not a number, not applied (as in 7th Heaven)\n", mod.c_str(), file.c_str());
        return;
    }
    if (r.delay_ms >= 0) {
        c.delayed++;
        done.state = "delayed";
        std::thread([mod, file, text, ms = r.delay_ms]() {
            std::this_thread::sleep_for(std::chrono::milliseconds(ms));
            Ctx d;
            apply_one(mod, file, text, false, d);
            std::fprintf(stderr, "[hext] delayed %s %s: %d writes (code: %d lifted, %d interpreted, %d without effect)\n",
                         mod.c_str(), file.c_str(), d.writes, d.code_ok, d.interpreted, d.code_bad);
        }).detach();
        return;
    }
    c.files++;
    if (!r.error.empty()) {
        c.errors++;
        done.state = "error";
        std::fprintf(stderr, "[hext] %s %s: stopped at: %s\n", mod.c_str(), file.c_str(), r.error.c_str());
    }
}

}  // namespace

extern "C" void ff7_hext_apply_mods(void) {
    Ctx c;
    {   // <game>/../mods/hext_status.txt (the process runs in the game folder), next to profile.xml
        char cwd[1024];
        if (getcwd(cwd, sizeof cwd)) {
            std::string p = cwd;
            size_t slash = p.rfind('/');
            if (slash != std::string::npos) g_status_path = p.substr(0, slash) + "/mods/hext_status.txt";
        }
        std::lock_guard<std::mutex> lk(g_rows_lock);
        g_rows.clear();
        write_status();                              // an empty file: no hext this run
    }
    ff7_mods_hext([](const char *mod, const char *file, const char *text, unsigned len, void *ctx) {
        apply_one(mod, file, std::string(text, len), true, *static_cast<Ctx *>(ctx));
    }, &c);
    if (c.files || c.delayed)
        std::fprintf(stderr, "[hext] %d file(s) applied, %d delayed: %d writes (%d data; code: %d lifted, %d interpreted, %d without effect), %d error(s)\n",
                     c.files, c.delayed, c.writes, c.data_writes, c.code_ok, c.interpreted, c.code_bad, c.errors);
}
