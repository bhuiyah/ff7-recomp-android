// ff7_launcher_jni.cpp - native side of the launcher (libff7launcher.so): what a mod is,
// read with the same code the game's mod overlay uses (src/mods), returned as JSON.
#include <jni.h>
#include <sys/stat.h>
#include <cstdio>
#include <string>
#include <vector>
#include "iro.hpp"
#include "modinfo.hpp"
#include "mods.h"
#include <cstdlib>
#include <mutex>

namespace {

std::string esc(const std::string &s) {
    std::string o;
    for (unsigned char c : s) {
        switch (c) {
        case '"': o += "\\\""; break;
        case '\\': o += "\\\\"; break;
        case '\n': o += "\\n"; break;
        case '\r': break;
        case '\t': o += "\\t"; break;
        default:
            if (c < 0x20) { char b[8]; std::snprintf(b, sizeof b, "\\u%04x", c); o += b; }
            else o += (char)c;
        }
    }
    return o;
}

std::string read_file(const std::string &p) {
    std::FILE *f = std::fopen(p.c_str(), "rb");
    if (!f) return std::string();
    std::string s;
    char buf[65536];
    size_t r;
    while ((r = std::fread(buf, 1, sizeof buf, f)) > 0) s.append(buf, r);
    std::fclose(f);
    return s;
}

std::string mod_json(const std::string &path) {
    using namespace ff7mods;
    std::string xml, err, kind;
    size_t files = 0;
    struct stat st;
    if (stat(path.c_str(), &st) != 0) return "{\"error\":\"not found\"}";
    if (st.st_mode & S_IFDIR) {
        kind = "folder";
        xml = read_file(path + "/mod.xml");
        if (xml.empty()) xml = read_file(path + "/Mod.xml");
    } else {
        kind = "iro";
        IroArchive a;
        if (!a.open(path)) return "{\"error\":\"" + esc(a.error()) + "\"}";
        files = a.entries().size();
        std::vector<uint8_t> v;
        if (a.read("mod.xml", v)) xml.assign(v.begin(), v.end());
    }
    if (xml.size() >= 3 && (uint8_t)xml[0] == 0xEF && (uint8_t)xml[1] == 0xBB && (uint8_t)xml[2] == 0xBF) xml.erase(0, 3);
    ModInfo m;
    bool has_xml = !xml.empty();
    if (has_xml && !parse_mod_xml(xml, m, err)) return "{\"error\":\"" + esc(err) + "\"}";
    std::string j = "{\"kind\":\"" + kind + "\",\"hasModXml\":" + (has_xml ? "true" : "false") +
                    ",\"files\":" + std::to_string(files) +
                    ",\"id\":\"" + esc(m.id) + "\",\"name\":\"" + esc(m.name) + "\",\"author\":\"" + esc(m.author) +
                    "\",\"version\":\"" + esc(m.version) + "\",\"category\":\"" + esc(m.category) +
                    "\",\"description\":\"" + esc(m.description) + "\",\"link\":\"" + esc(m.link) + "\",\"options\":[";
    for (size_t i = 0; i < m.options.size(); i++) {
        const ConfigOption &o = m.options[i];
        j += std::string(i ? "," : "") + "{\"id\":\"" + esc(o.id) + "\",\"name\":\"" + esc(o.name) + "\",\"type\":\"" +
             esc(o.type) + "\",\"default\":" + std::to_string(o.default_value) + ",\"description\":\"" + esc(o.description) +
             "\",\"values\":[";
        for (size_t k = 0; k < o.values.size(); k++)
            j += std::string(k ? "," : "") + "{\"value\":" + std::to_string(o.values[k].first) + ",\"name\":\"" + esc(o.values[k].second) + "\"}";
        j += "]}";
    }
    j += "],\"ffnx\":[";
    for (size_t i = 0; i < m.ffnx_config.size(); i++)
    {
        std::string when;
        if (i < m.ffnx_config_when.size())
            for (const auto &w : m.ffnx_config_when[i])
                when += (when.empty() ? "" : ";") + w.first + "=" + w.second;
        j += std::string(i ? "," : "") + "{\"key\":\"" + esc(m.ffnx_config[i].first) + "\",\"value\":\"" + esc(m.ffnx_config[i].second)
           + "\",\"when\":\"" + esc(when) + "\"}";
    }
    j += "],\"requires\":[";
    for (size_t i = 0; i < m.compat_requires.size(); i++)
        j += std::string(i ? "," : "") + "{\"id\":\"" + esc(m.compat_requires[i].mod_id) + "\",\"why\":\"" + esc(m.compat_requires[i].description) + "\"}";
    j += "],\"forbids\":[";
    for (size_t i = 0; i < m.compat_forbids.size(); i++)
        j += std::string(i ? "," : "") + "{\"id\":\"" + esc(m.compat_forbids[i].mod_id) + "\",\"why\":\"" + esc(m.compat_forbids[i].description) + "\"}";
    j += "],\"unsupported\":[";
    for (size_t i = 0; i < m.windows_code.size(); i++) j += std::string(i ? "," : "") + "\"" + esc(m.windows_code[i]) + "\"";
    j += "]}";
    return j;
}

}  // namespace

extern "C" int ff7_un7z(const char *archive, const char *outdir, char *err, size_t errlen);
extern "C" void ff7_un7z_cancel(void);
extern "C" void ff7_un7z_progress(unsigned long long *done, unsigned long long *total);

/** Stop the running unpack (its nativeUn7z returns "cannot unpack: cancelled"). */
extern "C" JNIEXPORT void JNICALL
Java_com_ff7recomp_ModDownloader_nativeUn7zCancel(JNIEnv *, jclass) { ff7_un7z_cancel(); }

/** The running unpack's progress: { bytes unpacked, bytes in all }. */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_ff7recomp_ModDownloader_nativeUn7zProgress(JNIEnv *env, jclass) {
    unsigned long long d, t;
    ff7_un7z_progress(&d, &t);
    jlong v[2] = { (jlong)d, (jlong)t };
    jlongArray a = env->NewLongArray(2);
    if (a) env->SetLongArrayRegion(a, 0, 2, v);
    return a;
}

/** Unpack a .7z download into outDir (src/android/un7z.c): null on success, else the error. */
extern "C" JNIEXPORT jstring JNICALL
Java_com_ff7recomp_ModDownloader_nativeUn7z(JNIEnv *env, jclass, jstring jarchive, jstring joutdir) {
    const char *a = env->GetStringUTFChars(jarchive, nullptr);
    const char *o = env->GetStringUTFChars(joutdir, nullptr);
    char err[512];
    int rc = ff7_un7z(a, o, err, sizeof err);
    env->ReleaseStringUTFChars(jarchive, a);
    env->ReleaseStringUTFChars(joutdir, o);
    return rc == 0 ? nullptr : env->NewStringUTF(err);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ff7recomp_ModLibrary_nativeModInfo(JNIEnv *env, jclass, jstring jpath) {
    const char *p = env->GetStringUTFChars(jpath, nullptr);
    std::string json = mod_json(p);
    env->ReleaseStringUTFChars(jpath, p);
    return env->NewStringUTF(json.c_str());
}

// The enabled mods of a profile as the game would load them, and where they collide
// (src/mods/mods.h ff7_mods_conflicts_json).
// The mod overlay is one global set (src/mods/mods.cpp): the conflict report and the sound
// lookup below each load it, one at a time.
static std::mutex g_overlay;

extern "C" JNIEXPORT jstring JNICALL
Java_com_ff7recomp_ModLibrary_nativeConflicts(JNIEnv *env, jclass, jstring jprofile) {
    const char *p = env->GetStringUTFChars(jprofile, nullptr);
    std::lock_guard<std::mutex> lk(g_overlay);
    char *json = ff7_mods_conflicts_json(p);
    env->ReleaseStringUTFChars(jprofile, p);
    jstring r = env->NewStringUTF(json ? json : "{}");
    std::free(json);
    return r;
}

// Where the game would read each of `rels` (paths relative to the game folder) with the
// profile's enabled mods: the replacing file (an IRO entry is extracted into cacheDir), or
// null where no mod replaces it. Sfx.java uses it for FFNx's external sounds (sfx/...).
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ff7recomp_Sfx_nativeModFiles(JNIEnv *env, jclass, jstring jprofile, jstring jcache, jobjectArray jrels) {
    const char *p = env->GetStringUTFChars(jprofile, nullptr);
    const char *c = env->GetStringUTFChars(jcache, nullptr);
    jsize n = env->GetArrayLength(jrels);
    jobjectArray out = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    {
        std::lock_guard<std::mutex> lk(g_overlay);
        ff7_mods_load(p, c);
        for (jsize i = 0; i < n; i++) {
            jstring jr = (jstring)env->GetObjectArrayElement(jrels, i);
            const char *r = env->GetStringUTFChars(jr, nullptr);
            char buf[4096];
            const char *hit = ff7_mods_redirect(r, buf, sizeof buf);
            if (hit) env->SetObjectArrayElement(out, i, env->NewStringUTF(hit));
            env->ReleaseStringUTFChars(jr, r);
            env->DeleteLocalRef(jr);
        }
    }
    env->ReleaseStringUTFChars(jprofile, p);
    env->ReleaseStringUTFChars(jcache, c);
    return out;
}
