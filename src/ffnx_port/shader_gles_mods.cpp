/* shader_gles_mods.cpp - which shaders OpenGL ES takes from a mod's Vulkan ones (GitHub #89):
 * those a mod replaces for Vulkan (shaders/X.vk.frag) and not for OpenGL ES (X.gles.frag). */
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
extern "C" const char *ff7_mods_redirect(const char *rel, char *buf, unsigned n);   // src/mods/mods.h
#include "shader_gles.h"

/* `path`: FFNx's shader path below the game folder. true: `out` is the shader to use instead
 * of that file. Test: FF7_TEST_GLES_FROM_VK=1 in files/env.txt converts every shader. */
bool ff7r_mod_gles_shader(const char *basedir, const char *path, std::vector<unsigned char> &out) {
    std::string name = path;
    const size_t dot = name.rfind(".gles.");
    if (dot == std::string::npos) return false;
    char redirect[1024];
    const char *test = getenv("FF7_TEST_GLES_FROM_VK");
    const bool all = test && test[0] == '1';
    if (!all && ff7_mods_redirect(path, redirect, sizeof redirect)) return false;
    name.replace(dot, 6, ".vk.");
    if (!all && !ff7_mods_redirect(name.c_str(), redirect, sizeof redirect)) return false;

    const std::string full = std::string(basedir) + "/" + name;
    FILE *f = fopen(full.c_str(), "rb");   // the prelude's: mods included
    if (!f) return false;
    std::vector<unsigned char> in;
    unsigned char buf[16384];
    for (size_t n; (n = fread(buf, 1, sizeof buf, f)) > 0; ) in.insert(in.end(), buf, buf + n);
    fclose(f);

    const size_t slash = name.rfind('/');
    std::string base = slash == std::string::npos ? name : name.substr(slash + 1);
    // FFNx.vert and FFNx.lighting.vert: FFNx[.lighting][.skinned].(flat|smooth)
    std::string kind = base;
    for (const char *part : {"FFNx.", "lighting.", "skinned."})
        if (kind.compare(0, strlen(part), part) == 0) kind.erase(0, strlen(part));
    const bool flip = kind == "flat.vk.vert" || kind == "smooth.vk.vert";
    std::string err;
    if (!ff7_shader_vk_to_gles(in, flip, out, err)) {
        fprintf(stderr, "[ffnx-android] mod shader %s: not usable with OpenGL ES (%s)\n", name.c_str(), err.c_str());
        return false;
    }
    fprintf(stderr, "[ffnx-android] mod shader %s: converted for OpenGL ES\n", name.c_str());
    return true;
}
