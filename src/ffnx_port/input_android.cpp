// input_android.cpp - FFNx's two non-XInput controller paths, as "no device" on Android.
//
// On the Thor, the built-in pad and any Bluetooth pad arrive as Android KeyEvents/MotionEvents
// in GameActivity and are presented to FFNx (and the game) through XInputGetState
// (src/ffnx_port/include/Xinput.h, fed by src/android/ff7_android_main.cpp). FFNx's other two
// paths are therefore present but empty:
//   SDL3 gamepads  SDL_InitSubSystem fails, so SDLGamepad::init() returns false and FFNx
//                  never opens a gamepad (sdl_gamepad.cpp); everything else is inert.
//   DirectInput    joystick.cpp (DirectInput 8 + WMI enumeration) is not built; Joystick
//                  reports "not connected", so ff7/misc.cpp's DInput branch never runs.
#include <SDL3/SDL.h>
#include <cstring>
#include "joystick.h"

extern "C" {

bool SDL_SetHint(const char *, const char *) { return false; }
bool SDL_InitSubSystem(SDL_InitFlags) { return false; }
void SDL_QuitSubSystem(SDL_InitFlags) {}
int SDL_AddGamepadMappingsFromFile(const char *) { return -1; }
bool SDL_PollEvent(SDL_Event *) { return false; }
SDL_Gamepad *SDL_OpenGamepad(SDL_JoystickID) { return nullptr; }
void SDL_CloseGamepad(SDL_Gamepad *) {}
const char *SDL_GetGamepadName(SDL_Gamepad *) { return nullptr; }
SDL_GamepadType SDL_GetGamepadType(SDL_Gamepad *) { return SDL_GAMEPAD_TYPE_UNKNOWN; }
bool SDL_GetGamepadButton(SDL_Gamepad *, SDL_GamepadButton) { return false; }
SDL_PropertiesID SDL_GetGamepadProperties(SDL_Gamepad *) { return 0; }
bool SDL_GetBooleanProperty(SDL_PropertiesID, const char *, bool default_value) { return default_value; }
bool SDL_RumbleGamepad(SDL_Gamepad *, Uint16, Uint16, Uint32) { return false; }

}  // extern "C"

Joystick joystick;

LPDIJOYSTATE2 Joystick::GetState() { std::memset(&currentState, 0, sizeof currentState); return &currentState; }
LPDIDEVCAPS Joystick::GetCaps() { std::memset(&caps, 0, sizeof caps); return &caps; }
bool Joystick::CheckConnection() { return false; }
bool Joystick::Refresh() { return false; }
bool Joystick::HasAnalogTriggers() { return false; }
bool Joystick::HasForceFeedback() { return false; }
void Joystick::Clean() {}
void Joystick::Vibrate(WORD, WORD) {}
bool Joystick::IsIdle() { return true; }
LONG Joystick::GetDeadZone(float percent) { return LONG(percent * 32767.0f); }
DWORD Joystick::GetMaxVibration() { return 0; }
