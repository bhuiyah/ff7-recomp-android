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

# FF7Recomp CRT filter, the end of FFNx's post shaders (FSHDRFlags.z: crt_filter).
CRT_BLOCK = (
    '\t// FF7Recomp: CRT filter (crt_filter): scanlines over the game\'s 240 lines, an\n'
    '\t// aperture-grille mask on the screen\'s pixels, a soft vignette.\n'
    '\tif (FSHDRFlags.z > 0.0) {\n'
    '\t\tfloat scan = 0.5 + 0.5 * cos(6.2831853 * v_texcoord0.y * 240.0);\n'
    '\t\tcolor.rgb *= mix(0.68, 1.0, scan);\n'
    '\t\tfloat px = mod(gl_FragCoord.x, 3.0);\n'
    '\t\tvec3 mask = px < 1.0 ? vec3(1.0, 0.8, 0.8) : (px < 2.0 ? vec3(0.8, 1.0, 0.8) : vec3(0.8, 0.8, 1.0));\n'
    '\t\tvec2 d = v_texcoord0.xy - vec2(0.5, 0.5);\n'
    '\t\tcolor.rgb = clamp(color.rgb * mask * 1.3 * (1.0 - 0.3 * dot(d, d)), 0.0, 1.0);\n'
    '\t}\n'
)

# FF7Recomp: the world map's sources use the world map's frame multiplier (60 FPS at most).
WORLD_MULT = (
    '#ifdef FFNX_ANDROID\n'
    'extern int ff7r_world_frame_multiplier;   // FF7Recomp: the world map runs at 60 in the 120 FPS mode\n'
    '#define common_frame_multiplier ff7r_world_frame_multiplier\n'
    '#endif\n'
)

EDITS = [
    # GitHub #42: the app sends the pad's A (the bottom button on an Android pad) as XInput B
    # (FF7 confirms with Circle, XInput B) and B as XInput A. The Xbox prompt icons name the
    # XInput button, so Config > Customize and the field prompts showed B for the button marked
    # A. On Android the two icons trade places: the icon names the button the player presses.
    ('ff7/universal_buttons.cpp',
     '  int column;\n  int row;\n  switch (button)\n',
     '  int column;\n  int row;\n'
     '  // FF7Recomp (#42): Circle (5) is the pad\'s A here, Cross (6) its B\n'
     '  if (atlas == prompt_atlas::xbox && (button == 5 || button == 6)) button = 11 - button;\n'
     '  switch (button)\n'),
    # GitHub #42: Config > Customize's icons took their row from the text's y ((y - 124) / 24),
    # but menu mods (Enhanced Stock UI) draw the rows a little higher: OK and CANCEL both
    # came out as row 0 and every row below showed the binding of the row above it. The game
    # draws one column's 14 bindings top to bottom, then the other's (0x6CDF54), so the row
    # is counted in drawing order instead.
    ('ff7/universal_buttons.cpp',
     '  const int row = (y - first_row_y) / row_height;\n'
     '  static constexpr int controller_column_x = 300;\n'
     '  const int column = x >= controller_column_x ? 1 : 0;\n',
     '  (void)row_height; (void)first_row_y;\n'
     '  static constexpr int controller_column_x = 300;\n'
     '  const int column = x >= controller_column_x ? 1 : 0;\n'
     '  // FF7Recomp (#42): the row in drawing order (a column\'s rows top to bottom), not from y\n'
     '  static int last_column = -1, last_y, counted_row;\n'
     '  counted_row = column != last_column || y <= last_y ? 0 : counted_row + 1;\n'
     '  last_column = column;\n'
     '  last_y = y;\n'
     '  const int row = counted_row;\n'),
    # GitHub #34: Config > Customize writes C:\FF7\ff7input.cfg, which a fresh install does not
    # have (Steam keeps it in the user folder, which this port has not). The Store edition's
    # redirection calls a missing file "required" and the open failed, so the button mapping
    # was lost at every launch. A file the game opens to write or create need not exist.
    ('ff7/file.cpp',
     '\t\tif (redirect_path_with_override(filename, _filename, sizeof(_filename)) == 1)\n'
     '\t\t{\n'
     '\t\t\tstrcpy(_filename, filename);\n'
     '\n'
     '\t\t\tgoto error;\n'
     '\t\t}',
     '\t\tif (redirect_path_with_override(filename, _filename, sizeof(_filename)) == 1)\n'
     '\t\t{\n'
     '\t\t\tstrcpy(_filename, filename);\n'
     '\n'
     '\t\t\t// FF7Recomp (#34): a file opened to write or create need not exist yet\n'
     '\t\t\tif (file_context->mode != FF7_FMODE_WRITE && file_context->mode != FF7_FMODE_CREATE) goto error;\n'
     '\t\t}'),
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
     '        std::vector<uint32_t> indices((WORD*)primitive->indices, (WORD*)primitive->indices + primitive->indexcount);\n'
     '        auto geometry = process_kawai_geometry({(nvertex*)primitive->vertices, primitive->vertexcount}, normals, indices, source_colors, light);'),
    # GitHub #37: the indices are 16-bit (WORD). Read as uint32_t, two indices became one
    # out-of-range index and every KAWAI-lit model was dropped ("KAWAI: vertex index ... exceeds
    # vertex count" in FFNx.log): the party was invisible in Sunken Gelnika. This entry corrects
    # trees that already carry the old uint32_t cast.
    ('ff7/field/kawai.cpp',
     '        std::vector<uint32_t> indices((uint32_t*)primitive->indices, (uint32_t*)primitive->indices + primitive->indexcount);',
     '        std::vector<uint32_t> indices((WORD*)primitive->indices, (WORD*)primitive->indices + primitive->indexcount);'),
    ('ff7/field/kawai_eyetx.cpp',
     '        p_hundred *left_eye = cached_eyes ? ff7_model_data[blink_data->model_id].left_eye_tex : field_anim_data->static_left_eye_tex;\n'
     '        p_hundred *right_eye = cached_eyes ? ff7_model_data[blink_data->model_id].right_eye_tex : field_anim_data->static_right_eye_tex;',
     '        p_hundred *left_eye = cached_eyes ? (p_hundred *)ff7_model_data[blink_data->model_id].left_eye_tex : (p_hundred *)field_anim_data->static_left_eye_tex;\n'
     '        p_hundred *right_eye = cached_eyes ? (p_hundred *)ff7_model_data[blink_data->model_id].right_eye_tex : (p_hundred *)field_anim_data->static_right_eye_tex;'),
    # The expression textures' eye file name goes to the game in a struct field (4 bytes): a
    # guest copy of the name, not the host string (crash entering towns with NinoStyle Chibi
    # models: GitHub issue #3).
    ('ff7/field/kawai_eyetx.cpp', '                eye_data.static_left_eye_filename = filename.data();',
     '#ifdef FFNX_ANDROID\n'
     '                eye_data.static_left_eye_filename = guest::ptr_of_raw<char>(guest::guest_string(filename.c_str()));   // FF7Recomp\n'
     '#else\n'
     '                eye_data.static_left_eye_filename = filename.data();\n'
     '#endif'),
    # 120 FPS world map: the movement multiplier (2, divided by 4) is stepped 0/1 per frame,
    # but the game stores it only once, when the world map starts: on a 0 frame the player
    # turns but cannot walk. Step the variable itself as well.
    ('ff7/world/world.cpp',
     '            patch_divide_code<DWORD>(ff7_externals.world_init_variables_74E1E9 + 0x15D, common_frame_multiplier);\n',
     '            patch_divide_code<DWORD>(ff7_externals.world_init_variables_74E1E9 + 0x15D, common_frame_multiplier);\n'
     '#ifdef FFNX_ANDROID\n'
     '            ffnx_recomp::step_mirror(ff7_externals.world_init_variables_74E1E9 + 0x15D, (uint32_t)(uintptr_t)ff7_externals.world_movement_multiplier_DFC480);   // FF7Recomp\n'
     '#endif\n'),
    # Replacement textures in a format the GPU cannot sample (the BC formats of .dds files on
    # Mali and most Adreno 6xx GPUs): bgfx refuses them and the draw shows whatever texture was
    # bound before (Composition7's portraits showing the world map: GitHub issue #10). Decode
    # them to RGBA8 on the CPU first.
    ('renderer.cpp', '    bimg::ImageContainer* img = createImageContainer(filename);\n',
     '    bimg::ImageContainer* img = createImageContainer(filename);\n'
     '#ifdef FFNX_ANDROID\n'
     '    img = ffnx_android_sampleable(img);   // FF7Recomp\n'
     '#endif\n'),
    ('renderer.cpp', '    bimg::ImageContainer* img = createImageContainer(file);\n',
     '    bimg::ImageContainer* img = createImageContainer(file);\n'
     '#ifdef FFNX_ANDROID\n'
     '    img = ffnx_android_sampleable(img);   // FF7Recomp\n'
     '#endif\n'),
    ('renderer.cpp', 'bimg::ImageContainer* Renderer::createImageContainer(const char* filename, bimg::TextureFormat::Enum targetFormat)\n',
     '#ifdef FFNX_ANDROID\n'
     '// FF7Recomp: an image in a format this GPU cannot sample as a texture, decoded to RGBA8.\n'
     'static bimg::ImageContainer* ffnx_android_sampleable(bimg::ImageContainer* img)\n'
     '{\n'
     '    if (img == nullptr) return img;\n'
     '    const uint16_t need = img->m_cubeMap ? BGFX_CAPS_FORMAT_TEXTURE_CUBE : BGFX_CAPS_FORMAT_TEXTURE_2D;\n'
     '    if (bgfx::getCaps()->formats[img->m_format] & need) return img;\n'
     '    static bx::DefaultAllocator alloc;   // the image keeps its allocator: imageFree / the release callback use it\n'
     '    bimg::ImageContainer* rgba = bimg::imageConvert(&alloc, bimg::TextureFormat::RGBA8, *img, true);\n'
     '    if (rgba == nullptr) return img;\n'
     '    if (trace_all || trace_renderer) ffnx_trace("Renderer: %s texture decoded to RGBA8 (not supported by this GPU)\\n", bimg::getName(img->m_format));\n'
     '    bimg::imageFree(img);\n'
     '    return rgba;\n'
     '}\n'
     '#endif\n\n'
     'bimg::ImageContainer* Renderer::createImageContainer(const char* filename, bimg::TextureFormat::Enum targetFormat)\n'),
    # FFNx's WindowProc passes messages on to the game's own window procedure (a game address):
    # a guest call, not a native jump (crash when the game sends its window a message, such as
    # Quit from the menu: GitHub issue #7's log).
    ('common.cpp', '\treturn common_externals.engine_wndproc(hwnd, uMsg, wParam, lParam);\n',
     '#ifdef FFNX_ANDROID\n'
     '\treturn guest::gfn<LRESULT(HWND, UINT, WPARAM, LPARAM)>::from_va((uint32_t)(uintptr_t)common_externals.engine_wndproc)(hwnd, uMsg, wParam, lParam);   // FF7Recomp\n'
     '#else\n'
     '\treturn common_externals.engine_wndproc(hwnd, uMsg, wParam, lParam);\n'
     '#endif\n'),
    # Vulkan that does not start falls back to bgfx's Noop renderer, and FFNx then stops on a
    # missing shader file (GitHub issue #8). Say what happened instead, with bgfx's Vulkan
    # start-up traces (patches/bgfx/0004); FF7_TEST_VK_TRACE=1 prints them on any device.
    ('renderer.cpp', '    if (!bgfx::init(bgfxInit)) exit(1);\n',
     '    if (!bgfx::init(bgfxInit)) exit(1);\n'
     '#ifdef FFNX_ANDROID\n'
     '    if ((bgfx::getRendererType() != bgfx::RendererType::Vulkan && bgfx::getRendererType() != bgfx::RendererType::OpenGLES) || getenv("FF7_TEST_VK_TRACE"))   // FF7Recomp\n'
     '    {\n'
     '        fprintf(stderr, "[ffnx-android] renderer: %s. Vulkan start-up:\\n%s", bgfx::getRendererName(bgfx::getRendererType()), ff7_bgfx_vk_trace());\n'
     '        if (bgfx::getRendererType() != bgfx::RendererType::Vulkan && bgfx::getRendererType() != bgfx::RendererType::OpenGLES)\n'
     '        {\n'
     '            fprintf(stderr, "[ffnx-android] FATAL: the graphics (%s) could not start on this device (see above). Try the other renderer in Settings.\\n", renderer_backend == RENDERER_BACKEND_OPENGL ? "OpenGL ES" : "Vulkan");\n'
     '            fflush(stderr);\n'
     '            usleep(400000);\n'
     '            _exit(1);\n'
     '        }\n'
     '    }\n'
     '#endif\n'),
    # OpenGL ES (Settings > Renderer, requested in the comments: some Mali devices run FF7 better
    # on GL): FFNx's renderer_backend 1 (OpenGL) is bgfx's OpenGL ES on Android, with its own
    # shaders (*.gles.*, tools/compile_shaders.sh); either renderer is fine at start-up.
    ('renderer.cpp',
     '    case RENDERER_BACKEND_OPENGL:\n'
     '        ret = bgfx::RendererType::OpenGL;\n',
     '    case RENDERER_BACKEND_OPENGL:\n'
     '#ifdef FFNX_ANDROID\n'
     '        ret = bgfx::RendererType::OpenGLES;   // FF7Recomp: Android has OpenGL ES\n'
     '#else\n'
     '        ret = bgfx::RendererType::OpenGL;\n'
     '#endif\n'),
    ('renderer.cpp',
     '    case bgfx::RendererType::Vulkan:\n'
     '        currentRenderer = "Vulkan";\n'
     '        shaderSuffix = ".vk";\n'
     '        break;\n',
     '    case bgfx::RendererType::Vulkan:\n'
     '        currentRenderer = "Vulkan";\n'
     '        shaderSuffix = ".vk";\n'
     '        break;\n'
     '#ifdef FFNX_ANDROID\n'
     '    case bgfx::RendererType::OpenGLES:   // FF7Recomp\n'
     '        currentRenderer = "OpenGL ES";\n'
     '        shaderSuffix = ".gles";\n'
     '        break;\n'
     '#endif\n'),
    ('renderer.cpp', 'void Renderer::init()\n{\n',
     '#ifdef FFNX_ANDROID\n'
     'extern "C" const char* ff7_bgfx_vk_trace();   // FF7Recomp: patches/bgfx/0004\n'
     '#endif\n\n'
     'void Renderer::init()\n{\n'),
    # CRT filter (Settings > Display; requested in the comments): crt_filter in FFNx.toml,
    # passed to the post shaders in FSHDRFlags.z (unused there): scanlines over the game's 240
    # lines, a light aperture-grille mask on the screen's pixels and a soft vignette.
    ('cfg.h', 'extern bool enable_ntscj_gamut_mode;\n',
     'extern bool enable_ntscj_gamut_mode;\nextern bool crt_filter;   // FF7Recomp\n'),
    ('cfg.cpp', 'bool enable_ntscj_gamut_mode;\n',
     'bool enable_ntscj_gamut_mode;\nbool crt_filter;   // FF7Recomp\n'),
    ('cfg.cpp', '\tenable_ntscj_gamut_mode = config["enable_ntscj_gamut_mode"].value_or(false);\n',
     '\tenable_ntscj_gamut_mode = config["enable_ntscj_gamut_mode"].value_or(false);\n'
     '\tcrt_filter = config["crt_filter"].value_or(false);   // FF7Recomp\n'),
    ('renderer.cpp',
     '    internalState.FSHDRFlags = {\n'
     '        (float)internalState.bIsHDR,\n'
     '        (float)hdr_max_nits,\n'
     '        NULL,\n',
     '    internalState.FSHDRFlags = {\n'
     '        (float)internalState.bIsHDR,\n'
     '        (float)hdr_max_nits,\n'
     '#ifdef FFNX_ANDROID\n'
     '        crt_filter ? 1.0f : 0.0f,   // FF7Recomp: the post shaders\' CRT filter\n'
     '#else\n'
     '        NULL,\n'
     '#endif\n'),
    ('../misc/FFNx.post.frag', '\tgl_FragColor = color;\n', CRT_BLOCK + '\tgl_FragColor = color;\n'),
    ('../misc/FFNx.post.ntscj.frag', '\tgl_FragColor = color;\n', CRT_BLOCK + '\tgl_FragColor = color;\n'),
    # The game's only _beginthread (0x68944F, battle effects such as Climhazzard: GitHub/Reddit
    # reports) starts 0x6893CA, which sets the effect object's state to 2, runs its job
    # ([obj+8]), sets state 3 and ends the thread. The runtime has no guest threads
    # (CreateThread is not implemented: the game aborted). Run the job in place, as FFNx
    # already does for the magic thread (magic_thread_start); the handle is never used.
    ('ff7_opengl.cpp', '\treplace_function(ff7_externals.magic_thread_start, ff7::battle::magic_thread_start);\n',
     '\treplace_function(ff7_externals.magic_thread_start, ff7::battle::magic_thread_start);\n'
     '#ifdef FFNX_ANDROID\n'
     '\tif (*(const uint8_t *)(uintptr_t)0x68944F == 0xE8 && *(const uint32_t *)(uintptr_t)0x68944B == 0x6893CA)   // FF7Recomp: US 1.02 layout\n'
     '\t\treplace_call_function(0x68944F, ff7r_effect_thread_sync);\n'
     '#endif\n'),
    ('ff7_opengl.cpp', 'void ff7_init_hooks(struct game_obj *_game_object)\n',
     '#ifdef FFNX_ANDROID\n'
     '// FF7Recomp: _beginthread(0x6893CA, stack, obj) run in place (see tools/ffnx_edit_android.py).\n'
     'static uint32_t ff7r_effect_thread_sync(uint32_t start, uint32_t stack_size, uint32_t obj)\n'
     '{\n'
     '\t(void)start; (void)stack_size;\n'
     '\tif (obj && *(const uint32_t *)(uintptr_t)(obj + 8))\n'
     '\t{\n'
     '\t\tguest::gfn<void(int, uint32_t)>::from_va(0x6893B6)(2, obj);\n'
     '\t\tguest::gfn<void()>::from_va(*(const uint32_t *)(uintptr_t)(obj + 8))();\n'
     '\t\tguest::gfn<void(int, uint32_t)>::from_va(0x6893B6)(3, obj);\n'
     '\t}\n'
     '\treturn 0x0000F00Du;   // a thread handle, never closed or waited on\n'
     '}\n'
     '#endif\n\n'
     'void ff7_init_hooks(struct game_obj *_game_object)\n'),
    # 120 FPS mode: the world map runs at 60 (its own multiplier, 2), as battles do. At x4 the
    # player's movement multiplier (2/4) alternates 0/1 per frame: Cloud moved every other frame
    # while the camera moved every frame, and looked blurred and too fast.
    ('common.cpp', 'int common_frame_multiplier = 1;\n',
     'int common_frame_multiplier = 1;\n'
     '#ifdef FFNX_ANDROID\n'
     'int ff7r_world_frame_multiplier = 1;   // FF7Recomp: the world map\'s (2 at most: 60 FPS)\n'
     '#endif\n'),
    ('ff7_opengl.cpp',
     '\t\t\t\tcommon_frame_multiplier = ff7_fps_limiter >= FPS_LIMITER_120FPS ? 4 : 2;   // FF7Recomp: 120 FPS mode\n',
     '\t\t\t\tcommon_frame_multiplier = ff7_fps_limiter >= FPS_LIMITER_120FPS ? 4 : 2;   // FF7Recomp: 120 FPS mode\n'
     '\t\t\t\t{ extern int ff7r_world_frame_multiplier; ff7r_world_frame_multiplier = common_frame_multiplier > 2 ? 2 : common_frame_multiplier; }   // FF7Recomp: world map at 60\n'),
    ('ff7/world/world.cpp', '#include "renderer.h"\n', '#include "renderer.h"\n' + WORLD_MULT),
    ('ff7/world/player.cpp', '#include "ff7/world/defs.h"\n', '#include "ff7/world/defs.h"\n' + WORLD_MULT),
    ('ff7/world/camera.cpp', '#include "defs.h"\n', '#include "defs.h"\n' + WORLD_MULT),
    ('ff7/world/renderer.cpp', '#include "../../lighting.h"\n', '#include "../../lighting.h"\n' + WORLD_MULT),
    ('ff7/time.cpp', 'namespace ff7\n',
     '#ifdef FFNX_ANDROID\nextern int ff7r_world_frame_multiplier;   // FF7Recomp: common.cpp\n#endif\nnamespace ff7\n'),
    ('ff7/time.cpp',
     '        if(mode->driver_mode == MODE_BATTLE)\n            modeFramesPerMinute *= battle_frame_multiplier;\n',
     '        if(mode->driver_mode == MODE_BATTLE)\n            modeFramesPerMinute *= battle_frame_multiplier;\n'
     '#ifdef FFNX_ANDROID\n'
     '        else if(mode->driver_mode == MODE_WORLDMAP)\n'
     '        modeFramesPerMinute *= 2 * ::ff7r_world_frame_multiplier;   // FF7Recomp\n'
     '#endif\n'),
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
    # The opening's music cue ("cmp edx, 0x6E0", movie frame 1760 at 15 FPS) is an immediate in
    # the field loop's code. A plain store into .text never reaches the lifted code, so a 30 FPS
    # movie started the field music halfway through (GitHub #27). patch_code_dword records it, and
    # the safety net (patch_recomp.cpp) runs the function with the new bytes. Scaled from the
    # original each time, so a second opening in one session does not compound it.
    ('movies.cpp',
     '\t\t\t*ff7_externals.opening_movie_music_start_frame = *ff7_externals.opening_movie_music_start_frame * movie_fps_ratio;\n',
     '\t\t\t// FF7Recomp: a code write (GitHub #27): through patch_code_dword, from the original value\n'
     '\t\t\tstatic DWORD ff7r_opening_music_frame = 0;\n'
     '\t\t\tif (!ff7r_opening_music_frame) ff7r_opening_music_frame = *ff7_externals.opening_movie_music_start_frame;\n'
     '\t\t\tpatch_code_dword((uint32_t)(uintptr_t)ff7_externals.opening_movie_music_start_frame, ff7r_opening_music_frame * movie_fps_ratio);\n'),
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
    # FFNx globals that FFNx's code patches make the game read: in the fixed guest block
    # (src/ffnx_port/ffnx_guest_static.cpp), so their addresses fit the patched operands.
    ('ff7/battle/animations.cpp', '    byte y_pos_offset_display_damage_30[] = {',
     '    static const byte y_pos_offset_display_damage_30_init[] = {'),
    ('ff7/battle/animations.cpp', '    byte y_pos_offset_display_damage_60[] = {',
     '    static const byte y_pos_offset_display_damage_60_init[] = {'),
    ('ff7/battle/animations.cpp', '    WORD ff7_odin_steel_frames_AEEC14;\n',
     '    auto &y_pos_offset_display_damage_30 = ffnx_guest_static_copy(y_pos_offset_display_damage_30_init);   // FF7Recomp: guest block\n'
     '    auto &y_pos_offset_display_damage_60 = ffnx_guest_static_copy(y_pos_offset_display_damage_60_init);\n'
     '    WORD &ff7_odin_steel_frames_AEEC14 = ffnx_guest_static<WORD>();\n'),
    ('ff7_opengl.cpp', '\t\t\t\tstatic const double swirl_fps = 60.0;',
     '\t\t\t\tstatic const double &swirl_fps = ffnx_guest_static<double>(60.0);   // FF7Recomp: guest block'),
    ('sfx.cpp', 'uint32_t real_volume;\n',
     'uint32_t &real_volume = ffnx_guest_static<uint32_t>();   // FF7Recomp: guest block\n'),
    ('sfx.cpp', '\tstatic int32_t volume_table[128];',
     '\tstatic int32_t (&volume_table)[128] = ffnx_guest_static_zero<int32_t, 128>();   // FF7Recomp: guest block'),
    ('ff7/widescreen.cpp', 'int viewport_width_plus_x_widescreen_fix = 750;',
     'int &viewport_width_plus_x_widescreen_fix = ffnx_guest_static<int>(750);   // FF7Recomp: guest block'),
    ('widescreen.h', 'int wide_viewport_x = -107;\nint wide_viewport_y = 0;\nint wide_viewport_width = 854;\nint wide_viewport_height = 480;',
     '// FF7Recomp: guest block (the game reads these through FFNx\'s code patches)\n'
     'inline int &wide_viewport_x = ffnx_guest_static<int>(-107);\ninline int &wide_viewport_y = ffnx_guest_static<int>(0);\n'
     'inline int &wide_viewport_width = ffnx_guest_static<int>(854);\ninline int &wide_viewport_height = ffnx_guest_static<int>(480);'),
    ('ff7/battle/battle.cpp', '		effect100_data *(*pandora_box_native_main_loop)();',
     '		guest::gfn<effect100_data *()> pandora_box_native_main_loop;   // FF7Recomp: a game function'),
    ('ff7/battle/battle.cpp',
     '		pandora_box_native_main_loop = reinterpret_cast<decltype(pandora_box_native_main_loop)>(get_absolute_value(ff7_externals.pandora_box_skill_init_566871, 0x1F));\n'
     '		patch_code_dword(ff7_externals.pandora_box_skill_init_566871 + 0x1F, reinterpret_cast<uint32_t>(pandora_box_main_loop));',
     '		pandora_box_native_main_loop = guest::gfn<effect100_data *()>::from_va(get_absolute_value(ff7_externals.pandora_box_skill_init_566871, 0x1F));\n'
     '		patch_code_dword(ff7_externals.pandora_box_skill_init_566871 + 0x1F, ffnx_guest_va(&pandora_box_main_loop));'),
    ('ff7/battle/battle.cpp', '		patch_code_dword(resource + 4, reinterpret_cast<uint32_t>("DOMU1.TIM"));',
     '		patch_code_dword(resource + 4, guest::guest_string("DOMU1.TIM"));   // FF7Recomp: guest copy'),
    # 120 FPS mode (ff7_fps_limiter = 4) for 120 Hz screens such as the Thor's: field and
    # world map run 4 game ticks per original one (30 -> 120 FPS) through FFNx's own 60 FPS
    # machinery, whose shift-count patches (m/2 standing for log2 m) stay exact at x4.
    # Battle runs x8 (below); swirl, menus and minigames keep their 60 FPS settings.
    ('cfg.h', '#define FPS_LIMITER_60FPS 3\n',
     '#define FPS_LIMITER_60FPS 3\n#define FPS_LIMITER_120FPS 4   // FF7Recomp: field/world at 120, the rest as 60\n'),
    ('cfg.cpp', '\telse if (ff7_fps_limiter > FPS_LIMITER_60FPS) ff7_fps_limiter = FPS_LIMITER_60FPS;',
     '\telse if (ff7_fps_limiter > FPS_LIMITER_120FPS) ff7_fps_limiter = FPS_LIMITER_120FPS;'),
    ('ff7_opengl.cpp', '\t\t\t\tcommon_frame_multiplier = 2;\n',
     '\t\t\t\tcommon_frame_multiplier = ff7_fps_limiter >= FPS_LIMITER_120FPS ? 4 : 2;   // FF7Recomp: 120 FPS mode\n'),
    ('ff7/misc.cpp', '\t\tswitch (mode->driver_mode)\n\t\t{\n\t\tcase MODE_FIELD:\n\t\tcase MODE_WORLDMAP:\n\t\tcase MODE_BATTLE:',
     '\t\tswitch (mode->driver_mode)\n\t\t{\n\t\tcase MODE_FIELD:\n'
     '\t\t\tframerate = 30.0f * common_frame_multiplier;   // FF7Recomp: 60, or 120 in the 120 FPS mode\n'
     '\t\t\tbreak;\n'
     '\t\tcase MODE_WORLDMAP:\n'
     '\t\t\t{ extern int ff7r_world_frame_multiplier; framerate = 30.0f * ff7r_world_frame_multiplier; }   // FF7Recomp: 60 (also in the 120 FPS mode)\n'
     '\t\t\tbreak;\n\t\tcase MODE_BATTLE:'),
    # 120 FPS battles (docs/ROADMAP.md M5b): battle runs 8 ticks per original 15 FPS one.
    # FFNx's battle maths is written for x2/x4; at x8:
    #   - inexact per-frame steps (6/8) are stepped frame by frame (patch.h patch_divide_code,
    #     ffnx_recomp::step_fraction), including the battle menu's sub-step count (4/8);
    #   - shift counts written as `c -/+ m/2` (m/2 standing for log2 m) use log2 m;
    #   - 0x5BD22A/0x5BD251 `shl reg, 2` would need a shift of -1 (x0.5): they alternate
    #     between `shl reg, 0` (x1) and `and reg, 0` (x0), both 3 bytes;
    #   - the battle-enter camera wait (a byte the game counts down) would overflow at 61*8:
    #     it keeps the x4 count and its decrement alternates 1 / 0;
    #   - a summon wait multiplied as a byte is a 16-bit immediate: multiplied as a word;
    #   - display-damage bounce: a 120 FPS table; integer index maths reworked.
    ('ff7_opengl.cpp', '\t\t\tbattle_frame_multiplier = (ff7_fps_limiter == FPS_LIMITER_30FPS) ? 2 : 4;\n',
     '\t\t\tbattle_frame_multiplier = (ff7_fps_limiter == FPS_LIMITER_30FPS) ? 2 : (ff7_fps_limiter >= FPS_LIMITER_120FPS && getenv("FF7_BATTLE120") && atoi(getenv("FF7_BATTLE120")) ? 8 : 4);   // FF7Recomp: 120 FPS battles are opt-in until every effect is checked at x8\n'),
    # The battle loop runs the menu step 4x per 15 FPS frame and its LAST pass draws the battle
    # windows; stepped to 0/1 per frame at x8, every other frame drew no windows (they flicker,
    # and screenshots caught them gone). Keep one pass per frame, as at 60.
    ('ff7_opengl.cpp',
     '\t\t\tpatch_divide_code<byte>(ff7_externals.battle_fps_menu_multiplier, battle_frame_multiplier); // Works perfectly only in 30 FPS',
     '\t\t\tpatch_divide_code<byte>(ff7_externals.battle_fps_menu_multiplier, battle_frame_multiplier >= 8 ? 4 : battle_frame_multiplier); // Works perfectly only in 30 FPS (FF7Recomp: x8 keeps one pass per frame)'),
    ('ff7/misc.cpp', '\t\t\tbreak;\n\t\tcase MODE_BATTLE:\n\t\tcase MODE_SWIRL:',
     '\t\t\tbreak;\n\t\tcase MODE_BATTLE:\n'
     '\t\t\tframerate = 15.0f * battle_frame_multiplier;   // FF7Recomp: 60, or 120 in the 120 FPS mode\n'
     '\t\t\tbreak;\n\t\tcase MODE_SWIRL:'),
    # Save states: every texture set is registered (src/ffnx_port/savestate_ffnx.cpp), so a
    # loaded state can give its sets fresh host state.
    ('common.cpp', '\t\tVRASS(texture_set, ogl.gl_set, new gl_texture_set());\n',
     '\t\tVRASS(texture_set, ogl.gl_set, new gl_texture_set());\n'
     '\t\tffnx_ss_texture_set_created((uint32_t)(uintptr_t)VPTR(texture_set));   // FF7Recomp: save states\n'),
    ('common.cpp', '\texternal_free(VREF(texture_set, texturehandle));\n\tdelete VREF(texture_set, ogl.gl_set);\n',
     '\tffnx_ss_texture_set_destroyed((uint32_t)(uintptr_t)VPTR(texture_set));   // FF7Recomp: save states\n'
     '\texternal_free(VREF(texture_set, texturehandle));\n\tdelete VREF(texture_set, ogl.gl_set);\n'),
    ('common.cpp', 'void common_unload_texture(struct texture_set *texture_set)\n{\n',
     'extern "C" void ffnx_ss_texture_set_created(uint32_t ts);     // FF7Recomp: src/ffnx_port/savestate_ffnx.cpp\n'
     'extern "C" void ffnx_ss_texture_set_destroyed(uint32_t ts);\n\n'
     'void common_unload_texture(struct texture_set *texture_set)\n{\n'),
    # Save states (src/host/ff7_savestate.c): taken and applied as the driver flips a frame --
    # once per frame, after the mode's loop has returned, so no game-mode code is on the host
    # stack (ff7_savestate.c also checks the host stack depth).
    ('common.cpp', 'void common_flip(struct game_obj *game_object)\n{\n',
     'extern "C" void ff7_savestate_frame(void);   // FF7Recomp: src/host/ff7_savestate.c\n\n'
     'void common_flip(struct game_obj *game_object)\n{\n\tff7_savestate_frame();   // FF7Recomp: the save-state safe point\n'),
    ('ff7/misc.cpp', 'void ff7_limit_fps()\n{\n\tstatic time_t last_gametime;\n',
     'namespace ffnx_recomp { void step_code(); }   // FF7Recomp: src/ffnx_port/patch_recomp.cpp\n\n'
     'void ff7_limit_fps()\n{\n\tffnx_recomp::step_code();   // FF7Recomp: per-frame stepped code values (120 FPS mode)\n'
     '\tstatic time_t last_gametime;\n'),
    ('ff7/battle/animations.cpp', '    WORD &ff7_odin_steel_frames_AEEC14 = ffnx_guest_static<WORD>();\n',
     '    WORD &ff7_odin_steel_frames_AEEC14 = ffnx_guest_static<WORD>();\n'
     '    // FF7Recomp: 120 FPS battles; the 60 FPS bounce with every entry held for two frames.\n'
     '    static const byte y_pos_offset_display_damage_120_init[] = {0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, '
     '7, 7, 7, 7, 7, 7, 6, 6, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 4, 4, 4, 3, 3, 2, 2, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0};\n'
     '    auto &y_pos_offset_display_damage_120 = ffnx_guest_static_copy(y_pos_offset_display_damage_120_init);\n'
     '    static int ffnx_frame_shift(int m) { int s = 0; while (m > 1) { m >>= 1; s++; } return s; }   // log2\n'),
    ('ff7/battle/animations.cpp', 'int index = ((fn_data.n_frames * (4 / battle_frame_multiplier)) % offset_z_position.size());',
     'int index = ((fn_data.n_frames * 4 / battle_frame_multiplier) % offset_z_position.size());   // FF7Recomp: exact at x8'),
    ('ff7/battle/animations.cpp',
     '        int shiftValue = 2 - battle_frame_multiplier / 2;\n'
     '        return ((int)*ff7_externals.field_byte_DC0E11 >> shiftValue) + 4 * battle_frame_multiplier;',
     '        // FF7Recomp: x * m / 4 (the shift `2 - m/2` goes negative at x8)\n'
     '        return ((int)*ff7_externals.field_byte_DC0E11 * battle_frame_multiplier / 4) + 4 * battle_frame_multiplier;'),
    ('ff7/battle/animations.cpp',
     '        else if(battle_frame_multiplier == 4)\n        {\n'
     '            patch_code_dword(ff7_externals.display_battle_damage_5BB410 + 0x1E2, ffnx_guest_va(y_pos_offset_display_damage_60));\n'
     '            patch_code_dword(ff7_externals.display_battle_damage_5BB410 + 0x2D7, ffnx_guest_va(y_pos_offset_display_damage_60));\n'
     '        }\n',
     '        else if(battle_frame_multiplier == 4)\n        {\n'
     '            patch_code_dword(ff7_externals.display_battle_damage_5BB410 + 0x1E2, ffnx_guest_va(y_pos_offset_display_damage_60));\n'
     '            patch_code_dword(ff7_externals.display_battle_damage_5BB410 + 0x2D7, ffnx_guest_va(y_pos_offset_display_damage_60));\n'
     '        }\n'
     '        else if(battle_frame_multiplier == 8)   // FF7Recomp: 120 FPS battles\n        {\n'
     '            patch_code_dword(ff7_externals.display_battle_damage_5BB410 + 0x1E2, ffnx_guest_va(y_pos_offset_display_damage_120));\n'
     '            patch_code_dword(ff7_externals.display_battle_damage_5BB410 + 0x2D7, ffnx_guest_va(y_pos_offset_display_damage_120));\n'
     '        }\n'),
    ('ff7/battle/animations.cpp', ' - battle_frame_multiplier / 2)', ' - ffnx_frame_shift(battle_frame_multiplier))', 'all'),
    ('ff7/battle/animations.cpp', ' + battle_frame_multiplier / 2)', ' + ffnx_frame_shift(battle_frame_multiplier))', 'all'),
    ('ff7/battle/animations.cpp',
     '        patch_code_byte(ff7_externals.battle_sub_5BD050 + 0x1DC, 0x2 - ffnx_frame_shift(battle_frame_multiplier));\n'
     '        patch_code_byte(ff7_externals.battle_sub_5BD050 + 0x203, 0x2 - ffnx_frame_shift(battle_frame_multiplier));\n',
     '        if (battle_frame_multiplier >= 8)\n        {\n'
     '            // FF7Recomp: x0.5 per tick = x1 and x0 on alternate ticks\n'
     '            static const byte shl_edx_0[] = {0xC1, 0xE2, 0x00}, and_edx_0[] = {0x83, 0xE2, 0x00};\n'
     '            static const byte shl_eax_0[] = {0xC1, 0xE0, 0x00}, and_eax_0[] = {0x83, 0xE0, 0x00};\n'
     '            ffnx_recomp::step_alternate(ff7_externals.battle_sub_5BD050 + 0x1DA, shl_edx_0, and_edx_0, 3);\n'
     '            ffnx_recomp::step_alternate(ff7_externals.battle_sub_5BD050 + 0x201, shl_eax_0, and_eax_0, 3);\n'
     '        }\n        else\n        {\n'
     '            patch_code_byte(ff7_externals.battle_sub_5BD050 + 0x1DC, 0x2 - ffnx_frame_shift(battle_frame_multiplier));\n'
     '            patch_code_byte(ff7_externals.battle_sub_5BD050 + 0x203, 0x2 - ffnx_frame_shift(battle_frame_multiplier));\n'
     '        }\n'),
    ('ff7/battle/animations.cpp',
     '        patch_multiply_code<byte>(ff7_externals.run_summon_animations_5C0E4B + 0x15B, battle_frame_multiplier);\n',
     '        if (battle_frame_multiplier >= 8)   // FF7Recomp: a 16-bit immediate (45 * 8 > 255)\n'
     '            patch_multiply_code<WORD>(ff7_externals.run_summon_animations_5C0E4B + 0x15B, battle_frame_multiplier);\n'
     '        else\n'
     '            patch_multiply_code<byte>(ff7_externals.run_summon_animations_5C0E4B + 0x15B, battle_frame_multiplier);\n'),
    ('ff7/battle/camera.cpp',
     '        patch_multiply_code<byte>(ff7_externals.battle_sub_429AC0 + 0x152, battle_frame_multiplier);\n'
     '        patch_multiply_code<byte>(ff7_externals.battle_sub_429D8A + 0x1D8, battle_frame_multiplier);\n',
     '        if (battle_frame_multiplier >= 8)\n        {\n'
     '            // FF7Recomp: the battle-enter wait is a byte (61 * 8 > 255): keep the x4 count and\n'
     '            // count it down every other tick (battle_sub_42D992 + 0x1A: sub cl, 1 = 80 E9 01).\n'
     '            patch_multiply_code<byte>(ff7_externals.battle_sub_429AC0 + 0x152, battle_frame_multiplier / 2);\n'
     '            patch_multiply_code<byte>(ff7_externals.battle_sub_429D8A + 0x1D8, battle_frame_multiplier / 2);\n'
     '            static const byte one = 1, zero = 0;\n'
     '            ffnx_recomp::step_alternate(ff7_externals.battle_sub_42D992 + 0x1C, &one, &zero, 1);   // the imm8\n'
     '        }\n        else\n        {\n'
     '            patch_multiply_code<byte>(ff7_externals.battle_sub_429AC0 + 0x152, battle_frame_multiplier);\n'
     '            patch_multiply_code<byte>(ff7_externals.battle_sub_429D8A + 0x1D8, battle_frame_multiplier);\n'
     '        }\n'),
    # Battle animations at raised frame rates: FFNx's battle code takes the animation files to
    # have 4x the original frames (8x at 120 FPS battles) -- the 60 FPS animation mods -- and
    # advances each model's keyframe counter (battle_update_3d_model_data, `add reg, 1`) 60
    # times a second (+2 at 30 FPS, +1 at 60) or 120 (x8). With the game's own files every
    # battle model moved 4x fast. Each animation gets its in-between frames the first time it
    # plays (ffnx_smooth_animation, ff7/loaders.cpp; marked so it is done once).
    # The weapons (Cloud's sword, the soldiers' batons) are models of their own, drawn with the
    # body's frame counter from their own animations: those get their in-between frames as they
    # are drawn (the battle model draw calls of draw_3d_model, 0x5E0D3C / 0x5E0DDE), or a weapon
    # vanishes whenever the counter passes its original length.
    ('ff7/battle/animations.cpp',
     '    // FF7Recomp: in-between frames for the animation a battle model is about to play\n',
     '    // FF7Recomp: battle models and their weapons drawn from animations with in-between frames\n'
     '    static void battle_draw_3d_model_smooth(uint32_t frame, uint32_t anim, uint32_t pos, uint32_t hrc, uint32_t game)\n'
     '    {\n'
     '        const int factor = battle_frame_multiplier == 2 ? 4 : battle_frame_multiplier;\n'
     '        if (anim && factor > 1) ffnx_smooth_animation((struct anim_header *)(uintptr_t)anim, factor);\n'
     '        (guest::gfn<void(uint32_t, uint32_t, uint32_t, uint32_t, uint32_t)>::from_va(ff7_externals.draw_3d_model))(frame, anim, pos, hrc, game);\n'
     '    }\n\n'
     '    // FF7Recomp: in-between frames for the animation a battle model is about to play\n'),
    ('ff7/battle/animations.cpp',
     '                replace_call_function(site, battle_update_3d_model_data_smooth);\n',
     '                replace_call_function(site, battle_update_3d_model_data_smooth);\n'
     '        for (uint32_t site : {0x5E0D8Cu, 0x5E0DD2u, 0x5E0E57u, 0x5E0EC6u})   // FF7Recomp: battle model and weapon draws\n'
     '            if (*(const byte *)(uintptr_t)site == 0xE8 && get_relative_call(site, 0) == ff7_externals.draw_3d_model)\n'
     '                replace_call_function(site, battle_draw_3d_model_smooth);\n'),
    ('ff7/battle/animations.cpp', '#include "animations.h"\n',
     '#include "../defs.h"   // FF7Recomp: ffnx_smooth_animation\n#include "animations.h"\n'),
    ('ff7/battle/animations.cpp', '    void animations_hook_init()\n',
     '    // FF7Recomp: in-between frames for the animation a battle model is about to play\n'
     '    static void battle_update_3d_model_data_smooth(int actor, int arg2, int arg3)\n'
     '    {\n'
     '        const int factor = battle_frame_multiplier == 2 ? 4 : battle_frame_multiplier;\n'
     '        const short id = (short)actor;\n'
     '        if (factor > 1 && id >= 0 && id < 10)\n'
     '        {\n'
     '            const uint32_t state = 0xBE1178u + (uint32_t)id * 0x1AECu;   // 1.02 US battle_model_state\n'
     '            const uint32_t model = *(const uint32_t *)(uintptr_t)(state + 0xBA8);\n'
     '            const short idx = *(const short *)(uintptr_t)(state + 0xE);   // runningAnimIdx\n'
     '            uint32_t anim = 0;\n'
     '            if (model && idx >= 0)\n'
     '            {\n'
     '                if (id < 3 && idx >= 0x2C && idx < 0x34)   // the weapon animations (0x424B1A)\n'
     '                {\n'
     '                    const uint32_t arr = *(const uint32_t *)(uintptr_t)(model + 0x14);\n'
     '                    if (arr) anim = *(const uint32_t *)(uintptr_t)(arr + 4u * (uint32_t)(idx - 0x2C));\n'
     '                }\n'
     '                else if (idx < *(const int *)(uintptr_t)(model + 8))\n'
     '                {\n'
     '                    const uint32_t arr = *(const uint32_t *)(uintptr_t)(model + 0xC);\n'
     '                    if (arr) anim = *(const uint32_t *)(uintptr_t)(arr + 4u * (uint32_t)idx);\n'
     '                }\n'
     '            }\n'
     '            if (anim) ffnx_smooth_animation((struct anim_header *)(uintptr_t)anim, factor);\n'
     '        }\n'
     '        (guest::gfn<void(int, int, int)>::from_va(ff7_externals.battle_update_3d_model_data))(actor, arg2, arg3);\n'
     '    }\n\n'
     '    void animations_hook_init()\n'),
    ('ff7/battle/animations.cpp',
     '            patch_multiply_code<byte>(ff7_externals.battle_update_3d_model_data + 0x316, battle_frame_multiplier);\n'
     '        }\n',
     '            patch_multiply_code<byte>(ff7_externals.battle_update_3d_model_data + 0x316, battle_frame_multiplier);\n'
     '        }\n'
     '        // FF7Recomp: every call of battle_update_3d_model_data (1.02 US; each site checked)\n'
     '        for (uint32_t site : {0x4201C7u, 0x4245FEu, 0x4246DCu, 0x4247AAu, 0x4247E6u, 0x424800u, 0x424815u, 0x42483Au, 0x42487Du})\n'
     '            if (*(const byte *)(uintptr_t)site == 0xE8 && get_relative_call(site, 0) == ff7_externals.battle_update_3d_model_data)\n'
     '                replace_call_function(site, battle_update_3d_model_data_smooth);\n'),
    # ... and the ATB: the battle menu step runs once per frame at x8 as at 60 (fewer passes
    # leave frames without the battle windows, which its last pass queues), so every gauge filled
    # twice as fast. The ATB tick (1.02 US 0x4322AE, `add ecx, [ebp-0xC]`: gauge += speed) adds
    # the speed and +0 on alternate frames (`add ecx, 0`, also 3 bytes).
    ('ff7_opengl.cpp',
     '(FF7Recomp: x8 keeps one pass per frame)\n',
     '(FF7Recomp: x8 keeps one pass per frame)\n'
     '\t\t\tif (battle_frame_multiplier >= 8 && !memcmp((const void *)(uintptr_t)0x4322AE, "\\x03\\x4D\\xF4", 3))   // FF7Recomp: ATB at the 60 FPS rate\n'
     '\t\t\t{\n'
     '\t\t\t\tstatic const byte add_speed[] = {0x03, 0x4D, 0xF4}, add_zero[] = {0x83, 0xC1, 0x00};\n'
     '\t\t\t\tffnx_recomp::step_alternate(0x4322AE, add_speed, add_zero, 3);\n'
     '\t\t\t}\n'),
    # Field animations at 60 / 120 FPS: FFNx's field code takes the animation files to have
    # common_frame_multiplier times the original frames (CANIM/CANMX frame numbers are scaled by
    # it; PC players install 60 FPS animation mods), so with the game's own files every walk and
    # run cycle played 2x / 4x fast. Field animations get the in-between frames as they load:
    # original frame k becomes frame k * m, with interpolated poses (shortest-way angles) between.
    ('ff7/loaders.cpp', '// load battle HRC file (does not save modpath name)\n',
     '// FF7Recomp: in-between frames for field animations at raised frame rates\n'
     'static float ffnx_lerp_angle(float a, float b, float t)\n'
     '{\n'
     '\tfloat d = remainderf(b - a, 360.0f);   // in [-180, 180] for any difference (fmodf keeps a negative sign)\n'
     '\treturn a + d * t;\n'
     '}\n'
     '\n'
     '// Blend two Euler triples (rotation order middle axis `mid`, -1 unknown): the second is\n'
     '// taken in whichever of its two equivalent spellings lies nearer the first.\n'
     'static void ffnx_lerp_euler(float ax, float ay, float az, float bx, float by, float bz, int mid, float t, float *o)\n'
     '{\n'
     '\tconst float av[3] = { ax, ay, az }, bv[3] = { bx, by, bz };\n'
     '\tfloat alt[3], d1 = 0.0f, d2 = 0.0f;\n'
     '\tfor (int i = 0; i < 3; i++)\n'
     '\t{\n'
     '\t\talt[i] = i == mid ? 180.0f - bv[i] : bv[i] + 180.0f;\n'
     '\t\td1 += fabsf(remainderf(bv[i] - av[i], 360.0f));\n'
     '\t\td2 += fabsf(remainderf(alt[i] - av[i], 360.0f));\n'
     '\t}\n'
     '\tconst float *use = (mid >= 0 && d2 + 1.0f < d1) ? alt : bv;\n'
     '\tfor (int i = 0; i < 3; i++) o[i] = ffnx_lerp_angle(av[i], use[i], t);\n'
     '}\n'
     '\n'
     '// Every animation is done once: rotation_order[3] (padding) marks it.\n'
     'void ffnx_smooth_animation(struct anim_header *a, int m)\n'
     '{\n'
     '\tif (!a || (byte)a->rotation_order[3] == 0xA5) return;\n'
     '\tconst uint32_t n = a->num_frames, nb = a->num_bones;\n'
     '\tif (m < 2 || n < 2 || n > 4096 || nb > 128 || a->use_matrix_array || !a->frame_data || !a->anim_frames) return;\n'
     '\tconst uint32_t per0 = sizeof(struct anim_frame_header) + nb * sizeof(vector3<float>);\n'
     '\tstruct anim_frame *f0 = (struct anim_frame *)(uintptr_t)(uint32_t)a->anim_frames;\n'
     '\t// only the layout load_animation builds: one block of [header, bones] per frame\n'
     '\tif ((uint32_t)f0[0].header != (uint32_t)a->frame_data || (uint32_t)f0[1].header != (uint32_t)a->frame_data + per0 ||\n'
     '\t    (uint32_t)f0[0].data != (uint32_t)a->frame_data + sizeof(struct anim_frame_header)) return;\n'
     '\tconst uint32_t total = n * m;\n'
     '\tconst uint32_t per = sizeof(struct anim_frame_header) + nb * sizeof(vector3<float>);\n'
     '\tbyte *data = (byte *)external_calloc(per, total);\n'
     '\tstruct anim_frame *frames = (struct anim_frame *)external_calloc(sizeof(struct anim_frame), total);\n'
     '\tif (!data || !frames) return;\n'
     '\tstruct anim_frame *src = (struct anim_frame *)(uintptr_t)(uint32_t)a->anim_frames;\n'
     '\tfor (uint32_t j = 0; j < total; j++)\n'
     '\t{\n'
     '\t\tuint32_t k = j / m;\n'
     '\t\tfloat t = (float)(j % m) / (float)m;\n'
     '\t\tif (k >= n - 1) { k = n - 1; t = 0.0f; }   // the last frame is held (one-shot animations end on it)\n'
     '\t\tconst uint32_t k1 = k + 1 < n ? k + 1 : k;\n'
     '\t\tconst struct anim_frame_header *h0 = (const struct anim_frame_header *)(uintptr_t)(uint32_t)src[k].header;\n'
     '\t\tconst struct anim_frame_header *h1 = (const struct anim_frame_header *)(uintptr_t)(uint32_t)src[k1].header;\n'
     '\t\tconst vector3<float> *b0 = (const vector3<float> *)(uintptr_t)(uint32_t)src[k].data;\n'
     '\t\tconst vector3<float> *b1 = (const vector3<float> *)(uintptr_t)(uint32_t)src[k1].data;\n'
     '\t\tstruct anim_frame_header *h = (struct anim_frame_header *)(data + per * j);\n'
     '\t\tvector3<float> *b = (vector3<float> *)(data + per * j + sizeof(struct anim_frame_header));\n'
     '\t\tconst int mid = (byte)a->rotation_order[1] < 3 ? (byte)a->rotation_order[1] : -1;\n'
     '\t\tfloat e[3];\n'
     '\t\tffnx_lerp_euler(h0->root_rotation.x, h0->root_rotation.y, h0->root_rotation.z,\n'
     '\t\t                h1->root_rotation.x, h1->root_rotation.y, h1->root_rotation.z, mid, t, e);\n'
     '\t\th->root_rotation.x = e[0]; h->root_rotation.y = e[1]; h->root_rotation.z = e[2];\n'
     '\t\th->root_translation.x = h0->root_translation.x + (h1->root_translation.x - h0->root_translation.x) * t;\n'
     '\t\th->root_translation.y = h0->root_translation.y + (h1->root_translation.y - h0->root_translation.y) * t;\n'
     '\t\th->root_translation.z = h0->root_translation.z + (h1->root_translation.z - h0->root_translation.z) * t;\n'
     '\t\tfor (uint32_t i = 0; i < nb; i++)\n'
     '\t\t{\n'
     '\t\t\tffnx_lerp_euler(b0[i].x, b0[i].y, b0[i].z, b1[i].x, b1[i].y, b1[i].z, mid, t, e);\n'
     '\t\t\tb[i].x = e[0]; b[i].y = e[1]; b[i].z = e[2];\n'
     '\t\t}\n'
     '\t\tframes[j].header = h;\n'
     '\t\tframes[j].data = b;\n'
     '\t}\n'
     '\texternal_free((void *)(uintptr_t)(uint32_t)a->frame_data);\n'
     '\texternal_free((void *)(uintptr_t)(uint32_t)a->anim_frames);\n'
     '\ta->frame_data = data;\n'
     '\ta->anim_frames = frames;\n'
     '\ta->num_frames = total;\n'
     '\ta->rotation_order[3] = (char)0xA5;\n'
     '}\n'
     '\n'
     'struct anim_header *field_load_animation_smooth(struct file_context *file_context, char *filename)\n'
     '{\n'
     '\tstruct anim_header *ret = load_animation(file_context, filename);\n'
     '\tif (ret && ff7_fps_limiter >= FPS_LIMITER_60FPS && common_frame_multiplier > 1)\n'
     '\t\tffnx_smooth_animation(ret, common_frame_multiplier);\n'
     '\treturn ret;\n'
     '}\n'
     '\n'
     '// load battle HRC file (does not save modpath name)\n'),
    # Euler angles have two spellings of one orientation: with rotation order (a, m, c) the
    # triple (x, y, z) equals the one with 180 - v on the middle axis m and v + 180 on the other
    # two. Mod animations switch spelling for single keyframes (NinoStyle's Cloud idle: hip
    # (-89, -180, -180) then (269, 0, 0)), harmless when frames are shown as they are, but a
    # per-angle blend between the spellings swings the bone through 180 degrees: Cloud turned
    # around for a moment while standing. The blend now uses the spelling nearer the previous frame.
    ('ff7/loaders.cpp', '\treturn a + d * t;\n}\n\n// Every animation is done once',
     '\treturn a + d * t;\n}\n\n'
     '// Blend two Euler triples (rotation order middle axis `mid`, -1 unknown): the second is\n'
     '// taken in whichever of its two equivalent spellings lies nearer the first.\n'
     'static void ffnx_lerp_euler(float ax, float ay, float az, float bx, float by, float bz, int mid, float t, float *o)\n'
     '{\n'
     '\tconst float av[3] = { ax, ay, az }, bv[3] = { bx, by, bz };\n'
     '\tfloat alt[3], d1 = 0.0f, d2 = 0.0f;\n'
     '\tfor (int i = 0; i < 3; i++)\n'
     '\t{\n'
     '\t\talt[i] = i == mid ? 180.0f - bv[i] : bv[i] + 180.0f;\n'
     '\t\td1 += fabsf(remainderf(bv[i] - av[i], 360.0f));\n'
     '\t\td2 += fabsf(remainderf(alt[i] - av[i], 360.0f));\n'
     '\t}\n'
     '\tconst float *use = (mid >= 0 && d2 + 1.0f < d1) ? alt : bv;\n'
     '\tfor (int i = 0; i < 3; i++) o[i] = ffnx_lerp_angle(av[i], use[i], t);\n'
     '}\n\n'
     '// Every animation is done once'),
    ('ff7/loaders.cpp',
     '\t\th->root_rotation.x = ffnx_lerp_angle(h0->root_rotation.x, h1->root_rotation.x, t);\n'
     '\t\th->root_rotation.y = ffnx_lerp_angle(h0->root_rotation.y, h1->root_rotation.y, t);\n'
     '\t\th->root_rotation.z = ffnx_lerp_angle(h0->root_rotation.z, h1->root_rotation.z, t);\n',
     '\t\tconst int mid = (byte)a->rotation_order[1] < 3 ? (byte)a->rotation_order[1] : -1;\n'
     '\t\tfloat e[3];\n'
     '\t\tffnx_lerp_euler(h0->root_rotation.x, h0->root_rotation.y, h0->root_rotation.z,\n'
     '\t\t                h1->root_rotation.x, h1->root_rotation.y, h1->root_rotation.z, mid, t, e);\n'
     '\t\th->root_rotation.x = e[0]; h->root_rotation.y = e[1]; h->root_rotation.z = e[2];\n'),
    ('ff7/loaders.cpp',
     '\t\t\tb[i].x = ffnx_lerp_angle(b0[i].x, b1[i].x, t);\n'
     '\t\t\tb[i].y = ffnx_lerp_angle(b0[i].y, b1[i].y, t);\n'
     '\t\t\tb[i].z = ffnx_lerp_angle(b0[i].z, b1[i].z, t);\n',
     '\t\t\tffnx_lerp_euler(b0[i].x, b0[i].y, b0[i].z, b1[i].x, b1[i].y, b1[i].z, mid, t, e);\n'
     '\t\t\tb[i].x = e[0]; b[i].y = e[1]; b[i].z = e[2];\n'),
    # Shortest-way angle blend: fmodf of a difference below -540 is negative, so the blend went
    # the long way round (HD mod animations store unwrapped angles) and an idle loop turned the
    # model around for one in-between frame. (For trees edited before this fix.)
    ('ff7/loaders.cpp', '\tfloat d = fmodf(b - a + 540.0f, 360.0f) - 180.0f;\n',
     '\tfloat d = remainderf(b - a, 360.0f);   // in [-180, 180] for any difference (fmodf keeps a negative sign)\n'),
    ('ff7/defs.h', 'struct anim_header *load_animation(struct file_context *file_context, char *filename);\n',
     'struct anim_header *load_animation(struct file_context *file_context, char *filename);\n'
     'struct anim_header *field_load_animation_smooth(struct file_context *file_context, char *filename);   // FF7Recomp\n'
     'void ffnx_smooth_animation(struct anim_header *a, int m);   // FF7Recomp: in-between frames, m per original frame\n'),
    ('ff7_opengl.cpp', '\treplace_function(ff7_externals.load_animation, load_animation);\n',
     '\treplace_function(ff7_externals.load_animation, load_animation);\n'
     '\treplace_call_function(ff7_externals.field_load_animation + 0x16D, field_load_animation_smooth);   // FF7Recomp: field animations at 60 / 120 FPS\n'),
    # One-button movie skip on the Thor: Start while an FMV plays (FFNx's own skipMovies,
    # with its exclusions for scenes that must not be skipped). FFNx's L3-then-Square
    # shortcut stays as well.
    ('gamehacks.cpp', '\t\t\tif (gamepad.IsPressed(XINPUT_GAMEPAD_LEFT_THUMB)) // L2\n',
     '#ifdef FFNX_ANDROID\n'
     '\t\t\tif (!ff8 && ff7_externals.movie_object->is_playing && gamepad.IsPressed(XINPUT_GAMEPAD_START))\n'
     '\t\t\t{\n'
     '\t\t\t\tskipMovies();\n'
     '\t\t\t\treturn;\n'
     '\t\t\t}\n'
     '#endif\n'
     '\t\t\tif (gamepad.IsPressed(XINPUT_GAMEPAD_LEFT_THUMB)) // L2\n'),
    # Pointer cells in game memory read through T** (8 bytes here): 4-byte guest cells.
    ('sfx.cpp', '\tBYTE** sfx_tmp_volume = (BYTE**)(ff7_externals.config_menu_sub + ff7_externals.call_menu_sound_slider_loop_sfx_down + 0xA);',
     '\tgptr<BYTE>* sfx_tmp_volume = (gptr<BYTE>*)(ff7_externals.config_menu_sub + ff7_externals.call_menu_sound_slider_loop_sfx_down + 0xA);   // FF7Recomp: guest cell'),
    ('sfx.cpp', '\tuint32_t** flags = (uint32_t**)(ff7_externals.battle_clear_sound_flags + 5);',
     '\tgptr<uint32_t>* flags = (gptr<uint32_t>*)(ff7_externals.battle_clear_sound_flags + 5);   // FF7Recomp: guest cell'),
    ('sfx.cpp', '\tmemset((void*)*flags, 0, 5 * sizeof(uint32_t));',
     '\tmemset((uint32_t*)*flags, 0, 5 * sizeof(uint32_t));'),
    ('ff7/universal_buttons.cpp', '  byte*& buffer = *(byte**)((byte*)caller_frame + 0x14);\n  int button;\n  int byte_count;\n'
                                  '  if (!universal_buttons_parse_field_prompt(buffer, &button, &byte_count))',
     '  uint32_t& buffer = *(uint32_t*)((byte*)caller_frame + 0x14);   // FF7Recomp: guest pointer cell\n  int button;\n  int byte_count;\n'
     '  if (!universal_buttons_parse_field_prompt((const byte*)(uintptr_t)buffer, &button, &byte_count))'),
    ('ff7/japanese_text.cpp', '(*(ff7_graphics_object**)ff7_externals.menu_objects, game_object);',
     '((ff7_graphics_object*)*(gptr<ff7_graphics_object>*)ff7_externals.menu_objects, game_object);   // FF7Recomp: guest cell'),
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
    # Game lighting (per-vertex, on the GPU) needs normals, which FFNx recomputes on the CPU for
    # every model draw (~7% of the game process with ChaOS at 120 FPS). FF7 models keep their
    # vertices in model space (placed by matrices), so a model's normals rarely change: cache
    # them per vertex/index buffer and recompute only when a fingerprint of the positions changes.
    ('gl/gl.cpp', '#include <algorithm>\n', '#include <algorithm>\n#include <unordered_map>\n'),
    ('gl/gl.cpp',
     '\t// If the previous code was not able to fetch the model normal data, we have to calculate it on the CPU\n'
     '\tif (!has_model_data)\n',
     '#ifdef FFNX_ANDROID\n'
     '\tstd::vector<vector3<float>> *ffnx_cached = nullptr;\n'
     '\tif (!has_model_data && ip->vertexcount > 0)\n'
     '\t{\n'
     '\t\tconst uint64_t key = (uint64_t)(uintptr_t)&ip->vertices[0] * 0x9E3779B97F4A7C15ull ^\n'
     '\t\t\t((uint64_t)(uintptr_t)&ip->indices[0] << 1) ^ ((uint64_t)ip->vertexcount << 40) ^ ip->indexcount;\n'
     '\t\tuint32_t a = 0, b = 0, c = 0;   // three independent lanes: cheap next to the normals\n'
     '\t\tfor (uint32_t i = 0; i < ip->vertexcount; i++)\n'
     '\t\t{\n'
     '\t\t\tconst uint32_t *p = (const uint32_t *)&ip->vertices[i]._;\n'
     '\t\t\ta = ((a << 5) | (a >> 27)) ^ p[0];\n'
     '\t\t\tb = ((b << 7) | (b >> 25)) ^ p[1];\n'
     '\t\t\tc = ((c << 11) | (c >> 21)) ^ p[2];\n'
     '\t\t}\n'
     '\t\tconst uint64_t sig = ((uint64_t)a << 32 | b) ^ ((uint64_t)c * 0xC2B2AE3D27D4EB4Full);\n'
     '\t\tstatic std::unordered_map<uint64_t, std::pair<uint64_t, std::vector<vector3<float>>>> cache;\n'
     '\t\tif (cache.size() > 8192) cache.clear();\n'
     '\t\tauto &entry = cache[key];\n'
     '\t\tif (entry.first == sig && entry.second.size() == ip->vertexcount) { normals = entry.second; return; }\n'
     '\t\tentry.first = sig;\n'
     '\t\tffnx_cached = &entry.second;\n'
     '\t}\n'
     '#endif\n'
     '\t// If the previous code was not able to fetch the model normal data, we have to calculate it on the CPU\n'
     '\tif (!has_model_data)\n'),
    ('gl/gl.cpp',
     '\t\tfor (uint32_t idx = 0; idx < ip->vertexcount; idx++)\n'
     '\t\t{\n'
     '\t\t\tnormalize_vector(&normals[idx]);\n'
     '\t\t}\n'
     '\t}\n',
     '\t\tfor (uint32_t idx = 0; idx < ip->vertexcount; idx++)\n'
     '\t\t{\n'
     '\t\t\tnormalize_vector(&normals[idx]);\n'
     '\t\t}\n'
     '#ifdef FFNX_ANDROID\n'
     '\t\tif (ffnx_cached) *ffnx_cached = normals;\n'
     '#endif\n'
     '\t}\n'),
    # Movies decode on the CPU (no MediaCodec): upscaled 30 fps movie mods (SYW Unified FMV)
    # took 1.1 cores on one thread and ran the Thor's CPU to 70 C. Frame threads share the
    # work, so the cores run at lower clocks. The decode loop already handles a decoder that
    # holds frames back (EAGAIN); the last few frames of a movie stay in it (under 0.1 s).
    ('video/movies.cpp', '\tavcodec_parameters_to_context(codec_ctx, format_ctx->streams[videostream]->codecpar);\n',
     '\tavcodec_parameters_to_context(codec_ctx, format_ctx->streams[videostream]->codecpar);\n'
     '#ifdef FFNX_ANDROID\n'
     '\tcodec_ctx->thread_count = 3;\n'
     '\tcodec_ctx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;\n'
     '#endif\n'),
    # A movie the CPU decodes slower than it plays (30 fps Cosmos FMV on a phone, from the
    # comments: "the music gets out of sync on the first cutscene", the 15 fps version is fine):
    # each update decoded and uploaded every frame up to now before drawing one, took longer than
    # the last, and the picture slid ever further behind the music. Catch up instead: a frame
    # whose successor is already due is dropped before conversion and upload, and while behind
    # the decoder skips non-reference frames and the loop filter (over a second: keyframes only).
    ('video/movies.cpp',
     '\t\t\t\t// use a pointer to identify the AVFrame object we ultimately want to use\n',
     '#ifdef FFNX_ANDROID\n'
     '\t\t\t\t{   // FF7Recomp: catch up with the music when decoding falls behind\n'
     '\t\t\t\t\tconst AVStream *vs = format_ctx->streams[videostream];\n'
     '\t\t\t\t\tconst int64_t org = vs->start_time == AV_NOPTS_VALUE ? 0 : vs->start_time;\n'
     '\t\t\t\t\tconst int64_t fdur = movie_fps > 0.0 ? int64_t(1000000.0 / movie_fps) : 0;\n'
     '\t\t\t\t\tif (movie_frame_counter > 0 && fdur > 0 && movie_frame->best_effort_timestamp != AV_NOPTS_VALUE) {\n'
     '\t\t\t\t\t\tconst int64_t lag = presentation_time - av_rescale_q(movie_frame->best_effort_timestamp - org, vs->time_base, AV_TIME_BASE_Q);\n'
     '\t\t\t\t\t\tAVDiscard want = codec_ctx->skip_frame;\n'
     '\t\t\t\t\t\tif (lag > 1000000) want = AVDISCARD_NONKEY;\n'
     '\t\t\t\t\t\telse if (lag > 150000) want = AVDISCARD_NONREF;\n'
     '\t\t\t\t\t\telse if (lag < fdur) want = AVDISCARD_DEFAULT;\n'
     '\t\t\t\t\t\tif (codec_ctx->skip_frame != want) {\n'
     '\t\t\t\t\t\t\tcodec_ctx->skip_frame = want;\n'
     '\t\t\t\t\t\t\tcodec_ctx->skip_loop_filter = want == AVDISCARD_DEFAULT ? AVDISCARD_DEFAULT : AVDISCARD_ALL;\n'
     '\t\t\t\t\t\t\tif (trace_movies || trace_all) ffnx_trace("movie: %lld ms behind, decoder skip %d\\n", (long long)(lag / 1000), int(want));\n'
     '\t\t\t\t\t\t}\n'
     '\t\t\t\t\t\tif (lag >= fdur) { av_frame_unref(movie_frame); continue; }   // a newer frame is already due\n'
     '\t\t\t\t\t}\n'
     '\t\t\t\t}\n'
     '#endif\n'
     '\t\t\t\t// use a pointer to identify the AVFrame object we ultimately want to use\n'),
    # The battle fade's length (battle_sub_5BCF9D + 0x3A) is the 16-bit value of a
    # `mov word [edx+0xBFC3A4], 0x15` at +0x33. Enhanced Stock UI's Transparency2 replaces that
    # instruction with a call and four NOPs (its hext runs before FFNx patches the game), so
    # FFNx's write landed in the NOPs: widescreen's 30 made `1E 00`, an instruction that is not
    # there, and every battle at frame rate Original crashed (at 60 FPS the multiplied value
    # happened to make a harmless jump). Only patch the fade while the original instruction is.
    ('ff7/widescreen.cpp',
     '    patch_code_short(ff7_externals.battle_sub_5BCF9D + 0x3A, 30);\n',
     '#ifdef FFNX_ANDROID\n'
     '    if (*(volatile uint8_t *)(uintptr_t)(ff7_externals.battle_sub_5BCF9D + 0x33) == 0x66 &&\n'
     '        *(volatile uint8_t *)(uintptr_t)(ff7_externals.battle_sub_5BCF9D + 0x34) == 0xC7)   // FF7Recomp: not replaced by a mod\n'
     '#endif\n'
     '    patch_code_short(ff7_externals.battle_sub_5BCF9D + 0x3A, 30);\n'),
    ('ff7/battle/animations.cpp',
     '            patch_multiply_code<WORD>(ff7_externals.battle_sub_5BCF9D + 0x3A, battle_frame_multiplier);\n',
     '#ifdef FFNX_ANDROID\n'
     '            if (*(volatile uint8_t *)(uintptr_t)(ff7_externals.battle_sub_5BCF9D + 0x33) == 0x66 &&\n'
     '                *(volatile uint8_t *)(uintptr_t)(ff7_externals.battle_sub_5BCF9D + 0x34) == 0xC7)   // FF7Recomp: see widescreen.cpp\n'
     '#endif\n'
     '            patch_multiply_code<WORD>(ff7_externals.battle_sub_5BCF9D + 0x3A, battle_frame_multiplier);\n'),
    ('common.cpp', '\t\t\t\tif (strstr(basedir, "workingdir") != NULL)',
     '\t\t\t\tif (FFNX_ANDROID || strstr(basedir, "workingdir") != NULL)   // FF7Recomp: 2026 rerelease exe'),
    # Save states: which graphics objects carry button prompts is host state (prompt_resources)
    # pointing into guest memory. After a load it must describe the loaded game, not the one
    # before it (unloading stale objects freed memory the game had reused: a crash after a
    # battle), and the private prompt texture sets the state brings back need this process's
    # host parts (gl_set, the atlas texture). src/ffnx_port/savestate_ffnx.cpp calls these.
    ('ff7/universal_buttons.cpp', 'void universal_buttons_unload()\n{\n',
     '#ifdef FFNX_ANDROID\n'
     'extern "C" uint32_t universal_buttons_ss_count() { return (uint32_t)prompt_atlas::count; }\n'
     '\n'
     '// three guest addresses per atlas: graphics object, its texture set, the polygon set\'s\n'
     'extern "C" void universal_buttons_ss_get(uint32_t *out)\n'
     '{\n'
     '  for (const prompt_resource& resource : prompt_resources)\n'
     '  {\n'
     '    *out++ = guest::to_va(resource.graphics_object);\n'
     '    *out++ = guest::to_va(resource.original_texture_set);\n'
     '    *out++ = guest::to_va(resource.polygon_original_texture_set);\n'
     '  }\n'
     '}\n'
     '\n'
     '// after a load (in == nullptr: the state has no record, so nothing is known)\n'
     'extern "C" void universal_buttons_ss_set(const uint32_t *in)\n'
     '{\n'
     '  for (prompt_resource& resource : prompt_resources)\n'
     '  {\n'
     '    resource.graphics_object = in ? guest::ptr_of<ff7_graphics_object>(in[0]) : nullptr;\n'
     '    resource.original_texture_set = in ? guest::ptr_of<ff7_texture_set>(in[1]) : nullptr;\n'
     '    resource.polygon_original_texture_set = in ? guest::ptr_of<ff7_texture_set>(in[2]) : nullptr;\n'
     '    if (in) in += 3;\n'
     '    ff7_graphics_object* object = resource.graphics_object;\n'
     '    ff7_polygon_set* polygon_set = object ? (ff7_polygon_set*)object->polygon_set : nullptr;\n'
     '    if (!object || !object->hundred_data || !resource.original_texture_set || !resource.polygon_original_texture_set\n'
     '        || !polygon_set || !polygon_set->hundred_data)\n'
     '    {\n'
     '      resource.graphics_object = nullptr;\n'
     '      resource.original_texture_set = nullptr;\n'
     '      resource.polygon_original_texture_set = nullptr;\n'
     '      continue;\n'
     '    }\n'
     '    ff7_texture_set* private_texture_set = (ff7_texture_set*)object->hundred_data->texture_set;\n'
     '    if (!private_texture_set || private_texture_set == resource.original_texture_set)\n'
     '      continue;\n'
     '    if (!texture_available(resource))\n'
     '    {\n'
     '      // this process has no prompt atlas: give the game its own sets back\n'
     '      object->hundred_data->texture_set = (struct texture_set*)resource.original_texture_set;\n'
     '      polygon_set->hundred_data->texture_set = (struct texture_set*)resource.polygon_original_texture_set;\n'
     '      guest_free(private_texture_set->texturehandle);\n'
     '      delete private_texture_set;\n'
     '      continue;\n'
     '    }\n'
     '    // the private set\'s host parts belong to the moment the state was saved: renew them\n'
     '    ff7_texture_set* original = resource.polygon_original_texture_set;\n'
     '    ff7_tex_header* texture_header = (ff7_tex_header*)original->tex_header;\n'
     '    uint32_t texture_count = std::max(std::max(texture_header->palettes, texture_header->palette_index + 1), 8u);\n'
     '    memcpy(private_texture_set->texturehandle, original->texturehandle, texture_count * sizeof(uint32_t));\n'
     '    private_texture_set->texturehandle[7] = resource.texture;\n'
     '    private_texture_set->ogl.gl_set = original->ogl.gl_set ? new gl_texture_set(*original->ogl.gl_set) : nullptr;\n'
     '    if (private_texture_set->ogl.gl_set)\n'
     '      private_texture_set->ogl.gl_set->force_filter = true;\n'
     '    private_texture_set->ogl.external = true;\n'
     '    private_texture_set->ogl.width = resource.texture_width;\n'
     '    private_texture_set->ogl.height = resource.texture_height;\n'
     '  }\n'
     '}\n'
     '#endif\n'
     '\n'
     'void universal_buttons_unload()\n{\n'),
    # The Thor's bottom screen (src/ffnx_port/companion.cpp) has a 2x speed button and shows
    # whether 2x is on: FFNx keeps both private.
    ('gamehacks.h', '\tdouble getCurrentSpeedhack();\n',
     '\tdouble getCurrentSpeedhack();\n'
     '#ifdef FFNX_ANDROID\n'
     '\tvoid companionToggleSpeedhack();   // FF7Recomp: the bottom screen (src/ffnx_port/companion.cpp)\n'
     '\tbool companionSpeedhackEnabled() const { return speedhack_enabled; }\n'
     # Game boosters (docs/ROADMAP.md M8, src/ffnx_port/boosters.cpp): speed 1x/2x/3x and
     # random battles on/off are FFNx's speed hack and battle mode, set from the bottom screen
     # and the launcher; battle enhancement is ours. (In this edit, not a second one anchored
     # inside it: a later edit that changes this one's text makes this one apply again.)
     '\tvoid companionSetSpeed(double speed);   // FF7Recomp: game boosters (src/ffnx_port/boosters.cpp)\n'
     '\tvoid companionSetBattles(bool on);\n'
     '\tbool companionBattlesWanted() const { return battle_wanted; }\n'
     '#endif\n'),
    # Cross in FFNx's L3 shortcut mode toggles battle enhancement (the only button that mode
    # leaves free).
    ('gamehacks.cpp',
     '\t\t\t\tgamepad.IsPressed(XINPUT_GAMEPAD_X)\n'
     '\t\t\t\t)\n'
     '\t\t\t\tskipMovies();\n',
     '\t\t\t\tgamepad.IsPressed(XINPUT_GAMEPAD_X)\n'
     '\t\t\t\t)\n'
     '\t\t\t\tskipMovies();\n'
     '#ifdef FFNX_ANDROID\n'
     '\t\t\t// FF7Recomp: battle enhancement booster on Cross\n'
     '\t\t\telse if (gamepad.IsPressed(XINPUT_GAMEPAD_A))\n'
     '\t\t\t{\n'
     '\t\t\t\textern void ff7_boosters_toggle_battle();\n'
     '\t\t\t\tff7_boosters_toggle_battle();\n'
     '\t\t\t\tholdInput();\n'
     '\t\t\t}\n'
     '#endif\n'),
    # 3x speed from a 60 FPS base asks for 180 frames a second; with vsync the Thor's 120 Hz
    # display holds it at 120 (2x). The boosters turn vsync off while the speed hack needs more.
    ('renderer.h', '    void reset();\n',
     '    void reset();\n'
     '    void setVsync(bool on);   // FF7Recomp: game boosters (speed above the display rate)\n'),
    ('renderer.cpp',
     '    bgfx::reset(bgfxInit.reset, &bgfxInit.swapChain);\n}\n\n#define FFNX_LOGO_PATH',
     '    bgfx::reset(bgfxInit.reset, &bgfxInit.swapChain);\n}\n\n'
     '// FF7Recomp: vsync on/off at run time (the game boosters\' speed above the display rate)\n'
     'void Renderer::setVsync(bool on)\n'
     '{\n'
     '    const uint32_t flags = on ? (bgfxInit.reset | BGFX_RESET_VSYNC) : (bgfxInit.reset & ~BGFX_RESET_VSYNC);\n'
     '    if (flags == bgfxInit.reset) return;\n'
     '    bgfxInit.reset = flags;\n'
     '    bgfx::reset(bgfxInit.reset, &bgfxInit.swapChain);\n'
     '}\n\n'
     '#define FFNX_LOGO_PATH'),
    # The Thor's bottom screen shows the battle windows (src/ffnx_port/companion_render.cpp):
    # a 2D battle draw inside the window band goes to the bottom screen's view instead.
    ('renderer.cpp',
     '    // Set current view rect\n'
     '    if (bgfx::isValid(targetFrameBuffer))\n',
     '#ifdef FFNX_ANDROID\n'
     '    // FF7Recomp: a battle window draw routed to the Thor\'s bottom screen (companion_render.cpp)\n'
     '    uint16_t drawViewId = backendViewId;\n'
     '    const bool companionDraw = ffnx_companion_take_draw(&drawViewId);\n'
     '    if (companionDraw) {}\n'
     '    else\n'
     '#else\n'
     '    const uint16_t drawViewId = backendViewId;\n'
     '#endif\n'
     '    // Set current view rect\n'
     '    if (bgfx::isValid(targetFrameBuffer))\n'),
    ('renderer.cpp',
     '    bgfx::submit(backendViewId, backendProgramHandles[program], 0, flags);\n\n'
     '    internalState.bHasDrawBeenDone = true;\n',
     '#ifdef FFNX_ANDROID\n'
     '    // FF7Recomp: the bottom screen crops one part of the band per view; the same draw goes to each\n'
     '    if (companionDraw) { uint16_t extraView; while (ffnx_companion_extra_view(&extraView)) bgfx::submit(extraView, backendProgramHandles[program], 0, BGFX_DISCARD_NONE); }\n'
     '#endif\n'
     '    bgfx::submit(drawViewId, backendProgramHandles[program], 0, flags);\n\n'
     '    internalState.bHasDrawBeenDone = true;\n'),
    ('renderer.cpp',
     '    backendViewId = 1;\n\n'
     '    vertexBufferData.clear();\n',
     '    backendViewId = 1;\n\n'
     '#ifdef FFNX_ANDROID\n'
     '    ffnx_companion_frame_begin();   // FF7Recomp: the bottom screen\'s view for the next frame\n'
     '#endif\n\n'
     '    vertexBufferData.clear();\n'),
    # Frame skipping while sped up (src/ffnx_port/boosters.cpp): at 2x / 3x the game runs 120 /
    # 180 frames a second but only every 2nd / 3rd is drawn and shown, so the GPU works as at 1x.
    # A skipped frame's draws are discarded and nothing is presented; its vertices are dropped.
    # The frame limiter waited by spinning on the clock: a whole core busy between frames (heat,
    # battery), hidden while presenting blocked on vsync but plain once frames are skipped. Sleep
    # most of the wait, spin only the last quarter millisecond.
    ('ff7/misc.cpp', '#include <algorithm>\n', '#include <algorithm>\n#include <chrono>\n#include <thread>\n'),
    ('ff7/misc.cpp',
     '\tdo qpc_get_time(&gametime);\n'
     '\twhile (gametime > last_gametime && qpc_diff_time(&gametime, &last_gametime, nullptr) < frame_time);\n',
     '#ifdef FFNX_ANDROID\n'
     '\t// FF7Recomp: sleep through most of the wait instead of spinning a core\n'
     '\tfor (;;)\n'
     '\t{\n'
     '\t\tqpc_get_time(&gametime);\n'
     '\t\tif (!(gametime > last_gametime)) break;\n'
     '\t\tconst double left = frame_time - (double)qpc_diff_time(&gametime, &last_gametime, nullptr);\n'
     '\t\tif (left <= 0) break;\n'
     '\t\tconst double left_us = left * 1000000.0 / game_object->countspersecond;\n'
     '\t\tif (left_us > 500.0) std::this_thread::sleep_for(std::chrono::microseconds((long long)(left_us - 250.0)));\n'
     '\t}\n'
     '#else\n'
     '\tdo qpc_get_time(&gametime);\n'
     '\twhile (gametime > last_gametime && qpc_diff_time(&gametime, &last_gametime, nullptr) < frame_time);\n'
     '#endif\n'),
    ('renderer.cpp', '#include "ff7/widescreen.h"\n',
     '#include "ff7/widescreen.h"\n'
     '#ifdef FFNX_ANDROID\n'
     'extern "C" bool ffnx_frame_skip_next(bool movie);   // FF7Recomp: src/ffnx_port/boosters.cpp\n'
     'static bool ffnx_skip_frame;                          // this frame is not drawn\n'
     '#endif\n'),
    ('renderer.cpp',
     '    auto flags = keepBindings ? BGFX_DISCARD_STATE : BGFX_DISCARD_ALL;\n',
     '    auto flags = keepBindings ? BGFX_DISCARD_STATE : BGFX_DISCARD_ALL;\n'
     '#ifdef FFNX_ANDROID\n'
     '    if (ffnx_skip_frame) {             // FF7Recomp: a sped-up frame that is not shown\n'
     '        bgfx::discard(flags);\n'
     '        internalState.bHasDrawBeenDone = true;\n'
     '        internalState.bTexturesBound = false;\n'
     '        return;\n'
     '    }\n'
     '#endif\n'),
    ('renderer.cpp',
     'void Renderer::show()\n{\n    // Reset internal state\n    resetState();\n',
     'void Renderer::show()\n{\n    // Reset internal state\n    resetState();\n'
     '#ifdef FFNX_ANDROID\n'
     '    if (ffnx_skip_frame) {             // FF7Recomp: nothing drawn, nothing presented\n'
     '        bgfx::discard(BGFX_DISCARD_ALL);\n'
     '        internalState.bHasDrawBeenDone = false;\n'
     '        internalState.bHasColorClearBeenDone = false;\n'
     '        vertexBufferData.clear();\n'
     '        indexBufferData.clear();\n'
     '        ffnx_skip_frame = ffnx_frame_skip_next(internalState.bIsMovie);\n'
     '        return;\n'
     '    }\n'
     '#endif\n'),
    ('renderer.cpp',
     '    indexBufferData.shrink_to_fit();\n\n    bgfx::setViewMode(backendViewId, bgfx::ViewMode::Sequential);\n',
     '    indexBufferData.shrink_to_fit();\n\n    bgfx::setViewMode(backendViewId, bgfx::ViewMode::Sequential);\n'
     '#ifdef FFNX_ANDROID\n'
     '    ffnx_skip_frame = ffnx_frame_skip_next(internalState.bIsMovie);   // FF7Recomp: the next frame\n'
     '#endif\n'),
    ('renderer.cpp', '#include "image/image.h"\n',
     '#include "image/image.h"\n'
     '#ifdef FFNX_ANDROID\n'
     'void ffnx_companion_frame_begin();                    // FF7Recomp: src/ffnx_port/companion_render.cpp\n'
     'bool ffnx_companion_take_draw(uint16_t *view);\n'
     'bool ffnx_companion_extra_view(uint16_t *view);\n'
     '#endif\n'),
    ('gl/gl.cpp',
     '\tif (!ff8 && enable_lighting && normals != nullptr && isLightingEnabledTexture)\n'
     '\t{\n'
     '\t\tnewRenderer.drawToShadowMap();\n'
     '\t\tnewRenderer.drawWithLighting(true, true);\n'
     '\t}\n'
     '\telse newRenderer.draw();\n',
     '\tif (!ff8 && enable_lighting && normals != nullptr && isLightingEnabledTexture)\n'
     '\t{\n'
     '\t\tnewRenderer.drawToShadowMap();\n'
     '\t\tnewRenderer.drawWithLighting(true, true);\n'
     '\t}\n'
     '\telse\n'
     '\t{\n'
     '#ifdef FFNX_ANDROID\n'
     '\t\t// FF7Recomp: battle windows go to the Thor\'s bottom screen (companion_render.cpp)\n'
     '\t\tif (!ff8 && vertextype == TLVERTEX && mode == MODE_BATTLE) ffnx_companion_route_2d(vertices, vertexcount);\n'
     '#endif\n'
     '\t\tnewRenderer.draw();\n'
     '\t}\n'),
    ('gl/gl.cpp', '#include "../ff7/widescreen.h"\n',
     '#include "../ff7/widescreen.h"\n'
     '#ifdef FFNX_ANDROID\n'
     'void ffnx_companion_route_2d(const struct nvertex *v, uint32_t n);   // FF7Recomp: src/ffnx_port/companion_render.cpp\n'
     '#endif\n'),
    # magic_thread_start gets the game's loader as a callback: a game function (guest address),
    # which native code cannot call directly (the first Magic in battle jumped to 0x5AEC40).
    ('ff7/battle/battle.cpp',
     '\t\t* effects synchronously.\n'
     '\t\t*/\n'
     '\t\tfunc();\n',
     '\t\t* effects synchronously.\n'
     '\t\t*/\n'
     '#ifdef FFNX_ANDROID\n'
     '\t\t(guest::gfn<void()>::from_va((uint32_t)(uintptr_t)func))();   // FF7Recomp: a game function\n'
     '#else\n'
     '\t\tfunc();\n'
     '#endif\n'),
    # The float camera projection (field background scrolling) hands a 16-float local matrix
    # to two game functions. As a float* the guest bridge bounces one float: the game wrote
    # 64 bytes into a 4-byte guest block and the matrix came back uninitialized, so the
    # camera stopped following the player. As a struct the bridge copies all of it.
    ('ff7/field/utils.h',
     '        ff7_externals.engine_convert_psx_matrix_to_float_matrix_row_version_661465(&global_game_data->rot_matrix, matrix);\n'
     '        ff7_externals.engine_apply_matrix_product_to_vector_66CF7E(matrix, &input_vector_copy, &output_temp);\n',
     '#ifdef FFNX_ANDROID\n'
     '        // FF7Recomp: the 16 floats as one struct, so the guest bridge copies all of them\n'
     '        struct float_matrix16 { float m[16]; } guest_matrix;\n'
     '        gfn<void(rotation_matrix *, float_matrix16 *)>::from_va(ff7_externals.engine_convert_psx_matrix_to_float_matrix_row_version_661465.va())(&global_game_data->rot_matrix, &guest_matrix);\n'
     '        gfn<void(float_matrix16 *, vector3<float> *, vector3<float> *)>::from_va(ff7_externals.engine_apply_matrix_product_to_vector_66CF7E.va())(&guest_matrix, &input_vector_copy, &output_temp);\n'
     '        std::memcpy(matrix, guest_matrix.m, sizeof(matrix));\n'
     '#else\n'
     '        ff7_externals.engine_convert_psx_matrix_to_float_matrix_row_version_661465(&global_game_data->rot_matrix, matrix);\n'
     '        ff7_externals.engine_apply_matrix_product_to_vector_66CF7E(matrix, &input_vector_copy, &output_temp);\n'
     '#endif\n'),
    # Battle menus read confirm/cancel through the repeatable query: held past 200 ms they
    # repeat every 50 ms, so a firm press on Attack also confirmed the target at once (the
    # aiming hand flashed and vanished). In battle, buttons other than the directions count
    # once per press; the D-pad still repeats (direction masks have bits in 0xFFF000).
    ('ff7_opengl.cpp',
     'static int ff7_query_repeatable_input(int mask)\n{\n',
     'static int ff7_query_repeatable_input(int mask)\n{\n'
     '#ifdef FFNX_ANDROID\n'
     '\tif ((mask & 0x00FFF000) == 0)\n'
     '\t{\n'
     '\t\tstruct game_mode *mode = getmode_cached();\n'
     '\t\tif (mode && mode->driver_mode == MODE_BATTLE) return ff7_externals.get_button_pressed(mask);\n'
     '\t}\n'
     '#endif\n'),
    # Steam achievements: no steam_api library to validate (src/ffnx_port/steam_android.cpp
    # stands in for the Steam client), and the achievement list is that of the FF7 the
    # player's Steam account owns (the 2013 release, app 39140, or the 2026 re-release, whose
    # package carries the same 1.02 exe), not the one FFNx detects from the files.
    ('steam.cpp',
     '\tstatic const bool is_valid = [] {\n',
     '#ifdef FFNX_ANDROID\n'
     '\treturn true;   // FF7Recomp: src/ffnx_port/steam_android.cpp\n'
     '#endif\n'
     '\tstatic const bool is_valid = [] {\n'),
    ('common.cpp',
     '\t\t\tg_FF7SteamAchievements = std::make_unique<SteamAchievementsFF7>(steam_edition);\n',
     '#ifdef FFNX_ANDROID\n'
     '\t\t\tg_FF7SteamAchievements = std::make_unique<SteamAchievementsFF7>(ff7r_steam_is_2013());   // FF7Recomp: the owned FF7\'s list\n'
     '#else\n'
     '\t\t\tg_FF7SteamAchievements = std::make_unique<SteamAchievementsFF7>(steam_edition);\n'
     '#endif\n'),
    ('common.cpp',
     'int common_create_window(HINSTANCE hInstance, struct game_obj* game_object)\n{\n',
     '#ifdef FFNX_ANDROID\n'
     'extern "C" int ff7r_steam_is_2013();   // FF7Recomp: src/ffnx_port/steam_android.cpp\n'
     '#endif\n'
     'int common_create_window(HINSTANCE hInstance, struct game_obj* game_object)\n{\n'),
    # The 2026 re-release in French, German or Spanish (game_language): FFNx patches the English
    # exe to read its own tables (HUD records, strings, the name grid). Values the game reads
    # must be guest addresses: FFNx's constant tables are copied to guest memory once
    # (ml_guest), and the tables FFNx fills at run time live in the guest static block.
    ('ff7/multilanguage_data.h',
     'inline uint8_t hud2[11][36];\n'
     'inline uint8_t hud64[13][36];\n'
     'inline uint8_t hud128[11][36];\n'
     'inline uint8_t text_buffer[128];\n',
     '#ifdef FFNX_ANDROID\n'
     '}\n#include "ffnx_guest.h"\nnamespace ff7::multilanguage::data\n{\n'
     '// FF7Recomp: the game reads these through FFNx\'s code patches: guest memory\n'
     'inline uint8_t (&hud2)[11][36] = ffnx_guest_static_zero<uint8_t[36], 11>();\n'
     'inline uint8_t (&hud64)[13][36] = ffnx_guest_static_zero<uint8_t[36], 13>();\n'
     'inline uint8_t (&hud128)[11][36] = ffnx_guest_static_zero<uint8_t[36], 11>();\n'
     'inline uint8_t (&text_buffer)[128] = ffnx_guest_static_zero<uint8_t, 128>();\n'
     '#else\n'
     'inline uint8_t hud2[11][36];\n'
     'inline uint8_t hud64[13][36];\n'
     'inline uint8_t hud128[11][36];\n'
     'inline uint8_t text_buffer[128];\n'
     '#endif\n'),
    ('ff7/multilanguage.cpp',
     'namespace ff7::multilanguage\n{\nnamespace\n{\n',
     '#ifdef FFNX_ANDROID\n'
     '#include "ffnx_guest.h"\n'
     '// FF7Recomp: a guest-memory copy of one of FFNx\'s constant tables (made once), for values\n'
     '// the game reads; guest memory (the tables FFNx fills) is its own address.\n'
     'static uint32_t ml_guest(const void *p, size_t n)\n{\n'
     '\tif (reinterpret_cast<uintptr_t>(p) <= 0xFFFFFFFFu) return (uint32_t)reinterpret_cast<uintptr_t>(p);\n'
     '\tstatic std::unordered_map<const void *, uint32_t> copies;\n'
     '\tauto it = copies.find(p);\n'
     '\tif (it != copies.end()) return it->second;\n'
     '\tvoid *g = guest_malloc(n);\n'
     '\tif (!g) guest::fatal("multilanguage: guest heap exhausted", n);\n'
     '\tmemcpy(g, p, n);\n'
     '\treturn copies[p] = guest::to_va(static_cast<uint8_t *>(g));\n'
     '}\n'
     '#define ML_GUEST(p, n) ml_guest((p), (n))\n'
     '#define ML_FN(f) ((void *)(uintptr_t)ffnx_recomp::guest_va_of(f))   // FFNx\'s text wrappers: typed guest thunks\n'
     '#else\n'
     '#define ML_GUEST(p, n) reinterpret_cast<uint32_t>(p)\n'
     '#define ML_FN(f) reinterpret_cast<void *>(f)\n'
     '#endif\n\n'
     'namespace ff7::multilanguage\n{\nnamespace\n{\n'),
    ('ff7/multilanguage.cpp',
     '\treinterpret_cast<void (__stdcall *)(const char *, int, int, int, ff7_graphics_object *)>(data::ascii_original)(\n',
     '#ifdef FFNX_ANDROID\n'
     '\tguest::gfn_stdcall<void(const char *, int, int, int, ff7_graphics_object *)>::from_va(data::ascii_original)(   // FF7Recomp\n'
     '#else\n'
     '\treinterpret_cast<void (__stdcall *)(const char *, int, int, int, ff7_graphics_object *)>(data::ascii_original)(\n'
     '#endif\n'),
    ('ff7/multilanguage.cpp',
     'patch_code_dword(copy.address + data::archive_copy_source_offset, reinterpret_cast<uint32_t>(entry.bytes));',
     'patch_code_dword(copy.address + data::archive_copy_source_offset, ML_GUEST(entry.bytes, std::strlen(entry.bytes) + 1));'),
    ('ff7/multilanguage.cpp',
     '\tpatch_code_dword(data::help_texture_addresses[0], reinterpret_cast<uint32_t>(help_texture));\n'
     '\tpatch_code_dword(data::help_texture_addresses[1], reinterpret_cast<uint32_t>(normal_help_texture));',
     '\tpatch_code_dword(data::help_texture_addresses[0], ML_GUEST(help_texture, std::strlen(help_texture) + 1));\n'
     '\tpatch_code_dword(data::help_texture_addresses[1], ML_GUEST(normal_help_texture, std::strlen(normal_help_texture) + 1));'),
    ('ff7/multilanguage.cpp',
     'reinterpret_cast<uint32_t>(table.records) + entry.value);',
     'ML_GUEST(table.records, table.size) + entry.value);'),
    ('ff7/multilanguage.cpp',
     '\t\tpatch_code_dword(address, reinterpret_cast<uint32_t>(name_characters));',
     '\t\tpatch_code_dword(address, ML_GUEST(name_characters, game_language == GAME_LANGUAGE_DE ? sizeof(data::de_name_characters) :\n'
     '\t\t\tgame_language == GAME_LANGUAGE_SP ? sizeof(data::es_name_characters) : sizeof(data::fr_name_characters)));'),
    # FFNx's text wrappers are passed to replace_call as void *: a typed guest thunk (ML_FN).
    ('ff7/multilanguage.cpp', 'reinterpret_cast<void *>(text_width)', 'ML_FN(text_width)', 'all'),
    ('ff7/multilanguage.cpp', 'reinterpret_cast<void *>(draw_text)', 'ML_FN(draw_text)', 'all'),
    ('ff7/multilanguage.cpp', 'reinterpret_cast<void *>(draw_text_fixed)', 'ML_FN(draw_text_fixed)', 'all'),
    ('ff7/multilanguage.cpp', 'reinterpret_cast<void *>(draw_ascii)', 'ML_FN(draw_ascii)', 'all'),
    # The language's archive paths are code writes the game runs before its first frame.
    ('ff7_opengl.cpp',
     '\tif(ff7_2026_rerelease && !ff7_japanese_edition) ff7::multilanguage::init();\n',
     '\tif(ff7_2026_rerelease && !ff7_japanese_edition) ff7::multilanguage::init();\n'
     '#ifdef FFNX_ANDROID\n'
     '\tffnx_recomp::verify_code_writes();   // FF7Recomp: patched start-up code runs interpreted at once\n'
     '#endif\n'),
    # The world map loop calls the game function stored at 0xDE68FC (a game address): a guest
    # call, not a native jump to x86 code (SIGBUS on the world map: GitHub issue #3).
    ('music.cpp', '\t(*ff7_externals.world_dword_DE68FC)();\n',
     '#ifdef FFNX_ANDROID\n'
     '\tguest::gfn<void()>::from_va(*(const uint32_t *)ff7_externals.world_dword_DE68FC)();   // FF7Recomp\n'
     '#else\n'
     '\t(*ff7_externals.world_dword_DE68FC)();\n'
     '#endif\n'),
    # a host array, as wide_speed_args above
    ('ff7/multilanguage.cpp',
     '\t\t\tmemcpy_code(reinterpret_cast<uint32_t>(time_args.data() + 6), const_cast<uint8_t *>(original + 3), 6);',
     '\t\t\tmemcpy(time_args.data() + 6, const_cast<uint8_t *>(original + 3), 6);   // FF7Recomp: host array'),
    # The device going to sleep (or the app to the background) destroys the game's surface.
    # The renderer takes the window to draw into each frame (src/android/ff7_android_main.cpp:
    # it waits there, the game paused, while there is none) and moves bgfx to a new one when
    # the surface comes back; before, the game crashed on wake (GitHub #26).
    ('renderer.cpp',
     '    if (!presentFrame) frameFlags |= BGFX_FRAME_FLUSH;\n'
     '    bgfx::frame(frameFlags);\n',
     '    if (!presentFrame) frameFlags |= BGFX_FRAME_FLUSH;\n'
     '#ifdef FFNX_ANDROID\n'
     '    if (void *nwh = ff7_platform_frame_window(); nwh && nwh != bgfxInit.swapChain.nwh)   // FF7Recomp\n'
     '    {\n'
     '        bgfxInit.swapChain.nwh = nwh;\n'
     '        bgfx::reset(bgfxInit.reset, &bgfxInit.swapChain);\n'
     '    }\n'
     '#endif\n'
     '    bgfx::frame(frameFlags);\n'),
    # ... with the music silent meanwhile (SoLoud's miniaudio output cannot pause; FFNx does
    # not use the global volume otherwise).
    ('audio.h', '\tbool init();\n\tvoid flush();\n\tvoid cleanup();\n',
     '\tbool init();\n\tvoid flush();\n\tvoid cleanup();\n'
     '#ifdef FFNX_ANDROID\n'
     '\tvoid ff7rSuspend(bool on) { _engine.setGlobalVolume(on ? 0.0f : 1.0f); }   // FF7Recomp: no surface\n'
     '#endif\n'),
    ('audio.cpp', 'bool NxAudioEngine::init()\n',
     '#ifdef FFNX_ANDROID\n'
     'extern "C" void ff7r_ffnx_audio_suspend(int on) { nxAudioEngine.ff7rSuspend(on != 0); }   // FF7Recomp\n'
     '#endif\n\n'
     'bool NxAudioEngine::init()\n'),
]


# patch_code_dword/uint(addr, (DWORD)&fn) with a native function or object: the value the
# game reads must be a guest address (ffnx_guest_va, src/ffnx_port/include/ffnx_guest.h).
PATCH_VA_RE = re.compile(r'(patch_code_(?:dword|uint)\((?:[^;]*?)),\s*\((?:DWORD|uint32_t)\)\s*(&?)([A-Za-z_][A-Za-z_0-9:]*)\)\s*;')


def rewrite_fps_tests():
    """`ff7_fps_limiter == FPS_LIMITER_60FPS` means "the high frame rate mode": with the 120 FPS
    mode it becomes >= (FF7 code only)."""
    n = 0
    for dirpath, _, files in os.walk(SRC):
        rel_dir = os.path.relpath(dirpath, SRC).replace(os.sep, '/')
        if rel_dir.startswith('ff8'):
            continue
        for f in files:
            if not f.endswith(('.cpp', '.h')) or f.startswith('ff8'):
                continue
            path = os.path.join(dirpath, f)
            raw = open(path, 'rb').read().decode('utf-8', errors='surrogateescape')
            new, k = re.subn(r'ff7_fps_limiter\s*==\s*FPS_LIMITER_60FPS', 'ff7_fps_limiter >= FPS_LIMITER_60FPS', raw)
            if k:
                open(path, 'wb').write(new.encode('utf-8', errors='surrogateescape'))
                n += k
    return n


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


GAME_ADDR_MARKERS = ('ff7_externals.', 'common_externals.', 'get_relative_call', 'get_absolute_value',
                     'data::', 'ff8_externals.')
FNPTR_TYPE_RE = re.compile(r'^\s*(?P<ret>[A-Za-z_][\w:\s]*?\**)\s*\(\s*\*\s*\)\s*\((?P<args>[^()]*)\)\s*$')
DECLTYPE_RE = re.compile(r'^\s*decltype\(\s*(?P<name>[A-Za-z_]\w*)\s*\)\s*$')


def _balanced(s, i, open_c, close_c):
    """Index just past the bracket matching s[i] (== open_c), or -1."""
    depth = 0
    for j in range(i, len(s)):
        if s[j] == open_c:
            depth += 1
        elif s[j] == close_c:
            depth -= 1
            if depth == 0:
                return j + 1
    return -1


def rewrite_game_fn_casts():
    """reinterpret_cast<R (*)(A)>(game address) and reinterpret_cast<decltype(v)>(game address),
    and `v = (decltype(v))game address;`: native calls into x86 code. They become guest
    calls (guest::gfn), and each such variable v a guest::gfn (its declaration rewritten).
    Only casts whose operand names a game address are touched (FFNx also casts its own
    native callbacks)."""
    n, names = 0, set()
    files = []
    for dirpath, _, fnames in os.walk(SRC):
        rel_dir = os.path.relpath(dirpath, SRC).replace(os.sep, '/')
        if rel_dir.startswith(FNPTR_SKIP_DIRS):
            continue
        files += [os.path.join(dirpath, f) for f in fnames if f.endswith(('.cpp', '.h')) and not f.startswith('ff8')]
    text = {p: open(p, 'rb').read().decode('utf-8', errors='surrogateescape') for p in files}
    cstyle = re.compile(r'(?P<name>[A-Za-z_]\w*)\s*=\s*\(\s*decltype\(\s*(?P=name)\s*\)\s*\)\s*(?P<expr>[^;]+);')
    for p, raw in text.items():
        out, i = [], 0
        while True:
            k = raw.find('reinterpret_cast<', i)
            if k < 0:
                out.append(raw[i:])
                break
            t0 = k + len('reinterpret_cast')
            t1 = _balanced(raw, t0, '<', '>')
            if t1 < 0 or t1 >= len(raw) or raw[t1] != '(':
                out.append(raw[i:t0]); i = t0; continue
            e1 = _balanced(raw, t1, '(', ')')
            tmpl, expr = raw[t0 + 1:t1 - 1], raw[t1 + 1:e1 - 1]
            fm, dm = FNPTR_TYPE_RE.match(tmpl), DECLTYPE_RE.match(tmpl)
            if (fm or dm) and any(mk in expr for mk in GAME_ADDR_MARKERS):
                gtype = f'guest::gfn<{fm.group("ret").strip()}({fm.group("args").strip()})>' if fm else f'decltype({dm.group("name")})'
                if dm:
                    names.add(dm.group('name'))
                out.append(raw[i:k])
                out.append(f'{gtype}::from_va((uint32_t)({expr.strip()}))')
                n += 1
            else:
                out.append(raw[i:e1])
            i = e1
        new = ''.join(out)

        def cs(m):
            nonlocal n
            if not any(mk in m.group('expr') for mk in GAME_ADDR_MARKERS):
                return m.group(0)
            names.add(m.group('name'))
            n += 1
            return f'{m.group("name")} = decltype({m.group("name")})::from_va((uint32_t)({m.group("expr").strip()}));'
        text[p] = cstyle.sub(cs, new)
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


def apply_edit(edit):
    """'applied', 'present' (already applied) or None (no match yet)."""
    rel, old, new = edit[:3]
    allow_many = len(edit) > 3 and edit[3] == 'all'
    path = os.path.join(SRC, rel)
    raw = open(path, 'rb').read().decode('utf-8', errors='surrogateescape')
    for o, n in ((old, new), (old.replace('\n', '\r\n'), new.replace('\n', '\r\n'))):
        if n in raw:
            return 'present'             # already applied (replacements may contain the original)
        if raw.count(o) == 1 or (allow_many and raw.count(o) >= 1):
            raw = raw.replace(o, n)
            open(path, 'wb').write(raw.encode('utf-8', errors='surrogateescape'))
            return 'applied'
    return None


def main():
    # Edits in order, then the text rewrites; edits that match only the rewrites' output (on a
    # fresh tree from ffnx_prepare.sh "(DWORD)x" is not yet "ffnx_guest_va(x)") wait for the next
    # pass. Idempotent: a re-run applies nothing.
    changed, counts, pending = 0, [0, 0, 0, 0], list(EDITS)
    for _ in range(4):
        left = []
        for edit in pending:
            st = apply_edit(edit)
            if st == 'applied':
                changed += 1
            elif st is None:
                left.append(edit)
        r = (rewrite_patch_values(), rewrite_fnptr_globals(), rewrite_game_fn_casts(), rewrite_fps_tests())
        counts = [a + b for a, b in zip(counts, r)]
        pending = left
        if not pending:
            break
    if pending:
        sys.exit(f'{pending[0][0]}: edit did not match exactly once:\n{pending[0][1][:120]}')
    print(f'{changed} edits applied ({len(EDITS)} total); {counts[0]} patch values made guest addresses; '
          f'{counts[1]} game-function pointer variables made guest calls; '
          f'{counts[2]} casts of game addresses to native functions made guest calls; '
          f'{counts[3]} 60 FPS tests made "60 or above"')

if __name__ == '__main__':
    main()
