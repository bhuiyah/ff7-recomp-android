/* ffnx_file_paths.h - force-included into the audio libraries' file openers (vgmstream's
 * streamfile_stdio.c, SoLoud's soloud_file.cpp). FFNx hands them the game's own paths
 * ("C:\FF7\data\movies\opening.ogg", "<basedir>/data\sound\audio.dat", music/vgmstream/...),
 * so their fopen goes through the game's path mapping and the mod overlay, as FFNx's own
 * file calls do (ffnx_prelude.h). */
#ifndef FFNX_FILE_PATHS_H
#define FFNX_FILE_PATHS_H
#include <stdio.h>
#ifdef __cplusplus
extern "C" {
#endif
FILE *ffnx_fopen(const char *path, const char *mode);
#ifdef __cplusplus
}
#endif
#define fopen(p, m) ffnx_fopen(p, m)
#endif
