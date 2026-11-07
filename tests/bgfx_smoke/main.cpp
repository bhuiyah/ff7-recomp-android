// bgfx smoke test: list compiled renderers, init Noop headless, run one frame, shut down.
#include <bgfx/bgfx.h>
#include <cstdio>

int main() {
    bgfx::RendererType::Enum types[bgfx::RendererType::Count];
    uint8_t n = bgfx::getSupportedRenderers(bgfx::RendererType::Count, types);
    std::printf("bgfx renderers compiled in:");
    for (uint8_t i = 0; i < n; i++) std::printf(" %s", bgfx::getRendererName(types[i]));
    std::printf("\n");

    bgfx::Init init;
    init.type = bgfx::RendererType::Noop;
    init.swapChain.width = 1920;     // FFNx's bgfx fork: resolution lives in Init::swapChain
    init.swapChain.height = 1080;
    if (!bgfx::init(init)) { std::printf("FAIL: bgfx::init\n"); return 1; }
    bgfx::setViewClear(0, BGFX_CLEAR_COLOR, 0x303030ff);
    bgfx::touch(0);
    uint32_t frame = bgfx::frame();
    std::printf("PASS: bgfx %s initialised, frame %u\n", bgfx::getRendererName(bgfx::getRendererType()), frame);
    bgfx::shutdown();
    return 0;
}
