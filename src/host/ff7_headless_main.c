/*
 * ff7_headless_main.c - the bring-up runner: boots the lifted game with no display.
 * The run ends at the first gap (a named STOP); that gap is the next thing to implement.
 *
 * usage: ff7_headless <game dir containing ff7_en.exe and data/>
 */
#include <stdio.h>
#include "ff7_boot.h"

void *ff7_platform_window(int *width, int *height) {
    if (width) *width = 0;
    if (height) *height = 0;
    return NULL;
}

void *ff7_platform_frame_window(void) { return NULL; }

void *ff7_platform_companion_window(int *width, int *height) {
    if (width) *width = 0;
    if (height) *height = 0;
    return NULL;
}

int ff7_platform_keyboard_state(unsigned char keys[256]) {
    for (int i = 0; i < 256; i++) keys[i] = 0;
    return 0;
}

int ff7_platform_pad_state(unsigned index, ff7_pad_state *out) { (void)index; (void)out; return 0; }
void ff7_platform_pad_rumble(unsigned index, unsigned short l, unsigned short r) { (void)index; (void)l; (void)r; }
void ff7_platform_pad_inject(unsigned short buttons) { (void)buttons; }
int ff7_platform_pad_buttons(void) { return 0; }

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <game dir>\n", argv[0]);
        return 64;
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    return ff7_boot_and_run(argv[1]);
}
