"""One-shot source edits to FFNx's FF7 files for the 64-bit guest boundary (the result is
kept in patches/ffnx/0001-*.patch; re-running is a no-op). Each edit must match exactly
once (or already be applied).

usage: py -I tools/ffnx_edit_android.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'external', 'FFNx', 'src')

EDITS = [
    # Host variables that hold *game* functions must be gfn (calling a guest VA as native
    # code would jump to a 32-bit address).
    ('ff7/graphics.cpp',
     '\tvoid (*root_animation_sub)(struct matrix *, struct anim_frame *, struct anim_header *, struct hrc_data *);\n'
     '\tvoid (*frame_animation_sub)(uint32_t, struct matrix *, vector3<float> *, struct anim_frame *, struct anim_header *, struct hrc_bone *, struct hrc_data *);',
     '\tdecltype(ff7_externals.root_animation) root_animation_sub;   // FF7Recomp: game functions\n'
     '\tdecltype(ff7_externals.frame_animation) frame_animation_sub;', 'all'),
    ('ff7/graphics.cpp', 'external_mesh = reinterpret_cast<ExternalMesh*>(polygon_set->polygon_data->field_48);',
     'external_mesh = (ExternalMesh*)(polygon_set->polygon_data->field_48);'),
    ('ff7/graphics.cpp', 'auto externalMesh = reinterpret_cast<ExternalMesh*>(polygon_set->polygon_data->field_48);',
     'auto externalMesh = (ExternalMesh*)(polygon_set->polygon_data->field_48);'),
    ('ff7/graphics.cpp', '\tstd::string animFullName = anim_header->file.pc_name;',
     '\tstd::string animFullName = (const char *)anim_header->file.pc_name;'),
    # auto* cannot deduce from a guest pointer.
    ('ff7/field/kawai.cpp', '                    auto* rsd = bone.rsd_array[j].rsd_data;',
     '                    auto rsd = bone.rsd_array[j].rsd_data;'),
    ('ff7/field/kawai.cpp', '                    const auto* rsd = bone.rsd_array[j].rsd_data;',
     '                    const auto rsd = bone.rsd_array[j].rsd_data;'),
    ('ff7/field/kawai.cpp', 'source_colors = {reinterpret_cast<const uint32_t*>(polygon->vertexcolordata) +',
     'source_colors = {(const uint32_t*)(polygon->vertexcolordata) +'),
    ('ff7/field/kawai.cpp',
     '        std::vector<uint32_t> indices(primitive->indices, primitive->indices + primitive->indexcount);\n'
     '        auto geometry = process_kawai_geometry({primitive->vertices, primitive->vertexcount}, normals, indices, source_colors, light);',
     '        std::vector<uint32_t> indices((uint32_t*)primitive->indices, (uint32_t*)primitive->indices + primitive->indexcount);\n'
     '        auto geometry = process_kawai_geometry({(nvertex*)primitive->vertices, primitive->vertexcount}, normals, indices, source_colors, light);'),
    ('ff7/field/kawai_eyetx.cpp',
     '        p_hundred *left_eye = cached_eyes ? ff7_model_data[blink_data->model_id].left_eye_tex : field_anim_data->static_left_eye_tex;\n'
     '        p_hundred *right_eye = cached_eyes ? ff7_model_data[blink_data->model_id].right_eye_tex : field_anim_data->static_right_eye_tex;',
     '        p_hundred *left_eye = cached_eyes ? (p_hundred *)ff7_model_data[blink_data->model_id].left_eye_tex : (p_hundred *)field_anim_data->static_left_eye_tex;\n'
     '        p_hundred *right_eye = cached_eyes ? (p_hundred *)ff7_model_data[blink_data->model_id].right_eye_tex : (p_hundred *)field_anim_data->static_right_eye_tex;'),
    ('ff7/field/model.cpp', 'int model_id = std::distance(*ff7_externals.field_event_data_ptr, field_event_model);',
     'int model_id = std::distance((field_event_data *)*ff7_externals.field_event_data_ptr, field_event_model);'),
    ('ff7/field/opcode.cpp', '        auto *field_global_data_ptr = *ff7_externals.field_global_object_ptr;',
     '        auto field_global_data_ptr = *ff7_externals.field_global_object_ptr;'),
    # FFNx's own object stored in a game field: allocate it where a 32-bit address can
    # reach it, and free it the same way (it was new'd, so not the game's free).
    ('ff7/loaders.cpp', '\t\tExternalMesh* externalModel = new ExternalMesh;',
     '#ifdef FFNX_ANDROID\n'
     '\t\t// FF7Recomp: stored in polygon_data.field_48 (4 bytes): must live in guest memory.\n'
     '\t\tExternalMesh* externalModel = new (guest_calloc(1, sizeof(ExternalMesh))) ExternalMesh;\n'
     '#else\n'
     '\t\tExternalMesh* externalModel = new ExternalMesh;\n'
     '#endif'),
    ('ff7/loaders.cpp',
     '\t\tExternalMesh* pExternalMesh = reinterpret_cast<ExternalMesh*>(ret->field_48);\n\t\texternal_free(pExternalMesh);',
     '\t\tExternalMesh* pExternalMesh = (ExternalMesh*)(ret->field_48);\n'
     '#ifdef FFNX_ANDROID\n'
     '\t\tpExternalMesh->~ExternalMesh();\n\t\tguest_free(pExternalMesh);\n'
     '#else\n'
     '\t\texternal_free(pExternalMesh);\n'
     '#endif'),
    # FFNx writes into its own std::array with memcpy_code (an address cast to uint32_t);
    # on a 64-bit host that truncates the address. Plain memcpy into host memory.
    ('ff7/multilanguage.cpp',
     '\t\tmemcpy_code(reinterpret_cast<uint32_t>(wide_speed_args.data() + 2), &speed_offset, sizeof(speed_offset));\n'
     '\t\tmemcpy_code(reinterpret_cast<uint32_t>(wide_speed_args.data() + 6), const_cast<uint8_t *>(original + 3), 5);',
     '\t\tmemcpy(wide_speed_args.data() + 2, &speed_offset, sizeof(speed_offset));   // FF7Recomp: host array\n'
     '\t\tmemcpy(wide_speed_args.data() + 6, const_cast<uint8_t *>(original + 3), 5);'),
    ('ff7/multilanguage.cpp', '{ reinterpret_cast<uint32_t>(ff7_externals.draw_string_from_buffer_sub_6F5B03), ',
     '{ (uint32_t)(ff7_externals.draw_string_from_buffer_sub_6F5B03), '),
    ('ff7/universal_buttons.cpp', '      const uintptr_t buffer_address = reinterpret_cast<uintptr_t>(buffer);',
     '      const uintptr_t buffer_address = (uintptr_t)(buffer);'),
    ('ff7/universal_buttons.cpp', '      const uintptr_t window_buffer_address = reinterpret_cast<uintptr_t>(window.text_buffer);',
     '      const uintptr_t window_buffer_address = (uintptr_t)(window.text_buffer);'),
    ('ff7/universal_buttons.cpp', '      ff7_externals.sub_671082(&ff7_externals.menu_jafont_1_graphics_object);',
     '      ff7_externals.sub_671082((gptr<ff7_graphics_object> *)&ff7_externals.menu_jafont_1_graphics_object);   // FF7Recomp: JP edition only'),
    # The game's keyboard device is a guest COM object; on Android keys come from the
    # platform input state instead (src/android/ff7_input.cpp).
    ('input.cpp', '        if (keyboard_device->GetDeviceState(256, keys) == 0)\n            return keys;',
     '#ifdef FFNX_ANDROID\n'
     '        if (ff7_platform_keyboard_state(keys) == 0)\n            return keys;\n'
     '#else\n'
     '        if (keyboard_device->GetDeviceState(256, keys) == 0)\n            return keys;\n'
     '#endif'),
    ('input.cpp', 'if (common_externals.dinput_acquire_keyboard() != 0 && keyboard_device->GetDeviceState(256, keys) != 0)',
     '#ifdef FFNX_ANDROID\n        if (0)\n#else\n        if (common_externals.dinput_acquire_keyboard() != 0 && keyboard_device->GetDeviceState(256, keys) != 0)\n#endif'),
    ('overlay.cpp', '    ImGui::GetMainViewport()->PlatformHandleRaw = gameHwnd;',
     '    ImGui::GetMainViewport()->PlatformHandleRaw = (void *)(uintptr_t)gameHwnd;'),
    ('ff7/japanese_text.cpp', '  struct _stat64 file_status;\n  if (_stat64(path, &file_status) != 0)',
     '  struct stat file_status;\n  if (stat(path, &file_status) != 0)'),
    ('input.cpp', '#include "input.h"',
     '#include "input.h"\n#ifdef FFNX_ANDROID\n#include "ff7_boot.h"   // FF7Recomp: ff7_platform_keyboard_state\n#endif'),
    ('external_mesh.cpp', '\tcgltf_options options = {0};', '\tcgltf_options options = {};'),
    # The game writes a pointer through out_buffer (a guest cell from its caller).
    ('ff7/battle/battle.cpp',
     '\t\tint ret = ff7_externals.engine_load_bin_file_sub_419210(filename, offset, size, out_buffer, callback);',
     '\t\tint ret = ff7_externals.engine_load_bin_file_sub_419210(filename, offset, size, out_buffer, callback);   // FF7Recomp: out_buffer is a guest cell'),
    ('ff7/battle/battle.cpp', '\tint load_scene_bin_chunk(char *filename, int offset, int size, char **out_buffer, void (*callback)(void))',
     '\tint load_scene_bin_chunk(char *filename, int offset, int size, gptr<char> *out_buffer, void (*callback)(void))'),
    ('ff7/battle/defs.h', '    int load_scene_bin_chunk(char *filename, int offset, int size, char **out_buffer, void (*callback)(void));',
     '    int load_scene_bin_chunk(char *filename, int offset, int size, guest::gptr<char> *out_buffer, void (*callback)(void));'),
    # Replacement functions the game calls with pointers to its own pointer cells.
    ('ff7/battle/defs.h', '    void load_battle_stage(int param_1, int battle_location_id, int **param_3);',
     '    void load_battle_stage(int param_1, int battle_location_id, guest::gptr<int> *param_3);'),
    ('ff7/battle/battle.cpp', '\tvoid load_battle_stage(int param_1, int battle_location_id, int **param_3){',
     '\tvoid load_battle_stage(int param_1, int battle_location_id, gptr<int> *param_3){'),
    ('ff7/battle/animations.cpp', '    void run_animation_script(byte actorID, byte **ptrToScriptTable)',
     '    void run_animation_script(byte actorID, gptr<byte> *ptrToScriptTable)'),
    ('ff7/battle/animations.cpp', '    byte *getAnimScriptPointer(byte **ptrToScriptTable, battle_model_state &ownerModelState)',
     '    byte *getAnimScriptPointer(gptr<byte> *ptrToScriptTable, battle_model_state &ownerModelState)'),
    ('audio.cpp', 'vgm_log_set_callback(NULL, 100, 0, NxAudioEngineVgmstreamCallback);',
     'vgm_log_set_callback(NULL, 100, 0, (void *)NxAudioEngineVgmstreamCallback);'),
    ('movies.cpp', 'uint32_t ff7_prepare_movie(char *name, uint32_t loop, struct dddevice **dddevice, uint32_t dd2interface)',
     'uint32_t ff7_prepare_movie(char *name, uint32_t loop, gptr<struct dddevice> *dddevice, uint32_t dd2interface)'),
    ('steam.cpp', '\t\tMessageBoxA(nullptr, message, "Error", MB_ICONERROR | MB_OK);',
     '\t\tMessageBoxA(0, message, "Error", MB_ICONERROR | MB_OK);'),
    # The game unloads the object and clears the pointer cell: the cell must be guest memory.
    ('ff7/universal_buttons.cpp', '    ff7_externals.sub_671082(&resource.graphics_object);',
     '    {   // FF7Recomp: the game clears this pointer cell, so it must be guest memory\n'
     '      gptr<ff7_graphics_object> *cell = (gptr<ff7_graphics_object> *)guest_calloc(1, sizeof(uint32_t));\n'
     '      *cell = resource.graphics_object;\n'
     '      ff7_externals.sub_671082(cell);\n'
     '      resource.graphics_object = *cell;\n'
     '      guest_free(cell);\n'
     '    }'),
    # FFNx's per-texture GL state hangs off the game's texture_set (ogl.gl_set, 4 bytes):
    # allocate it in guest memory so its address fits; new/delete stay as written.
    ('gl.h', 'struct gl_texture_set\n{',
     'struct gl_texture_set\n{\n'
     '#ifdef FFNX_ANDROID\n'
     '\tstatic void *operator new(size_t n) { void *p = guest_calloc(1, n); if (!p) throw std::bad_alloc(); return p; }\n'
     '\tstatic void operator delete(void *p) { guest_free(p); }\n'
     '#endif'),
    # Named parameters with a nested function pointer defeat the scripted cast rewrite.
    ('ff7_data.h',
     '\tff7_externals.engine_load_bin_file_sub_419210 = (int (*)(char *filename, int offset, int size, char **out_buffer, void (*callback)(void)))(get_relative_call(ff7_externals.battle_scene_bin_sub_5D1050, 0x85));',
     '\tff7_externals.engine_load_bin_file_sub_419210 = get_relative_call(ff7_externals.battle_scene_bin_sub_5D1050, 0x85);   // FF7Recomp: address only'),
    # The exe is the 2026 rerelease's ff7_en (data/lang-*, music_ogg, ...). FFNx recognises
    # that edition by its install folder name ("workingdir"); on Android the game folder is the
    # app's files/game, so the edition is a given.
    # The game keeps the blend_mode pointer the driver returns: hand it a guest copy of the
    # table (read-only for both sides).
    ('common.cpp', '// called by the game to retrieve blend mode parameters\n',
     '#ifdef FFNX_ANDROID\n'
     'static struct blend_mode *ffnx_guest_blend_modes()\n{\n'
     '\tstatic struct blend_mode *copy = [] { auto *p = (struct blend_mode *)guest_calloc(1, sizeof(blend_modes)); memcpy(p, blend_modes, sizeof(blend_modes)); return p; }();\n'
     '\treturn copy;\n}\n'
     '#define blend_modes ffnx_guest_blend_modes()\n'
     '#endif\n'
     '// called by the game to retrieve blend mode parameters\n'),
    ('common.cpp', '\tffnx_unexpected("invalid blendmode: %u\\n", blend_mode);\n\n\treturn 0;\n}\n',
     '\tffnx_unexpected("invalid blendmode: %u\\n", blend_mode);\n\n\treturn 0;\n}\n#ifdef FFNX_ANDROID\n#undef blend_modes\n#endif\n'),
    # FFNx's "driver" heap shares the game's 32-bit address space on Windows, and FFNx hands
    # its blocks to game code (field files to lzss_decode, ...): the guest heap here.
    ('common.h', '#ifndef HEAP_DEBUG\n#define driver_malloc(x) malloc(x)\n#define driver_calloc(x, y) calloc(x, y)\n'
                 '#define driver_free(x) free(x)\n#define driver_realloc(x, y) realloc(x, y)\n',
     '#if defined(FFNX_ANDROID) && !defined(HEAP_DEBUG)\n'
     '#define driver_malloc(x) guest_malloc(x)\n#define driver_calloc(x, y) guest_calloc(x, y)\n'
     '#define driver_free(x) guest_free(x)\n#define driver_realloc(x, y) guest_realloc(x, y)\n'
     '#elif !defined(HEAP_DEBUG)\n#define driver_malloc(x) malloc(x)\n#define driver_calloc(x, y) calloc(x, y)\n'
     '#define driver_free(x) free(x)\n#define driver_realloc(x, y) realloc(x, y)\n'),
    # Facial textures draw a recoloured copy of a game primitive: its vertices go behind the
    # copy's gptr member, so they live in a guest scratch buffer.
    ('gl/gl.cpp', '\t\tfacial.vertices = vertices.data();',
     '#ifdef FFNX_ANDROID\n'
     '\t\tfacial.vertices = ffnx_guest_scratch<nvertex, __LINE__>(vertices.data(), vertices.size());\n'
     '#else\n'
     '\t\tfacial.vertices = vertices.data();\n'
     '#endif'),
    # Button prompts (universal_buttons.cpp) clone a game texture_set and its handle array
    # and hand them to the game's renderer: allocate both in guest memory.
    ('ff7.h', 'struct ff7_texture_set\n{\n',
     'struct ff7_texture_set\n{\n'
     '#ifdef FFNX_ANDROID\n'
     '\tstatic void *operator new(size_t n) { void *p = guest_calloc(1, n); if (!p) throw std::bad_alloc(); return p; }\n'
     '\tstatic void operator delete(void *p) { guest_free(p); }\n'
     '#endif\n'),
    ('ff7/universal_buttons.cpp', '  private_texture_set->texturehandle = new uint32_t[texture_count];',
     '#ifdef FFNX_ANDROID\n'
     '  private_texture_set->texturehandle = (uint32_t *)guest_calloc(texture_count, sizeof(uint32_t));\n'
     '#else\n'
     '  private_texture_set->texturehandle = new uint32_t[texture_count];\n'
     '#endif'),
    ('ff7/universal_buttons.cpp', '      delete[] private_texture_set->texturehandle;',
     '#ifdef FFNX_ANDROID\n'
     '      guest_free(private_texture_set->texturehandle);\n'
     '#else\n'
     '      delete[] private_texture_set->texturehandle;\n'
     '#endif'),
    # FFNx's get_keyboard_state replacement hands the game its key array: keep it in guest
    # memory (allocated on first use; the guest heap exists only once the game boots).
    ('input.cpp', 'byte keys[256];\n',
     '#ifdef FFNX_ANDROID\n'
     'static byte *ffnx_guest_keys() { static byte *k = (byte *)guest_calloc(1, 256); return k; }\n'
     '#define keys ffnx_guest_keys()\n'
     '#else\n'
     'byte keys[256];\n'
     '#endif\n'),
    # FFmpeg opens movie files itself: give it the host path of the game's movie name.
    ('video/movies.cpp', '\tif(avformat_open_input(&format_ctx, name, NULL, NULL))',
     '#ifdef FFNX_ANDROID\n'
     '\tchar ffnx_host_name[1024];\n'
     '\tname = hle_host_path(name, ffnx_host_name, sizeof(ffnx_host_name));\n'
     '#endif\n'
     '\tif(avformat_open_input(&format_ctx, name, NULL, NULL))'),
    ('common.cpp', '\t\t\t\tif (strstr(basedir, "workingdir") != NULL)',
     '\t\t\t\tif (FFNX_ANDROID || strstr(basedir, "workingdir") != NULL)   // FF7Recomp: 2026 rerelease exe'),
]


# patch_code_dword/uint(addr, (DWORD)&fn) with a native function or object: the value the
# game reads must be a guest address (ffnx_guest_va, src/ffnx_port/include/ffnx_guest.h).
PATCH_VA_RE = re.compile(r'(patch_code_(?:dword|uint)\((?:[^;]*?)),\s*\((?:DWORD|uint32_t)\)\s*(&?)([A-Za-z_][A-Za-z_0-9:]*)\)\s*;')


def rewrite_patch_values():
    n = 0
    for dirpath, _, files in os.walk(SRC):
        rel_dir = os.path.relpath(dirpath, SRC).replace(os.sep, '/')
        if rel_dir.startswith('ff8'):
            continue
        for f in files:
            if not f.endswith('.cpp') or f.startswith('ff8'):
                continue
            path = os.path.join(dirpath, f)
            raw = open(path, 'rb').read().decode('utf-8', errors='surrogateescape')
            new, k = PATCH_VA_RE.subn(lambda m: f'{m.group(1)}, ffnx_guest_va({m.group(2)}{m.group(3)}));', raw)
            if k:
                open(path, 'wb').write(new.encode('utf-8', errors='surrogateescape'))
                n += k
    return n


# Game functions FFNx keeps in native function-pointer variables:
#   int (*opcode_old_pc)();  ...  opcode_old_pc = (int (*)())ff7_externals.opcode_pc;
# Calling such a pointer would jump to x86 code. The variable becomes a guest::gfn<Sig>
# (a call into the lifted game) and the assignment a from_va of the address.
FNPTR_ASSIGN_RE = re.compile(
    r'(?P<lead>(?:^|[;{}]|\n)\s*)(?P<decl>[A-Za-z_][\w:]*\s*\*?\s*\(\s*\*\s*)?(?P<name>[A-Za-z_]\w*)(?(decl)\s*\)\s*\((?P<dargs>[^)]*)\))\s*=\s*'
    r'\(\s*(?P<ret>[A-Za-z_][\w:\s]*?\**)\s*\(\s*\*\s*\)\s*\((?P<args>[^)]*)\)\s*\)\s*(?P<expr>[^;]+);')
FNPTR_SKIP_DIRS = ('ff8', 'audio/vgmstream')


def fnptr_decl_re(name):
    return re.compile(r'(?P<lead>^[ \t]*(?:static\s+|extern\s+)?)(?P<ret>[A-Za-z_][\w:]*(?:\s*\*)*)\s*\(\s*\*\s*'
                      + re.escape(name) + r'\s*\)\s*\((?P<args>[^)]*)\)\s*(?P<init>=\s*[^;]*)?;', re.M)


def rewrite_fnptr_globals():
    files = []
    for dirpath, _, names in os.walk(SRC):
        rel_dir = os.path.relpath(dirpath, SRC).replace(os.sep, '/')
        if rel_dir.startswith(FNPTR_SKIP_DIRS):
            continue
        files += [os.path.join(dirpath, f) for f in names if f.endswith(('.cpp', '.h')) and not f.startswith('ff8')]
    text = {p: open(p, 'rb').read().decode('utf-8', errors='surrogateescape') for p in files}
    names, n = set(), 0
    for p, raw in text.items():
        def assign(m):
            nonlocal n
            n += 1
            sig = f'{m.group("ret").strip()}({m.group("args").strip()})'
            value = f'guest::gfn<{sig}>::from_va((uint32_t)({m.group("expr").strip()}))'
            if m.group('decl'):                       # local declaration with initialiser
                return f'{m.group("lead")}auto {m.group("name")} = {value};'
            names.add(m.group('name'))
            return f'{m.group("lead")}{m.group("name")} = {value};'
        text[p] = FNPTR_ASSIGN_RE.sub(assign, raw)
    for name in names:
        dre = fnptr_decl_re(name)
        for p, raw in text.items():
            def decl(m):
                nonlocal n
                n += 1
                init = ' = 0' if m.group('init') else ''
                return f'{m.group("lead")}guest::gfn<{m.group("ret").strip()}({m.group("args").strip()})> {name}{init};'
            text[p] = dre.sub(decl, raw)
    for p, raw in text.items():
        if raw != open(p, 'rb').read().decode('utf-8', errors='surrogateescape'):
            open(p, 'wb').write(raw.encode('utf-8', errors='surrogateescape'))
    return n


def main():
    changed = 0
    for edit in EDITS:
        rel, old, new = edit[:3]
        allow_many = len(edit) > 3 and edit[3] == 'all'
        path = os.path.join(SRC, rel)
        raw = open(path, 'rb').read().decode('utf-8', errors='surrogateescape')
        for o, n in ((old, new), (old.replace('\n', '\r\n'), new.replace('\n', '\r\n'))):
            if n in raw:
                break                    # already applied (replacements may contain the original)
            if raw.count(o) == 1 or (allow_many and raw.count(o) >= 1):
                raw = raw.replace(o, n)
                open(path, 'wb').write(raw.encode('utf-8', errors='surrogateescape'))
                changed += 1
                break
        else:
            sys.exit(f'{rel}: edit did not match exactly once:\n{old[:120]}')
    print(f'{changed} edits applied ({len(EDITS)} total); {rewrite_patch_values()} patch values made guest addresses; '
          f'{rewrite_fnptr_globals()} game-function pointer variables made guest calls')


if __name__ == '__main__':
    main()
