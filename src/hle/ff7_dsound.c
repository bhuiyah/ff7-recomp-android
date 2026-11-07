/*
 * ff7_dsound.c - DirectSound for FF7's sound effects, mixed into one AAudio stream.
 *
 * FF7 1.02 plays its SFX (data/sound/audio.dat, MS-ADPCM decoded through MSACM into PCM,
 * see ff7_msacm.c) with DirectSound secondary buffers: one per effect, duplicated per
 * channel, with volume, pan and frequency per buffer. Music and movies do not come here
 * (FFNx plays them through its own engine). FFNx's sfx.cpp patches the game's volume and
 * pan maths but keeps DirectSound as the output for FF7.
 *
 * Buffers keep their PCM in guest memory (Lock hands the game pointers into it). The mixer
 * runs on AAudio's callback thread and reads that memory directly (flat memory); a mutex
 * guards the voice list and per-voice state.
 */
#include <aaudio/AAudio.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "guest_alloc.h"
#include "win32hle.h"
#include "ff7_com.h"
#include "ff7_hle.h"

#define DS_OK              0u
#define DSERR_INVALIDPARAM 0x80070057u
#define DSERR_NODRIVER     0x88780078u
#define E_NOINTERFACE      0x80004002u
#define DSBCAPS_PRIMARYBUFFER 0x1u
#define DSBPLAY_LOOPING    0x1u
#define DSBSTATUS_PLAYING  0x1u
#define DSBSTATUS_LOOPING  0x4u
#define DSBLOCK_FROMWRITECURSOR 0x1u
#define DSBLOCK_ENTIREBUFFER    0x2u
#define W32(p) (*(uint32_t *)(uintptr_t)(p))
#define W16(p) (*(uint16_t *)(uintptr_t)(p))

#define MIX_RATE 48000
#define MAX_VOICES 256

typedef struct voice {
    uint32_t obj;            /* guest COM object (0 = free slot) */
    uint32_t data;           /* guest PCM; shared between a buffer and its duplicates */
    uint32_t bytes;
    uint16_t channels, bits, block;
    uint32_t freq;           /* current playback rate (Hz) */
    int32_t volume, pan;     /* hundredths of a dB, -10000..0 / -10000..10000 */
    float gain_l, gain_r;
    double pos;              /* play position in frames */
    int playing, looping, primary;
} voice;

static voice g_voices[MAX_VOICES];
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream *g_stream;
static int g_trace;

static voice *voice_of(uint32_t obj) { return &g_voices[W32(FF7_COM_STATE(obj))]; }

static float db100_to_gain(int32_t v) { return v <= -10000 ? 0.0f : powf(10.0f, (float)v / 2000.0f); }
static void voice_gains(voice *v) {
    float g = db100_to_gain(v->volume);
    /* DirectSound pan attenuates the opposite side only. */
    v->gain_l = g * (v->pan > 0 ? db100_to_gain(-v->pan) : 1.0f);
    v->gain_r = g * (v->pan < 0 ? db100_to_gain(v->pan) : 1.0f);
}

static inline float sample_at(const voice *v, uint32_t frame, int ch) {
    const uint8_t *p = (const uint8_t *)(uintptr_t)v->data + (size_t)frame * v->block;
    if (v->bits == 16) return (float)((const int16_t *)p)[ch < v->channels ? ch : 0] * (1.0f / 32768.0f);
    return ((float)p[ch < v->channels ? ch : 0] - 128.0f) * (1.0f / 128.0f);
}

static aaudio_data_callback_result_t mix_cb(AAudioStream *s, void *user, void *audio, int32_t frames) {
    (void)s; (void)user;
    float *out = (float *)audio;
    memset(out, 0, sizeof(float) * 2 * (size_t)frames);
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < MAX_VOICES; i++) {
        voice *v = &g_voices[i];
        if (!v->obj || !v->playing || v->primary || !v->data || !v->block) continue;
        uint32_t nframes = v->bytes / v->block;
        if (!nframes) continue;
        double step = (double)v->freq / MIX_RATE;
        for (int32_t f = 0; f < frames; f++) {
            uint32_t fi = (uint32_t)v->pos;
            if (fi >= nframes) {
                if (!v->looping) { v->playing = 0; v->pos = 0; break; }
                v->pos -= nframes; fi = (uint32_t)v->pos;
            }
            out[2 * f]     += sample_at(v, fi, 0) * v->gain_l;
            out[2 * f + 1] += sample_at(v, fi, 1) * v->gain_r;
            v->pos += step;
        }
    }
    pthread_mutex_unlock(&g_lock);
    for (int32_t i = 0; i < 2 * frames; i++) out[i] = out[i] > 1.0f ? 1.0f : out[i] < -1.0f ? -1.0f : out[i];
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static int mixer_start(void) {
    if (g_stream) return 0;
    AAudioStreamBuilder *b;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return -1;
    AAudioStreamBuilder_setSampleRate(b, MIX_RATE);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setDataCallback(b, mix_cb, NULL);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &g_stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { fprintf(stderr, "[dsound] AAudio open failed: %s\n", AAudio_convertResultToText(r)); g_stream = NULL; return -1; }
    AAudioStream_requestStart(g_stream);
    fprintf(stderr, "[dsound] mixer: AAudio %d Hz stereo float\n", AAudioStream_getSampleRate(g_stream));
    return 0;
}

static int voice_alloc(void) {
    for (int i = 1; i < MAX_VOICES; i++) if (!g_voices[i].obj) return i;
    return -1;
}

/* ---- IDirectSoundBuffer */
static uint32_t g_buf_vtable;
static void buffer_vtable(void);

static uint32_t buffer_new(const voice *tmpl) {
    pthread_mutex_lock(&g_lock);
    int i = voice_alloc();
    if (i < 0) { pthread_mutex_unlock(&g_lock); return 0; }
    uint32_t obj = ff7_com_new(g_buf_vtable, 4);
    W32(FF7_COM_STATE(obj)) = (uint32_t)i;
    g_voices[i] = *tmpl;
    g_voices[i].obj = obj;
    g_voices[i].playing = 0;
    g_voices[i].pos = 0;
    voice_gains(&g_voices[i]);
    pthread_mutex_unlock(&g_lock);
    return obj;
}

static void b_QueryInterface(void) { uint32_t ppv = A32(2); if (ppv) W32(ppv) = 0; RET(E_NOINTERFACE, 3); }
static void b_AddRef(void) { uint32_t s = A32(0); RET(++FF7_COM_REFS(s), 1); }
static void b_Release(void) {
    uint32_t s = A32(0);
    uint32_t r = FF7_COM_REFS(s) ? --FF7_COM_REFS(s) : 0;
    if (!r) {
        pthread_mutex_lock(&g_lock);
        voice *v = voice_of(s);
        /* PCM is freed with the last buffer that shares it. */
        int shared = 0;
        for (int i = 1; i < MAX_VOICES; i++) if (&g_voices[i] != v && g_voices[i].obj && g_voices[i].data == v->data) shared = 1;
        if (!shared && v->data) guest_free((void *)(uintptr_t)v->data);
        memset(v, 0, sizeof *v);
        pthread_mutex_unlock(&g_lock);
    }
    RET(r, 1);
}
static void b_GetCaps(void) {              /* (this, DSBCAPS*) */
    uint32_t c = A32(1); voice *v = voice_of(A32(0));
    if (!c) RET(DSERR_INVALIDPARAM, 2);
    W32(c + 4) = v->primary ? DSBCAPS_PRIMARYBUFFER : 0; W32(c + 8) = v->bytes; W32(c + 12) = 0; W32(c + 16) = 0;
    RET(DS_OK, 2);
}
static void b_GetCurrentPosition(void) {   /* (this, pPlay, pWrite) */
    uint32_t pp = A32(1), pw = A32(2); voice *v = voice_of(A32(0));
    pthread_mutex_lock(&g_lock);
    uint32_t play = v->block ? ((uint32_t)v->pos * v->block) % (v->bytes ? v->bytes : 1) : 0;
    uint32_t write = v->bytes ? (play + v->block * (v->freq / 100)) % v->bytes : 0;   /* ~10 ms ahead */
    pthread_mutex_unlock(&g_lock);
    if (pp) W32(pp) = play;
    if (pw) W32(pw) = write;
    RET(DS_OK, 3);
}
static void b_GetFormat(void) {            /* (this, wfx, size, pWritten) */
    uint32_t w = A32(1), sz = A32(2), pn = A32(3); voice *v = voice_of(A32(0));
    if (w && sz >= 16) {
        W16(w) = 1; W16(w + 2) = v->channels; W32(w + 4) = v->freq;
        W32(w + 8) = v->freq * v->block; W16(w + 12) = v->block; W16(w + 14) = v->bits;
        if (sz >= 18) W16(w + 16) = 0;
    }
    if (pn) W32(pn) = 18;
    RET(DS_OK, 4);
}
static void b_GetVolume(void)    { uint32_t p = A32(1); if (p) W32(p) = (uint32_t)voice_of(A32(0))->volume; RET(DS_OK, 2); }
static void b_GetPan(void)       { uint32_t p = A32(1); if (p) W32(p) = (uint32_t)voice_of(A32(0))->pan; RET(DS_OK, 2); }
static void b_GetFrequency(void) { uint32_t p = A32(1); if (p) W32(p) = voice_of(A32(0))->freq; RET(DS_OK, 2); }
static void b_GetStatus(void) {
    uint32_t p = A32(1); voice *v = voice_of(A32(0));
    pthread_mutex_lock(&g_lock);
    uint32_t st = (v->playing ? DSBSTATUS_PLAYING : 0) | (v->playing && v->looping ? DSBSTATUS_LOOPING : 0);
    pthread_mutex_unlock(&g_lock);
    if (p) W32(p) = st;
    RET(DS_OK, 2);
}
static void b_Lock(void) {   /* (this, off, bytes, pp1, pn1, pp2, pn2, flags) */
    uint32_t s = A32(0), off = A32(1), n = A32(2), pp1 = A32(3), pn1 = A32(4), pp2 = A32(5), pn2 = A32(6), fl = A32(7);
    voice *v = voice_of(s);
    if (!v->bytes) RET(DSERR_INVALIDPARAM, 8);
    if (fl & DSBLOCK_ENTIREBUFFER) { off = 0; n = v->bytes; }
    if (fl & DSBLOCK_FROMWRITECURSOR) off = v->block ? ((uint32_t)v->pos * v->block) % v->bytes : 0;
    off %= v->bytes;
    if (n > v->bytes) n = v->bytes;
    uint32_t first = n <= v->bytes - off ? n : v->bytes - off;
    if (pp1) W32(pp1) = v->data + off;
    if (pn1) W32(pn1) = first;
    if (pp2) W32(pp2) = n > first ? v->data : 0;
    if (pn2) W32(pn2) = n - first;
    RET(DS_OK, 8);
}
static void b_Play(void) {   /* (this, r1, r2, flags) */
    voice *v = voice_of(A32(0)); uint32_t fl = A32(3);
    pthread_mutex_lock(&g_lock);
    v->looping = (fl & DSBPLAY_LOOPING) != 0;
    v->playing = 1;
    pthread_mutex_unlock(&g_lock);
    if (g_trace) fprintf(stderr, "[dsound] Play obj 0x%08X %u bytes %u Hz vol %d pan %d%s\n", A32(0), v->bytes, v->freq, v->volume, v->pan, v->looping ? " loop" : "");
    RET(DS_OK, 4);
}
static void b_SetCurrentPosition(void) {
    voice *v = voice_of(A32(0)); uint32_t p = A32(1);
    pthread_mutex_lock(&g_lock);
    v->pos = v->block ? (double)(p / v->block) : 0;
    pthread_mutex_unlock(&g_lock);
    RET(DS_OK, 2);
}
static void b_SetFormat(void) { RET(DS_OK, 2); }           /* primary buffer only; the mixer is fixed */
static void b_SetVolume(void) {
    voice *v = voice_of(A32(0)); int32_t vol = (int32_t)A32(1);
    if (vol > 0) vol = 0;
    if (vol < -10000) vol = -10000;
    pthread_mutex_lock(&g_lock); v->volume = vol; voice_gains(v); pthread_mutex_unlock(&g_lock);
    RET(DS_OK, 2);
}
static void b_SetPan(void) {
    voice *v = voice_of(A32(0)); int32_t pan = (int32_t)A32(1);
    if (pan > 10000) pan = 10000;
    if (pan < -10000) pan = -10000;
    pthread_mutex_lock(&g_lock); v->pan = pan; voice_gains(v); pthread_mutex_unlock(&g_lock);
    RET(DS_OK, 2);
}
static void b_SetFrequency(void) {
    voice *v = voice_of(A32(0)); uint32_t f = A32(1);
    pthread_mutex_lock(&g_lock);
    if (f) v->freq = f; /* 0 = DSBFREQUENCY_ORIGINAL: keep */
    pthread_mutex_unlock(&g_lock);
    RET(DS_OK, 2);
}
static void b_Stop(void) {
    voice *v = voice_of(A32(0));
    pthread_mutex_lock(&g_lock); v->playing = 0; pthread_mutex_unlock(&g_lock);
    RET(DS_OK, 1);
}
static void b_Unlock(void) { RET(DS_OK, 5); }
static void b_Restore(void) { RET(DS_OK, 1); }

static const ff7_com_method buf_methods[] = {
    { "IDirectSoundBuffer::QueryInterface", b_QueryInterface },
    { "IDirectSoundBuffer::AddRef", b_AddRef },
    { "IDirectSoundBuffer::Release", b_Release },
    { "IDirectSoundBuffer::GetCaps", b_GetCaps },
    { "IDirectSoundBuffer::GetCurrentPosition", b_GetCurrentPosition },
    { "IDirectSoundBuffer::GetFormat", b_GetFormat },
    { "IDirectSoundBuffer::GetVolume", b_GetVolume },
    { "IDirectSoundBuffer::GetPan", b_GetPan },
    { "IDirectSoundBuffer::GetFrequency", b_GetFrequency },
    { "IDirectSoundBuffer::GetStatus", b_GetStatus },
    { "IDirectSoundBuffer::Initialize", NULL },
    { "IDirectSoundBuffer::Lock", b_Lock },
    { "IDirectSoundBuffer::Play", b_Play },
    { "IDirectSoundBuffer::SetCurrentPosition", b_SetCurrentPosition },
    { "IDirectSoundBuffer::SetFormat", b_SetFormat },
    { "IDirectSoundBuffer::SetVolume", b_SetVolume },
    { "IDirectSoundBuffer::SetPan", b_SetPan },
    { "IDirectSoundBuffer::SetFrequency", b_SetFrequency },
    { "IDirectSoundBuffer::Stop", b_Stop },
    { "IDirectSoundBuffer::Unlock", b_Unlock },
    { "IDirectSoundBuffer::Restore", b_Restore },
};
static void buffer_vtable(void) {
    if (!g_buf_vtable) g_buf_vtable = ff7_com_vtable(buf_methods, (int)(sizeof buf_methods / sizeof buf_methods[0]));
}

/* ---- IDirectSound */
static void s_QueryInterface(void) { uint32_t ppv = A32(2); if (ppv) W32(ppv) = 0; RET(E_NOINTERFACE, 3); }
static void s_AddRef(void) { uint32_t s = A32(0); RET(++FF7_COM_REFS(s), 1); }
static void s_Release(void) { uint32_t s = A32(0); uint32_t r = FF7_COM_REFS(s) ? --FF7_COM_REFS(s) : 0; RET(r, 1); }
static void s_CreateSoundBuffer(void) {   /* (this, DSBUFFERDESC*, ppBuf, outer) */
    uint32_t d = A32(1), out = A32(2);
    if (!d || !out) RET(DSERR_INVALIDPARAM, 4);
    buffer_vtable();
    voice t; memset(&t, 0, sizeof t);
    uint32_t flags = W32(d + 4), bytes = W32(d + 8), wfx = W32(d + 16);
    t.primary = (flags & DSBCAPS_PRIMARYBUFFER) != 0;
    if (wfx) {
        t.channels = W16(wfx + 2); t.freq = W32(wfx + 4); t.block = W16(wfx + 12); t.bits = W16(wfx + 14);
    } else {
        t.channels = 2; t.freq = 22050; t.bits = 16; t.block = 4;
    }
    if (!t.primary) {
        t.bytes = bytes;
        void *pcm = guest_calloc(1, bytes ? bytes : 1);
        if (!pcm) RET(DSERR_INVALIDPARAM, 4);
        t.data = (uint32_t)(uintptr_t)pcm;
    }
    uint32_t obj = buffer_new(&t);
    if (!obj) RET(DSERR_INVALIDPARAM, 4);
    W32(out) = obj;
    if (g_trace) fprintf(stderr, "[dsound] CreateSoundBuffer flags 0x%X %u bytes %u ch %u Hz %u bit -> 0x%08X\n",
                         flags, bytes, t.channels, t.freq, t.bits, obj);
    RET(DS_OK, 4);
}
static void s_GetCaps(void) {             /* (this, DSCAPS*) */
    uint32_t c = A32(1);
    if (!c) RET(DSERR_INVALIDPARAM, 2);
    uint32_t size = W32(c);
    memset((void *)(uintptr_t)(c + 4), 0, size > 4 ? size - 4 : 0);
    /* PRIMARYMONO|STEREO|8BIT|16BIT, CONTINUOUSRATE, SECONDARYMONO|STEREO|8BIT|16BIT */
    W32(c + 4) = 0x1u | 0x2u | 0x4u | 0x8u | 0x10u | 0x100u | 0x200u | 0x400u | 0x800u;
    W32(c + 8) = 100; W32(c + 12) = 200000;
    RET(DS_OK, 2);
}
static void s_DuplicateSoundBuffer(void) {   /* (this, orig, ppDup) */
    uint32_t orig = A32(1), out = A32(2);
    if (!orig || !out) RET(DSERR_INVALIDPARAM, 3);
    voice t = *voice_of(orig);
    uint32_t obj = buffer_new(&t);
    if (!obj) RET(DSERR_INVALIDPARAM, 3);
    W32(out) = obj;
    RET(DS_OK, 3);
}
static void s_SetCooperativeLevel(void) { RET(DS_OK, 3); }
static void s_Compact(void) { RET(DS_OK, 1); }
static void s_GetSpeakerConfig(void) { uint32_t p = A32(1); if (p) W32(p) = 4; /* DSSPEAKER_STEREO */ RET(DS_OK, 2); }
static void s_SetSpeakerConfig(void) { RET(DS_OK, 2); }

static const ff7_com_method ds_methods[] = {
    { "IDirectSound::QueryInterface", s_QueryInterface },
    { "IDirectSound::AddRef", s_AddRef },
    { "IDirectSound::Release", s_Release },
    { "IDirectSound::CreateSoundBuffer", s_CreateSoundBuffer },
    { "IDirectSound::GetCaps", s_GetCaps },
    { "IDirectSound::DuplicateSoundBuffer", s_DuplicateSoundBuffer },
    { "IDirectSound::SetCooperativeLevel", s_SetCooperativeLevel },
    { "IDirectSound::Compact", s_Compact },
    { "IDirectSound::GetSpeakerConfig", s_GetSpeakerConfig },
    { "IDirectSound::SetSpeakerConfig", s_SetSpeakerConfig },
    { "IDirectSound::Initialize", NULL },
};

/* ---- exports */
/* DirectSoundEnumerateA(cb, ctx): cb(GUID*, desc, module, ctx) once, for the default device. */
static void d_DirectSoundEnumerateA(void) {
    uint32_t cb = A32(0), ctx = A32(1);
    static uint32_t desc, mod;
    if (!desc) { desc = ff7_guest_strdup("Primary Sound Driver"); mod = ff7_guest_strdup(""); }
    if (cb) { uint32_t args[4] = { 0, desc, mod, ctx }; hle_call_guest(cb, 4, args); }
    RET(DS_OK, 2);
}

/* DirectSoundCreate(guid, ppDS, outer) */
static void d_DirectSoundCreate(void) {
    uint32_t out = A32(1);
    static uint32_t vtable;
    if (!out) RET(DSERR_INVALIDPARAM, 3);
    g_trace = getenv("FF7_TRACE_DSOUND") != NULL;
    if (mixer_start() != 0) { W32(out) = 0; RET(DSERR_NODRIVER, 3); }
    if (!vtable) vtable = ff7_com_vtable(ds_methods, (int)(sizeof ds_methods / sizeof ds_methods[0]));
    W32(out) = ff7_com_new(vtable, 4);
    fprintf(stderr, "[dsound] DirectSoundCreate -> 0x%08X\n", W32(out));
    RET(DS_OK, 3);
}

const win32hle_shim ff7_dsound[] = {
    { "DirectSoundEnumerateA", d_DirectSoundEnumerateA },
    { "DirectSoundCreate", d_DirectSoundCreate },
    { NULL, NULL }
};
