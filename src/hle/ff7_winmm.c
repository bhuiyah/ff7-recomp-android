/*
 * ff7_winmm.c - WINMM imports for ff7_en.exe.
 *
 *   timeGetTime            ms since boot (CLOCK_MONOTONIC), wrapping at 2^32 like Windows
 *   timeBegin/EndPeriod    accepted; the host clock is already 1 ms or better
 *   joy*                   no WINMM joystick (JOYERR_UNPLUGGED): the Thor's controls reach
 *                          the game through DirectInput / FFNx's gamepad layer instead
 *   midiOut* / midiStream* no MIDI device (0 devices, MMSYSERR_NODRIVER): FFNx replaces the
 *                          game's MIDI music with its own player
 *
 *   timeSetEvent/KillEvent multimedia timers (FF7 paces frames with a periodic one). The
 *                          callbacks run on the game thread, at the points where it polls:
 *                          timeGetTime, Sleep and the message pump. Windows runs them on a
 *                          timer thread; FF7's only set flags and counters, and running them
 *                          in-line keeps the single register file uncontended.
 */
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>
#include "win32hle.h"
#include "ff7_hle.h"

#define MMSYSERR_NOERROR  0u
#define MMSYSERR_NODRIVER 6u
#define JOYERR_UNPLUGGED  167u

static uint32_t now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint32_t)((uint64_t)t.tv_sec * 1000u + (uint64_t)t.tv_nsec / 1000000u);
}

/* ---- multimedia timers */
#define TIME_PERIODIC 0x1u
#define MAX_TIMERS 16
typedef struct { int active; uint32_t period, cb, user, flags, due; } mm_timer;
static mm_timer g_timers[MAX_TIMERS];
static int g_in_timer;

void ff7_timers_poll(void) {
    if (g_in_timer) return;                    /* no nesting: a callback that polls */
    uint32_t now = now_ms();
    for (int i = 0; i < MAX_TIMERS; i++) {
        mm_timer *t = &g_timers[i];
        if (!t->active || (int32_t)(now - t->due) < 0) continue;
        if (t->flags & TIME_PERIODIC) {
            t->due += t->period ? t->period : 1;
            if ((int32_t)(now - t->due) > (int32_t)(4 * t->period)) t->due = now + t->period;  /* fell behind: resync */
        } else {
            t->active = 0;
        }
        uint32_t args[5] = { (uint32_t)(i + 1), 0, t->user, 0, 0 };   /* (uTimerID, uMsg, dwUser, dw1, dw2) */
        g_in_timer = 1;
        hle_call_guest(t->cb, 5, args);
        g_in_timer = 0;
    }
}

/* timeSetEvent(uDelay, uResolution, lpTimeProc, dwUser, fuEvent) -> timer id (0 = failure) */
static void m_timeSetEvent(void) {
    uint32_t delay = A32(0), cb = A32(2), user = A32(3), flags = A32(4);
    for (int i = 0; i < MAX_TIMERS; i++) {
        if (g_timers[i].active) continue;
        g_timers[i] = (mm_timer){ 1, delay, cb, user, flags, now_ms() + delay };
        fprintf(stderr, "[winmm] timeSetEvent(%u ms, cb 0x%08X, %s) -> %d\n", delay, cb,
                (flags & TIME_PERIODIC) ? "periodic" : "one-shot", i + 1);
        RET((uint32_t)(i + 1), 5);
    }
    RET(0, 5);
}
static void m_timeKillEvent(void) {
    uint32_t id = A32(0);
    if (id >= 1 && id <= MAX_TIMERS && g_timers[id - 1].active) { g_timers[id - 1].active = 0; RET(MMSYSERR_NOERROR, 1); }
    RET(97u /* TIMERR_NOCANDO */, 1);
}

static void m_timeGetTime(void) {
    uint32_t t = now_ms();
    ff7_timers_poll();
    RET(t, 0);
}

/* Sleep: the game's frame wait. Run due timers first, then sleep. */
static void k_Sleep(void) {
    uint32_t ms = A32(0);
    ff7_timers_poll();
    if (ms) usleep(ms * 1000u); else sched_yield();
    RETV(1);
}
static void m_timeBeginPeriod(void) { RET(MMSYSERR_NOERROR, 1); }
static void m_timeEndPeriod(void)   { RET(MMSYSERR_NOERROR, 1); }

static void m_joyGetDevCapsA(void) { RET(JOYERR_UNPLUGGED, 3); }   /* (id, caps*, size) */
static void m_joyGetPosEx(void)    { RET(JOYERR_UNPLUGGED, 2); }   /* (id, info*) */

static void m_midiOutGetNumDevs(void)    { RET(0, 0); }
static void m_midiOutGetDevCapsA(void)   { RET(MMSYSERR_NODRIVER, 3); }  /* (id, caps*, size) */
static void m_midiOutOpen(void)          { RET(MMSYSERR_NODRIVER, 5); }  /* (h*, id, cb, inst, flags) */
static void m_midiOutClose(void)         { RET(MMSYSERR_NODRIVER, 1); }
static void m_midiOutGetVolume(void)     { RET(MMSYSERR_NODRIVER, 2); }
static void m_midiOutSetVolume(void)     { RET(MMSYSERR_NODRIVER, 2); }
static void m_midiOutLongMsg(void)       { RET(MMSYSERR_NODRIVER, 3); }
static void m_midiOutPrepareHeader(void) { RET(MMSYSERR_NODRIVER, 3); }
static void m_midiOutUnprepareHeader(void){ RET(MMSYSERR_NODRIVER, 3); }
static void m_midiStreamOpen(void)       { RET(MMSYSERR_NODRIVER, 6); }  /* (h*, id*, n, cb, inst, flags) */
static void m_midiStreamClose(void)      { RET(MMSYSERR_NODRIVER, 1); }
static void m_midiStreamOut(void)        { RET(MMSYSERR_NODRIVER, 3); }
static void m_midiStreamPause(void)      { RET(MMSYSERR_NODRIVER, 1); }
static void m_midiStreamRestart(void)    { RET(MMSYSERR_NODRIVER, 1); }
static void m_midiStreamStop(void)       { RET(MMSYSERR_NODRIVER, 1); }
static void m_midiStreamProperty(void)   { RET(MMSYSERR_NODRIVER, 3); }

const win32hle_shim ff7_winmm[] = {
    { "timeGetTime", m_timeGetTime },
    { "timeBeginPeriod", m_timeBeginPeriod },
    { "timeEndPeriod", m_timeEndPeriod },
    { "timeSetEvent", m_timeSetEvent },
    { "timeKillEvent", m_timeKillEvent },
    { "Sleep", k_Sleep },
    { "joyGetDevCapsA", m_joyGetDevCapsA },
    { "joyGetPosEx", m_joyGetPosEx },
    { "midiOutGetNumDevs", m_midiOutGetNumDevs },
    { "midiOutGetDevCapsA", m_midiOutGetDevCapsA },
    { "midiOutOpen", m_midiOutOpen },
    { "midiOutClose", m_midiOutClose },
    { "midiOutGetVolume", m_midiOutGetVolume },
    { "midiOutSetVolume", m_midiOutSetVolume },
    { "midiOutLongMsg", m_midiOutLongMsg },
    { "midiOutPrepareHeader", m_midiOutPrepareHeader },
    { "midiOutUnprepareHeader", m_midiOutUnprepareHeader },
    { "midiStreamOpen", m_midiStreamOpen },
    { "midiStreamClose", m_midiStreamClose },
    { "midiStreamOut", m_midiStreamOut },
    { "midiStreamPause", m_midiStreamPause },
    { "midiStreamRestart", m_midiStreamRestart },
    { "midiStreamStop", m_midiStreamStop },
    { "midiStreamProperty", m_midiStreamProperty },
    { NULL, NULL }
};
