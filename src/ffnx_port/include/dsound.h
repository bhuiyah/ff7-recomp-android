// dsound.h - DirectSound types FFNx refers to. The lifted game's own DirectSound goes
// through FFNx's sound replacement (SoLoud) once audio is ported; nothing here talks to a
// device. Interfaces appear only behind guest pointers.
#pragma once
#include <windows.h>
#include <mmsystem.h>
#include "ffnx_guest.h"

struct IDirectSound; struct IDirectSound3DBuffer; struct IDirectSound3DListener;

// The game's sound buffers are guest COM objects (from the DirectSound the game sees).
// FFNx calls methods on them; each call goes through the object's guest vtable
// (stdcall, `this` first), in IDirectSoundBuffer's slot order.
struct IDirectSoundBuffer {
private:
    uint32_t self() const { return guest::va_of(this); }
    uint32_t slot(int i) const { return *reinterpret_cast<const uint32_t *>((uintptr_t)(*reinterpret_cast<const uint32_t *>(this) + 4u * (uint32_t)i)); }
    template <typename... A> HRESULT call(int i, A... a) const { return guest::gcall<HRESULT>(slot(i), self(), a...); }
public:
    ULONG AddRef() { return (ULONG)call(1); }
    ULONG Release() { return (ULONG)call(2); }
    HRESULT GetCurrentPosition(LPDWORD play, LPDWORD write) { return call(4, play, write); }
    HRESULT GetVolume(LPLONG v) { return call(6, v); }
    HRESULT GetPan(LPLONG p) { return call(7, p); }
    HRESULT GetFrequency(LPDWORD f) { return call(8, f); }
    HRESULT GetStatus(LPDWORD s) { return call(9, s); }
    HRESULT Lock(DWORD off, DWORD n, LPVOID *p1, LPDWORD n1, LPVOID *p2, LPDWORD n2, DWORD flags) {
        // The game hands back guest pointers in 4-byte cells.
        uint32_t *c1 = static_cast<uint32_t *>(guest_calloc(4, sizeof(uint32_t)));
        HRESULT hr = call(11, off, n, c1, n1, p2 ? c1 + 1 : (uint32_t *)nullptr, n2, flags);
        if (p1) *p1 = (LPVOID)(uintptr_t)c1[0];
        if (p2) *p2 = (LPVOID)(uintptr_t)c1[1];
        guest_free(c1);
        return hr;
    }
    HRESULT Play(DWORD r1, DWORD prio, DWORD flags) { return call(12, r1, prio, flags); }
    HRESULT SetCurrentPosition(DWORD pos) { return call(13, pos); }
    HRESULT SetVolume(LONG v) { return call(15, v); }
    HRESULT SetPan(LONG p) { return call(16, p); }
    HRESULT SetFrequency(DWORD f) { return call(17, f); }
    HRESULT Stop() { return call(18); }
    HRESULT Unlock(LPVOID p1, DWORD n1, LPVOID p2, DWORD n2) { return call(19, p1, n1, p2, n2); }
    HRESULT Restore() { return call(20); }
};
#define DSERR_BUFFERLOST ((HRESULT)0x88780096)
#define DSBSTATUS_BUFFERLOST 0x2u
typedef IDirectSound *LPDIRECTSOUND;
typedef IDirectSoundBuffer *LPDIRECTSOUNDBUFFER;
// The game's IDirectSound* lives in a 4-byte guest cell: a pointer to that cell is a
// pointer to a guest pointer (tools/ffnx_port.py converts members declared with these).
typedef gptr<IDirectSound> *LPLPDIRECTSOUND;
typedef gptr<IDirectSoundBuffer> *LPLPDIRECTSOUNDBUFFER;
typedef struct _DSBUFFERDESC { DWORD dwSize, dwFlags, dwBufferBytes, dwReserved; LPWAVEFORMATEX lpwfxFormat; GUID guid3DAlgorithm; } DSBUFFERDESC, *LPDSBUFFERDESC;
typedef struct _DSBUFFERDESC1 { DWORD dwSize, dwFlags, dwBufferBytes, dwReserved; LPWAVEFORMATEX lpwfxFormat; } DSBUFFERDESC1, *LPDSBUFFERDESC1;   // DirectX 5
#define DS_OK S_OK
#define DSBCAPS_CTRLVOLUME 0x80u
#define DSBCAPS_CTRLPAN 0x40u
#define DSBCAPS_CTRLFREQUENCY 0x20u
#define DSBCAPS_STATIC 0x2u
#define DSBCAPS_LOCSOFTWARE 0x8u
#define DSBCAPS_GLOBALFOCUS 0x8000u
#define DSBCAPS_PRIMARYBUFFER 0x1u
#define DSBPLAY_LOOPING 0x1u
#define DSBSTATUS_PLAYING 0x1u
#define DSBSTATUS_LOOPING 0x4u
#define DSBVOLUME_MIN (-10000)
#define DSBVOLUME_MAX 0
#define DSBPAN_LEFT (-10000)
#define DSBPAN_RIGHT 10000
#define DSBPAN_CENTER 0
