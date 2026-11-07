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
extern "C" DWORD timeGetTime(void);
extern "C" MMRESULT timeBeginPeriod(UINT p);
extern "C" MMRESULT timeEndPeriod(UINT p);
