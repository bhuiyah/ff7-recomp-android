// mmsystem.h - WINMM types/constants FFNx refers to.
#pragma once
#include <windows.h>
#define MMSYSERR_NOERROR 0
#define MMSYSERR_ERROR 1
#define MMSYSERR_NODRIVER 6
#define TIMERR_NOERROR 0
#define TIME_ONESHOT 0x0000
#define TIME_PERIODIC 0x0001
#define TIME_CALLBACK_FUNCTION 0x0000
#define JOYERR_NOERROR 0
#define JOYERR_UNPLUGGED 167
typedef UINT MCIERROR;
typedef UINT MCIDEVICEID;
#define MCI_OPEN 0x0803
#define MCI_CLOSE 0x0804
#define MCI_PLAY 0x0806
#define MCI_STOP 0x0808
#define MCI_SET 0x080D
#define MCI_STATUS 0x0814
extern "C" MCIERROR mciSendCommandA(MCIDEVICEID id, UINT msg, DWORD_PTR flags, DWORD_PTR param);
#define mciSendCommand mciSendCommandA
#define WAVE_FORMAT_ADPCM 0x0002
typedef void (*LPTIMECALLBACK)(UINT id, UINT msg, DWORD_PTR user, DWORD_PTR dw1, DWORD_PTR dw2);
extern "C" MMRESULT timeSetEvent(UINT delay, UINT resolution, LPTIMECALLBACK cb, DWORD_PTR user, UINT flags);
extern "C" MMRESULT timeKillEvent(UINT id);
extern "C" DWORD timeGetTime(void);
extern "C" MMRESULT timeBeginPeriod(UINT p);
extern "C" MMRESULT timeEndPeriod(UINT p);
