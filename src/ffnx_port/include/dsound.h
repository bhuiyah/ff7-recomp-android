// dsound.h - DirectSound types FFNx refers to. The lifted game's own DirectSound goes
// through FFNx's sound replacement (SoLoud) once audio is ported; nothing here talks to a
// device. Interfaces appear only behind guest pointers.
#pragma once
#include <windows.h>
#include <mmsystem.h>

struct IDirectSound; struct IDirectSoundBuffer; struct IDirectSound3DBuffer; struct IDirectSound3DListener;
typedef IDirectSound *LPDIRECTSOUND;
typedef IDirectSoundBuffer *LPDIRECTSOUNDBUFFER;
typedef struct _DSBUFFERDESC { DWORD dwSize, dwFlags, dwBufferBytes, dwReserved; LPWAVEFORMATEX lpwfxFormat; GUID guid3DAlgorithm; } DSBUFFERDESC, *LPDSBUFFERDESC;
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
