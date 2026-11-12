// ff7_android_main.cpp - JNI glue for com.ff7recomp.GameActivity.
//
// The activity hands over its Surface (as an ANativeWindow, for bgfx/Vulkan) and the game
// folder; the lifted game then runs on its own thread via ff7_boot_and_run. stdout/stderr
// are copied to logcat (tag FF7Recomp) and to <files>/ff7.log so a run can be read back
// with adb even after the process is gone.
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <vector>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include "ff7_boot.h"

namespace {

std::mutex g_win_lock;
std::condition_variable g_win_cv;
ANativeWindow *g_window;
int g_width, g_height;
ANativeWindow *g_companion;         // the Thor's bottom screen, if any
int g_cwidth, g_cheight;

// A window the app replaced (its surface was destroyed: the device went to sleep, the app
// went to the background) can still be bgfx's: its Vulkan swap chain re-creates a lost
// surface from the same window. Releasing it at once freed it under bgfx, which crashed in
// libvulkan on the next frame (GitHub #26). Replaced windows are kept until the renderer has
// moved to the new one and a few frames have passed.
struct Retired { ANativeWindow *win; unsigned long frame; };
std::vector<Retired> g_retired;
unsigned long g_frames;             // the renderer's frames (ff7_platform_frame_window)
bool g_renderer_live;               // the renderer draws into g_window
bool g_parked;                      // the game waits for a surface

void retire_locked(ANativeWindow *w) {
    if (w) g_retired.push_back({ w, g_frames });
}

void release_retired_locked() {
    for (size_t i = 0; i < g_retired.size();) {
        if (g_frames - g_retired[i].frame >= 8) {
            ANativeWindow_release(g_retired[i].win);
            g_retired[i] = g_retired.back();
            g_retired.pop_back();
        } else {
            i++;
        }
    }
}

std::string g_game_dir;
FILE *g_logfile;

// stdout+stderr -> pipe -> logcat + file, one line at a time.
void *log_pump(void *arg) {
    int fd = static_cast<int>(reinterpret_cast<intptr_t>(arg));
    char buf[4096];
    std::string line;
    ssize_t n;
    while ((n = read(fd, buf, sizeof buf)) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            if (buf[i] == '\n') {
                __android_log_write(ANDROID_LOG_INFO, "FF7Recomp", line.c_str());
                if (g_logfile) { std::fputs(line.c_str(), g_logfile); std::fputc('\n', g_logfile); std::fflush(g_logfile); }
                line.clear();
            } else {
                line.push_back(buf[i]);
            }
        }
    }
    return nullptr;
}

void redirect_output(const std::string &log_dir) {
    g_logfile = std::fopen((log_dir + "/ff7.log").c_str(), "w");
    int fds[2];
    if (pipe(fds) != 0) return;
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    pthread_t t;
    pthread_create(&t, nullptr, log_pump, reinterpret_cast<void *>(static_cast<intptr_t>(fds[0])));
    pthread_detach(t);
}

void *game_thread(void *) {
    int rc = ff7_boot_and_run(g_game_dir.c_str());
    std::fprintf(stderr, "[ff7] game thread finished with %d\n", rc);
    // The game owns this process (":game"): when it ends, so does the process, and Android
    // shows the launcher again. Give the log pump a moment to write the last lines.
    std::fflush(stderr);
    usleep(300000);
    _exit(0);
}

}  // namespace

extern "C" void *ff7_platform_window(int *width, int *height) {
    std::lock_guard<std::mutex> lk(g_win_lock);
    if (width) *width = g_width;
    if (height) *height = g_height;
    return g_window;
}

// Audio while the game is paused for want of a surface (src/hle/ff7_dsound.c, FFNx's audio).
extern "C" void ff7_dsound_suspend(int on) __attribute__((weak));
extern "C" void ff7r_ffnx_audio_suspend(int on) __attribute__((weak));

// The renderer, once a frame before presenting (FFNx's Renderer::draw): returns the window
// to draw into. While the app has no surface (asleep, in the background) the game waits
// here, silent, and goes on where it was when a surface comes back (GitHub #26).
extern "C" void *ff7_platform_frame_window(void) {
    std::unique_lock<std::mutex> lk(g_win_lock);
    g_renderer_live = true;
    g_frames++;
    release_retired_locked();
    if (!g_window) {
        g_parked = true;
        g_win_cv.notify_all();
        std::fprintf(stderr, "[ff7] no surface (asleep or in the background): game paused\n");
        if (ff7_dsound_suspend) ff7_dsound_suspend(1);
        if (ff7r_ffnx_audio_suspend) ff7r_ffnx_audio_suspend(1);
        g_win_cv.wait(lk, [] { return g_window != nullptr; });
        if (ff7r_ffnx_audio_suspend) ff7r_ffnx_audio_suspend(0);
        if (ff7_dsound_suspend) ff7_dsound_suspend(0);
        g_parked = false;
        std::fprintf(stderr, "[ff7] surface back (%dx%d): game resumed\n", g_width, g_height);
    }
    return g_window;
}

extern "C" void *ff7_platform_companion_window(int *width, int *height) {
    std::lock_guard<std::mutex> lk(g_win_lock);
    if (width) *width = g_cwidth;
    if (height) *height = g_cheight;
    return g_companion;
}

// ---- controls: GameActivity folds key and motion events into one XInput-style pad.
namespace {
std::mutex g_pad_lock;
ff7_pad_state g_pad{};
unsigned short g_inject;               // buttons src/ffnx_port/quickboot.cpp presses
}

extern "C" int ff7_platform_pad_state(unsigned index, ff7_pad_state *out) {
    if (index != 0) return 0;          // the Thor's built-in controls (and any paired pad) are pad 0
    std::lock_guard<std::mutex> lk(g_pad_lock);
    *out = g_pad;
    // Select+L1 / Select+R1 / Select+Left / Select+Right are the save-state buttons
    // (src/host/ff7_savestate.c): the game does not see those combinations.
    if ((out->buttons & 0x0020) && (out->buttons & 0x030C)) out->buttons &= (unsigned short)~0x032C;
    out->buttons |= g_inject;
    return 1;
}

extern "C" void ff7_platform_pad_inject(unsigned short buttons) {
    std::lock_guard<std::mutex> lk(g_pad_lock);
    g_inject = buttons;
}

extern "C" int ff7_platform_pad_buttons(void) {
    std::lock_guard<std::mutex> lk(g_pad_lock);
    return g_pad.buttons;
}

extern "C" void ff7_platform_pad_rumble(unsigned, unsigned short, unsigned short) {}

// No physical keyboard path yet: the game's DirectInput keyboard sees nothing pressed and
// FFNx drives FF7's controls from the pad.
extern "C" int ff7_platform_keyboard_state(unsigned char keys[256]) {
    std::memset(keys, 0, 256);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ff7recomp_GameActivity_nativePad(JNIEnv *, jclass, jint buttons, jint lt, jint rt,
                                          jint lx, jint ly, jint rx, jint ry) {
    std::lock_guard<std::mutex> lk(g_pad_lock);
    g_pad.buttons = (unsigned short)buttons;
    g_pad.left_trigger = (unsigned char)lt;
    g_pad.right_trigger = (unsigned char)rt;
    g_pad.lx = (short)lx; g_pad.ly = (short)ly;
    g_pad.rx = (short)rx; g_pad.ry = (short)ry;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ff7recomp_GameActivity_nativeSetCompanionSurface(JNIEnv *env, jclass, jobject surface, jint w, jint h) {
    std::lock_guard<std::mutex> lk(g_win_lock);
    retire_locked(g_companion);         // bgfx may still hold it: released a few frames later
    g_companion = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    g_cwidth = w;
    g_cheight = h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ff7recomp_GameActivity_nativeSetSurface(JNIEnv *env, jclass, jobject surface, jint w, jint h) {
    std::unique_lock<std::mutex> lk(g_win_lock);
    retire_locked(g_window);            // bgfx may still hold it: released a few frames later
    g_window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    g_width = w;
    g_height = h;
    g_win_cv.notify_all();
    // The surface is going away (surfaceDestroyed): give the game a moment to finish its frame
    // and stop at ff7_platform_frame_window, so it does not present into a destroyed surface.
    // Not forever: a game busy elsewhere (loading) parks at its next frame.
    if (!g_window && g_renderer_live)
        g_win_cv.wait_for(lk, std::chrono::milliseconds(500), [] { return g_parked || g_window != nullptr; });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ff7recomp_GameActivity_nativeStart(JNIEnv *env, jclass, jstring game_dir, jstring log_dir) {
    // Once per process: if Android recreates GameActivity while the game runs (a display-size or
    // font-scale change, ...), the new activity only hands the running game its new surface
    // (nativeSetSurface); a second game thread would re-initialise the allocator under the
    // running game and then quit (GitHub #16).
    static std::atomic<bool> started{ false };
    if (started.exchange(true)) {
        std::fprintf(stderr, "[ff7] nativeStart again (activity recreated): the game keeps running\n");
        return;
    }
    const char *g = env->GetStringUTFChars(game_dir, nullptr);
    const char *l = env->GetStringUTFChars(log_dir, nullptr);
    g_game_dir = g;
    redirect_output(l);
    // Debug switches for the runtime (FF7_PATH_TRACE=1, FF7_TRACE_IMPORTS=1, ...): one
    // KEY=VALUE per line in files/env.txt (pushed with adb; absent normally).
    if (FILE *env = std::fopen((std::string(l) + "/env.txt").c_str(), "r")) {
        char line[256];
        while (std::fgets(line, sizeof line, env)) {
            line[std::strcspn(line, "\r\n")] = 0;
            char *eq = std::strchr(line, '=');
            if (!eq || line[0] == '#') continue;
            *eq = 0;
            setenv(line, eq + 1, 1);
            std::fprintf(stderr, "[ff7] env %s=%s\n", line, eq + 1);
        }
        std::fclose(env);
    }
    env->ReleaseStringUTFChars(game_dir, g);
    env->ReleaseStringUTFChars(log_dir, l);

    // The lifted game wants a deep stack (its own guest stack is separate, but the
    // native recursion of lifted calls is not): 64 MB.
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 64u << 20);
    pthread_t t;
    pthread_create(&t, &attr, game_thread, nullptr);
    pthread_detach(t);
}

// ---- the bottom screen's panels (src/ffnx_port/companion.cpp) ------------------------------
extern "C" int ff7_companion_json(char *out, int cap) __attribute__((weak));
extern "C" void ff7_companion_command(int cmd) __attribute__((weak));

extern "C" JNIEXPORT jstring JNICALL
Java_com_ff7recomp_CompanionView_nativeState(JNIEnv *env, jclass) {
    static char buf[8192];
    if (!ff7_companion_json || ff7_companion_json(buf, sizeof buf) <= 0) std::snprintf(buf, sizeof buf, "{\"ready\":0}");
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ff7recomp_CompanionView_nativeCommand(JNIEnv *, jclass, jint cmd) {
    if (ff7_companion_command) ff7_companion_command(cmd);
}

// a short button press from the bottom screen (XInput bits), on top of the real pad
extern "C" JNIEXPORT void JNICALL
Java_com_ff7recomp_CompanionView_nativeTap(JNIEnv *, jclass, jint buttons) {
    ff7_platform_pad_inject((unsigned short)buttons);
}

// the bottom screen's battle windows (src/ffnx_port/companion_render.cpp)
extern "C" void ff7_companion_set_area(int top, int bottom) __attribute__((weak));
extern "C" void ff7_companion_battle_tap(float x, float y) __attribute__((weak));
extern "C" void ff7_companion_battle_back() __attribute__((weak));

extern "C" JNIEXPORT void JNICALL
Java_com_ff7recomp_CompanionView_nativeSetArea(JNIEnv *, jclass, jint top, jint bottom) {
    if (ff7_companion_set_area) ff7_companion_set_area(top, bottom);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ff7recomp_CompanionView_nativeBattleTap(JNIEnv *, jclass, jfloat x, jfloat y) {
    if (ff7_companion_battle_tap) ff7_companion_battle_tap(x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ff7recomp_CompanionView_nativeBattleBack(JNIEnv *, jclass) {
    if (ff7_companion_battle_back) ff7_companion_battle_back();
}
