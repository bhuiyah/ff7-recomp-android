// ffnx_hash.cpp - MD5 and SHA-1 as lowercase hex, replacing FFNx's Crypto++ use on Android
// (utils.cpp: md5_hash for save metadata, sha1_file for the Steam API check). Straight
// implementations of RFC 1321 and FIPS 180-1; no dependencies.
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include "ffnx_hash.h"

namespace {

std::string hex(const uint8_t *d, size_t n) {
    static const char *x = "0123456789abcdef";
    std::string s(n * 2, '0');
    for (size_t i = 0; i < n; i++) { s[2 * i] = x[d[i] >> 4]; s[2 * i + 1] = x[d[i] & 15]; }
    return s;
}

inline uint32_t rol(uint32_t v, int s) { return (v << s) | (v >> (32 - s)); }

struct md5 {
    uint32_t h[4] = { 0x67452301u, 0xefcdab89u, 0x98badcfeu, 0x10325476u };
    uint8_t buf[64]; size_t blen = 0; uint64_t total = 0;
    void block(const uint8_t *p) {
        static const uint32_t K[64] = {
            0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,
            0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
            0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,
            0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
            0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,
            0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
            0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,
            0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391 };
        static const int S[64] = { 7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22, 5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
                                   4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23, 6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21 };
        uint32_t m[16];
        for (int i = 0; i < 16; i++) m[i] = p[4*i] | (p[4*i+1] << 8) | (p[4*i+2] << 16) | ((uint32_t)p[4*i+3] << 24);
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3];
        for (int i = 0; i < 64; i++) {
            uint32_t f; int g;
            if (i < 16) { f = (b & c) | (~b & d); g = i; }
            else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) & 15; }
            else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) & 15; }
            else { f = c ^ (b | ~d); g = (7 * i) & 15; }
            uint32_t t = d; d = c; c = b;
            b = b + rol(a + f + K[i] + m[g], S[i]);
            a = t;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
    }
    void update(const uint8_t *p, size_t n) {
        total += n;
        while (n) { size_t k = 64 - blen < n ? 64 - blen : n; memcpy(buf + blen, p, k); blen += k; p += k; n -= k; if (blen == 64) { block(buf); blen = 0; } }
    }
    std::string finish() {
        uint64_t bits = total * 8; uint8_t pad = 0x80; update(&pad, 1); pad = 0;
        while (blen != 56) update(&pad, 1);
        uint8_t len[8]; for (int i = 0; i < 8; i++) len[i] = (uint8_t)(bits >> (8 * i));
        update(len, 8);
        uint8_t out[16]; for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) out[4*i+j] = (uint8_t)(h[i] >> (8 * j));
        return hex(out, 16);
    }
};

struct sha1 {
    uint32_t h[5] = { 0x67452301u, 0xEFCDAB89u, 0x98BADCFEu, 0x10325476u, 0xC3D2E1F0u };
    uint8_t buf[64]; size_t blen = 0; uint64_t total = 0;
    void block(const uint8_t *p) {
        uint32_t w[80];
        for (int i = 0; i < 16; i++) w[i] = ((uint32_t)p[4*i] << 24) | (p[4*i+1] << 16) | (p[4*i+2] << 8) | p[4*i+3];
        for (int i = 16; i < 80; i++) w[i] = rol(w[i-3] ^ w[i-8] ^ w[i-14] ^ w[i-16], 1);
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4];
        for (int i = 0; i < 80; i++) {
            uint32_t f, k;
            if (i < 20) { f = (b & c) | (~b & d); k = 0x5A827999u; }
            else if (i < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1u; }
            else if (i < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDCu; }
            else { f = b ^ c ^ d; k = 0xCA62C1D6u; }
            uint32_t t = rol(a, 5) + f + e + k + w[i];
            e = d; d = c; c = rol(b, 30); b = a; a = t;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e;
    }
    void update(const uint8_t *p, size_t n) {
        total += n;
        while (n) { size_t k = 64 - blen < n ? 64 - blen : n; memcpy(buf + blen, p, k); blen += k; p += k; n -= k; if (blen == 64) { block(buf); blen = 0; } }
    }
    std::string finish() {
        uint64_t bits = total * 8; uint8_t pad = 0x80; update(&pad, 1); pad = 0;
        while (blen != 56) update(&pad, 1);
        uint8_t len[8]; for (int i = 0; i < 8; i++) len[i] = (uint8_t)(bits >> (56 - 8 * i));
        update(len, 8);
        uint8_t out[20]; for (int i = 0; i < 5; i++) for (int j = 0; j < 4; j++) out[4*i+j] = (uint8_t)(h[i] >> (24 - 8 * j));
        return hex(out, 20);
    }
};

}  // namespace

std::string ffnx_md5_hex(const unsigned char *data, size_t length) { md5 m; m.update(data, length); return m.finish(); }

std::string ffnx_sha1_file_hex(const char *filename) {
    FILE *f = fopen(filename, "rb");
    if (!f) return "";
    sha1 s;
    std::vector<uint8_t> buf(1 << 16);
    size_t n;
    while ((n = std::fread(buf.data(), 1, buf.size(), f)) > 0) s.update(buf.data(), n);
    std::fclose(f);
    return s.finish();
}

// Known-answer check, run once from the FFNx bring-up (RFC 1321 / FIPS 180-1 vectors).
bool ffnx_hash_selftest() {
    return ffnx_md5_hex((const unsigned char *)"abc", 3) == "900150983cd24fb0d6963f7d28e17f72"
        && ffnx_md5_hex((const unsigned char *)"", 0) == "d41d8cd98f00b204e9800998ecf8427e";
}
