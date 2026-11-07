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
#include "win32hle.h"

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

const char *hle_host_path(const char *guest, char *buf, unsigned n) {
    if (path_trace < 0) path_trace = getenv("FF7_PATH_TRACE") != NULL;
    if (!guest || !n) return guest;

    char tmp[1024];
    snprintf(tmp, sizeof tmp, "%s", guest);
    for (char *p = tmp; *p; p++) if (*p == '\\') *p = '/';

    char *s = tmp;
    int absolute = 0;
    if (isalpha((unsigned char)s[0]) && s[1] == ':') { s += 2; absolute = 1; }
    if (*s == '/') absolute = 1;
    while (*s == '/') s++;

    /* Split into components. */
    char *parts[128];
    int np = 0;
    for (char *tok = strtok(s, "/"); tok && np < 128; tok = strtok(NULL, "/")) {
        if (!strcmp(tok, ".")) continue;
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
    snprintf(buf, n, "%s", len ? out : ".");
    if (path_trace) fprintf(stderr, "[path] %s -> %s%s\n", guest, buf, missing ? " (new)" : "");
    return buf;
}
