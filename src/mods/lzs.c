/* lzs.c - FF7's LZS decoder (Okumura LZSS: N = 4096, F = 18, THRESHOLD = 2), the same
 * stream 7th Heaven's AppWrapper/Lzs.cs decodes for IRO entries flagged CompressLZS. */
#include <string.h>
#include "lzs.h"

#define N 4096
#define F 18
#define THRESHOLD 2

size_t lzs_decode(const uint8_t *in, size_t n, lzs_put_fn put, void *ctx) {
    uint8_t ring[N];
    uint8_t out[4096];
    size_t no = 0, total = 0, ip = 0;
    unsigned r = N - F, flags = 0;
    memset(ring, 0, sizeof ring);
#define EMIT(c) do { const uint8_t b_ = (uint8_t)(c); /* evaluate once: callers pass in[ip++] */ \
                     out[no++] = b_; ring[r++] = b_; r &= N - 1; \
                     if (no == sizeof out) { put(ctx, out, no); total += no; no = 0; } } while (0)
    for (;;) {
        if (((flags >>= 1) & 256) == 0) {
            if (ip >= n) break;
            flags = in[ip++] | 0xff00u;          /* the high byte counts eight flags */
        }
        if (flags & 1) {
            if (ip >= n) break;
            EMIT(in[ip++]);
        } else {
            if (ip + 1 >= n) break;
            unsigned i = in[ip], j = in[ip + 1];
            ip += 2;
            i |= (j & 0xf0u) << 4;
            j = (j & 0x0fu) + THRESHOLD;
            for (unsigned k = 0; k <= j; k++) EMIT(ring[(i + k) & (N - 1)]);
        }
    }
#undef EMIT
    if (no) { put(ctx, out, no); total += no; }
    return total;
}
