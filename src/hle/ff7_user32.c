/*
 * ff7_user32.c - the USER32 imports ff7_en.exe needs beyond pcrecomp's win32hle.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "win32hle.h"
#include "ff7_hle.h"

/* MessageBoxA(hwnd, text, caption, type). There is no dialog on the device; the text is
 * the diagnosis, so it is always printed. Answers IDOK (1) / IDYES (6) for yes/no boxes;
 * FF7_MSGBOX_FATAL=1 stops the run at the first box instead. */
static void u_MessageBoxA(void) {
    const char *text = ASTR(1), *cap = ASTR(2);
    uint32_t type = A32(3);
    fprintf(stderr, "[ff7] MessageBox \"%s\": %s (type 0x%X, from 0x%08X)\n",
            cap ? cap : "", text ? text : "", type, g_cur_func);
    if (getenv("FF7_MSGBOX_FATAL")) exit(94);
    uint32_t kind = type & 0xFu;                  /* MB_OK 0, MB_OKCANCEL 1, ..., MB_YESNO 4 */
    RET(kind == 3 || kind == 4 ? 6u : 1u, 4);
}

#define W32(p) (*(uint32_t *)(uintptr_t)(p))

/* The display the guest is told about: the Thor's main screen. */
#define SCREEN_W 1920
#define SCREEN_H 1080

static void u_LoadCursorA(void)  { RET(0x00C0C000u + (A32(1) & 0xFFFFu), 2); }  /* (hInst, name/id) */
static int g_cursor_count;
static void u_ShowCursor(void)   { g_cursor_count += A32(0) ? 1 : -1; RET((uint32_t)g_cursor_count, 1); }
static void u_SetRect(void) {                                   /* (rect, l, t, r, b) */
    uint32_t r = A32(0);
    if (!r) RET(0, 5);
    W32(r) = A32(1); W32(r + 4) = A32(2); W32(r + 8) = A32(3); W32(r + 12) = A32(4);
    RET(1, 5);
}
/* AdjustWindowRect(rect, style, menu): the window has no frame on the device, so the
 * client rect is the window rect. */
static void u_AdjustWindowRect(void) { RET(1, 3); }
/* SystemParametersInfoA(action, uiParam, pvParam, winIni): SPI_GETWORKAREA (0x30) is the
 * only query FF7 makes that needs an answer; others report success unchanged. */
static void u_SystemParametersInfoA(void) {
    uint32_t action = A32(0), pv = A32(2);
    if (action == 0x30u && pv) { W32(pv) = 0; W32(pv + 4) = 0; W32(pv + 8) = SCREEN_W; W32(pv + 12) = SCREEN_H; }
    RET(1, 4);
}
static void u_GetActiveWindow(void)  { RET(hle_first_hwnd(), 0); }
static void u_GetKeyState(void)      { RET(0, 1); }             /* keyboard input comes later */
static void u_GetAsyncKeyState(void) { RET(0, 1); }
/* BeginPaint(hwnd, PAINTSTRUCT*): hdc +0, fErase +4, rcPaint +8..+0x18. */
static void u_BeginPaint(void) {
    uint32_t ps = A32(1);
    if (ps) { W32(ps) = 0x00DC0001u; W32(ps + 4) = 0; W32(ps + 8) = 0; W32(ps + 12) = 0; W32(ps + 16) = SCREEN_W; W32(ps + 20) = SCREEN_H; }
    RET(0x00DC0001u, 2);
}
static void u_EndPaint(void)    { RET(1, 2); }
static void u_WaitMessage(void) { hle_yield_machine(); RET(1, 0); }

const win32hle_shim ff7_user32[] = {
    { "MessageBoxA", u_MessageBoxA },
    { "LoadCursorA", u_LoadCursorA },
    { "ShowCursor", u_ShowCursor },
    { "SetRect", u_SetRect },
    { "AdjustWindowRect", u_AdjustWindowRect },
    { "SystemParametersInfoA", u_SystemParametersInfoA },
    { "GetActiveWindow", u_GetActiveWindow },
    { "GetKeyState", u_GetKeyState },
    { "GetAsyncKeyState", u_GetAsyncKeyState },
    { "BeginPaint", u_BeginPaint },
    { "EndPaint", u_EndPaint },
    { "WaitMessage", u_WaitMessage },
    { NULL, NULL }
};
