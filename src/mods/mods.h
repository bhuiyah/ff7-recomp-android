/* mods.h - the 7th Heaven mod overlay (C API for the runtime's path mapper).
 *
 * A profile (written by the launcher) lists mods in priority order -- the first that
 * provides a file wins, as in 7th Heaven -- each an .iro archive or a folder, with its
 * option choices:
 *
 *   <Profile Language="en">
 *     <Mod Path="mods/IFRIT.iro"> <Option ID="SomeOption" Value="1"/> </Mod>
 *     <Mod Path="mods/NinostyleChibi"/>
 *   </Profile>
 *
 * Paths are relative to the profile's folder or absolute. Each mod's mod.xml decides which
 * of its folders are active (ModFolder / Conditional with ActiveWhen); the files in them
 * replace game files with the same path relative to one of the folders 7th Heaven watches:
 * data/ (and data/lang-<language>/ for the 2026 edition), FFNx's mods/Textures/, and the
 * game root (FFNx's direct/ and other root files).
 *
 * Archive entries are extracted on first use into the cache folder (reused while the
 * archive is unchanged), so every consumer -- the game's CreateFile, FFNx's fopen, FFmpeg --
 * opens an ordinary file.
 */
#ifndef FF7_MODS_H
#define FF7_MODS_H
#ifdef __cplusplus
extern "C" {
#endif

/* Load the profile (0 on success; a missing profile file = no mods, also 0). The report
 * (mods, active folders, unsupported parts) goes to stderr. */
int ff7_mods_load(const char *profile_path, const char *cache_dir);

/* `rel` is a path relative to the game root ('/' separators, any case). If an active mod
 * replaces it, write the host path of the replacement into buf and return buf; else NULL. */
const char *ff7_mods_redirect(const char *rel, char *buf, unsigned n);

/* Number of game paths the loaded mods replace (0 = no mods). */
unsigned ff7_mods_count(void);

#ifdef __cplusplus
}
#endif
#endif
