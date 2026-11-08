// iro.cpp - see iro.hpp.
#include "iro.hpp"
#include <cctype>
#include <cstdlib>
#include <cstring>
#include "lzs.h"
extern "C" {
#include "LzmaDec.h"
}

namespace ff7mods {

namespace {
constexpr uint32_t kSig = 0x534F5249u;           // "IROS"
constexpr uint32_t kMinVersion = 0x10000u, kMaxVersion = 0x10002u;
constexpr uint32_t kCompressMask = 0xFu, kLZS = 1u, kLZMA = 2u;
constexpr uint32_t kRudeFlags = 0xFF0000u;       // obfuscated entries: not supported, as in 7th Heaven

std::string utf16le_to_utf8(const uint8_t *p, size_t bytes) {
    std::string s;
    for (size_t i = 0; i + 1 < bytes; i += 2) {
        uint32_t c = p[i] | (p[i + 1] << 8);
        if (c >= 0xD800 && c < 0xDC00 && i + 3 < bytes) {          // surrogate pair
            uint32_t lo = p[i + 2] | (p[i + 3] << 8);
            c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
            i += 2;
        }
        if (c < 0x80) s += (char)c;
        else if (c < 0x800) { s += (char)(0xC0 | (c >> 6)); s += (char)(0x80 | (c & 0x3F)); }
        else if (c < 0x10000) { s += (char)(0xE0 | (c >> 12)); s += (char)(0x80 | ((c >> 6) & 0x3F)); s += (char)(0x80 | (c & 0x3F)); }
        else { s += (char)(0xF0 | (c >> 18)); s += (char)(0x80 | ((c >> 12) & 0x3F)); s += (char)(0x80 | ((c >> 6) & 0x3F)); s += (char)(0x80 | (c & 0x3F)); }
    }
    return s;
}

void *lz_alloc(ISzAllocPtr, size_t n) { return std::malloc(n); }
void lz_free(ISzAllocPtr, void *p) { std::free(p); }
const ISzAlloc kLzAlloc = { lz_alloc, lz_free };

void put_vec(void *ctx, const uint8_t *d, size_t n) {
    auto *v = static_cast<std::vector<uint8_t> *>(ctx);
    v->insert(v->end(), d, d + n);
}
}  // namespace

IroArchive::~IroArchive() { if (f_) std::fclose(f_); }

std::string IroArchive::key(const std::string &name) {
    std::string k = name;
    for (char &c : k) c = c == '/' ? '\\' : (char)std::tolower((unsigned char)c);
    return k;
}

bool IroArchive::read_at(uint64_t off, void *buf, size_t n) const {
#if defined(_WIN32)
    if (_fseeki64(f_, (long long)off, SEEK_SET) != 0) return false;
#else
    if (fseeko(f_, (off_t)off, SEEK_SET) != 0) return false;
#endif
    return std::fread(buf, 1, n, f_) == n;
}

bool IroArchive::open(const std::string &path) {
    path_ = path;
    f_ = std::fopen(path.c_str(), "rb");
    if (!f_) { err_ = "cannot open"; return false; }
    uint32_t hdr[4];
    if (!read_at(0, hdr, sizeof hdr) || hdr[0] != kSig) { err_ = "not an IRO archive"; return false; }
    const uint32_t version = hdr[1];
    if (version < kMinVersion || version > kMaxVersion) { err_ = "unsupported IRO version"; return false; }
    uint64_t pos = hdr[3];
    int32_t count = 0;
    for (int hops = 0;; hops++) {                       // a patched archive relocates its directory
        if (hops > 8 || !read_at(pos, &count, 4)) { err_ = "bad directory"; return false; }
        if (count != -1) break;
        uint64_t next;
        if (!read_at(pos + 4, &next, 8)) { err_ = "bad directory link"; return false; }
        pos = next;
    }
    if (count < 0) { err_ = "bad directory count"; return false; }
    pos += 4;
    entries_.reserve((size_t)count);
    std::vector<uint8_t> rec;
    for (int32_t i = 0; i < count; i++) {
        uint16_t sz[2];
        if (!read_at(pos, sz, 4) || sz[0] < 4 + sz[1]) { err_ = "bad directory entry"; return false; }
        rec.resize(sz[0]);
        if (!read_at(pos, rec.data(), sz[0])) { err_ = "truncated directory"; return false; }
        const uint8_t *p = rec.data() + 4;
        Entry e;
        e.name = utf16le_to_utf8(p, sz[1]);
        p += sz[1];
        std::memcpy(&e.flags, p, 4); p += 4;
        if (version < 0x10001u) { int32_t o; std::memcpy(&o, p, 4); e.offset = (uint32_t)o; p += 4; }
        else { std::memcpy(&e.offset, p, 8); p += 8; }
        std::memcpy(&e.length, p, 4);
        pos += sz[0];
        if (e.flags & kRudeFlags) { err_ = "obfuscated entry " + e.name + " (not supported)"; return false; }
        lookup_[key(e.name)] = entries_.size();
        entries_.push_back(std::move(e));
    }
    return true;
}

const IroArchive::Entry *IroArchive::find(const std::string &name) const {
    auto it = lookup_.find(key(name));
    return it == lookup_.end() ? nullptr : &entries_[it->second];
}

int64_t IroArchive::size(const std::string &name) const {
    const Entry *e = find(name);
    if (!e) return -1;
    if ((e->flags & kCompressMask) == kLZMA) {
        int32_t dec;
        return read_at(e->offset, &dec, 4) ? dec : -1;
    }
    if ((e->flags & kCompressMask) == kLZS) {          // size only known by decoding
        std::vector<uint8_t> v;
        return read(name, v) ? (int64_t)v.size() : -1;
    }
    return e->length;
}

bool IroArchive::read(const std::string &name, std::vector<uint8_t> &out) const {
    const Entry *e = find(name);
    out.clear();
    if (!e) return false;
    std::vector<uint8_t> stored(e->length);
    if (e->length && !read_at(e->offset, stored.data(), e->length)) return false;
    switch (e->flags & kCompressMask) {
    case 0:
        out.swap(stored);
        return true;
    case kLZS:
        lzs_decode(stored.data(), stored.size(), put_vec, &out);
        return true;
    case kLZMA: {
        if (stored.size() < 8) return false;
        int32_t dec, props;
        std::memcpy(&dec, stored.data(), 4);
        std::memcpy(&props, stored.data() + 4, 4);
        if (dec < 0 || props < LZMA_PROPS_SIZE || (size_t)props + 8 > stored.size()) return false;
        out.resize((size_t)dec);
        SizeT dst_len = (SizeT)dec, src_len = stored.size() - 8 - (size_t)props;
        ELzmaStatus status;
        SRes r = LzmaDecode(out.data(), &dst_len, stored.data() + 8 + props, &src_len,
                            stored.data() + 8, (unsigned)props, LZMA_FINISH_ANY, &status, &kLzAlloc);
        if (r != SZ_OK || dst_len != (SizeT)dec) { out.clear(); return false; }
        return true;
    }
    default:
        return false;
    }
}

}  // namespace ff7mods
