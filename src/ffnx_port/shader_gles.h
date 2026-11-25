/* shader_gles.h - a mod's Vulkan shader as an OpenGL ES one (shader_gles.cpp, GitHub #89) */
#pragma once
#include <string>
#include <vector>

/* `in`: bgfx's container with SPIR-V; `out`: the same with ESSL. flip_fb: FFNx.vert and
 * FFNx.lighting.vert, which flip frame buffer textures on GL. false + err: not convertible. */
bool ff7_shader_vk_to_gles(const std::vector<unsigned char> &in, bool flip_fb, std::vector<unsigned char> &out, std::string &err);
