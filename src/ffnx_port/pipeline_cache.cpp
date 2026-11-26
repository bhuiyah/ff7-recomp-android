/* pipeline_cache.cpp - Vulkan pipelines kept on disk between launches (GitHub #90).
 *
 * bgfx asks its callback for a cached VkPipelineCache blob before each vkCreateGraphicsPipelines
 * and hands the result back afterwards; FFNx's callback kept nothing, so every launch compiled
 * every pipeline again. On Mali (SM-A155F, #90) the driver's shader compiler aborted in one of
 * its own worker threads (free() of a bad pointer under cmpbe_v2_compile_multiple_shaders) while
 * a battle built its pipelines. With the blobs kept, a pipeline built once is loaded from the
 * driver's cache instead of compiled, so that code runs once per pipeline, not on every load.
 *
 * Files: <files>/pipelinecache/<key>/<id>.bin, <files> being the game folder's parent; <key>
 * changes with the system build (a driver update) so an old driver's blobs are never offered.
 * The driver checks each blob's header (vendor, device, cache UUID) as well.
 * OpenGL ES (1.1.58): bgfx hands each linked program's binary to the same callbacks (<id>.glb);
 * a binary the driver turns down is linked from its shaders again (patches/bgfx/0008).
 * Test: FF7_TEST_NO_PCACHE=1 in files/env.txt turns it off. */
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

namespace {

const uint32_t kMaxBlob = 16u << 20;     // one pipeline's blob; larger = not ours
const int kMaxFiles = 4000;              // more than this: start the folder again

std::string g_dir;                       // empty: off
bool g_ready = false;
unsigned g_hits = 0, g_stored = 0;

uint64_t fnv1a(const char *s) {
    uint64_t h = 1469598103934665603ull;
    for (; *s; ++s) { h ^= (unsigned char)*s; h *= 1099511628211ull; }
    return h;
}

void remove_tree(const std::string &dir) {
    if (DIR *d = opendir(dir.c_str())) {
        while (dirent *e = readdir(d)) {
            if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
            std::string p = dir + "/" + e->d_name;
            struct stat st;
            if (stat(p.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) remove_tree(p);
            else unlink(p.c_str());
        }
        closedir(d);
    }
    rmdir(dir.c_str());
}

int count_files(const std::string &dir) {
    int n = 0;
    if (DIR *d = opendir(dir.c_str())) {
        while (dirent *e = readdir(d)) if (e->d_name[0] != '.') ++n;
        closedir(d);
    }
    return n;
}

void setup(const char *basedir) {
    g_ready = true;
    const char *off = getenv("FF7_TEST_NO_PCACHE");
    if (off && off[0] == '1') { fprintf(stderr, "[ffnx-android] pipeline cache: off (test)\n"); return; }
    std::string base = basedir && basedir[0] ? basedir : ".";
    while (base.size() > 1 && base.back() == '/') base.pop_back();
    const size_t slash = base.rfind('/');
    const std::string root = (slash == std::string::npos ? std::string(".") : base.substr(0, slash)) + "/pipelinecache";

    std::string build = "unknown";
#if defined(__ANDROID__)
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.build.fingerprint", v) > 0) build = v;
    char inc[PROP_VALUE_MAX] = {0};
    if (__system_property_get("ro.vendor.build.fingerprint", inc) > 0) build += std::string("|") + inc;
#endif
    char key[32];
    snprintf(key, sizeof key, "%016llx", (unsigned long long)fnv1a(build.c_str()));

    mkdir(root.c_str(), 0700);
    // only the current driver's folder is kept
    if (DIR *d = opendir(root.c_str())) {
        while (dirent *e = readdir(d))
            if (e->d_name[0] != '.' && strcmp(e->d_name, key)) remove_tree(root + "/" + e->d_name);
        closedir(d);
    }
    const std::string dir = root + "/" + key;
    if (count_files(dir) > kMaxFiles) remove_tree(dir);
    if (mkdir(dir.c_str(), 0700) != 0 && errno != EEXIST) {
        fprintf(stderr, "[ffnx-android] pipeline cache: cannot create %s (%s), off\n", dir.c_str(), strerror(errno));
        return;
    }
    g_dir = dir;
    fprintf(stderr, "[ffnx-android] pipeline cache: %s (%d kept)\n", dir.c_str(), count_files(dir));
}

/* .bin: a Vulkan pipeline cache blob; .glb: an OpenGL ES program binary (1.1.58). */
std::string path_of(uint64_t id, bool gl) {
    char name[40];
    snprintf(name, sizeof name, gl ? "/%016llx.glb" : "/%016llx.bin", (unsigned long long)id);
    return g_dir + name;
}

long file_size(const std::string &p) {
    struct stat st;
    return stat(p.c_str(), &st) == 0 && S_ISREG(st.st_mode) ? (long)st.st_size : -1;
}

}  // namespace

/* bgfx's cacheReadSize: the blob's size, 0 = none. */
uint32_t ff7r_pcache_size(const char *basedir, uint64_t id, bool gl) {
    if (!g_ready) setup(basedir);
    if (g_dir.empty()) return 0;
    const long n = file_size(path_of(id, gl));
    return n > 0 && n <= (long)kMaxBlob ? (uint32_t)n : 0;
}

/* bgfx's cacheRead: the blob, exactly `size` bytes. */
bool ff7r_pcache_read(const char *basedir, uint64_t id, void *data, uint32_t size, bool gl) {
    if (!g_ready) setup(basedir);
    if (g_dir.empty() || !data || !size) return false;
    FILE *f = fopen(path_of(id, gl).c_str(), "rb");
    if (!f) return false;
    const bool ok = fread(data, 1, size, f) == size;
    fclose(f);
    if (ok && ++g_hits % 50 == 1) fprintf(stderr, "[ffnx-android] pipeline cache: %u loaded, %u stored\n", g_hits, g_stored);
    return ok;
}

/* bgfx's cacheWrite, after every pipeline it builds (also after one it loaded): the blob is
 * written to a temporary name and renamed, so a crash never leaves half a file. */
void ff7r_pcache_write(const char *basedir, uint64_t id, const void *data, uint32_t size, bool gl) {
    if (!g_ready) setup(basedir);
    if (g_dir.empty() || !data || !size || size > kMaxBlob) return;
    const std::string p = path_of(id, gl);
    if (file_size(p) == (long)size) return;   // already kept (loaded from it)
    const std::string tmp = p + ".tmp";
    FILE *f = fopen(tmp.c_str(), "wb");
    if (!f) return;
    const bool ok = fwrite(data, 1, size, f) == size;
    if (fclose(f) != 0 || !ok) { unlink(tmp.c_str()); return; }
    if (rename(tmp.c_str(), p.c_str()) != 0) { unlink(tmp.c_str()); return; }
    ++g_stored;
}
