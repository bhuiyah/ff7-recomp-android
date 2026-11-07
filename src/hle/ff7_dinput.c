/*
 * ff7_dinput.c - the DirectInput FF7 sets up at start (DirectInputCreateA, keyboard device).
 *
 * FFNx takes FF7's input over: the keyboard state comes from FFNx's get_keyboard_state
 * replacement (ff7_platform_keyboard_state on Android), gamepads from its get_gamepad /
 * update_gamepad_status replacements (XInput -> the device's controls) and the mouse
 * device creation is a no-op. What reaches DirectInput is the game's own init: create the
 * interface, create the keyboard, set its format and cooperative level, acquire it. Those
 * work; joysticks enumerate as none (FFNx supplies the pad). A device's GetDeviceState
 * answers from ff7_platform_keyboard_state as well, for any read that bypasses FFNx.
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "guest_alloc.h"
#include "win32hle.h"
#include "ff7_com.h"
#include "ff7_hle.h"
#include "ff7_boot.h"

#define DI_OK            0u
#define DI_NOEFFECT      1u
#define DIERR_INVALIDPARAM 0x80070057u
#define E_NOINTERFACE    0x80004002u
#define W32(p) (*(uint32_t *)(uintptr_t)(p))

/* ---- IDirectInputDeviceA / IDirectInputDevice2A (same object) */
static void dev_QueryInterface(void) {   /* (this, riid, ppv) */
    uint32_t self = A32(0), riid = A32(1), ppv = A32(2);
    if (!ppv) RET(DIERR_INVALIDPARAM, 3);
    uint32_t d1 = riid ? W32(riid) : 0;
    /* IID_IDirectInputDeviceA {5944E680-...}, IID_IDirectInputDevice2A {5944E682-...} */
    if (d1 == 0x5944E680u || d1 == 0x5944E682u) { W32(ppv) = self; FF7_COM_REFS(self)++; RET(DI_OK, 3); }
    W32(ppv) = 0;
    RET(E_NOINTERFACE, 3);
}
static void dev_AddRef(void)  { uint32_t s = A32(0); RET(++FF7_COM_REFS(s), 1); }
static void dev_Release(void) { uint32_t s = A32(0); uint32_t r = FF7_COM_REFS(s) ? --FF7_COM_REFS(s) : 0; RET(r, 1); }
static void dev_GetCapabilities(void) {  /* (this, DIDEVCAPS*) */
    uint32_t c = A32(1);
    if (c) { uint32_t n = W32(c); memset((void *)(uintptr_t)(c + 4), 0, n > 4 ? n - 4 : 0); W32(c + 4) = 1; /* DIDC_ATTACHED */ W32(c + 8) = 0x13; /* keyboard */ W32(c + 16) = 128; }
    RET(DI_OK, 2);
}
static void dev_ok2(void) { RET(DI_OK, 2); }
static void dev_ok3(void) { RET(DI_OK, 3); }
static void dev_Acquire(void) { RET(DI_OK, 1); }
static void dev_Unacquire(void) { RET(DI_OK, 1); }
static void dev_Poll(void) { RET(DI_NOEFFECT, 1); }
static void dev_GetDeviceState(void) {   /* (this, cb, data) */
    uint32_t cb = A32(1), data = A32(2);
    if (!data) RET(DIERR_INVALIDPARAM, 3);
    unsigned char keys[256];
    ff7_platform_keyboard_state(keys);
    memcpy((void *)(uintptr_t)data, keys, cb < 256 ? cb : 256);
    if (cb > 256) memset((void *)(uintptr_t)(data + 256), 0, cb - 256);
    RET(DI_OK, 3);
}
static void dev_GetDeviceData(void) {    /* (this, cbObjectData, rgdod, pdwInOut, flags): no buffered events */
    uint32_t inout = A32(3);
    if (inout) W32(inout) = 0;
    RET(DI_OK, 5);
}
static void dev_SetEventNotification(void) { RET(DI_OK, 2); }

static const ff7_com_method dev_methods[] = {
    { "IDirectInputDevice::QueryInterface", dev_QueryInterface },
    { "IDirectInputDevice::AddRef", dev_AddRef },
    { "IDirectInputDevice::Release", dev_Release },
    { "IDirectInputDevice::GetCapabilities", dev_GetCapabilities },
    { "IDirectInputDevice::EnumObjects", NULL },
    { "IDirectInputDevice::GetProperty", NULL },
    { "IDirectInputDevice::SetProperty", dev_ok3 },
    { "IDirectInputDevice::Acquire", dev_Acquire },
    { "IDirectInputDevice::Unacquire", dev_Unacquire },
    { "IDirectInputDevice::GetDeviceState", dev_GetDeviceState },
    { "IDirectInputDevice::GetDeviceData", dev_GetDeviceData },
    { "IDirectInputDevice::SetDataFormat", dev_ok2 },
    { "IDirectInputDevice::SetEventNotification", dev_SetEventNotification },
    { "IDirectInputDevice::SetCooperativeLevel", dev_ok3 },
    { "IDirectInputDevice::GetObjectInfo", NULL },
    { "IDirectInputDevice::GetDeviceInfo", NULL },
    { "IDirectInputDevice::RunControlPanel", NULL },
    { "IDirectInputDevice::Initialize", NULL },
    { "IDirectInputDevice2::CreateEffect", NULL },
    { "IDirectInputDevice2::EnumEffects", NULL },
    { "IDirectInputDevice2::GetEffectInfo", NULL },
    { "IDirectInputDevice2::GetForceFeedbackState", NULL },
    { "IDirectInputDevice2::SendForceFeedbackCommand", NULL },
    { "IDirectInputDevice2::EnumCreatedEffectObjects", NULL },
    { "IDirectInputDevice2::Escape", NULL },
    { "IDirectInputDevice2::Poll", dev_Poll },
    { "IDirectInputDevice2::SendDeviceData", NULL },
};

/* ---- IDirectInputA */
static void di_QueryInterface(void) { uint32_t ppv = A32(2); if (ppv) W32(ppv) = 0; RET(E_NOINTERFACE, 3); }
static void di_AddRef(void)  { uint32_t s = A32(0); RET(++FF7_COM_REFS(s), 1); }
static void di_Release(void) { uint32_t s = A32(0); uint32_t r = FF7_COM_REFS(s) ? --FF7_COM_REFS(s) : 0; RET(r, 1); }
static void di_CreateDevice(void) {      /* (this, rguid, ppDev, outer) */
    uint32_t guid = A32(1), out = A32(2);
    static uint32_t vtable;
    if (!out) RET(DIERR_INVALIDPARAM, 4);
    if (!vtable) vtable = ff7_com_vtable(dev_methods, (int)(sizeof dev_methods / sizeof dev_methods[0]));
    W32(out) = ff7_com_new(vtable, 4);
    fprintf(stderr, "[dinput] CreateDevice(%08X-...) -> 0x%08X\n", guid ? W32(guid) : 0, W32(out));
    RET(DI_OK, 4);
}
static void di_EnumDevices(void) {       /* (this, type, cb, ref, flags): no joysticks (FFNx supplies the pad) */
    RET(DI_OK, 5);
}
static void di_GetDeviceStatus(void) { RET(DI_OK, 2); }

static const ff7_com_method di_methods[] = {
    { "IDirectInput::QueryInterface", di_QueryInterface },
    { "IDirectInput::AddRef", di_AddRef },
    { "IDirectInput::Release", di_Release },
    { "IDirectInput::CreateDevice", di_CreateDevice },
    { "IDirectInput::EnumDevices", di_EnumDevices },
    { "IDirectInput::GetDeviceStatus", di_GetDeviceStatus },
    { "IDirectInput::RunControlPanel", NULL },
    { "IDirectInput::Initialize", NULL },
};

/* DirectInputCreateA(hinst, version, ppDI, outer) */
static void d_DirectInputCreateA(void) {
    uint32_t out = A32(2);
    static uint32_t vtable;
    if (!out) RET(DIERR_INVALIDPARAM, 4);
    if (!vtable) vtable = ff7_com_vtable(di_methods, (int)(sizeof di_methods / sizeof di_methods[0]));
    W32(out) = ff7_com_new(vtable, 4);
    fprintf(stderr, "[dinput] DirectInputCreateA(version 0x%X) -> 0x%08X\n", A32(1), W32(out));
    RET(DI_OK, 4);
}

const win32hle_shim ff7_dinput[] = {
    { "DirectInputCreateA", d_DirectInputCreateA },
    { NULL, NULL }
};
