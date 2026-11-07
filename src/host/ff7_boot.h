/* ff7_boot.h - boot the lifted game; see ff7_boot.c. */
#ifndef FF7_BOOT_H
#define FF7_BOOT_H
#ifdef __cplusplus
extern "C" {
#endif

/* chdir to game_dir, set up guest memory and shims, map ff7_en.exe and run its lifted
 * entry on this thread. Returns the guest exit code or a small host error code. */
int ff7_boot_and_run(const char *game_dir);

/* The display the renderer draws into. The Android app provides it (ANativeWindow);
 * the headless runner has none and gets NULL. */
void *ff7_platform_window(int *width, int *height);

/* The second (companion) screen -- the AYN Thor's bottom display -- or NULL if the device
 * has none, or before its surface exists. */
void *ff7_platform_companion_window(int *width, int *height);

/* The 256-byte DirectInput keyboard state (DIK_* scan codes, 0x80 = down) built from the
 * device's controls. Returns 0 on success, as IDirectInputDevice::GetDeviceState does. */
int ff7_platform_keyboard_state(unsigned char keys[256]);

#ifdef __cplusplus
}
#endif
#endif
