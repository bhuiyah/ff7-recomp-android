// DirectXTex.h - the slice of Microsoft's DirectXTex that FFNx uses to load .dds mod textures
// (image/image.cpp: parseDds/convertDds), on bimg: DirectXTex is Windows-only. Mods may ship
// their textures as DDS.
#pragma once
#include <windows.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <memory>
#include <string>
#include <thread>
#include <vector>
#include <bx/allocator.h>
#include <bx/error.h>
#include <bimg/bimg.h>
#include <bimg/decode.h>

// The formats FFNx names; every other DDS format is DXGI_FORMAT_BIMG + its bimg format, kept
// opaque (FFNx only asks IsCompressed and compares it with these two).
typedef enum DXGI_FORMAT : uint32_t {
    DXGI_FORMAT_UNKNOWN = 0,
    DXGI_FORMAT_R8G8B8A8_UNORM = 28,
    DXGI_FORMAT_B8G8R8A8_UNORM = 87,
    DXGI_FORMAT_BIMG = 0x10000,
} DXGI_FORMAT;

namespace DirectX {

enum DDS_FLAGS : unsigned long { DDS_FLAGS_NONE = 0 };
enum TEX_FILTER_FLAGS : unsigned long { TEX_FILTER_DEFAULT = 0 };
constexpr float TEX_THRESHOLD_DEFAULT = 0.5f;

struct TexMetadata {
    size_t width = 0, height = 0, depth = 0, arraySize = 0, mipLevels = 0;
    uint32_t miscFlags = 0, miscFlags2 = 0;
    DXGI_FORMAT format = DXGI_FORMAT_UNKNOWN;
    int dimension = 0;
};

struct Image {
    size_t width = 0, height = 0;
    DXGI_FORMAT format = DXGI_FORMAT_UNKNOWN;
    size_t rowPitch = 0, slicePitch = 0;
    uint8_t *pixels = nullptr;
};

namespace port {
inline bx::AllocatorI &allocator() { static bx::DefaultAllocator a; return a; }

inline DXGI_FORMAT dxgi(bimg::TextureFormat::Enum f) {
    if (f == bimg::TextureFormat::RGBA8) return DXGI_FORMAT_R8G8B8A8_UNORM;
    if (f == bimg::TextureFormat::BGRA8) return DXGI_FORMAT_B8G8R8A8_UNORM;
    return DXGI_FORMAT(uint32_t(DXGI_FORMAT_BIMG) + uint32_t(f));
}

inline bimg::TextureFormat::Enum bimgFormat(DXGI_FORMAT f) {
    if (f == DXGI_FORMAT_R8G8B8A8_UNORM) return bimg::TextureFormat::RGBA8;
    if (f == DXGI_FORMAT_B8G8R8A8_UNORM) return bimg::TextureFormat::BGRA8;
    if (uint32_t(f) < uint32_t(DXGI_FORMAT_BIMG)) return bimg::TextureFormat::Unknown;
    return bimg::TextureFormat::Enum(uint32_t(f) - uint32_t(DXGI_FORMAT_BIMG));
}

struct FreeImage { void operator()(bimg::ImageContainer *c) const { if (c) bimg::imageFree(c); } };
}  // namespace port

class ScratchImage {
    TexMetadata meta_;
    std::vector<Image> images_;
    std::vector<uint8_t> pixels_;                                   // a decoded image
    std::unique_ptr<bimg::ImageContainer, port::FreeImage> file_;   // a parsed file
public:
    const Image *GetImages() const { return images_.data(); }
    size_t GetImageCount() const { return images_.size(); }
    const TexMetadata &GetMetadata() const { return meta_; }
    uint8_t *GetPixels() const { return images_.empty() ? nullptr : images_[0].pixels; }
    size_t GetPixelsSize() const { return images_.empty() ? 0 : images_[0].slicePitch; }
    void Release() { images_.clear(); pixels_ = {}; file_.reset(); meta_ = {}; }

    // Takes the container; the mips of its first layer are the images, largest first. The sizes
    // are the file's own (bimg rounds a block format's up to whole blocks), and only the mips the
    // file holds count (present bytes, mips of them): bimg always makes the full chain.
    bool setFile(bimg::ImageContainer *c, uint32_t width, uint32_t height, size_t present, uint32_t mips) {
        Release();
        file_.reset(c);
        if (c == nullptr) return false;
        for (uint8_t lod = 0; lod < std::max<uint32_t>(1, std::min<uint32_t>(mips, c->m_numMips)); ++lod) {
            bimg::ImageMip mip;
            if (!bimg::imageGetRawData(*c, 0, lod, c->m_data, c->m_size, mip) || mip.m_data == nullptr) break;
            if (size_t(mip.m_data - (const uint8_t *)c->m_data) + mip.m_size > present) break;
            Image im;
            im.width = std::max<uint32_t>(1, width >> lod);
            im.height = std::max<uint32_t>(1, height >> lod);
            im.format = port::dxgi(mip.m_format);
            im.slicePitch = mip.m_size;
            im.rowPitch = mip.m_size / im.height;
            im.pixels = const_cast<uint8_t *>(mip.m_data);
            images_.push_back(im);
        }
        if (images_.empty()) { Release(); return false; }
        meta_.width = width;
        meta_.height = height;
        meta_.depth = c->m_depth;
        meta_.arraySize = c->m_numLayers;
        meta_.mipLevels = images_.size();
        meta_.format = images_[0].format;
        meta_.dimension = 3;   // TEX_DIMENSION_TEXTURE2D
        return true;
    }

    uint8_t *setPixels(size_t w, size_t h, DXGI_FORMAT format) {
        Release();
        pixels_.resize(w * h * 4);
        Image im;
        im.width = w; im.height = h; im.format = format;
        im.rowPitch = w * 4; im.slicePitch = w * h * 4; im.pixels = pixels_.data();
        images_.push_back(im);
        meta_.width = w; meta_.height = h; meta_.depth = 1; meta_.arraySize = 1; meta_.mipLevels = 1;
        meta_.format = format; meta_.dimension = 3;
        return pixels_.data();
    }
};

inline HRESULT LoadFromDDSFile(const wchar_t *path, DDS_FLAGS, TexMetadata *meta, ScratchImage &image) {
    char name[1024];
    const size_t len = wcstombs(name, path, sizeof name);
    if (len == size_t(-1) || len >= sizeof name) return E_FAIL;
    FILE *f = fopen(name, "rb");   // the prelude's: mods included
    if (f == nullptr) return E_FAIL;
    fseek(f, 0, SEEK_END);
    const long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::vector<uint8_t> data(size > 0 ? size_t(size) : 0);
    const bool read = !data.empty() && fread(data.data(), 1, data.size(), f) == data.size();
    fclose(f);
    if (!read || data.size() < 128 || memcmp(data.data(), "DDS ", 4) != 0) return E_FAIL;
    uint32_t height, width, mips;   // DDS_HEADER
    memcpy(&height, data.data() + 12, 4);
    memcpy(&width, data.data() + 16, 4);
    memcpy(&mips, data.data() + 28, 4);
    if (width == 0 || height == 0 || width > 16384 || height > 16384) return E_FAIL;
    bimg::ImageContainer head;
    bx::Error err;
    if (!bimg::imageParse(head, data.data(), uint32_t(data.size()), &err) || !err.isOk() || head.m_offset >= data.size()) return E_FAIL;
    if (head.m_cubeMap || head.m_depth > 1 || head.m_numLayers > 1) return E_FAIL;
    // bimg copies a full mip chain whatever the file holds: keep that inside the buffer.
    const size_t present = data.size() - head.m_offset;
    data.resize(head.m_offset + size_t((width + 3) & ~3u) * ((height + 3) & ~3u) * bimg::getBitsPerPixel(head.m_format) / 8 * 2 + 4096);
    if (!image.setFile(bimg::imageParseDds(&port::allocator(), data.data(), uint32_t(data.size()), &err), width, height, present, mips)) return E_FAIL;
    if (meta != nullptr) *meta = image.GetMetadata();
    return S_OK;
}

inline bool IsCompressed(DXGI_FORMAT f) {
    const bimg::TextureFormat::Enum b = port::bimgFormat(f);
    return b != bimg::TextureFormat::Unknown && bimg::isCompressed(b);
}

// The BC formats decode in rows of 4x4 blocks on all cores, into a block-aligned buffer (a mip
// may be smaller than its blocks); the image is then cut out of it.
inline HRESULT Decompress(const Image &src, DXGI_FORMAT format, ScratchImage &out) {
    const bimg::TextureFormat::Enum in = port::bimgFormat(src.format);
    if (in < bimg::TextureFormat::BC1 || in > bimg::TextureFormat::BC7) return E_FAIL;
    if (format != DXGI_FORMAT_B8G8R8A8_UNORM && format != DXGI_FORMAT_R8G8B8A8_UNORM) return E_FAIL;
    const uint32_t w = uint32_t(src.width + 3) & ~3u, h = uint32_t(src.height + 3) & ~3u, rows = h / 4;
    const size_t srcRow = size_t(w) * bimg::getBitsPerPixel(in) / 8 * 4, dstRow = size_t(w) * 4 * 4;
    if (w == 0 || rows == 0 || srcRow * rows > src.slicePitch) return E_FAIL;
    std::vector<uint8_t> full(dstRow * rows);
    std::atomic<uint32_t> next{0};
    auto work = [&] {
        bx::DefaultAllocator scratch;
        for (;;) {
            const uint32_t b = next.fetch_add(4);
            if (b >= rows) break;
            const uint32_t n = std::min(4u, rows - b);
            uint8_t *d = full.data() + b * dstRow;
            const uint8_t *s = src.pixels + b * srcRow;
            if (format == DXGI_FORMAT_B8G8R8A8_UNORM) bimg::imageDecodeToBgra8(&scratch, d, s, w, n * 4, w * 4, in);
            else bimg::imageDecodeToRgba8(&scratch, d, s, w, n * 4, w * 4, in);
        }
    };
    std::vector<std::thread> pool;
    if (rows >= 64) {
        const unsigned helpers = std::min(7u, std::max(1u, std::thread::hardware_concurrency()) - 1);
        for (unsigned i = 0; i < helpers; i++) pool.emplace_back(work);
    }
    work();
    for (std::thread &t : pool) t.join();
    const size_t width = src.width, height = src.height;
    uint8_t *d = out.setPixels(width, height, format);
    for (size_t y = 0; y < height; y++) memcpy(d + y * width * 4, full.data() + y * size_t(w) * 4, width * 4);
    return S_OK;
}

inline HRESULT Convert(const Image &src, DXGI_FORMAT format, TEX_FILTER_FLAGS, float, ScratchImage &out) {
    const bimg::TextureFormat::Enum in = port::bimgFormat(src.format), to = port::bimgFormat(format);
    if (in == bimg::TextureFormat::Unknown || to == bimg::TextureFormat::Unknown || bimg::isCompressed(in)) return E_FAIL;
    const uint32_t w = uint32_t(src.width), h = uint32_t(src.height);
    std::vector<uint8_t> pixels(size_t(w) * h * 4);
    if (!bimg::imageConvert(&port::allocator(), pixels.data(), to, src.pixels, in, w, h, 1)) return E_FAIL;
    memcpy(out.setPixels(w, h, format), pixels.data(), pixels.size());
    return S_OK;
}

}  // namespace DirectX
