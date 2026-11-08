// savestate_ffnx.cpp - what the save states (src/host/ff7_savestate.c) need from FFNx: the
// game's mode, whether this moment can be saved, an on-screen note, and FFNx's texture sets.
//
// Texture sets live in guest memory (the game holds them) but carry host state: GPU texture
// handles, and gl_texture_set's std::maps whose nodes are host memory. A loaded state brings
// back the guest bytes of the sets alive when it was saved, with handles and map pointers
// from that moment (or another process). So: every set FFNx creates is registered here; the
// list is part of the state; before a load the current sets' GPU textures are deleted, and
// after it every saved set gets fresh maps and has each palette it had uploaded again from
// the image data in (restored) guest memory -- FFNx's own common_load_texture.
#include <cstdio>
#include <cstring>
#include <new>
#include <set>
#include <vector>
#include "common.h"
#include "globals.h"
#include "gl.h"
#include "log.h"
#include "renderer.h"
#include "ff7/defs.h"
#include "ff7/field/enter.h"
#include "ff7/widescreen.h"
#include "audio.h"
#include "music.h"
#include "ff7_savestate.h"

void stop_music_for_channel(int channel);   // music.cpp
// gl/deferred.cpp: queues allocated once from the game heap, kept across frames
extern struct deferred_draw *deferred_draws;
extern uint32_t num_deferred;
extern struct deferred_sorted_draw *deferred_sorted_draws;
extern uint32_t num_sorted_deferred;
extern "C" uint32_t universal_buttons_ss_count();          // ff7/universal_buttons.cpp
extern "C" void universal_buttons_ss_get(uint32_t *out);
extern "C" void universal_buttons_ss_set(const uint32_t *in);

namespace {
std::set<uint32_t> g_sets;                 // live texture sets (guest addresses)
std::vector<uint32_t> g_restored;          // the sets of a state being loaded
}

extern "C" void ffnx_ss_texture_set_created(uint32_t ts) { g_sets.insert(ts); }
extern "C" void ffnx_ss_texture_set_destroyed(uint32_t ts) { g_sets.erase(ts); }

namespace {

int ss_save(void *, ff7_ss_writer put, void *buf) {
    uint32_t n = (uint32_t)g_sets.size();
    if (put(buf, &n, 4)) return -1;
    for (uint32_t ts : g_sets) if (put(buf, &ts, 4)) return -1;
    return 0;
}

// The running game's sets are about to be replaced: free their GPU textures (their guest
// bytes are about to be overwritten, so read the handles now).
void ss_before_load(void *) {
    for (uint32_t a : g_sets) {
        struct ff7_texture_set *ts = (struct ff7_texture_set *)(uintptr_t)a;
        struct gl_texture_set *gl = ts->ogl.gl_set;
        uint32_t *handles = ts->texturehandle;
        if (!gl || !handles) continue;
        for (uint32_t i = 0; i < gl->textures; i++)
            if (handles[i]) newRenderer.deleteTexture(handles[i]);
        for (auto &kv : gl->additional_textures) if (kv.second) newRenderer.deleteTexture(kv.second);
        for (auto &kv : gl->animated_textures) if (kv.second) newRenderer.deleteTexture(kv.second);
    }
    g_sets.clear();
}

void ss_restore(void *, const void *data, size_t n) {
    g_restored.clear();
    if (n < 4) return;
    uint32_t count;
    std::memcpy(&count, data, 4);
    if (n < 4 + 4ull * count) return;
    g_restored.resize(count);
    std::memcpy(g_restored.data(), (const uint8_t *)data + 4, 4ull * count);
}

void ss_after_load(void *) {
    int sets = 0, uploads = 0;
    for (uint32_t a : g_restored) {
        struct ff7_texture_set *ts = (struct ff7_texture_set *)(uintptr_t)a;
        struct gl_texture_set *gl = ts->ogl.gl_set;
        g_sets.insert(a);
        if (!gl) continue;
        // the maps' bytes are another moment's host pointers: start them afresh (leaking
        // whatever they pointed at, which belongs to that moment)
        new (&gl->animated_textures) std::map<std::string, uint32_t>();
        new (&gl->additional_textures) std::map<uint16_t, uint32_t>();
        for (short slot = RendererTextureSlot::TEX_NML; slot < RendererTextureSlot::COUNT; slot++) gl->additional_textures[slot] = 0;
        uint32_t *handles = ts->texturehandle;
        struct tex_header *generic = ts->tex_header;
        if (!handles || !generic) continue;
        struct ff7_tex_header *th = (struct ff7_tex_header *)generic;
        const uint32_t keep = th->palette_index;
        std::vector<uint32_t> had;
        for (uint32_t i = 0; i < gl->textures; i++) if (handles[i]) { had.push_back(i); handles[i] = 0; }
        for (uint32_t i : had) {
            th->palette_index = i;
            common_load_texture((struct texture_set *)ts, generic, ts->texture_format);
            uploads++;
        }
        th->palette_index = keep;
        sets++;
    }
    g_restored.clear();
    ffnx_info("savestate: %d texture sets rebuilt (%d uploads)\n", sets, uploads);
}

const ff7_savestate_hook g_hook = { "ffnx-textures", nullptr, ss_save, ss_before_load, ss_restore, ss_after_load };

// ---- the scene around the state: music, and FFNx's per-field host state ------------------
// FFNx sets these up when a scene is entered (the game's own calls), which a load skips: the
// music the state had, and the field caches and widescreen layout FFNx derives from the field.
struct SceneState { uint32_t music_id; uint32_t music_playing; int32_t mode; };
SceneState g_scene_loaded;
bool g_scene_have;

int scene_save(void *, ff7_ss_writer put, void *buf) {
    SceneState s{ nxAudioEngine.currentMusicId(0), nxAudioEngine.isMusicPlaying(0) ? 1u : 0u, ff7_ss_driver_mode() };
    return put(buf, &s, sizeof s);
}
void scene_restore(void *, const void *data, size_t n) {
    g_scene_have = n >= sizeof(SceneState);
    if (g_scene_have) std::memcpy(&g_scene_loaded, data, sizeof g_scene_loaded);
}
void scene_after_load(void *) {
    // the mode the state was saved in: FFNx's cached mode only catches up next frame
    const int mode = g_scene_have ? g_scene_loaded.mode : ff7_ss_driver_mode();
    if (g_scene_have) {
        const SceneState &s = g_scene_loaded;
        const uint32_t was = nxAudioEngine.currentMusicId(0);
        if (s.music_playing && (was != s.music_id || !nxAudioEngine.isMusicPlaying(0))) {
            ff7_play_midi(s.music_id);
            ffnx_info("savestate: music %u -> %u\n", was, s.music_id);
        } else if (!s.music_playing && nxAudioEngine.isMusicPlaying(0)) {
            stop_music_for_channel(0);
            ffnx_info("savestate: music %u stopped\n", was);
        }
        g_scene_have = false;
    }
    if (mode == MODE_FIELD) {          // ff7/field/enter.h, without the game's part
        using namespace ff7::field;
        ff7_handle_KAWAI_reset();
        field_3d_world_pos = {INVALID_VALUE, INVALID_VALUE};
        bg_main_layer_pos = {INVALID_VALUE, INVALID_VALUE};
        bg_layer3_pos = {INVALID_VALUE, INVALID_VALUE};
        bg_layer4_pos = {INVALID_VALUE, INVALID_VALUE};
        field_curr_delta_world_pos = {INVALID_VALUE, INVALID_VALUE};
        cursor_position = {INVALID_VALUE, INVALID_VALUE};
        for (auto &external_data : external_model_data) {
            external_data.moveFrameIndex = 0;
            external_data.rotationMoveFrameIndex = 0;
            external_data.prevCollisionRadius = 0;
        }
        ff7_field_reset_blink_frames();
        if (widescreen_enabled || enable_uncrop) widescreen.initParamsFromConfig();
    }
}
const ff7_savestate_hook g_scene_hook = { "ffnx-scene", nullptr, scene_save, nullptr, scene_restore, scene_after_load };

// ---- button prompts: which game objects FFNx swapped the controller-button atlas into ------
// (ff7/universal_buttons.cpp). Registered after the texture sets: their rebuild comes first.
std::vector<uint32_t> g_prompts;
bool g_prompts_have;
int prompts_save(void *, ff7_ss_writer put, void *buf) {
    std::vector<uint32_t> v(3 * universal_buttons_ss_count());
    universal_buttons_ss_get(v.data());
    return put(buf, v.data(), v.size() * 4);
}
void prompts_restore(void *, const void *data, size_t n) {
    g_prompts.assign(3 * universal_buttons_ss_count(), 0);
    g_prompts_have = n == g_prompts.size() * 4;
    if (g_prompts_have) std::memcpy(g_prompts.data(), data, n);
}
void prompts_after_load(void *) {
    universal_buttons_ss_set(g_prompts_have ? g_prompts.data() : nullptr);
    g_prompts_have = false;
}
const ff7_savestate_hook g_prompts_hook = { "ffnx-prompts", nullptr, prompts_save, nullptr, prompts_restore, prompts_after_load };

// ---- FFNx's draw queues (gl/deferred.cpp): arrays allocated once from the game heap and kept
// across frames (magic effects use them). The state's heap has the arrays its own session had:
// point at those; a state without the record gets new ones (the old pointers would name memory
// the restored heap gives to something else).
uint32_t g_deferred[2];
bool g_deferred_have;
int deferred_save(void *, ff7_ss_writer put, void *buf) {
    uint32_t v[2] = { guest::to_va(deferred_draws), guest::to_va(deferred_sorted_draws) };
    return put(buf, v, sizeof v);
}
void deferred_restore(void *, const void *data, size_t n) {
    g_deferred_have = n == sizeof g_deferred;
    if (g_deferred_have) std::memcpy(g_deferred, data, n);
}
void deferred_after_load(void *) {
    deferred_draws = g_deferred_have ? guest::ptr_of<struct deferred_draw>(g_deferred[0]) : nullptr;
    deferred_sorted_draws = g_deferred_have ? guest::ptr_of<struct deferred_sorted_draw>(g_deferred[1]) : nullptr;
    num_deferred = 0;
    num_sorted_deferred = 0;
    g_deferred_have = false;
}
const ff7_savestate_hook g_deferred_hook = { "ffnx-deferred", nullptr, deferred_save, nullptr, deferred_restore, deferred_after_load };

struct Register {
    Register() { ff7_savestate_register(&g_hook); ff7_savestate_register(&g_scene_hook); ff7_savestate_register(&g_prompts_hook);
                 ff7_savestate_register(&g_deferred_hook); }
} g_register;

}  // namespace

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

// Where the game is, for slot lists: the place name the main menu shows. The field script sets
// it (opcode MPNAM, 0x633691 in 1.02 US) in the savemap at +0xF0C: up to 24 characters of FF7
// text (ASCII shifted down by 0x20, 0xFF ends it; MPNAM has already expanded names and
// control codes). Outside fields the mode is named; with no place name yet, the field's own
// name. And the game's play-time counter.
extern "C" void ff7_ss_location(char *out, unsigned n, uint32_t *play_seconds) {
    struct savemap *sm = ff7_externals.savemap;
    *play_seconds = sm ? sm->seconds : 0;
    if (!n) return;
    out[0] = 0;
    char loc[32] = "";
    if (sm) {
        const uint8_t *place = (const uint8_t *)sm + 0xF0C;
        unsigned k = 0;
        for (unsigned i = 0; i < 24 && k + 1 < sizeof loc; i++) {
            uint8_t c = place[i];
            if (c == 0xFF) break;
            if (c <= 0x5E) loc[k++] = (char)(c + 0x20);   // printable ASCII; accented letters etc. are left out
        }
        while (k && loc[k - 1] == ' ') k--;
        loc[k] = 0;
    }
    switch (ff7_ss_driver_mode()) {
    case MODE_BATTLE: std::snprintf(out, n, "Battle%s%s", loc[0] ? " - " : "", loc); return;
    case MODE_WORLDMAP: std::snprintf(out, n, "World map"); return;
    case MODE_MENU: case MODE_MAIN_MENU: std::snprintf(out, n, "Menu%s%s", loc[0] ? " - " : "", loc); return;
    default: break;
    }
    if (loc[0]) std::snprintf(out, n, "%s", loc);
    else if (const char *f = get_current_field_name()) std::snprintf(out, n, "%s", f);
}

// The settings whose FFNx patches a state depends on: "120 FPS (x8 battles), 16:9".
extern "C" void ff7_ss_config(char *out, unsigned n) {
    static const char *const fps[] = { "original", "original", "30 FPS", "60 FPS", "120 FPS" };
    const char *ar = aspect_ratio == 0 ? "4:3" : aspect_ratio == 2 ? "16:9" : aspect_ratio == 3 ? "16:10" : "other aspect";
    std::snprintf(out, n, "%s (x%d battles), %s%s", ff7_fps_limiter >= 0 && ff7_fps_limiter <= 4 ? fps[ff7_fps_limiter] : "?",
                  battle_frame_multiplier, ar, (widescreen_enabled || enable_uncrop) ? " widescreen" : "");
}

extern "C" void ff7_ss_notify(const char *msg) {
    ffnx_info("savestate: %s\n", msg);
    show_popup_msg(TEXTCOLOR_LIGHT_BLUE, "%s", msg);
}
