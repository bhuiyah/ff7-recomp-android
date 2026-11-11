/*
 * ff7_paths.c - guest Win32 paths -> host paths for FF7 (strong hle_host_path).
 *
 * The host runs with its working directory at the game root (the folder holding
 * ff7_en.exe and data/). FF7 opens paths such as "data\\field\\flevel.lgp",
 * ".\\data\\movies\\...", or absolute ones built from its install path
 * ("C:\\Program Files\\...\\data\\..."). Mapping:
 *
 *   1. backslashes become slashes;
 *   2. a drive letter ("C:") is dropped, and an absolute path is re-rooted at the game
 *      root from its first component that exists there case-insensitively ("data", a
 *      save folder, ...), so the install path the game was told does not matter;
 *   3. each component is matched case-insensitively against the directory, because
 *      Windows is case-insensitive and Android's filesystems are not. A component that
 *      does not exist is kept as written (the game may be creating it).
 *
 * FF7_PATH_TRACE=1 logs every mapping.
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>
#include "win32hle.h"
#include "mods.h"

static int path_trace = -1;

/* Find `name` in directory `dir` ignoring case; copy the real spelling into out. */
static int match_component(const char *dir, const char *name, char *out, size_t n) {
    char probe[1024];
    snprintf(probe, sizeof probe, "%s/%s", dir, name);
    struct stat st;
    if (stat(probe, &st) == 0) {                       /* exact spelling exists: fast path */
        snprintf(out, n, "%s", name);
        return 1;
    }
    DIR *d = opendir(dir[0] ? dir : ".");
    if (!d) return 0;
    struct dirent *e;
    int found = 0;
    while ((e = readdir(d)) != NULL) {
        if (!strcasecmp(e->d_name, name)) {
            snprintf(out, n, "%s", e->d_name);
            found = 1;
            break;
        }
    }
    closedir(d);
    return found;
}

/* The guest working directory relative to the game root ("" = root). Written only by the
 * game thread's SetCurrentDirectoryA; read by any thread's path mapping. */
static char g_cwd[512];

const char *ff7_guest_cwd(void) { return g_cwd; }
/* Save states: put back a value ff7_guest_cwd returned (no path resolution). */
void ff7_restore_guest_cwd(const char *cwd) { snprintf(g_cwd, sizeof g_cwd, "%s", cwd ? cwd : ""); }

const char *hle_host_path(const char *guest, char *buf, unsigned n);

int ff7_set_guest_cwd(const char *guest_path) {
    if (!guest_path) return -1;
    char host[1024];
    char saved[512];
    snprintf(saved, sizeof saved, "%s", g_cwd);
    hle_host_path(guest_path, host, sizeof host);   /* relative to the current cwd, as Windows */
    struct stat st;
    if (stat(host, &st) != 0 || !S_ISDIR(st.st_mode)) {
        snprintf(g_cwd, sizeof g_cwd, "%s", saved);
        return -1;
    }
    snprintf(g_cwd, sizeof g_cwd, "%s", strcmp(host, ".") ? host : "");
    if (path_trace > 0) fprintf(stderr, "[path] cwd %s -> %s\n", guest_path, g_cwd[0] ? g_cwd : ".");
    return 0;
}

const char *hle_host_path(const char *guest, char *buf, unsigned n) {
    if (path_trace < 0) path_trace = getenv("FF7_PATH_TRACE") != NULL;
    if (!guest || !n) return guest;
    /* A host absolute path (FFNx builds some from its base directory): already real. The
     * game's own rooted paths use backslashes or a drive letter. */
    if (guest[0] == '/') {
        /* FFNx appends Windows-style parts to its base directory ("<base>/data\lang-en"). */
        snprintf(buf, n, "%s", guest);
        for (char *p = buf; *p; p++) if (*p == '\\') *p = '/';
        /* Under the game folder (FFNx's <basedir>/data/lang-en/... paths): mods apply too. */
        static char game_root[1024];
        if (!game_root[0] && !getcwd(game_root, sizeof game_root)) game_root[0] = 0;
        size_t gl = strlen(game_root);
        /* Any case: FFNx lower-cases some paths it built from basedir (open_file after its
         * override/ redirection: ".../android/data/com.ff7recomp/..."). */
        /* Put the real spelling of the game root's folders back (the longest prefix shared,
         * folder by folder, so the mods folder beside it too): on an SD card the volume folder
         * is the card's serial ("/storage/4A98-1CE6") and /storage is case-sensitive, so the
         * lower-cased path does not exist (GitHub #13). */
        {
            size_t k = 0, keep = 0;
            while (k < gl && buf[k] && tolower((unsigned char)buf[k]) == tolower((unsigned char)game_root[k])) {
                k++;
                if (game_root[k] == '/' || k == gl) { if (buf[k] == '/' || buf[k] == 0) keep = k; }
            }
            memcpy(buf, game_root, keep);
        }
        if (gl && !strncasecmp(buf, game_root, gl) && buf[gl] == '/') {
            char rel[1024], mod[1024];
            snprintf(rel, sizeof rel, "%s", buf + gl + 1);
            if (ff7_mods_redirect(rel, mod, sizeof mod)) {
                if (path_trace) fprintf(stderr, "[path] %s -> %s (mod)\n", guest, mod);
                snprintf(buf, n, "%s", mod);
                return buf;
            }
        }
        if (path_trace) fprintf(stderr, "[path] %s -> %s (host)\n", guest, buf);
        return buf;
    }

    char tmp[1024];
    snprintf(tmp, sizeof tmp, "%s", guest);
    for (char *p = tmp; *p; p++) if (*p == '\\') *p = '/';

    char *s = tmp;
    int absolute = 0, rooted = 0;
    /* Under the guest install path (C:\FF7): the rest is relative to the game root. */
    static const char root[] = "C:/FF7";
    size_t rl = sizeof root - 1;
    if (!strncasecmp(s, root, rl) && (s[rl] == '/' || s[rl] == 0)) { s += rl; rooted = 1; }
    else if (isalpha((unsigned char)s[0]) && s[1] == ':') { s += 2; absolute = 1; }
    else if (*s == '/') absolute = 1;
    while (*s == '/') s++;

    /* A relative path resolves against the guest working directory (already host-relative
     * to the game root, so it is not re-rooted). */
    char joined[1024];
    if (!absolute && !rooted && g_cwd[0]) {
        snprintf(joined, sizeof joined, "%s/%s", g_cwd, s);
        s = joined;
    }

    /* Split into components. */
    char *parts[128];
    int np = 0;
    char *save = NULL;   /* strtok_r: FFNx opens files from several threads */
    for (char *tok = strtok_r(s, "/", &save); tok && np < 128; tok = strtok_r(NULL, "/", &save)) {
        if (!strcmp(tok, ".")) continue;
        if (!strcmp(tok, "..")) { if (np) np--; continue; }
        parts[np++] = tok;
    }

    /* An absolute path is re-rooted at its first component found in the game root. */
    int start = 0;
    if (absolute) {
        char real[256];
        start = np;   /* nothing matched: keep the whole tail relative to the root */
        for (int i = 0; i < np; i++) {
            if (match_component(".", parts[i], real, sizeof real)) { start = i; break; }
        }
        if (start == np) start = np ? np - 1 : 0;
    }

    char out[1024] = "";
    size_t len = 0;
    int missing = 0;
    for (int i = start; i < np; i++) {
        char real[256];
        const char *dir = len ? out : ".";
        const char *use = parts[i];
        if (!missing && match_component(dir, parts[i], real, sizeof real)) use = real;
        else missing = 1;
        len += (size_t)snprintf(out + len, sizeof out - len, "%s%s", len ? "/" : "", use);
        if (len >= sizeof out) break;
    }
    /* 7th Heaven mods (src/mods/mods.h): a replaced game file opens its replacement. */
    if (len && ff7_mods_redirect(out, buf, n)) {
        if (path_trace) fprintf(stderr, "[path] %s -> %s (mod)\n", guest, buf);
        return buf;
    }
    snprintf(buf, n, "%s", len ? out : ".");
    if (path_trace) fprintf(stderr, "[path] %s -> %s%s\n", guest, buf, missing ? " (new)" : "");
    return buf;
}

/* Referenced from ff7_hle_register so the linker takes this object out of the archive:
 * otherwise win32hle's weak hle_host_path (slashes only) is the one linked. */
void ff7_paths_link(void) {}
