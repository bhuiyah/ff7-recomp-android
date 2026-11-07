// DirectXTex.h - the slice of Microsoft's DirectXTex that FFNx uses to load .dds mod
// textures (image/image.cpp: parseDds/convertDds). DirectXTex is Windows-only. Until DDS
// loading is reimplemented on bimg (which parses DDS natively), every call fails loudly:
// FFNx then reports the texture as unloadable and falls back to the game's own texture.
#pragma once
#include <windows.h>
#include <cstdio>

typedef enum DXGI_FORMAT {
    DXGI_FORMAT_UNKNOWN = 0,
    DXGI_FORMAT_R8G8B8A8_UNORM = 28,
    DXGI_FORMAT_B8G8R8A8_UNORM = 87,
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

class ScratchImage {
    TexMetadata meta_;
    Image image_;
public:
    const Image *GetImages() const { return &image_; }
    size_t GetImageCount() const { return 0; }
    const TexMetadata &GetMetadata() const { return meta_; }
    uint8_t *GetPixels() const { return nullptr; }
    size_t GetPixelsSize() const { return 0; }
    void Release() {}
};

inline void dds_unsupported(const char *what) {
    static bool said;
    if (!said) {
        std::fprintf(stderr, "[ffnx-port] DDS textures are not supported yet on this platform (%s): "
                             "mod .dds textures will not load\n", what);
        said = true;
    }
}
inline HRESULT LoadFromDDSFile(const wchar_t *, DDS_FLAGS, TexMetadata *, ScratchImage &) { dds_unsupported("LoadFromDDSFile"); return E_FAIL; }
inline bool IsCompressed(DXGI_FORMAT) { return false; }
inline HRESULT Decompress(const Image &, DXGI_FORMAT, ScratchImage &) { dds_unsupported("Decompress"); return E_FAIL; }
inline HRESULT Convert(const Image &, DXGI_FORMAT, TEX_FILTER_FLAGS, float, ScratchImage &) { dds_unsupported("Convert"); return E_FAIL; }

}  // namespace DirectX
