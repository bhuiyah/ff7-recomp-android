// framegen.cpp - frame generation: in-between frames shown between the ones the game draws, made by
// Lossless Scaling's shaders from the player's own Lossless.dll (FF8Recomp #8). The game itself runs as
// ever (15 frames a second in battles, 30 elsewhere at its own rate): nothing is sped up, only more is shown.
//
// The engine is lsfg-vk's (external/deps/lsfg-vk-android, MIT). It runs on a Vulkan device of its
// own; images go between it and the renderer's device as AHardwareBuffers. Each frame the renderer
// presents is copied to one of two input buffers; the engine makes three frames between it and the
// one before (a quarter, half and three quarters of the way); those that fit the display's 60 a
// second are presented before the frame itself, each with the time it is due.
//
// Lossless.dll is never part of FF7Recomp: the launcher copies the player's to <files>/framegen,
// and its shaders (SPIR-V, RCDATA 304-351 half and 353-400 full precision) are read from there.
#include <android/hardware_buffer.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <map>
#include <string>
#include <unordered_map>
#include <vector>
#include <algorithm>
#include <stdexcept>
#define VK_USE_PLATFORM_ANDROID_KHR
#include "framegen.h"
#include <vulkan/vulkan_android.h>
#include "lsfg_3_1p.hpp"
#undef fopen   // ffnx_prelude.h's guest-path fopen: host paths here

bool ff7_framegen_movie_playing();   // framegen_game.cpp
int ff7_framegen_target();           // frames a second the game means to draw, 0: unknown
namespace {
bool movie_playing() { return ff7_framegen_movie_playing(); }
constexpr uint32_t k_between = 3;      // made per pair of frames at most: 15 to 60
constexpr double k_display = 60.0;
double g_lead = 0.015;                 // before a refresh, the time the compositor wants a frame

bool g_on, g_failed, g_engine, g_half = true;
float g_flow = 0.6f;
std::vector<uint8_t> g_dll;
std::unordered_map<uint32_t, std::pair<uint32_t, uint32_t>> g_rcdata;   // id -> file offset, size

// ---------------------------------------------------------------- Lossless.dll
uint32_t u32(size_t at) { uint32_t v = 0; if (at + 4 <= g_dll.size()) memcpy(&v, &g_dll[at], 4); return v; }
uint16_t u16(size_t at) { uint16_t v = 0; if (at + 2 <= g_dll.size()) memcpy(&v, &g_dll[at], 2); return v; }

// RVA -> file offset
size_t rva(uint32_t r) {
    const size_t pe = u32(0x3C), n = u16(pe + 6), sec = pe + 24 + u16(pe + 20);
    for (size_t i = 0; i < n; i++) {
        const size_t s = sec + 40 * i;
        const uint32_t va = u32(s + 12), raw = u32(s + 16);
        if (r >= va && r < va + raw) return u32(s + 20) + (r - va);
    }
    return 0;
}

bool read_dll(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return false;
    uint8_t buf[65536];
    for (size_t n; (n = fread(buf, 1, sizeof buf, f)) > 0 && g_dll.size() < (64u << 20);) g_dll.insert(g_dll.end(), buf, buf + n);
    fclose(f);
    const size_t pe = u32(0x3C);
    if (u16(0) != 0x5A4D || u32(pe) != 0x4550) return false;
    const size_t dir = pe + 24 + (u16(pe + 24) == 0x20B ? 112 : 96) + 8 * 2;   // resources
    const size_t root = rva(u32(dir));
    if (!root) return false;
    auto entries = [&](size_t at, auto fn) {
        const size_t n = std::min<size_t>(u16(at + 12) + u16(at + 14), 4096);
        for (size_t i = 0; i < n; i++) fn(u32(at + 16 + 8 * i), u32(at + 20 + 8 * i));
    };
    entries(root, [&](uint32_t type, uint32_t off) {
        if (type != 10 || !(off >> 31)) return;                                // RT_RCDATA
        entries(root + (off & 0x7FFFFFFF), [&](uint32_t id, uint32_t off2) {
            if (id >> 31 || !(off2 >> 31)) return;
            entries(root + (off2 & 0x7FFFFFFF), [&](uint32_t, uint32_t leaf) {
                if (leaf >> 31) return;
                const size_t at = rva(u32(root + leaf));
                const uint32_t size = u32(root + leaf + 4);
                if (at && at + size <= g_dll.size()) g_rcdata[id] = { (uint32_t)at, size };
            });
        });
    });
    return true;
}

// lsfg-vk's names for the performance variant's shaders -> the shader's number in the dll
uint32_t shader_id(const std::string &n) {
    static const std::map<std::string, uint32_t> t = {
        { "p_mipmaps", 255 }, { "p_alpha[0]", 290 }, { "p_alpha[1]", 291 }, { "p_alpha[2]", 292 }, { "p_alpha[3]", 293 },
        { "p_beta[0]", 298 }, { "p_beta[1]", 299 }, { "p_beta[2]", 300 }, { "p_beta[3]", 301 }, { "p_beta[4]", 302 },
        { "p_gamma[0]", 280 }, { "p_gamma[1]", 282 }, { "p_gamma[2]", 283 }, { "p_gamma[3]", 284 }, { "p_gamma[4]", 285 },
        { "p_delta[0]", 280 }, { "p_delta[1]", 286 }, { "p_delta[2]", 287 }, { "p_delta[3]", 288 }, { "p_delta[4]", 289 },
        { "p_delta[5]", 281 }, { "p_delta[6]", 294 }, { "p_delta[7]", 295 }, { "p_delta[8]", 296 }, { "p_delta[9]", 297 },
        { "p_generate", 256 } };
    const auto it = t.find(n);
    return it == t.end() ? 0 : it->second;
}

// The dll's SPIR-V numbers its bindings by HLSL register; the engine's layouts count uniform
// buffers, samplers, sampled images, then storage images (as DXVK's translation has them).
bool renumber(std::vector<uint8_t> &code) {
    const size_t n = code.size() / 4;
    std::vector<uint32_t> w(n);
    memcpy(w.data(), code.data(), n * 4);
    if (n < 5 || w[0] != 0x07230203) return false;
    std::unordered_map<uint32_t, int> kind;                     // type -> 0 buffer, 1 sampler, 2 sampled, 3 storage
    std::unordered_map<uint32_t, uint32_t> pointee, var;
    struct Site { size_t at; uint32_t id, binding; int kind; };
    std::vector<Site> sites;
    for (size_t i = 5; i < n;) {
        const uint32_t len = w[i] >> 16, op = w[i] & 0xFFFF;
        if (!len || i + len > n) return false;
        if (op == 54) break;                                    // OpFunction
        if (op == 26) kind[w[i + 1]] = 1;
        else if (op == 25 && len >= 8) kind[w[i + 1]] = w[i + 7] == 2 ? 3 : 2;
        else if (op == 27) kind[w[i + 1]] = 2;
        else if (op == 30) kind[w[i + 1]] = 0;
        else if (op == 32 && len == 4) pointee[w[i + 1]] = w[i + 3];
        else if (op == 59 && len >= 4) var[w[i + 2]] = w[i + 1];
        else if (op == 71 && len == 4 && w[i + 2] == 33) sites.push_back({ i + 3, w[i + 1], w[i + 3], -1 });
        i += len;
    }
    for (Site &s : sites) {
        const auto v = var.find(s.id);
        const auto p = v == var.end() ? pointee.end() : pointee.find(v->second);
        const auto k = p == pointee.end() ? kind.end() : kind.find(p->second);
        if (k == kind.end()) return false;
        s.kind = k->second;
    }
    std::stable_sort(sites.begin(), sites.end(), [](const Site &a, const Site &b) {
        return a.kind != b.kind ? a.kind < b.kind : a.binding < b.binding; });
    for (size_t k = 0; k < sites.size(); k++) w[sites[k].at] = (uint32_t)k;
    memcpy(code.data(), w.data(), n * 4);
    return true;
}

std::vector<uint8_t> shader(const std::string &name) {
    const uint32_t id = shader_id(name) + (g_half ? 49 : 98);
    const auto it = g_rcdata.find(id);
    if (it == g_rcdata.end()) return {};
    std::vector<uint8_t> code(g_dll.begin() + it->second.first, g_dll.begin() + it->second.first + it->second.second);
    if (!renumber(code)) return {};
    return code;
}

// ---------------------------------------------------------------- the renderer's side
struct Shared { AHardwareBuffer *ahb = nullptr; VkImage image = VK_NULL_HANDLE; VkDeviceMemory memory = VK_NULL_HANDLE; };
#define FG_FNS(X) X(CreateImage) X(DestroyImage) X(AllocateMemory) X(FreeMemory) X(BindImageMemory) X(CreateCommandPool) \
    X(AllocateCommandBuffers) X(BeginCommandBuffer) X(EndCommandBuffer) X(CmdPipelineBarrier) X(CmdBlitImage) X(QueueSubmit) \
    X(CreateFence) X(WaitForFences) X(ResetFences) X(CreateSemaphore) X(AcquireNextImageKHR) X(QueuePresentKHR) X(DeviceWaitIdle) \
    X(GetAndroidHardwareBufferPropertiesANDROID) X(DestroyFence) X(DestroySemaphore) X(DestroyCommandPool)
struct {
#define X(n) PFN_vk##n n;
    FG_FNS(X)
#undef X
} vk;

struct Slot { VkCommandBuffer cmd; VkFence fence; VkSemaphore acquired, done; };   // one per frame presented
constexpr uint32_t k_slots = 16;

VkDevice g_dev;
VkCommandPool g_pool;
Slot g_slot[k_slots];
uint32_t g_next_slot;
Shared g_in[2], g_out[k_between];
VkExtent2D g_extent;
int32_t g_context = -1;
uint64_t g_frames;              // captured since the context was made
int64_t g_last_ns, g_due_ns;
int g_cap = k_between + 1, g_over;   // frames shown for one drawn, at most
int64_t g_cap_since;
int g_target = -1;
double g_delay = 1.0 / 30, g_work = 0.016, g_work_most = 0.016, g_dts[5];

int64_t now_ns() { timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000000000ll + t.tv_nsec; }

PFN_vkGetRefreshCycleDurationGOOGLE g_get_refresh;
PFN_vkGetPastPresentationTimingGOOGLE g_get_past;
int64_t g_refresh_ns, g_vsync_ns;   // how long a refresh lasts, and when one was
int64_t g_last_vsync;               // the refresh the last frame was for

bool make_shared(const ff7_fg_frame &f, Shared &s) {
    AHardwareBuffer_Desc d = {};
    d.width = f.extent.width; d.height = f.extent.height; d.layers = 1;
    d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (int e = AHardwareBuffer_allocate(&d, &s.ahb)) { fprintf(stderr, "[framegen] no buffer: %d\n", e); return false; }
    VkAndroidHardwareBufferPropertiesANDROID p = { VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID };
    if (vk.GetAndroidHardwareBufferPropertiesANDROID(f.device, s.ahb, &p) != VK_SUCCESS) { fprintf(stderr, "[framegen] no buffer properties\n"); return false; }
    VkExternalMemoryImageCreateInfo ext = { VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO };
    ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo ci = { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &ext };
    ci.imageType = VK_IMAGE_TYPE_2D; ci.format = VK_FORMAT_R8G8B8A8_UNORM;
    ci.extent = { f.extent.width, f.extent.height, 1 }; ci.mipLevels = ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    if (VkResult e = vk.CreateImage(f.device, &ci, nullptr, &s.image)) { fprintf(stderr, "[framegen] no shared image: %d\n", e); return false; }
    VkMemoryDedicatedAllocateInfo ded = { VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO };
    ded.image = s.image;
    VkImportAndroidHardwareBufferInfoANDROID imp = { VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID, &ded };
    imp.buffer = s.ahb;
    VkMemoryAllocateInfo ai = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &imp };
    ai.allocationSize = p.allocationSize;
    while (ai.memoryTypeIndex < 32 && !(p.memoryTypeBits >> ai.memoryTypeIndex & 1)) ai.memoryTypeIndex++;
    VkResult e = vk.AllocateMemory(f.device, &ai, nullptr, &s.memory);
    if (e == VK_SUCCESS) e = vk.BindImageMemory(f.device, s.image, s.memory, 0);
    if (e != VK_SUCCESS) fprintf(stderr, "[framegen] buffer not imported: %d\n", e);
    return e == VK_SUCCESS;
}

void drop_shared(Shared &s) {
    if (s.image) vk.DestroyImage(g_dev, s.image, nullptr);
    if (s.memory) vk.FreeMemory(g_dev, s.memory, nullptr);
    if (s.ahb) AHardwareBuffer_release(s.ahb);
    s = {};
}

// The swapchain goes (a new size, the app in the background): the buffers are its size.
void drop() {
    if (!g_dev) return;
    vk.DeviceWaitIdle(g_dev);
    if (g_context != -1) { try { LSFG_3_1P::deleteContext(g_context); } catch (...) { } }
    g_context = -1;
    for (Shared &s : g_in) drop_shared(s);
    for (Shared &s : g_out) drop_shared(s);
    for (Slot &s : g_slot) {
        if (s.fence) vk.DestroyFence(g_dev, s.fence, nullptr);
        if (s.acquired) vk.DestroySemaphore(g_dev, s.acquired, nullptr);
        if (s.done) vk.DestroySemaphore(g_dev, s.done, nullptr);
        s = {};
    }
    if (g_pool) vk.DestroyCommandPool(g_dev, g_pool, nullptr);
    g_pool = VK_NULL_HANDLE;
    g_frames = 0;
    g_extent = {};
    g_dev = VK_NULL_HANDLE;
}

bool setup(const ff7_fg_frame &f) {
    const auto gdpa = (PFN_vkGetDeviceProcAddr)f.get(f.instance, "vkGetDeviceProcAddr");
#define X(n) if (!(vk.n = (PFN_vk##n)gdpa(f.device, "vk" #n))) { fprintf(stderr, "[framegen] no vk" #n "\n"); return false; }
    FG_FNS(X)
#undef X
    g_get_refresh = (PFN_vkGetRefreshCycleDurationGOOGLE)gdpa(f.device, "vkGetRefreshCycleDurationGOOGLE");
    g_get_past = (PFN_vkGetPastPresentationTimingGOOGLE)gdpa(f.device, "vkGetPastPresentationTimingGOOGLE");
    g_refresh_ns = g_vsync_ns = g_last_vsync = 0;
    g_dev = f.device;
    g_extent = f.extent;
    VkCommandPoolCreateInfo pi = { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
    pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pi.queueFamilyIndex = f.family;
    if (vk.CreateCommandPool(f.device, &pi, nullptr, &g_pool) != VK_SUCCESS) return false;
    for (Slot &s : g_slot) {
        VkCommandBufferAllocateInfo ai = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
        ai.commandPool = g_pool; ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; ai.commandBufferCount = 1;
        VkFenceCreateInfo fi = { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        VkSemaphoreCreateInfo si = { VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
        if (vk.AllocateCommandBuffers(f.device, &ai, &s.cmd) != VK_SUCCESS || vk.CreateFence(f.device, &fi, nullptr, &s.fence) != VK_SUCCESS ||
            vk.CreateSemaphore(f.device, &si, nullptr, &s.acquired) != VK_SUCCESS || vk.CreateSemaphore(f.device, &si, nullptr, &s.done) != VK_SUCCESS) { fprintf(stderr, "[framegen] no command buffers\n"); return false; }
    }
    for (Shared &s : g_in) if (!make_shared(f, s)) return false;
    for (Shared &s : g_out) if (!make_shared(f, s)) return false;
    if (!g_engine) {
        const auto gp = (PFN_vkGetPhysicalDeviceProperties)f.get(f.instance, "vkGetPhysicalDeviceProperties");
        const auto gf = (PFN_vkGetPhysicalDeviceFeatures2)f.get(f.instance, "vkGetPhysicalDeviceFeatures2");
        VkPhysicalDeviceProperties p;
        gp(f.physical, &p);
        if (p.apiVersion < VK_API_VERSION_1_3) { fprintf(stderr, "[framegen] needs a Vulkan 1.3 driver\n"); return false; }
        VkPhysicalDeviceVulkan12Features f12 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES };
        VkPhysicalDeviceFeatures2 f2 = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &f12 };
        if (gf) gf(f.physical, &f2);
        g_half = f12.shaderFloat16 && f12.vulkanMemoryModel && g_rcdata.count(304);
        LSFG_3_1P::initialize((uint64_t)p.vendorID << 32 | p.deviceID, false, g_flow, k_between, shader);
        g_engine = true;
    }
    std::vector<AHardwareBuffer *> out;
    for (Shared &s : g_out) out.push_back(s.ahb);
    g_context = LSFG_3_1P::createContextFromAHB(g_in[0].ahb, g_in[1].ahb, out, f.extent, VK_FORMAT_R8G8B8A8_UNORM);
    fprintf(stderr, "[framegen] on: %ux%u, %s precision, flow scale %.2f\n", f.extent.width, f.extent.height, g_half ? "half" : "full", g_flow);
    return true;
}

void barrier(VkCommandBuffer c, VkImage i, VkImageLayout from, VkImageLayout to, VkAccessFlags sa, VkAccessFlags da,
             uint32_t sq = VK_QUEUE_FAMILY_IGNORED, uint32_t dq = VK_QUEUE_FAMILY_IGNORED) {
    VkImageMemoryBarrier b = { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
    b.srcAccessMask = sa; b.dstAccessMask = da; b.oldLayout = from; b.newLayout = to;
    b.srcQueueFamilyIndex = sq; b.dstQueueFamilyIndex = dq; b.image = i;
    b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    vk.CmdPipelineBarrier(c, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
}

Slot &next_slot() {
    Slot &s = g_slot[g_next_slot++ % k_slots];
    vk.WaitForFences(g_dev, 1, &s.fence, VK_TRUE, UINT64_MAX);
    vk.ResetFences(g_dev, 1, &s.fence);
    VkCommandBufferBeginInfo bi = { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk.BeginCommandBuffer(s.cmd, &bi);
    return s;
}

void blit(VkCommandBuffer c, VkImage from, VkImageLayout fl, VkImage to, VkImageLayout tl) {
    VkImageBlit r = {};
    r.srcSubresource = r.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    r.srcOffsets[1] = r.dstOffsets[1] = { (int32_t)g_extent.width, (int32_t)g_extent.height, 1 };
    vk.CmdBlitImage(c, from, fl, to, tl, 1, &r, VK_FILTER_NEAREST);
}

VkResult submit(const ff7_fg_frame &f, Slot &s, VkSemaphore wait) {
    vk.EndCommandBuffer(s.cmd);
    const VkPipelineStageFlags st = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo si = { VK_STRUCTURE_TYPE_SUBMIT_INFO };
    si.waitSemaphoreCount = 1; si.pWaitSemaphores = &wait; si.pWaitDstStageMask = &st;
    si.commandBufferCount = 1; si.pCommandBuffers = &s.cmd;
    si.signalSemaphoreCount = 1; si.pSignalSemaphores = &s.done;
    return vk.QueueSubmit(f.queue, 1, &si, s.fence);
}

void refresh(const ff7_fg_frame &f) {
    if (!f.display_timing || !g_get_refresh || !g_get_past) return;
    VkRefreshCycleDurationGOOGLE d = {};
    if (g_get_refresh(g_dev, f.swapchain, &d) == VK_SUCCESS) g_refresh_ns = (int64_t)d.refreshDuration;
    VkPastPresentationTimingGOOGLE past[32];
    uint32_t n = 32;
    const VkResult e = g_get_past(g_dev, f.swapchain, &n, past);
    if ((e == VK_SUCCESS || e == VK_INCOMPLETE) && n) g_vsync_ns = (int64_t)past[n - 1].actualPresentTime;
}

// The middle of the refresh before the one at or after t, and never the same one twice.
int64_t aligned(int64_t t) {
    if (g_refresh_ns <= 0 || g_vsync_ns <= 0) return t;
    const int64_t r = g_refresh_ns;
    int64_t k = (t - g_vsync_ns + r - 1) / r;
    const int64_t last = g_last_vsync ? (g_last_vsync - g_vsync_ns + r / 2) / r : k - 1;
    if (k <= last && last - k < 4) k = last + 1;
    g_last_vsync = g_vsync_ns + k * r;
    return g_last_vsync - r / 2;
}

VkResult show(const ff7_fg_frame &f, uint32_t image, VkSemaphore wait, int64_t due) {
    VkPresentTimeGOOGLE t = { (uint32_t)g_next_slot, (uint64_t)due };
    VkPresentTimesInfoGOOGLE ti = { VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE };
    ti.swapchainCount = 1; ti.pTimes = &t;
    VkPresentInfoKHR pi = { VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, f.display_timing && due ? &ti : nullptr };
    pi.waitSemaphoreCount = 1; pi.pWaitSemaphores = &wait;
    pi.swapchainCount = 1; pi.pSwapchains = &f.swapchain; pi.pImageIndices = &image;
    return vk.QueuePresentKHR(f.queue, &pi);
}

int present(const ff7_fg_frame *pf, VkResult *result) {
    const ff7_fg_frame &f = *pf;
    VkSemaphore taken = VK_NULL_HANDLE;   // what the frame waits on once f.rendered is waited on
    if (g_failed || !f.external_memory) return 0;
    try {
        if (g_dev && (g_dev != f.device || g_extent.width != f.extent.width || g_extent.height != f.extent.height)) drop();
        if (!g_dev && !setup(f)) throw std::runtime_error("set-up failed");
        const int64_t now = now_ns();
        const double dt = g_last_ns ? (now - g_last_ns) * 1e-9 : g_delay;
        g_last_ns = now;
        // A frame that took much longer than the last ones (a load, a menu that waits) has nothing
        // between it and the one before.
        // When this frame is shown. What is made lies between the last frame and this one, so this one
        // has to wait while that is made and shown: that wait is felt as the controls' delay, and is
        // kept as short as it can be. (1 made: one refresh and the work; 60 a second already: none.)
        g_dts[g_frames % 5] = dt;
        double s[5];
        std::copy(g_dts, g_dts + 5, s);
        std::sort(s, s + 5);
        const double usual = std::clamp(s[2], 1 / k_display, (k_between + 1) / k_display);
        // No more than fit the game's own rate: a slow moment (a load) would have three made where one
        // belongs, and making them would keep the game slow.
        const int target = movie_playing() ? 0 : ff7_framegen_target();
        if (target != g_target) { g_target = target; g_cap = k_between + 1; g_over = 0; }
        const int fit = target > 0 ? std::clamp((int)(k_display / target + 0.5), 1, g_cap) : g_cap;
        const int n_usual = std::min((int)(usual * k_display + 0.5), fit);
        double wait = n_usual > 1 ? g_work + (n_usual - 1) / k_display : 0;
        // A movie's frames come unevenly (its 15 a second over a loop of 30) and nobody is steering:
        // shown as long after they come as the longest recent one took, they are even.
        if (dt > 0.004 && dt < 0.25) {
            const double longest = std::min(dt, (k_between + 1) / k_display);
            g_delay = std::max(longest, g_delay - (g_delay - longest) * 0.02);
        }
        if (movie_playing()) wait = g_delay + std::clamp(g_work_most, 0.012, 0.045);
        const int64_t drawn_due = now + (int64_t)(wait * 1e9);
        // Frames still waiting to be shown beyond that: the display is behind, and the more it is
        // given the longer the game waits for room (after a slow spell it would then stay slow).
        // None are added until it has caught up; the game then draws at its own rate again.
        const bool behind = g_refresh_ns > 0 && g_last_vsync - now > (int64_t)(wait * 1e9) + g_refresh_ns * 5 / 2;
        const bool still = g_frames == 0 || dt > 0.25 || behind || g_due_ns == 0;
        const double gap = still ? 0 : (drawn_due - g_due_ns) * 1e-9;
        int steps = std::clamp((int)(gap * k_display + 0.5), 1, fit);
        // One refresh each: after a hiccup the frames would stay that much later for good (felt as the
        // controls' delay). One is left out until they are back where they should be.
        if (steps > 1 && g_refresh_ns > 0 && g_last_vsync + steps * g_refresh_ns > drawn_due + g_refresh_ns * 3 / 2) steps--;

        // 1. the frame, to the input buffer the engine reads next
        Shared &in = g_in[g_frames % 2];
        Slot &a = next_slot();
        const VkImage img = f.images[f.image];
        barrier(a.cmd, img, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        barrier(a.cmd, in.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
        blit(a.cmd, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, in.image, VK_IMAGE_LAYOUT_GENERAL);
        barrier(a.cmd, in.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_WRITE_BIT, 0, f.family, VK_QUEUE_FAMILY_EXTERNAL);
        barrier(a.cmd, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_READ_BIT, 0);
        if (submit(f, a, f.rendered) != VK_SUCCESS) throw std::runtime_error("submit");
        taken = a.done;
        // the engine is another device: it can only be told through us
        vk.WaitForFences(g_dev, 1, &a.fence, VK_TRUE, UINT64_MAX);

        // 2. what lies between (the engine keeps its own history, so it runs for every frame)
        uint32_t mask = 0;
        for (int k = 1; !still && k < steps; k++) {
            const int q = (k * (int)(k_between + 1) + steps / 2) / steps;   // in quarters
            mask |= 1u << (q - 1);
        }
        static double s_render, s_gen; static int s_n;
        const int64_t t_r = now_ns();
        LSFG_3_1P::setPasses(mask);
        LSFG_3_1P::presentContext(g_context, -1, {});
        if (mask) LSFG_3_1P::waitIdle();
        g_frames++;
        if (mask) { s_render += (t_r - now) * 1e-6; s_gen += (now_ns() - t_r) * 1e-6; s_n++; }
        const double work = (now_ns() - now) * 1e-9 + g_lead;
        // Too slow for the time there is (a weak GPU): the game would wait for it, its frames would come
        // further apart, more would be made between them, and slower still. Fewer are made, down to
        // none; after a while one more is tried.
        if (dt > 0.004 && dt < 0.25) {
            const double part = (work - g_lead) / dt;
            g_over = part > 0.85 ? g_over + 1 : 0;
            if (g_over >= 8 && g_cap > 1) {
                g_cap--; g_over = 0; g_cap_since = now;
                fprintf(stderr, "[framegen] too slow here: %d made between at most\n", g_cap - 1);
            } else if (g_cap < (int)k_between + 1 && now - g_cap_since > 30000000000ll && part < 0.5) {
                g_cap++; g_cap_since = now;
            }
        }
        g_work_most = std::max(work, g_work_most - (g_work_most - work) * 0.02);
        if (mask) g_work += (std::min(work, 0.056) - g_work) * 0.1;

        // 3. shown in order, each when it is due. A due time is half a refresh before the refresh it is
        // for: the compositor shows the newest frame that is due and drops the others, so two must
        // never fall on one refresh.
        const int count = __builtin_popcount(mask) + 1;
        const int64_t each = (drawn_due - g_due_ns) / count;
        const int64_t first = g_due_ns;
        refresh(f);
        auto when = [&](int k) { return aligned(k == count - 1 ? drawn_due : first + each * (k + 1)); };
        int n = 0;
        for (uint32_t q = 0; q < k_between; q++) {
            if (!(mask >> q & 1)) continue;
            Slot &s = next_slot();
            uint32_t idx;
            const VkResult r = vk.AcquireNextImageKHR(g_dev, f.swapchain, 20000000, s.acquired, VK_NULL_HANDLE, &idx);
            if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {   // none free in time: this one is left out
                vk.EndCommandBuffer(s.cmd);
                VkSubmitInfo none = { VK_STRUCTURE_TYPE_SUBMIT_INFO };
                vk.QueueSubmit(f.queue, 1, &none, s.fence);
                break;
            }
            Shared &o = g_out[q];
            barrier(s.cmd, o.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_TRANSFER_READ_BIT, VK_QUEUE_FAMILY_EXTERNAL, f.family);
            barrier(s.cmd, f.images[idx], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT);
            blit(s.cmd, o.image, VK_IMAGE_LAYOUT_GENERAL, f.images[idx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            barrier(s.cmd, f.images[idx], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0);
            barrier(s.cmd, o.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_READ_BIT, 0, f.family, VK_QUEUE_FAMILY_EXTERNAL);
            if (submit(f, s, s.acquired) != VK_SUCCESS) throw std::runtime_error("submit");
            show(f, idx, s.done, when(n++));
        }
        *result = show(f, f.image, a.done, when(n));
        g_due_ns = drawn_due;
        static double s_late, s_late_most; static int s_late_n;
        if (g_last_vsync) { const double l = (g_last_vsync - now) * 1e-6; s_late += l; s_late_most = std::max(s_late_most, l); s_late_n++; }
        static const bool trace = getenv("FF7_FG_TRACE") != nullptr;
        static int traced;
        if (trace && traced++ % 600 < 60)
            fprintf(stderr, "[fg] %.1f delay %.1f steps %d made %d behind %d took %.1f ahead %.1f", dt * 1e3, g_delay * 1e3, steps, n, behind,
                    (now_ns() - now) * 1e-6, (g_last_vsync - now) * 1e-6), fputc(10, stderr);

        static int64_t t0; static uint32_t drawn, made;
        drawn++; made += n;
        if (!t0) t0 = now;
        if (now - t0 > 10000000000ll) {
            const double s = (now - t0) * 1e-9;
            fprintf(stderr, "[framegen] %.1f drawn + %.1f made a second, shown %.0f ms after, refresh %.2f ms%s\n", drawn / s, made / s, wait * 1e3, g_refresh_ns * 1e-6, f.display_timing ? "" : " (untimed)");
            if (s_n) fprintf(stderr, "[framegen] of that: the game's drawing %.1f ms, making %.1f ms", s_render / s_n, s_gen / s_n), fputc(10, stderr);
            if (s_late_n) fprintf(stderr, "[framegen] frames due %.0f ms after they were drawn, %.0f at most", s_late / s_late_n, s_late_most), fputc(10, stderr);
            s_late = s_late_most = 0; s_late_n = 0;
            s_render = s_gen = 0; s_n = 0;
            t0 = now; drawn = made = 0;
        }
        return 1;
    } catch (const std::exception &e) {
        // what the frame waited on may be used up: then it is shown here, and the next ones as ever
        fprintf(stderr, "[framegen] off: %s\n", e.what());
        g_failed = true;
        if (!taken) return 0;
        *result = show(f, f.image, taken, 0);
        return 1;
    }
}
}  // namespace

void framegen_init() {
    FILE *f = fopen("../framegen/settings.txt", "rb");
    if (!f) return;
    char line[64];
    while (fgets(line, sizeof line, f)) {
        if (!strncmp(line, "on=1", 4)) g_on = true;
        if (!strncmp(line, "flow=", 5)) g_flow = std::clamp((float)atof(line + 5), 0.5f, 1.0f);
    }
    fclose(f);
    if (const char *l = getenv("FF7_FG_LEAD")) g_lead = atof(l) * 1e-3;   // test
    if (!g_on) return;
    if (!read_dll("../framegen/Lossless.dll")) { fprintf(stderr, "[framegen] off: no readable Lossless.dll\n"); return; }
    bool half = true, full = true;
    for (uint32_t id = 255; id <= 302; id++) { half &= g_rcdata.count(id + 49) != 0; full &= g_rcdata.count(id + 98) != 0; }
    fprintf(stderr, "[framegen] Lossless.dll: %zu resources, half precision %s, full %s\n", g_rcdata.size(), half ? "yes" : "no", full ? "yes" : "no");
    if (!full) { fprintf(stderr, "[framegen] off: this Lossless.dll has no Vulkan shaders (update Lossless Scaling in Steam and import it again)\n"); return; }
    ff7_fg_images = 8;
    ff7_fg_swapchain_gone = drop;
    ff7_fg_present = present;
}
