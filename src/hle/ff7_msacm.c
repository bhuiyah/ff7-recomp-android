/*
 * ff7_msacm.c - the Audio Compression Manager calls FF7 makes: MS-ADPCM -> PCM.
 *
 * FF7's sound effects (data/sound/audio.dat, formats in audio.fmt) are Microsoft ADPCM
 * (WAVE_FORMAT_ADPCM, tag 2). The game opens an ACM stream from that format to 16-bit PCM
 * and converts each effect into a DirectSound buffer (ff7_dsound.c). This is the MS-ADPCM
 * decoder ACM's msadp32.acm driver provides, for that one conversion.
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "guest_alloc.h"
#include "win32hle.h"
#include "ff7_hle.h"

#define MMSYSERR_NOERROR     0u
#define MMSYSERR_INVALPARAM  11u
#define ACMERR_NOTPOSSIBLE   512u
#define ACM_STREAMSIZEF_DESTINATION 0x1u
#define ACMSTREAMHEADER_STATUSF_DONE     0x00010000u
#define ACMSTREAMHEADER_STATUSF_PREPARED 0x00020000u
#define W32(p) (*(uint32_t *)(uintptr_t)(p))
#define W16(p) (*(uint16_t *)(uintptr_t)(p))

typedef struct adpcm_stream {
    uint16_t channels, block_align, samples_per_block, ncoef;
    int16_t coef[32][2];
    int pcm_passthrough;          /* source already 16-bit PCM */
} adpcm_stream;

static const int16_t k_default_coef[7][2] = {
    { 256, 0 }, { 512, -256 }, { 0, 0 }, { 192, 64 }, { 240, 0 }, { 460, -208 }, { 392, -232 } };
static const int16_t k_adapt[16] = { 230, 230, 230, 230, 307, 409, 512, 614, 768, 614, 512, 409, 307, 230, 230, 230 };

static int16_t clamp16(int v) { return (int16_t)(v > 32767 ? 32767 : v < -32768 ? -32768 : v); }

/* Decode one block; returns the number of frames written. `bytes` may be short (last block). */
static uint32_t decode_block(const adpcm_stream *s, const uint8_t *in, uint32_t bytes, int16_t *out) {
    int C = s->channels;
    if (bytes < 7u * (uint32_t)C) return 0;
    int pred[2], delta[2], s1[2], s2[2];
    const uint8_t *p = in;
    for (int c = 0; c < C; c++) pred[c] = *p++ < s->ncoef ? p[-1] : 0;
    for (int c = 0; c < C; c++) { delta[c] = (int16_t)(p[0] | p[1] << 8); p += 2; }
    for (int c = 0; c < C; c++) { s1[c] = (int16_t)(p[0] | p[1] << 8); p += 2; }
    for (int c = 0; c < C; c++) { s2[c] = (int16_t)(p[0] | p[1] << 8); p += 2; }
    uint32_t frames = 2;
    for (int c = 0; c < C; c++) out[c] = (int16_t)s2[c];
    for (int c = 0; c < C; c++) out[C + c] = (int16_t)s1[c];
    uint32_t nibbles = (bytes - 7u * (uint32_t)C) * 2u;
    uint32_t max_frames = s->samples_per_block;
    int16_t *o = out + 2 * C;
    for (uint32_t n = 0; n < nibbles && frames < max_frames; ) {
        for (int c = 0; c < C && n < nibbles; c++, n++) {
            uint8_t byte = p[n >> 1];
            int u = (n & 1) ? (byte & 0x0F) : (byte >> 4);
            int sn = u >= 8 ? u - 16 : u;
            int predict = (s1[c] * s->coef[pred[c]][0] + s2[c] * s->coef[pred[c]][1]) >> 8;
            int16_t v = clamp16(predict + sn * delta[c]);
            s2[c] = s1[c]; s1[c] = v;
            delta[c] = (k_adapt[u] * delta[c]) >> 8;
            if (delta[c] < 16) delta[c] = 16;
            *o++ = v;
        }
        frames++;
    }
    return frames;
}

/* acmStreamOpen(phas, had, pwfxSrc, pwfxDst, pwfltr, dwCallback, dwInstance, fdwOpen) */
static void a_acmStreamOpen(void) {
    uint32_t phas = A32(0), src = A32(2), dst = A32(3);
    if (!src || !dst) RET(MMSYSERR_INVALPARAM, 8);
    uint16_t tag = W16(src), dtag = W16(dst);
    if (dtag != 1 || W16(dst + 14) != 16 || (tag != 2 && tag != 1)) {
        fprintf(stderr, "[msacm] acmStreamOpen: unsupported %u -> %u (%u bit)\n", tag, dtag, W16(dst + 14));
        RET(ACMERR_NOTPOSSIBLE, 8);
    }
    adpcm_stream *s = (adpcm_stream *)guest_calloc(1, sizeof *s);
    s->channels = W16(src + 2);
    s->block_align = W16(src + 12);
    if (s->channels < 1 || s->channels > 2) RET(ACMERR_NOTPOSSIBLE, 8);
    if (tag == 1) {
        s->pcm_passthrough = 1;
    } else {
        uint16_t cb = W16(src + 16);
        s->samples_per_block = cb >= 2 ? W16(src + 18) : 0;
        s->ncoef = cb >= 4 ? W16(src + 20) : 0;
        if (!s->samples_per_block) s->samples_per_block = (uint16_t)((s->block_align - 7 * s->channels) * 2 / s->channels + 2);
        if (s->ncoef == 0 || s->ncoef > 32) {
            s->ncoef = 7;
            memcpy(s->coef, k_default_coef, sizeof k_default_coef);
        } else {
            for (int i = 0; i < s->ncoef; i++) { s->coef[i][0] = (int16_t)W16(src + 22 + 4 * i); s->coef[i][1] = (int16_t)W16(src + 24 + 4 * i); }
        }
    }
    if (phas) W32(phas) = (uint32_t)(uintptr_t)s;
    RET(MMSYSERR_NOERROR, 8);
}

/* acmStreamSize(has, cbInput, pdwOutputBytes, fdwSize) */
static void a_acmStreamSize(void) {
    adpcm_stream *s = (adpcm_stream *)(uintptr_t)A32(0);
    uint32_t in = A32(1), pout = A32(2), fl = A32(3);
    if (!s || !pout) RET(MMSYSERR_INVALPARAM, 4);
    uint32_t frame = 2u * s->channels;
    if (s->pcm_passthrough) { W32(pout) = in; RET(MMSYSERR_NOERROR, 4); }
    if (fl & ACM_STREAMSIZEF_DESTINATION) {
        uint32_t per_block = s->samples_per_block * frame;
        W32(pout) = (in + per_block - 1) / per_block * s->block_align;
    } else {
        uint32_t blocks = (in + s->block_align - 1) / s->block_align;
        W32(pout) = blocks * s->samples_per_block * frame;
    }
    RET(MMSYSERR_NOERROR, 4);
}

/* ACMSTREAMHEADER: cbStruct +0, fdwStatus +4, dwUser +8, pbSrc +12, cbSrcLength +16,
 * cbSrcLengthUsed +20, dwSrcUser +24, pbDst +28, cbDstLength +32, cbDstLengthUsed +36 */
static void a_acmStreamPrepareHeader(void) {
    uint32_t h = A32(1);
    if (!h) RET(MMSYSERR_INVALPARAM, 3);
    W32(h + 4) |= ACMSTREAMHEADER_STATUSF_PREPARED;
    RET(MMSYSERR_NOERROR, 3);
}
static void a_acmStreamUnprepareHeader(void) {
    uint32_t h = A32(1);
    if (!h) RET(MMSYSERR_INVALPARAM, 3);
    W32(h + 4) &= ~ACMSTREAMHEADER_STATUSF_PREPARED;
    RET(MMSYSERR_NOERROR, 3);
}

/* acmStreamConvert(has, pash, fdwConvert) */
static void a_acmStreamConvert(void) {
    adpcm_stream *s = (adpcm_stream *)(uintptr_t)A32(0);
    uint32_t h = A32(1);
    if (!s || !h) RET(MMSYSERR_INVALPARAM, 3);
    const uint8_t *in = (const uint8_t *)(uintptr_t)W32(h + 12);
    uint32_t in_len = W32(h + 16);
    uint8_t *out = (uint8_t *)(uintptr_t)W32(h + 28);
    uint32_t out_len = W32(h + 32);
    uint32_t frame = 2u * s->channels, used_in = 0, used_out = 0;
    if (s->pcm_passthrough) {
        used_in = used_out = in_len < out_len ? in_len : out_len;
        memcpy(out, in, used_out);
    } else {
        int16_t tmp[2 * 4096];
        while (used_in < in_len) {
            uint32_t n = in_len - used_in < s->block_align ? in_len - used_in : s->block_align;
            if (s->samples_per_block > 4096) break;
            uint32_t frames = decode_block(s, in + used_in, n, tmp);
            if (!frames) { used_in += n; continue; }
            uint32_t bytes = frames * frame;
            if (used_out + bytes > out_len) break;
            memcpy(out + used_out, tmp, bytes);
            used_out += bytes;
            used_in += n;
        }
    }
    W32(h + 20) = used_in;
    W32(h + 36) = used_out;
    W32(h + 4) |= ACMSTREAMHEADER_STATUSF_DONE;
    RET(MMSYSERR_NOERROR, 3);
}

/* acmStreamClose(has, fdwClose) */
static void a_acmStreamClose(void) {
    void *s = (void *)(uintptr_t)A32(0);
    if (s) guest_free(s);
    RET(MMSYSERR_NOERROR, 2);
}

const win32hle_shim ff7_msacm[] = {
    { "acmStreamOpen", a_acmStreamOpen },
    { "acmStreamSize", a_acmStreamSize },
    { "acmStreamPrepareHeader", a_acmStreamPrepareHeader },
    { "acmStreamUnprepareHeader", a_acmStreamUnprepareHeader },
    { "acmStreamConvert", a_acmStreamConvert },
    { "acmStreamClose", a_acmStreamClose },
    { NULL, NULL }
};
