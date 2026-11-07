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

#ifdef __cplusplus
}
#endif
#endif
