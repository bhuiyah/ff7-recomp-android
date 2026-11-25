/* shader_gles.cpp - a PC mod's Vulkan shaders on the OpenGL ES renderer (GitHub #89).
 *
 * Mods that replace FFNx's shaders ship what FFNx has on PC: *.gl, *.d3d11, *.d3d12 and *.vk.
 * None of them is an OpenGL ES shader (*.gles, tools/compile_shaders.sh), so the mod did nothing
 * with that renderer. Its Vulkan shader (SPIR-V in bgfx's container) is turned into ESSL with
 * SPIRV-Cross, the generator bgfx's shaderc makes our own *.gles shaders with.
 *
 * Where a Vulkan shader differs from shaderc's OpenGL ES one:
 *  - uniforms are members of a block; bgfx's GL renderer looks plain ones up by name
 *  - the block's matrices are row_major, plain GL uniforms hold the transposed matrix
 *  - textures and samplers are separate (tex_0Texture, tex_0Sampler); GL has one, tex_0
 *  - outputs are named @entryPointOutput.x, and stages meet by location; ours meet by name
 *  - FFNx.vert and FFNx.lighting.vert flip frame buffer textures on GL only
 * Everything else in the container (version, hashes, uniform list) is the same for both.
 */
#include "shader_gles.h"
#include <cstdint>
#include <cstring>
#include <exception>
#include <set>
#include "spirv_glsl.hpp"

namespace {

// Plain uniforms have no row_major layout: the conversions are written into the expressions.
struct Compiler : spirv_cross::CompilerGLSL {
    explicit Compiler(std::vector<uint32_t> words) : CompilerGLSL(std::move(words)) { backend.native_row_major_matrix = false; }
};

struct Reader {
    const uint8_t *p, *end;
    bool ok = true;
    const uint8_t *skip(size_t n) {
        if ((size_t)(end - p) < n) { ok = false; p = end; return nullptr; }
        const uint8_t *at = p;
        p += n;
        return at;
    }
    template <class T> T get() {
        T v{};
        if (const uint8_t *at = skip(sizeof v)) memcpy(&v, at, sizeof v);
        return v;
    }
};

bool is_ident(char c) { return c == '_' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }

void drop_prefix(std::string &s, const std::string &prefix) {
    for (size_t at = 0; (at = s.find(prefix, at)) != std::string::npos; ) {
        if (at && is_ident(s[at - 1])) at += prefix.size();
        else s.erase(at, prefix.size());
    }
}

// `struct T { ... }; uniform T inst;` -> the members as plain uniforms
bool flatten_block(std::string &s, const std::string &type, const std::string &inst, bool frag) {
    const size_t a = s.find("struct " + type + "\n{\n");
    if (a == std::string::npos) return false;
    const size_t body = s.find("{\n", a) + 2, b = s.find("};\n", body);
    if (b == std::string::npos) return false;
    const std::string tail = type + " " + inst + ";\n";
    const size_t t = s.find(tail, b);
    if (t == std::string::npos) return false;
    const size_t d = s.rfind('\n', t) + 1;
    s.erase(d, t + tail.size() - d);
    std::string plain;
    for (size_t at = body; at < b; ) {
        const size_t nl = s.find('\n', at);
        std::string line = s.substr(at, nl - at);
        at = nl + 1;
        const size_t f = line.find_first_not_of(' ');
        if (f == std::string::npos) continue;
        line.erase(0, f);
        if (line.compare(0, 7, "layout(") == 0) line.erase(0, line.find_first_not_of(' ', line.find(')') + 1));
        plain += std::string("uniform ") + (frag && line.compare(0, 6, "highp ") != 0 ? "highp " : "") + line + "\n";
    }
    s.replace(a, b + 3 - a, plain);
    drop_prefix(s, inst + ".");
    return true;
}

// drops the declaration of sampler `name` (the same type as `base`) and uses `base` in its place
bool merge_sampler(std::string &s, const std::string &name, const std::string &base) {
    const size_t at = s.find(" " + name + ";\n"), b = s.find(" " + base + ";\n");
    if (at == std::string::npos || b == std::string::npos) return false;
    const size_t a0 = s.rfind('\n', at) + 1, b0 = s.rfind('\n', b) + 1;
    if (s.substr(a0, at - a0) != s.substr(b0, b - b0)) return false;
    s.erase(a0, at + name.size() + 3 - a0);
    for (size_t p = 0; (p = s.find(name, p)) != std::string::npos; ) {
        if ((p && is_ident(s[p - 1])) || is_ident(s[p + name.size()])) p += name.size();
        else s.replace(p, name.size(), base);
    }
    return true;
}

}  // namespace

bool ff7_shader_vk_to_gles(const std::vector<unsigned char> &in, bool flip_fb, std::vector<unsigned char> &out, std::string &err) {
    try {
        Reader r{in.data(), in.data() + in.size()};
        char magic[4]{};
        if (const uint8_t *m = r.skip(4)) memcpy(magic, m, 4);
        if ((magic[0] != 'V' && magic[0] != 'F') || magic[1] != 'S' || magic[2] != 'H') { err = "not a bgfx shader"; return false; }
        if (magic[3] != 12) { err = "shader version " + std::to_string((int)magic[3]) + ", expected 12"; return false; }
        const bool frag = magic[0] == 'F';
        r.skip(4 + 4 + 8);   // hashes, raw bindings
        const unsigned count = r.get<uint16_t>();
        for (unsigned i = 0; i < count && r.ok; i++) {
            const unsigned name = r.get<uint8_t>();
            r.skip(name + 10);
        }
        const size_t head = (size_t)(r.p - in.data());
        const uint32_t size = r.get<uint32_t>();
        const uint8_t *code = r.skip(size);
        if (!r.ok || size < 20 || size % 4) { err = "cut short"; return false; }
        std::vector<uint32_t> words(size / 4);
        memcpy(words.data(), code, size);
        if (words[0] != 0x07230203u) { err = "no SPIR-V"; return false; }

        Compiler c(std::move(words));
        auto o = c.get_common_options();
        o.version = 310;
        o.es = true;
        o.vulkan_semantics = false;
        o.enable_420pack_extension = false;
        o.emit_uniform_buffer_as_plain_uniforms = true;
        o.fragment.default_float_precision = spirv_cross::CompilerGLSL::Options::Highp;
        c.set_common_options(o);

        c.build_dummy_sampler_for_combined_images();
        c.build_combined_image_samplers();
        std::set<std::string> used;
        std::vector<std::string> twice;   // one texture, read through its sampler and by texelFetch/textureSize
        for (auto &m : c.get_combined_image_samplers()) {
            std::string name = c.get_name(m.image_id);
            if (name.size() > 7 && name.compare(name.size() - 7, 7, "Texture") == 0) name.resize(name.size() - 7);
            if (!used.insert(name).second) {
                twice.push_back(name);
                name += "_ff7r" + std::to_string(twice.size());
            }
            c.set_name(m.combined_id, name);
        }
        auto res = c.get_shader_resources();
        if (!res.storage_images.empty() || !res.storage_buffers.empty() || res.uniform_buffers.size() > 1) {
            err = "uses storage or several uniform blocks";
            return false;
        }
        for (auto &v : res.sampled_images) {
            c.unset_decoration(v.id, spv::DecorationBinding);
            c.unset_decoration(v.id, spv::DecorationDescriptorSet);
        }
        for (auto &v : frag ? res.stage_inputs : res.stage_outputs) {
            c.unset_decoration(v.id, spv::DecorationLocation);
            const std::string n = c.get_name(v.id);
            const size_t dot = n.rfind('.');
            if (dot != std::string::npos) c.set_name(v.id, n.substr(dot + 1));
        }
        std::string type, inst;
        if (!res.uniform_buffers.empty()) {
            auto &u = res.uniform_buffers[0];
            c.unset_decoration(u.id, spv::DecorationBinding);
            c.unset_decoration(u.id, spv::DecorationDescriptorSet);
            c.set_name(u.base_type_id, type = "ff7r_Uniforms");
            c.set_name(u.id, inst = "ff7r_u");
        }
        std::string s = c.compile();
        if (s.compare(0, 8, "#version") == 0) s.erase(0, s.find('\n') + 1);   // bgfx writes its own
        for (size_t i = 0; i < twice.size(); i++)
            if (!merge_sampler(s, twice[i] + "_ff7r" + std::to_string(i + 1), twice[i])) { err = "two kinds of sampler read " + twice[i]; return false; }
        if (!type.empty() && !flatten_block(s, type, inst, frag)) { err = "uniform block not found"; return false; }
        if (flip_fb && !frag && s.find(" v_texcoord0;") != std::string::npos) {
            const size_t at = s.find("void main()");
            if (at == std::string::npos) { err = "no main"; return false; }
            s.replace(at, 11, "void ff7r_main()");
            if (s.find("uniform vec4 VSFlags;") == std::string::npos) s.insert(at, "uniform vec4 VSFlags;\n");
            s += "\nvoid main()\n{\n    ff7r_main();\n    if (VSFlags.z > 0.0) v_texcoord0.y = 1.0 - v_texcoord0.y;\n}\n";
        }
        out.assign(in.begin(), in.begin() + (long)head);
        const uint32_t n = (uint32_t)s.size();
        unsigned char nb[4];
        memcpy(nb, &n, 4);
        out.insert(out.end(), nb, nb + 4);
        out.insert(out.end(), s.begin(), s.end());
        out.push_back(0);
        return true;
    } catch (const std::exception &e) {
        err = e.what();
        return false;
    }
}
