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

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <game dir>\n", argv[0]);
        return 64;
    }
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    return ff7_boot_and_run(argv[1]);
}
