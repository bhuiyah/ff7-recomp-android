// Xinput.h - XInput for FFNx's gamepad.cpp. On Android the device's controls are pad 0
// (ff7_platform_pad_state); FFNx's SDL3 path reports no gamepads (input_android.cpp).
#pragma once
#include <windows.h>

typedef struct _XINPUT_GAMEPAD {
    WORD wButtons; BYTE bLeftTrigger, bRightTrigger; SHORT sThumbLX, sThumbLY, sThumbRX, sThumbRY;
} XINPUT_GAMEPAD, *PXINPUT_GAMEPAD;
typedef struct _XINPUT_STATE { DWORD dwPacketNumber; XINPUT_GAMEPAD Gamepad; } XINPUT_STATE, *PXINPUT_STATE;
typedef struct _XINPUT_VIBRATION { WORD wLeftMotorSpeed, wRightMotorSpeed; } XINPUT_VIBRATION, *PXINPUT_VIBRATION;

#define XUSER_MAX_COUNT 4
#define XINPUT_GAMEPAD_DPAD_UP 0x0001
#define XINPUT_GAMEPAD_DPAD_DOWN 0x0002
#define XINPUT_GAMEPAD_DPAD_LEFT 0x0004
#define XINPUT_GAMEPAD_DPAD_RIGHT 0x0008
#define XINPUT_GAMEPAD_START 0x0010
#define XINPUT_GAMEPAD_BACK 0x0020
#define XINPUT_GAMEPAD_LEFT_THUMB 0x0040
#define XINPUT_GAMEPAD_RIGHT_THUMB 0x0080
#define XINPUT_GAMEPAD_LEFT_SHOULDER 0x0100
#define XINPUT_GAMEPAD_RIGHT_SHOULDER 0x0200
#define XINPUT_GAMEPAD_A 0x1000
#define XINPUT_GAMEPAD_B 0x2000
#define XINPUT_GAMEPAD_X 0x4000
#define XINPUT_GAMEPAD_Y 0x8000
#define XINPUT_GAMEPAD_LEFT_THUMB_DEADZONE 7849
#define XINPUT_GAMEPAD_RIGHT_THUMB_DEADZONE 8689
#define XINPUT_GAMEPAD_TRIGGER_THRESHOLD 30
#define ERROR_DEVICE_NOT_CONNECTED 1167L
#define ZeroMemory(p, n) std::memset((p), 0, (n))

// The device's controls (src/android/ff7_android_main.cpp: Android key/motion events).
#include "ff7_boot.h"
static_assert(sizeof(ff7_pad_state) == sizeof(XINPUT_GAMEPAD), "ff7_pad_state is XINPUT_GAMEPAD");

inline DWORD XInputGetState(DWORD i, XINPUT_STATE *s) {
    static DWORD packet;
    if (!ff7_platform_pad_state(i, reinterpret_cast<ff7_pad_state *>(&s->Gamepad))) return ERROR_DEVICE_NOT_CONNECTED;
    s->dwPacketNumber = ++packet;
    return ERROR_SUCCESS;
}
inline DWORD XInputSetState(DWORD i, XINPUT_VIBRATION *v) {
    ff7_pad_state probe;
    if (!ff7_platform_pad_state(i, &probe)) return ERROR_DEVICE_NOT_CONNECTED;
    ff7_platform_pad_rumble(i, v->wLeftMotorSpeed, v->wRightMotorSpeed);
    return ERROR_SUCCESS;
}
