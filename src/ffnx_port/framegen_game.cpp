// framegen_game.cpp - what framegen.cpp asks of the game (it is built with Vulkan's headers, this with FFNx's).
#include "common.h"
#include "globals.h"
#include "ff7.h"

bool ff7_framegen_movie_playing() {
    return !ff8 && ff7_externals.movie_object && ff7_externals.movie_object->is_playing;
}
