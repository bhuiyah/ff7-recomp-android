// ffnx_display.h - bring-up display: bgfx on the platform window, before FFNx's renderer.
//
// ffnx_display_init() starts bgfx (Vulkan) on ff7_platform_window() if there is one and
// shows a status screen. ffnx_display_status(text) puts a line of status on that screen
// and returns true; with no window (headless runner) both do nothing and status returns
// false. Builds without bgfx (FF7_WITH_BGFX undefined) are the headless case.
#pragma once

bool ffnx_display_init();
bool ffnx_display_status(const char *text);
