"""One-shot source edits to FFNx's FF7 files for the 64-bit guest boundary (the result is
kept in patches/ffnx/0001-*.patch; re-running is a no-op). Each edit must match exactly
once (or already be applied).

usage: py -I tools/ffnx_edit_android.py
"""
import os
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
]


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
    print(f'{changed} edits applied ({len(EDITS)} total)')


if __name__ == '__main__':
    main()
