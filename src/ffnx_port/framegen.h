// framegen.h - the hook the Vulkan renderer (patches/bgfx/0007) calls in place of its present of the
// main screen, for frame generation (framegen.cpp).
#pragma once
#include <vulkan/vulkan.h>

struct ff7_fg_frame {
    VkInstance instance;
    VkPhysicalDevice physical;
    VkDevice device;
    VkQueue queue;
    uint32_t family;
    VkSwapchainKHR swapchain;
    const VkImage *images;      // the swapchain's
    uint32_t image;             // the one drawn, in PRESENT_SRC
    VkExtent2D extent;
    VkSemaphore rendered;       // signalled once it is drawn
    PFN_vkGetInstanceProcAddr get;
    int external_memory;        // the device has the AHardwareBuffer import extensions
    int display_timing;         // ... VK_GOOGLE_display_timing
};

extern "C" {
// null: off. Returns 1 when it presented the frame (*result: what vkQueuePresentKHR said), else 0.
extern int (*ff7_fg_present)(const ff7_fg_frame *, VkResult *result);
// the swapchain is about to go (the device is idle): drop what refers to its images
extern void (*ff7_fg_swapchain_gone)(void);
extern uint32_t ff7_fg_images;   // swapchain images wanted, 0 = as usual
}

void framegen_init();            // reads ../framegen; before the renderer starts
