/* lzs.h - FF7's LZS (Okumura LZSS, 4 KB window), as 7th Heaven's IRO archives store it. */
#ifndef FF7_MODS_LZS_H
#define FF7_MODS_LZS_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Decode `n` bytes of LZS from `in`. Output goes through `put` (one byte at a time is too
 * slow for big files, so the callback gets runs). Returns the number of bytes produced. */
typedef void (*lzs_put_fn)(void *ctx, const uint8_t *data, size_t n);
size_t lzs_decode(const uint8_t *in, size_t n, lzs_put_fn put, void *ctx);

#ifdef __cplusplus
}
#endif
#endif
