// dinput.h - DirectInput types FFNx refers to (input arrives through SDL3 on Android).
#pragma once
#include <windows.h>
#ifndef DIRECTINPUT_VERSION
#define DIRECTINPUT_VERSION 0x0800
#endif

struct IDirectInputA; struct IDirectInput8A; struct IDirectInputDeviceA; struct IDirectInputDevice8A;
typedef IDirectInputDevice8A *LPDIRECTINPUTDEVICE8A, *LPDIRECTINPUTDEVICE8;
typedef IDirectInput8A *LPDIRECTINPUT8A, *LPDIRECTINPUT8;
struct IDirectInputEffect;
typedef IDirectInputEffect *LPDIRECTINPUTEFFECT;
typedef struct DIDEVCAPS { DWORD dwSize, dwFlags, dwDevType, dwAxes, dwButtons, dwPOVs, dwFFSamplePeriod, dwFFMinTimeResolution, dwFirmwareRevision, dwHardwareRevision, dwFFDriverVersion; } DIDEVCAPS, *LPDIDEVCAPS;
typedef struct DIDEVICEINSTANCEA { DWORD dwSize; GUID guidInstance, guidProduct; DWORD dwDevType; CHAR tszInstanceName[MAX_PATH], tszProductName[MAX_PATH]; GUID guidFFDriver; WORD wUsagePage, wUsage; } DIDEVICEINSTANCEA, DIDEVICEINSTANCE;
typedef const DIDEVICEINSTANCEA *LPCDIDEVICEINSTANCEA, *LPCDIDEVICEINSTANCE;
typedef struct DIDEVICEOBJECTINSTANCEA { DWORD dwSize; GUID guidType; DWORD dwOfs, dwType, dwFlags; CHAR tszName[MAX_PATH]; DWORD dwFFMaxForce, dwFFForceResolution; WORD wCollectionNumber, wDesignatorIndex, wUsagePage, wUsage; DWORD dwDimension; WORD wExponent, wReportId; } DIDEVICEOBJECTINSTANCEA, DIDEVICEOBJECTINSTANCE;
typedef const DIDEVICEOBJECTINSTANCEA *LPCDIDEVICEOBJECTINSTANCEA, *LPCDIDEVICEOBJECTINSTANCE;

typedef struct DIJOYSTATE2 {
    LONG lX, lY, lZ, lRx, lRy, lRz, rglSlider[2];
    DWORD rgdwPOV[4];
    BYTE rgbButtons[128];
    LONG lVX, lVY, lVZ, lVRx, lVRy, lVRz, rglVSlider[2];
    LONG lAX, lAY, lAZ, lARx, lARy, lARz, rglASlider[2];
    LONG lFX, lFY, lFZ, lFRx, lFRy, lFRz, rglFSlider[2];
} DIJOYSTATE2, *LPDIJOYSTATE2;
typedef struct DIJOYSTATE {
    LONG lX, lY, lZ, lRx, lRy, lRz, rglSlider[2];
    DWORD rgdwPOV[4];
    BYTE rgbButtons[32];
} DIJOYSTATE, *LPDIJOYSTATE;
typedef struct DIDEVICEOBJECTDATA { DWORD dwOfs, dwData, dwTimeStamp, dwSequence; UINT_PTR uAppData; } DIDEVICEOBJECTDATA, *LPDIDEVICEOBJECTDATA;
#define DI_OK S_OK

#define DIK_ESCAPE 0x01
#define DIK_RETURN 0x1C
#define DIK_SPACE 0x39
#define DIK_UP 0xC8
#define DIK_DOWN 0xD0
#define DIK_LEFT 0xCB
#define DIK_RIGHT 0xCD
