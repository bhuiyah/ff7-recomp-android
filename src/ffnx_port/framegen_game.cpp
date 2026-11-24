// framegen_game.cpp - what framegen.cpp asks of the game (it is built with Vulkan's headers, this with FFNx's).
#include "common.h"
#include "globals.h"
#include "ff7.h"
#include "cfg.h"
#include "gamehacks.h"
#include <cstdlib>

bool ff7_framegen_movie_playing() {
    return !ff8 && ff7_externals.movie_object && ff7_externals.movie_object->is_playing;
}

int ff7_framegen_target() {
    if (ff8 || gamehacks.getCurrentSpeedhack() != 1.0) return 0;
    static const bool battle30 = getenv("FF7_BATTLE30") && atoi(getenv("FF7_BATTLE30"));   // GitHub #64
    switch (getmode_cached()->driver_mode) {
    case MODE_BATTLE: return ff7_fps_limiter < FPS_LIMITER_30FPS ? 15 : ff7_fps_limiter == FPS_LIMITER_30FPS || battle30 ? 30 : 60;
    case MODE_FIELD: case MODE_WORLDMAP: return ff7_fps_limiter < FPS_LIMITER_60FPS ? 30 : 60;
    default: return 0;
    }
}
