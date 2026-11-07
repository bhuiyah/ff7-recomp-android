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
 * timeSetEvent / timeKillEvent are deliberately absent until the lift calls them: a timer
 * callback needs a host thread calling into the guest, and FF7 may never use it once FFNx
 * owns music.
 */
#include <stdint.h>
#include <time.h>
#include "win32hle.h"
#include "ff7_hle.h"

#define MMSYSERR_NOERROR  0u
#define MMSYSERR_NODRIVER 6u
#define JOYERR_UNPLUGGED  167u

static void m_timeGetTime(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    RET((uint32_t)((uint64_t)t.tv_sec * 1000u + (uint64_t)t.tv_nsec / 1000000u), 0);
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
