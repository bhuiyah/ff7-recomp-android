// quickboot.cpp - straight from launch to the game: skips the credits, picks New Game (or
// Continue and the first save) on the title screen, and skips the opening FMV, by driving the
// game's own flow -- the presses a player would make, timed off the game's mode instead of
// fixed delays. Off unless asked for:
//   files/quickboot.txt  (the launcher's Start setting)  "newgame" or "continue"
//   FF7_QUICKBOOT=newgame|continue                       (env.txt, tests)
// Ticked once per frame from ffnx_recomp::step_code (ff7_limit_fps). Stops for good once the
// field is reached, after 40 s, or at the first real press of a button.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <dirent.h>
#include <strings.h>
#include "common.h"
#include "globals.h"
#include "log.h"
#include "ff7/defs.h"

extern "C" void ff7_platform_pad_inject(unsigned short buttons);   // src/android (the pad the game reads)
extern "C" int ff7_platform_pad_buttons(void);                    // the player's own buttons

namespace {

constexpr unsigned short PAD_UP = 0x0001, PAD_START = 0x0010, PAD_OK = 0x2000;   // XInput (OK = Circle = B)
constexpr uint32_t TITLE_CURSOR = 0x00DD6F24u;   // title menu: 0 New Game, 1 Continue (found with FF7_MEMDIFF)

int g_target = -2;            // -2 not configured yet, -1 off, 0 new game, 1 continue
bool g_done;
uint32_t g_mode = ~0u;
int64_t g_mode_since, g_started, g_last_press, g_last_skip;
int g_title_presses;

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Any save file (save/saveNN.ff7 in the game folder, the process's working directory)?
bool have_saves() {
    DIR *d = opendir("save");
    if (!d) return false;
    bool any = false;
    while (dirent *e = readdir(d)) {
        size_t n = std::strlen(e->d_name);
        if (n > 4 && !strncasecmp(e->d_name, "save", 4) && !strcasecmp(e->d_name + n - 4, ".ff7")) { any = true; break; }
    }
    closedir(d);
    return any;
}

int read_target() {
    const char *e = std::getenv("FF7_QUICKBOOT");
    char buf[32] = "";
    if (e) std::snprintf(buf, sizeof buf, "%s", e);
    else {
        // files/quickboot.txt, beside the game folder the process runs in; a host path, so
        // around FFNx's fopen (which maps game paths)
        char cwd[1024], path[1100];
        if (getcwd(cwd, sizeof cwd)) {
            std::snprintf(path, sizeof path, "%s/../quickboot.txt", cwd);
            if (std::FILE *f = (std::fopen)(path, "rb")) {
                size_t n = std::fread(buf, 1, sizeof buf - 1, f);
                buf[n] = 0;
                std::fclose(f);
            }
        }
    }
    if (!std::strncmp(buf, "newgame", 7)) return 0;
    if (!std::strncmp(buf, "continue", 8)) {
        if (have_saves()) return 1;
        ffnx_info("quickboot: no save files yet, starting a new game instead\n");
        return 0;
    }
    return -1;
}

void finish(const char *why) {
    g_done = true;
    ff7_platform_pad_inject(0);
    ffnx_info("quickboot: %s after %.1f s\n", why, (now_ms() - g_started) / 1000.0);
}

// One press: held ~80 ms, then released, at most every `gap` ms.
void press(unsigned short b, int64_t t, int gap) {
    if (t - g_last_press >= gap) { ff7_platform_pad_inject(b); g_last_press = t; }
    else if (t - g_last_press >= 80) ff7_platform_pad_inject(0);
}

}  // namespace

namespace ffnx_recomp {

void quickboot_frame() {
    if (g_target == -2) {
        g_target = read_target();
        g_started = now_ms();
        if (g_target >= 0) ffnx_info("quickboot: %s\n", g_target ? "continue" : "new game");
    }
    if (g_target < 0 || g_done) return;
    const int64_t t = now_ms();
    if (t - g_started > 40000) { finish("gave up (40 s)"); return; }
    if (ff7_platform_pad_buttons()) { finish("stopped by a button press"); return; }

    struct game_mode *m = getmode_cached();
    const uint32_t mode = m ? m->driver_mode : ~0u;
    if (mode != g_mode) { g_mode = mode; g_mode_since = t; ff7_platform_pad_inject(0); }
    const int64_t in_mode = t - g_mode_since;

    switch (mode) {
    case MODE_CREDITS:
        if (in_mode > 300) press(PAD_START, t, 250);
        break;
    case MODE_MAIN_MENU: {
        if (in_mode < 800) break;                            // the menu fading in
        const uint8_t cursor = *(volatile uint8_t *)(uintptr_t)TITLE_CURSOR;
        if (g_title_presses == 0) {
            if (cursor != g_target) press(PAD_UP, t, 300);
            else if (t - g_last_press >= 300) { press(PAD_OK, t, 0); g_title_presses = 1; }
            else ff7_platform_pad_inject(0);
        } else if (g_target == 1) {
            press(PAD_OK, t, 600);                           // Continue: the save file, then its first slot
        } else {
            ff7_platform_pad_inject(0);
        }
        break;
    }
    case MODE_FIELD:
        ff7_platform_pad_inject(0);
        if (ff7_externals.movie_object && ff7_externals.movie_object->is_playing) {
            if (t - g_last_skip > 500) { ff7_skip_movies(); g_last_skip = t; }
        } else if ((g_last_skip && t - g_last_skip > 1500) || in_mode > 6000) {
            finish("in the field");                          // the FMV starts a moment into the field
        }
        break;
    default:
        ff7_platform_pad_inject(0);
        break;
    }
}

}  // namespace ffnx_recomp
