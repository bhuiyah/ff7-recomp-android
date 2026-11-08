// savestate_ffnx.cpp - what the save states (src/host/ff7_savestate.c) need from FFNx: the
// game's mode, whether this moment can be saved, and an on-screen note.
#include <cstdio>
#include "common.h"
#include "globals.h"
#include "log.h"
#include "ff7/defs.h"
#include "ff7_savestate.h"

extern "C" int ff7_ss_driver_mode(void) {
    struct game_mode *m = getmode_cached();
    return m ? (int)m->driver_mode : -1;
}

extern "C" int ff7_ss_can_save(const char **why) {
    if (ff7_externals.movie_object && ff7_externals.movie_object->is_playing) { *why = "a movie is playing"; return 0; }
    switch (ff7_ss_driver_mode()) {
    case MODE_FIELD: case MODE_WORLDMAP: case MODE_BATTLE: case MODE_MENU: case MODE_MAIN_MENU:
        return 1;
    default:
        *why = "the game is between scenes";
        return 0;
    }
}

extern "C" void ff7_ss_notify(const char *msg) {
    ffnx_info("savestate: %s\n", msg);
    show_popup_msg(TEXTCOLOR_LIGHT_BLUE, "%s", msg);
}
