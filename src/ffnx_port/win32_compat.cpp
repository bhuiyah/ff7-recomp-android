// win32_compat.cpp - the Win32 calls FFNx's *own* code makes (src/ffnx_port/include/*.h),
// on Android. The lifted game's Win32 calls go through win32hle instead; this is only
// FFNx's view. Where Android has an equivalent it is used (files, time, paths); where it
// has none the call answers the way Windows does when the thing is absent (no registry
// key, no DLL, no extra monitor), so FFNx takes its existing fallback path.
#include <dirent.h>
#include <fnmatch.h>
#include <pthread.h>
#include <sched.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#include <mutex>
#include <windows.h>
#include <mmsystem.h>
#include <shlwapi.h>
#include <shlobj.h>
#include "libavcodec/avcodec.h"
#include "ffnx_android.h"

extern HWND gameHwnd;   // FFNx's global (C++ linkage)
static HWND ffnx_game_hwnd() { return gameHwnd; }

namespace {
thread_local DWORD t_last_error;

// HANDLEs for FFNx's own files: index + base into a FILE* table.
constexpr uint32_t FH_BASE = 0x00FF0000u;
constexpr int FH_MAX = 256;
FILE *g_fh[FH_MAX];
std::mutex g_fh_lock;
FILE *fh_get(HANDLE h) { uint32_t i = h - FH_BASE; return (h >= FH_BASE && i < FH_MAX) ? g_fh[i] : nullptr; }

struct find_state { DIR *d; char dir[1024]; char pat[256]; };
}  // namespace

extern "C" {

// ---- process / time
DWORD GetCurrentDirectoryA(DWORD n, LPSTR buf) { if (!getcwd(buf, n)) return 0; return (DWORD)strlen(buf); }
BOOL SetCurrentDirectoryA(LPCSTR p) { return chdir(p) == 0; }
// NULL is the game; FFNX_MODULE (ffnx_boot.cpp) is FFNx itself, installed as the game's
// graphics driver AF3DN.P, which is how DllMain tells it is driving FF7 (not FF8).
DWORD GetModuleFileNameA(HMODULE m, LPSTR buf, DWORD n) {
    char cwd[1024]; if (!getcwd(cwd, sizeof cwd)) cwd[0] = 0;
    return (DWORD)snprintf(buf, n, "%s/%s", cwd, m == FFNX_MODULE ? "AF3DN.P" : "ff7_en.exe");
}
DWORD GetTickCount(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (DWORD)(t.tv_sec * 1000ull + t.tv_nsec / 1000000ull); }
ULONGLONG GetTickCount64(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000ull + t.tv_nsec / 1000000ull; }
BOOL QueryPerformanceCounter(LARGE_INTEGER *c) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); c->QuadPart = (LONGLONG)t.tv_sec * 1000000000ll + t.tv_nsec; return TRUE; }
BOOL QueryPerformanceFrequency(LARGE_INTEGER *f) { f->QuadPart = 1000000000ll; return TRUE; }
void Sleep(DWORD ms) { if (ms) usleep(ms * 1000u); else sched_yield(); }
DWORD GetLastError(void) { return t_last_error; }
void SetLastError(DWORD e) { t_last_error = e; }
DWORD GetCurrentThreadId(void) { return (DWORD)gettid(); }
DWORD GetCurrentProcessId(void) { return (DWORD)getpid(); }
void OutputDebugStringA(LPCSTR s) { fputs(s, stderr); }
DWORD timeGetTime(void) { return GetTickCount(); }
MMRESULT timeBeginPeriod(UINT) { return 0; }
MMRESULT timeEndPeriod(UINT) { return 0; }
// Multimedia timers: FFNx's sfx uses one for fades. A detached thread per timer that
// calls back on schedule (periodic or once) until killed.
struct mm_timer { UINT id, delay; LPTIMECALLBACK cb; DWORD_PTR user; UINT flags; volatile bool alive; };
static std::mutex g_timer_lock;
static mm_timer *g_timers[64];
static void *mm_timer_thread(void *arg) {
    mm_timer *t = static_cast<mm_timer *>(arg);
    do {
        usleep(t->delay * 1000u);
        if (!t->alive) break;
        t->cb(t->id, 0, t->user, 0, 0);
    } while (t->alive && (t->flags & TIME_PERIODIC));
    return nullptr;
}
MMRESULT timeSetEvent(UINT delay, UINT, LPTIMECALLBACK cb, DWORD_PTR user, UINT flags) {
    std::lock_guard<std::mutex> lk(g_timer_lock);
    for (UINT i = 0; i < 64; i++) if (!g_timers[i]) {
        mm_timer *t = new mm_timer{ i + 1, delay ? delay : 1, cb, user, flags, true };
        g_timers[i] = t;
        pthread_t th; pthread_create(&th, nullptr, mm_timer_thread, t); pthread_detach(th);
        return t->id;
    }
    return 0;
}
MMRESULT timeKillEvent(UINT id) {
    std::lock_guard<std::mutex> lk(g_timer_lock);
    if (id == 0 || id > 64 || !g_timers[id - 1]) return 1;
    g_timers[id - 1]->alive = false;   // the thread exits on its next wake; the record is leaked deliberately
    g_timers[id - 1] = nullptr;
    return 0;
}
void GetLocalTime(LPSYSTEMTIME st) {
    struct timeval tv; gettimeofday(&tv, nullptr); struct tm t; time_t s = tv.tv_sec; localtime_r(&s, &t);
    st->wYear = (WORD)(t.tm_year + 1900); st->wMonth = (WORD)(t.tm_mon + 1); st->wDayOfWeek = (WORD)t.tm_wday; st->wDay = (WORD)t.tm_mday;
    st->wHour = (WORD)t.tm_hour; st->wMinute = (WORD)t.tm_min; st->wSecond = (WORD)t.tm_sec; st->wMilliseconds = (WORD)(tv.tv_usec / 1000);
}
void GetSystemTime(LPSYSTEMTIME st) {
    struct timeval tv; gettimeofday(&tv, nullptr); struct tm t; time_t s = tv.tv_sec; gmtime_r(&s, &t);
    st->wYear = (WORD)(t.tm_year + 1900); st->wMonth = (WORD)(t.tm_mon + 1); st->wDayOfWeek = (WORD)t.tm_wday; st->wDay = (WORD)t.tm_mday;
    st->wHour = (WORD)t.tm_hour; st->wMinute = (WORD)t.tm_min; st->wSecond = (WORD)t.tm_sec; st->wMilliseconds = (WORD)(tv.tv_usec / 1000);
}
BOOL GlobalMemoryStatusEx(LPMEMORYSTATUSEX m) {
    long pages = sysconf(_SC_PHYS_PAGES), avail = sysconf(_SC_AVPHYS_PAGES), psz = sysconf(_SC_PAGE_SIZE);
    m->dwMemoryLoad = pages ? (DWORD)(100 - avail * 100 / pages) : 0;
    m->ullTotalPhys = (DWORDLONG)pages * psz; m->ullAvailPhys = (DWORDLONG)avail * psz;
    m->ullTotalPageFile = m->ullTotalPhys; m->ullAvailPageFile = m->ullAvailPhys;
    m->ullTotalVirtual = 0x80000000ull; m->ullAvailVirtual = 0x70000000ull; m->ullAvailExtendedVirtual = 0;
    return TRUE;
}
int MessageBoxA(HWND, LPCSTR text, LPCSTR caption, UINT) {
    fprintf(stderr, "[ffnx] MessageBox \"%s\": %s\n", caption ? caption : "", text ? text : "");
    return 1;     // IDOK
}
DWORD FormatMessageA(DWORD, LPCVOID, DWORD id, DWORD, LPSTR buf, DWORD n, va_list *) {
    return buf && n ? (DWORD)snprintf(buf, n, "error %u", id) : 0;
}
HLOCAL LocalFree(HLOCAL) { return 0; }

void InitializeCriticalSection(LPCRITICAL_SECTION cs) {
    pthread_mutexattr_t a; pthread_mutexattr_init(&a); pthread_mutexattr_settype(&a, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_t *m = new pthread_mutex_t; pthread_mutex_init(m, &a); cs->impl = m;
}
void DeleteCriticalSection(LPCRITICAL_SECTION cs) { if (cs->impl) { pthread_mutex_destroy((pthread_mutex_t *)cs->impl); delete (pthread_mutex_t *)cs->impl; cs->impl = nullptr; } }
void EnterCriticalSection(LPCRITICAL_SECTION cs) { pthread_mutex_lock((pthread_mutex_t *)cs->impl); }
void LeaveCriticalSection(LPCRITICAL_SECTION cs) { pthread_mutex_unlock((pthread_mutex_t *)cs->impl); }

// ---- windows (the game's window lives in win32hle; FFNx's own window calls are inert)
int ShowCursor(BOOL show) { static int c; c += show ? 1 : -1; return c; }
BOOL MoveWindow(HWND, int, int, int, int, BOOL) { return TRUE; }
LONG SetWindowLongA(HWND, int, LONG) { return 0; }
LONG GetWindowLongA(HWND, int) { return 0; }
BOOL SetWindowTextA(HWND, LPCSTR) { return TRUE; }
SHORT GetKeyState(int) { return 0; }
SHORT GetAsyncKeyState(int) { return 0; }
BOOL SetRectEmpty(LPRECT r) { if (r) memset(r, 0, sizeof *r); return TRUE; }
BOOL PeekMessageA(LPMSG, HWND, UINT, UINT, UINT) { return FALSE; }
BOOL TranslateMessage(const MSG *) { return FALSE; }
LRESULT DispatchMessageA(const MSG *) { return 0; }
LRESULT DefWindowProcA(HWND, UINT, WPARAM, LPARAM) { return 0; }
LRESULT CallWindowProcA(WNDPROC p, HWND w, UINT m, WPARAM wp, LPARAM lp) { return p ? p(w, m, wp, lp) : 0; }
BOOL SetProcessDPIAware(void) { return TRUE; }
BOOL GetMonitorInfoA(HMONITOR, MONITORINFOEXA *mi) {
    // One monitor: the app's surface.
    if (!mi) return FALSE;
    int w = 0, h = 0;
    extern void *ff7_platform_window(int *, int *);
    ff7_platform_window(&w, &h);
    mi->rcMonitor = { 0, 0, w, h }; mi->rcWork = mi->rcMonitor; mi->dwFlags = MONITORINFOF_PRIMARY;
    snprintf(mi->szDevice, sizeof mi->szDevice, "\\\\.\\DISPLAY1");
    return TRUE;
}
LONG ChangeDisplaySettingsExA(LPCSTR, DEVMODEA *, HWND, DWORD, LPVOID) { return DISP_CHANGE_SUCCESSFUL; }
HCURSOR LoadCursorA(HINSTANCE, LPCSTR) { return 1; }
HCURSOR SetCursor(HCURSOR c) { return c; }
BOOL GetCursorPos(LPPOINT p) { if (p) { p->x = 0; p->y = 0; } return TRUE; }
BOOL SetCursorPos(int, int) { return TRUE; }
BOOL ScreenToClient(HWND, LPPOINT) { return TRUE; }
BOOL ClientToScreen(HWND, LPPOINT) { return TRUE; }
BOOL GetClientRect(HWND, LPRECT r) {
    int w = 0, h = 0;
    extern void *ff7_platform_window(int *, int *);
    ff7_platform_window(&w, &h);
    if (r) *r = { 0, 0, w, h };
    return TRUE;
}
HWND GetCapture(void) { return 0; }
HWND SetCapture(HWND) { return 0; }
BOOL ReleaseCapture(void) { return TRUE; }
HWND GetForegroundWindow(void) { return ffnx_game_hwnd(); }
HWND GetActiveWindow(void) { return ffnx_game_hwnd(); }
BOOL IsChild(HWND, HWND) { return FALSE; }
BOOL WaitMessage(void) { sched_yield(); return TRUE; }
LSTATUS RegSetValueExA(HKEY, LPCSTR, DWORD, DWORD, const BYTE *, DWORD) { return ERROR_FILE_NOT_FOUND; }
LSTATUS RegFlushKey(HKEY) { return ERROR_SUCCESS; }
BOOL MoveFileExA(LPCSTR from, LPCSTR to, DWORD) { return rename(from, to) == 0; }
char *StrStrIA(const char *hay, const char *needle) { return (char *)strcasestr(hay, needle); }
BOOL AdjustWindowRect(LPRECT, DWORD, BOOL) { return TRUE; }

// ---- modules / exceptions: no DLLs to load; crashes are reported by src/host/ff7_crash.c
HMODULE LoadLibraryA(LPCSTR) { return 0; }
FARPROC GetProcAddress(HMODULE, LPCSTR) { return nullptr; }
BOOL FreeLibrary(HMODULE) { return TRUE; }
UINT GetSystemDirectoryA(LPSTR buf, UINT n) { return buf && n ? (UINT)snprintf(buf, n, "/system/lib64") : 0; }
LPTOP_LEVEL_EXCEPTION_FILTER SetUnhandledExceptionFilter(LPTOP_LEVEL_EXCEPTION_FILTER) { return nullptr; }
PVOID AddVectoredExceptionHandler(ULONG, PVECTORED_EXCEPTION_HANDLER) { return nullptr; }
DWORD SetThreadExecutionState(DWORD) { return ES_CONTINUOUS; }   // the activity keeps the screen on
int _setmaxstdio(int n) { return n; }

// ---- registry: absent
LSTATUS RegOpenKeyExA(HKEY, LPCSTR, DWORD, REGSAM, PHKEY out) { if (out) *out = 0; return ERROR_FILE_NOT_FOUND; }
LSTATUS RegQueryValueExA(HKEY, LPCSTR, LPDWORD, LPDWORD, LPBYTE, LPDWORD) { return ERROR_FILE_NOT_FOUND; }
LSTATUS RegCloseKey(HKEY) { return ERROR_SUCCESS; }

// ---- files
HANDLE CreateFileA(LPCSTR name, DWORD access, DWORD, LPSECURITY_ATTRIBUTES, DWORD disp, DWORD, HANDLE) {
    const char *mode = (disp == CREATE_ALWAYS || disp == CREATE_NEW) ? "wb+" : (access & GENERIC_WRITE) ? "rb+" : "rb";
    FILE *f = name ? fopen(name, mode) : nullptr;
    if (!f && disp == OPEN_ALWAYS && name) f = fopen(name, "wb+");
    if (!f) { t_last_error = ERROR_FILE_NOT_FOUND; return INVALID_HANDLE_VALUE; }
    std::lock_guard<std::mutex> lk(g_fh_lock);
    for (int i = 0; i < FH_MAX; i++) if (!g_fh[i]) { g_fh[i] = f; return FH_BASE + (uint32_t)i; }
    fclose(f);
    return INVALID_HANDLE_VALUE;
}
BOOL ReadFile(HANDLE h, LPVOID buf, DWORD n, LPDWORD got, void *) {
    FILE *f = fh_get(h); size_t r = f ? fread(buf, 1, n, f) : 0; if (got) *got = (DWORD)r; return f != nullptr;
}
BOOL WriteFile(HANDLE h, LPCVOID buf, DWORD n, LPDWORD put, void *) {
    FILE *f = fh_get(h); size_t w = f ? fwrite(buf, 1, n, f) : 0; if (put) *put = (DWORD)w; return f && w == n;
}
DWORD GetFileSize(HANDLE h, LPDWORD high) {
    FILE *f = fh_get(h); if (!f) return 0xFFFFFFFFu;
    struct stat st; fstat(fileno(f), &st); if (high) *high = (DWORD)((uint64_t)st.st_size >> 32); return (DWORD)st.st_size;
}
BOOL CloseHandle(HANDLE h) {
    std::lock_guard<std::mutex> lk(g_fh_lock);
    uint32_t i = h - FH_BASE;
    if (h >= FH_BASE && i < FH_MAX && g_fh[i]) { fclose(g_fh[i]); g_fh[i] = nullptr; return TRUE; }
    return FALSE;
}
BOOL DeleteFileA(LPCSTR name) { return unlink(name) == 0; }
DWORD GetFileAttributesA(LPCSTR path) { struct stat st; if (stat(path, &st)) return INVALID_FILE_ATTRIBUTES; return S_ISDIR(st.st_mode) ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL; }
BOOL CreateDirectoryA(LPCSTR path, LPSECURITY_ATTRIBUTES) { return mkdir(path, 0775) == 0; }
UINT GetDriveTypeA(LPCSTR) { return DRIVE_FIXED; }   // no CD drives: the game data is on storage
// Directory searches: their own handle range and table.
static constexpr uint32_t FIND_BASE = 0x00FE0000u;
static find_state *g_find[64];

static BOOL find_next(find_state *fs, LPWIN32_FIND_DATAA data) {
    struct dirent *e;
    while ((e = readdir(fs->d))) {
        if (fnmatch(fs->pat, e->d_name, FNM_CASEFOLD)) continue;
        memset(data, 0, sizeof *data);
        snprintf(data->cFileName, MAX_PATH, "%s", e->d_name);
        data->dwFileAttributes = e->d_type == DT_DIR ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
        return TRUE;
    }
    return FALSE;
}
HANDLE FindFirstFileA(LPCSTR spec, LPWIN32_FIND_DATAA data) {
    find_state *fs = new find_state();
    snprintf(fs->dir, sizeof fs->dir, "%s", spec);
    char *slash = strrchr(fs->dir, '/'); char *bs = strrchr(fs->dir, '\\'); if (bs > slash) slash = bs;
    if (slash) { snprintf(fs->pat, sizeof fs->pat, "%s", slash + 1); *slash = 0; }
    else { snprintf(fs->pat, sizeof fs->pat, "%s", fs->dir); snprintf(fs->dir, sizeof fs->dir, "."); }
    if (!strcmp(fs->pat, "*.*")) snprintf(fs->pat, sizeof fs->pat, "*");
    fs->d = opendir(fs->dir);
    if (!fs->d || !find_next(fs, data)) { if (fs->d) closedir(fs->d); delete fs; return INVALID_HANDLE_VALUE; }
    std::lock_guard<std::mutex> lk(g_fh_lock);
    for (uint32_t i = 0; i < 64; i++) if (!g_find[i]) { g_find[i] = fs; return FIND_BASE + i; }
    closedir(fs->d); delete fs;
    return INVALID_HANDLE_VALUE;
}
BOOL FindNextFileA(HANDLE h, LPWIN32_FIND_DATAA data) {
    uint32_t i = h - FIND_BASE;
    return (h >= FIND_BASE && i < 64 && g_find[i]) ? find_next(g_find[i], data) : FALSE;
}
BOOL FindClose(HANDLE h) {
    std::lock_guard<std::mutex> lk(g_fh_lock);
    uint32_t i = h - FIND_BASE;
    if (h < FIND_BASE || i >= 64 || !g_find[i]) return FALSE;
    closedir(g_find[i]->d); delete g_find[i]; g_find[i] = nullptr;
    return TRUE;
}
int MultiByteToWideChar(UINT, DWORD, LPCSTR s, int n, LPWSTR w, int wn) {
    if (n < 0) n = (int)strlen(s) + 1;
    if (!wn) return n;
    int k = n < wn ? n : wn; for (int i = 0; i < k; i++) w[i] = (unsigned char)s[i]; return k;
}
int WideCharToMultiByte(UINT, DWORD, LPCWSTR w, int wn, LPSTR s, int n, LPCSTR, LPBOOL) {
    if (wn < 0) wn = (int)wcslen(w) + 1;
    if (!n) return wn;
    int k = wn < n ? wn : n; for (int i = 0; i < k; i++) s[i] = (char)(w[i] < 256 ? w[i] : '?'); return k;
}

// ---- shlwapi / shlobj
BOOL PathAppendA(LPSTR path, LPCSTR more) {
    size_t n = strlen(path);
    if (n && path[n - 1] != '/' && path[n - 1] != '\\') strcat(path, "/");
    strcat(path, more);
    return TRUE;
}
BOOL PathFileExistsA(LPCSTR path) { return access(path, F_OK) == 0; }
LPSTR PathFindFileNameA(LPCSTR path) { const char *s = strrchr(path, '/'); const char *b = strrchr(path, '\\'); if (b > s) s = b; return (LPSTR)(s ? s + 1 : path); }
BOOL PathRemoveFileSpecA(LPSTR path) { char *s = strrchr(path, '/'); char *b = strrchr(path, '\\'); if (b > s) s = b; if (!s) return FALSE; *s = 0; return TRUE; }
HRESULT SHGetKnownFolderPath(REFKNOWNFOLDERID, DWORD, HANDLE, PWSTR *path) { if (path) *path = nullptr; return E_FAIL; }
void CoTaskMemFree(LPVOID) {}

// ---- WINMM MCI (FFNx's CD audio path): no CD device
MCIERROR mciSendCommandA(MCIDEVICEID, UINT, DWORD_PTR, DWORD_PTR) { return 1; }

#ifndef FF7_HAVE_FFMPEG
// ---- FFmpeg log (placeholder for builds without build/ffmpeg-android)
void av_log_set_level(int) {}
void av_log_set_callback(void (*)(void *, int, const char *, va_list)) {}
void av_log_format_line(void *, int, const char *fmt, va_list vl, char *line, int n, int *) { vsnprintf(line, (size_t)n, fmt, vl); }
#endif

}  // extern "C"

const KNOWNFOLDERID FOLDERID_Documents = { 0xFDD39AD0, 0x238F, 0x46AF, { 0xAD, 0xB4, 0x6C, 0x85, 0x48, 0x03, 0x69, 0xC7 } };
