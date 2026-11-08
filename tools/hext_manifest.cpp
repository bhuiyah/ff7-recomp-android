// hext_manifest.cpp - build-time: the code writes of 7th Heaven mods' hext patches, as re-lift
// manifests (analysis/ffnx_manifests/hext_*.json, read by tools/run_lift.py --manifests), so
// the lift has the patched code and the device runs it when the mod is enabled.
//
// The mods are read exactly as on the device (src/mods: profile, mod.xml folders, hext files
// in 7th Heaven's order; src/mods/hext.cpp: the patch format). A `^` pointer read uses the
// exe's initial data; a file that reads a pointer set at run time stops there (its code
// writes before that line are kept) -- such pointers point at data, not code.
//
// usage: hext_manifest <ff7_en.exe> <out dir> <profile.xml | mod.iro | mod folder>...
//   profile.xml   one manifest for that profile's enabled mods together (hext_profile_<name>.json)
//   mod           one manifest for that mod alone with its default options (hext_<name>.json)
#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#include "hext.hpp"
#include "mods.h"

namespace {

constexpr uint32_t TEXT_LO = 0x00401000u, TEXT_HI = 0x007B6000u;

struct Image {
    uint32_t base = 0;
    std::vector<uint8_t> mem;     // [base, base + size)
    bool load(const char *path) {
        std::FILE *f = std::fopen(path, "rb");
        if (!f) return false;
        std::vector<uint8_t> file;
        uint8_t buf[65536];
        size_t r;
        while ((r = std::fread(buf, 1, sizeof buf, f)) > 0) file.insert(file.end(), buf, buf + r);
        std::fclose(f);
        auto u16 = [&](size_t o) { return (uint32_t)file.at(o) | (uint32_t)file.at(o + 1) << 8; };
        auto u32 = [&](size_t o) { return u16(o) | u16(o + 2) << 16; };
        size_t pe = u32(0x3C), opt = pe + 24;
        uint32_t nsec = u16(pe + 6), optsize = u16(pe + 20);
        base = u32(opt + 28);
        mem.assign(u32(opt + 56), 0);
        std::memcpy(mem.data(), file.data(), std::min<size_t>(u32(opt + 60), file.size()));
        for (uint32_t i = 0; i < nsec; i++) {
            size_t s = opt + optsize + 40 * i;
            uint32_t va = u32(s + 12), raw = u32(s + 16), ptr = u32(s + 20);
            if (raw && ptr + raw <= file.size() && va + raw <= mem.size()) std::memcpy(&mem[va], &file[ptr], raw);
        }
        return true;
    }
    uint8_t *at(uint32_t va, uint32_t n) {
        if (va < base || (uint64_t)va - base + n > mem.size()) return nullptr;
        return &mem[va - base];
    }
};

struct Run {
    Image img;                    // a fresh copy per manifest
    struct W { uint32_t va; std::vector<uint8_t> b; std::string from; };
    std::vector<W> writes;
    int files = 0, stopped = 0;
};

void on_hext(const char *mod, const char *file, const char *text, unsigned len, void *ctx) {
    Run &r = *static_cast<Run *>(ctx);
    std::string from = std::string(mod) + " " + file;
    ff7mods::HextResult res = ff7mods::hext_run(std::string(text, len), false,
        [&](uint32_t va) -> uint32_t {
            uint8_t *p = r.img.at(va, 4);
            if (!p) throw std::runtime_error("pointer read outside the exe image");
            uint32_t v; std::memcpy(&v, p, 4); return v;
        },
        [&](uint32_t va, const uint8_t *b, uint32_t n) {
            uint8_t *p = r.img.at(va, n);
            if (!p) throw std::runtime_error("write outside the exe image (run-time memory)");
            std::memcpy(p, b, n);
            if (va < TEXT_HI && va + n > TEXT_LO) r.writes.push_back({ va, std::vector<uint8_t>(b, b + n), from });
        });
    r.files++;
    if (!res.error.empty()) {
        r.stopped++;
        std::fprintf(stderr, "  %s: stopped at: %s\n", from.c_str(), res.error.c_str());
    }
}

std::string base_name(std::string p) {
    for (char &c : p) if (c == '\\') c = '/';
    while (!p.empty() && p.back() == '/') p.pop_back();
    p = p.substr(p.rfind('/') + 1);
    size_t dot = p.rfind('.');
    if (dot != std::string::npos && dot > 0) p = p.substr(0, dot);
    for (char &c : p) if (!std::isalnum((unsigned char)c) && c != '-' && c != '_') c = '_';
    return p;
}

bool ends_with_ci(const std::string &s, const char *suf) {
    size_t n = std::strlen(suf);
    if (s.size() < n) return false;
    for (size_t i = 0; i < n; i++) if (std::tolower((unsigned char)s[s.size() - n + i]) != suf[i]) return false;
    return true;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: hext_manifest <ff7_en.exe> <out dir> <profile.xml | mod.iro | mod folder>...\n");
        return 2;
    }
    Image exe;
    if (!exe.load(argv[1])) { std::fprintf(stderr, "cannot read %s\n", argv[1]); return 1; }
    std::string out = argv[2], tmp = out + "/.hext_profile.xml", cache = out + "/.hext_cache";
    int total = 0;
    for (int i = 3; i < argc; i++) {
        std::string arg = argv[i], profile, name;
        if (ends_with_ci(arg, ".xml")) {
            profile = arg;
            name = "hext_profile_" + base_name(arg);
        } else {
            std::string abs;
            for (char c : arg) {
                if (c == '&') abs += "&amp;"; else if (c == '<') abs += "&lt;"; else if (c == '"') abs += "&quot;"; else abs += c;
            }
            std::FILE *pf = std::fopen(tmp.c_str(), "wb");
            if (!pf) { std::fprintf(stderr, "cannot write %s\n", tmp.c_str()); return 1; }
            std::fprintf(pf, "<Profile Language=\"en\"><Mod Path=\"%s\"/></Profile>\n", abs.c_str());
            std::fclose(pf);
            profile = tmp;
            name = "hext_" + base_name(arg);
        }
        if (ff7_mods_load(profile.c_str(), cache.c_str()) != 0) { std::fprintf(stderr, "%s: cannot load\n", arg.c_str()); continue; }
        Run r;
        r.img = exe;
        ff7_mods_hext(on_hext, &r);
        std::string path = out + "/" + name + ".json";
        if (r.writes.empty()) {
            std::printf("%s: %d hext file(s), no code writes (nothing to lift)\n", arg.c_str(), r.files);
            std::remove(path.c_str());
            continue;
        }
        std::FILE *f = std::fopen(path.c_str(), "wb");
        if (!f) { std::fprintf(stderr, "cannot write %s\n", path.c_str()); return 1; }
        std::fprintf(f, "{\n \"source\": \"7th Heaven hext (tools/hext_manifest.cpp)\",\n \"hooks\": [],\n \"code_writes\": [\n");
        for (size_t k = 0; k < r.writes.size(); k++) {
            std::fprintf(f, "  {\"kind\": \"hext\", \"offset\": \"0x%08X\", \"bytes\": \"", r.writes[k].va);
            for (uint8_t b : r.writes[k].b) std::fprintf(f, "%02X", b);
            std::string from;
            for (char c : r.writes[k].from) { if (c == '"' || c == '\\') from += '\\'; from += c; }
            std::fprintf(f, "\", \"from\": \"%s\"}%s\n", from.c_str(), k + 1 < r.writes.size() ? "," : "");
        }
        std::fprintf(f, " ],\n \"data_writes\": 0\n}\n");
        std::fclose(f);
        total++;
        std::printf("%s: %d hext file(s), %zu code write(s) -> %s\n", arg.c_str(), r.files, r.writes.size(), path.c_str());
    }
    std::remove(tmp.c_str());
    return 0;
}
