// windows.h - the slice of the Win32 headers FFNx compiles against, for Android/Linux.
//
// Layout rule: anything that can sit inside game memory keeps its 32-bit Windows size.
// So the scalar types have explicit widths (DWORD is 32-bit even though `unsigned long`
// is 64-bit here) and handles are 32-bit values: the lifted game's handles come from
// win32hle and never were host pointers. Pointer typedefs (LPVOID, LPSTR...) are native
// pointers for FFNx's own code; inside game structs tools/ffnx_port.py turns them into
// gptr. Functions declared here are implemented in src/ffnx_port/win32_compat.cpp, or
// not at all when only a dead (Windows-only) path calls them.
#pragma once
#include <cstdarg>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <cwchar>
#include <climits>

// ---- calling conventions and attributes: meaningless for native code here
#define __stdcall
#define __cdecl
#define __fastcall
#define __thiscall
#define WINAPI
#define APIENTRY
#define CALLBACK
#define WINAPIV
#define STDMETHODCALLTYPE
#define __declspec(x)
#define __forceinline inline __attribute__((always_inline))
#define FAR
#define NEAR
#define CONST const
#define VOID void
#ifndef IN
#define IN
#endif
#ifndef OUT
#define OUT
#endif
#define UNREFERENCED_PARAMETER(x) (void)(x)

// ---- scalar types (Windows widths)
typedef uint8_t BYTE, UCHAR, BOOLEAN;
typedef unsigned char byte;            // rpcndr.h
typedef unsigned char boolean;
typedef uint16_t WORD, USHORT;
typedef int16_t SHORT;
typedef uint32_t DWORD, UINT, ULONG, DWORD32;
typedef int32_t LONG, INT, BOOL, HRESULT;
typedef int64_t LONGLONG, LONG64, INT64;
typedef uint64_t ULONGLONG, DWORD64, UINT64, ULONG64;
typedef float FLOAT;
typedef char CHAR;
typedef wchar_t WCHAR;
typedef intptr_t INT_PTR, LONG_PTR;
typedef uintptr_t UINT_PTR, ULONG_PTR, DWORD_PTR, SIZE_T;
// Window-message types cross into the guest (FFNx's WindowProc is called by the game's
// message loop with 4-byte arguments), so they keep their 32-bit Windows width.
typedef uint32_t WPARAM;
typedef int32_t LPARAM, LRESULT;
typedef intptr_t SSIZE_T;
typedef DWORD *LPDWORD, *PDWORD;
typedef WORD *LPWORD;
typedef BYTE *LPBYTE, *PBYTE;
typedef LONG *LPLONG, *PLONG;
typedef BOOL *LPBOOL;
typedef void *LPVOID, *PVOID;
typedef const void *LPCVOID;
typedef char *LPSTR, *PSTR, *PCHAR;
typedef const char *LPCSTR, *PCSTR;
typedef wchar_t *LPWSTR, *PWSTR;
typedef const wchar_t *LPCWSTR, *PCWSTR;
typedef LPSTR LPTSTR;
typedef LPCSTR LPCTSTR;
typedef char TCHAR;
typedef DWORD COLORREF;
typedef WORD ATOM;
typedef LONG LSTATUS;

#ifndef TRUE
#define TRUE 1
#endif
#ifndef FALSE
#define FALSE 0
#endif
#define MAX_PATH 260
#define INFINITE 0xFFFFFFFFu
#define S_OK ((HRESULT)0)
#define S_FALSE ((HRESULT)1)
#define E_FAIL ((HRESULT)0x80004005)
#define E_NOINTERFACE ((HRESULT)0x80004002)
#define SUCCEEDED(hr) (((HRESULT)(hr)) >= 0)
#define FAILED(hr) (((HRESULT)(hr)) < 0)
#define ERROR_SUCCESS 0L
#define ERROR_FILE_NOT_FOUND 2L
#define MAKEWORD(a, b) ((WORD)(((BYTE)(a)) | ((WORD)((BYTE)(b))) << 8))
#define MAKELONG(a, b) ((LONG)(((WORD)(a)) | ((DWORD)((WORD)(b))) << 16))
#define LOWORD(l) ((WORD)(((DWORD_PTR)(l)) & 0xffff))
#define HIWORD(l) ((WORD)((((DWORD_PTR)(l)) >> 16) & 0xffff))
#define LOBYTE(w) ((BYTE)(((DWORD_PTR)(w)) & 0xff))
#define HIBYTE(w) ((BYTE)((((DWORD_PTR)(w)) >> 8) & 0xff))
#define RGB(r, g, b) ((COLORREF)(((BYTE)(r) | ((WORD)((BYTE)(g)) << 8)) | (((DWORD)(BYTE)(b)) << 16)))

// ---- handles: 32-bit values (see the layout rule above)
typedef uint32_t HANDLE, HWND, HINSTANCE, HMODULE, HKEY, HDC, HGLRC, HBITMAP, HBRUSH, HCURSOR,
                 HICON, HMENU, HFONT, HGDIOBJ, HMONITOR, HRGN, HPALETTE, HGLOBAL, HLOCAL, HHOOK, HRSRC,
                 HMIDIOUT, HMIDISTRM, HWAVEOUT, HMMIO, HACMSTREAM, HACMDRIVER, HDROP, HEVENT;
typedef HANDLE *PHANDLE, *LPHANDLE;
typedef HKEY *PHKEY;
typedef DWORD REGSAM;
typedef UINT MMRESULT;
#define INVALID_HANDLE_VALUE ((HANDLE)0xFFFFFFFFu)

// ---- structures
typedef struct tagRECT { LONG left, top, right, bottom; } RECT, *LPRECT, *PRECT;
typedef const RECT *LPCRECT;
typedef struct tagPOINT { LONG x, y; } POINT, *LPPOINT, *PPOINT;
typedef struct tagSIZE { LONG cx, cy; } SIZE, *LPSIZE;
typedef struct _GUID { uint32_t Data1; uint16_t Data2; uint16_t Data3; uint8_t Data4[8]; } GUID, IID, CLSID;
typedef GUID *LPGUID;
typedef const GUID *LPCGUID, &REFGUID, &REFIID, &REFCLSID;
typedef union _LARGE_INTEGER { struct { DWORD LowPart; LONG HighPart; }; LONGLONG QuadPart; } LARGE_INTEGER, *PLARGE_INTEGER;
typedef union _ULARGE_INTEGER { struct { DWORD LowPart; DWORD HighPart; }; ULONGLONG QuadPart; } ULARGE_INTEGER;
typedef struct _FILETIME { DWORD dwLowDateTime, dwHighDateTime; } FILETIME, *LPFILETIME;
typedef struct _SYSTEMTIME { WORD wYear, wMonth, wDayOfWeek, wDay, wHour, wMinute, wSecond, wMilliseconds; } SYSTEMTIME, *LPSYSTEMTIME;
typedef uint64_t DWORDLONG;
typedef struct _MEMORYSTATUSEX {
    DWORD dwLength, dwMemoryLoad;
    DWORDLONG ullTotalPhys, ullAvailPhys, ullTotalPageFile, ullAvailPageFile, ullTotalVirtual, ullAvailVirtual, ullAvailExtendedVirtual;
} MEMORYSTATUSEX, *LPMEMORYSTATUSEX;
typedef struct _CRITICAL_SECTION { void *impl; } CRITICAL_SECTION, *LPCRITICAL_SECTION;
typedef struct tagPALETTEENTRY { BYTE peRed, peGreen, peBlue, peFlags; } PALETTEENTRY, *LPPALETTEENTRY;
typedef struct tWAVEFORMATEX { WORD wFormatTag, nChannels; DWORD nSamplesPerSec, nAvgBytesPerSec; WORD nBlockAlign, wBitsPerSample, cbSize; } WAVEFORMATEX, *LPWAVEFORMATEX, *PWAVEFORMATEX;
typedef const WAVEFORMATEX *LPCWAVEFORMATEX;
#define WAVE_FORMAT_PCM 1
typedef struct _SECURITY_ATTRIBUTES { DWORD nLength; LPVOID lpSecurityDescriptor; BOOL bInheritHandle; } SECURITY_ATTRIBUTES, *LPSECURITY_ATTRIBUTES;
typedef struct tagMSG { HWND hwnd; UINT message; WPARAM wParam; LPARAM lParam; DWORD time; POINT pt; } MSG, *LPMSG;
typedef LRESULT (*WNDPROC)(HWND, UINT, WPARAM, LPARAM);
// WNDCLASSA is embedded by value in game memory (game_obj), so its pointer members are
// 32-bit guest values here: 40 bytes, as on 32-bit Windows.
typedef struct tagWNDCLASSA {
    UINT style;
    uint32_t lpfnWndProc;      // guest address of the window procedure
    int cbClsExtra, cbWndExtra;
    HINSTANCE hInstance;
    HICON hIcon;
    HCURSOR hCursor;
    HBRUSH hbrBackground;
    uint32_t lpszMenuName;     // guest char*
    uint32_t lpszClassName;    // guest char*
} WNDCLASSA, *LPWNDCLASSA;
static_assert(sizeof(WNDCLASSA) == 40, "WNDCLASSA must match 32-bit Windows");
typedef struct _EXCEPTION_POINTERS EXCEPTION_POINTERS, *PEXCEPTION_POINTERS;

// ---- functions FFNx's ported paths call (src/ffnx_port/win32_compat.cpp)
extern "C" {
DWORD GetCurrentDirectoryA(DWORD n, LPSTR buf);
BOOL SetCurrentDirectoryA(LPCSTR path);
DWORD GetModuleFileNameA(HMODULE m, LPSTR buf, DWORD n);
DWORD GetTickCount(void);
ULONGLONG GetTickCount64(void);
BOOL QueryPerformanceCounter(LARGE_INTEGER *c);
BOOL QueryPerformanceFrequency(LARGE_INTEGER *f);
void Sleep(DWORD ms);
DWORD GetLastError(void);
void SetLastError(DWORD e);
DWORD GetCurrentThreadId(void);
DWORD GetCurrentProcessId(void);
void OutputDebugStringA(LPCSTR s);
int MessageBoxA(HWND w, LPCSTR text, LPCSTR caption, UINT type);
BOOL GlobalMemoryStatusEx(LPMEMORYSTATUSEX m);
void InitializeCriticalSection(LPCRITICAL_SECTION cs);
void DeleteCriticalSection(LPCRITICAL_SECTION cs);
void EnterCriticalSection(LPCRITICAL_SECTION cs);
void LeaveCriticalSection(LPCRITICAL_SECTION cs);
DWORD GetFileAttributesA(LPCSTR path);
BOOL CreateDirectoryA(LPCSTR path, LPSECURITY_ATTRIBUTES sa);
void GetLocalTime(LPSYSTEMTIME t);
void GetSystemTime(LPSYSTEMTIME t);
}
#define GetCurrentDirectory GetCurrentDirectoryA
#define SetCurrentDirectory SetCurrentDirectoryA
#define GetModuleFileName GetModuleFileNameA
#define MessageBox MessageBoxA
#define OutputDebugString OutputDebugStringA
#define GetFileAttributes GetFileAttributesA
#define CreateDirectory CreateDirectoryA
#define INVALID_FILE_ATTRIBUTES ((DWORD)-1)
#define FILE_ATTRIBUTE_DIRECTORY 0x10
#define MB_OK 0x0u
#define MB_ICONERROR 0x10u
#define MB_ICONWARNING 0x30u
#define MB_ICONINFORMATION 0x40u
#define MB_YESNO 0x4u
#define IDYES 6
#define IDNO 7

// Memory protection: the guest image is mapped read/write; FFNx's own direct
// VirtualProtect calls (outside patch.h) only need to succeed.
#define PAGE_NOACCESS 0x01
#define PAGE_READONLY 0x02
#define PAGE_READWRITE 0x04
#define PAGE_EXECUTE_READ 0x20
#define PAGE_EXECUTE_READWRITE 0x40
inline BOOL VirtualProtect(LPVOID, SIZE_T, DWORD, LPDWORD old) { if (old) *old = PAGE_EXECUTE_READWRITE; return TRUE; }

#define FORMAT_MESSAGE_ALLOCATE_BUFFER 0x00000100
#define FORMAT_MESSAGE_IGNORE_INSERTS 0x00000200
#define FORMAT_MESSAGE_FROM_SYSTEM 0x00001000
#define LANG_NEUTRAL 0x00
#define SUBLANG_DEFAULT 0x01
#define MAKELANGID(p, s) ((((WORD)(s)) << 10) | (WORD)(p))
extern "C" DWORD FormatMessageA(DWORD flags, LPCVOID src, DWORD id, DWORD lang, LPSTR buf, DWORD n, va_list *args);
#define FormatMessage FormatMessageA
extern "C" HLOCAL LocalFree(HLOCAL h);

// ---- virtual-key codes (FFNx's configurable hotkeys; input comes through SDL3 here)
#define VK_BACK 0x08
#define VK_TAB 0x09
#define VK_RETURN 0x0D
#define VK_SHIFT 0x10
#define VK_CONTROL 0x11
#define VK_MENU 0x12
#define VK_PAUSE 0x13
#define VK_ESCAPE 0x1B
#define VK_SPACE 0x20
#define VK_PRIOR 0x21
#define VK_NEXT 0x22
#define VK_END 0x23
#define VK_HOME 0x24
#define VK_LEFT 0x25
#define VK_UP 0x26
#define VK_RIGHT 0x27
#define VK_DOWN 0x28
#define VK_INSERT 0x2D
#define VK_DELETE 0x2E
#define VK_F1 0x70
#define VK_F2 0x71
#define VK_F3 0x72
#define VK_F4 0x73
#define VK_F5 0x74
#define VK_F6 0x75
#define VK_F7 0x76
#define VK_F8 0x77
#define VK_F9 0x78
#define VK_F10 0x79
#define VK_F11 0x7A
#define VK_F12 0x7B
#define VK_LSHIFT 0xA0
#define VK_RSHIFT 0xA1
#define VK_LCONTROL 0xA2
#define VK_RCONTROL 0xA3
#define VK_LMENU 0xA4
#define VK_RMENU 0xA5

// ---- window management (FFNx's WindowProc / fullscreen toggling). On Android the game
// window is a win32hle window inside the app's surface: these succeed and change nothing.
#define WM_DESTROY 0x0002
#define WM_MOVE 0x0003
#define WM_SIZE 0x0005
#define WM_ACTIVATE 0x0006
#define WM_SETFOCUS 0x0007
#define WM_KILLFOCUS 0x0008
#define WM_CLOSE 0x0010
#define WM_QUIT 0x0012
#define WM_ACTIVATEAPP 0x001C
#define WM_KEYDOWN 0x0100
#define WM_KEYUP 0x0101
#define WM_CHAR 0x0102
#define WM_SYSKEYDOWN 0x0104
#define WM_SYSKEYUP 0x0105
#define WM_SYSCOMMAND 0x0112
#define WM_MENUCHAR 0x0120
#define WM_MOUSEMOVE 0x0200
#define WM_LBUTTONDOWN 0x0201
#define WM_LBUTTONUP 0x0202
#define WM_RBUTTONDOWN 0x0204
#define WM_RBUTTONUP 0x0205
#define WM_MOUSEWHEEL 0x020A
#define WM_ENTERSIZEMOVE 0x0231
#define WM_EXITSIZEMOVE 0x0232
#define SIZE_RESTORED 0
#define SIZE_MINIMIZED 1
#define SIZE_MAXIMIZED 2
#define MNC_CLOSE 1
#define GWL_STYLE (-16)
#define GWL_WNDPROC (-4)
#define WS_OVERLAPPEDWINDOW 0x00CF0000L
#define WS_POPUP 0x80000000L
#define WS_VISIBLE 0x10000000L
#define WS_CLIPSIBLINGS 0x04000000L
#define WS_CLIPCHILDREN 0x02000000L
#define WS_SYSMENU 0x00080000L
#define CDS_FULLSCREEN 0x00000004
#define DISP_CHANGE_SUCCESSFUL 0
#define MONITORINFOF_PRIMARY 0x00000001
#define CCHDEVICENAME 32
typedef struct tagMONITORINFOEXA { DWORD cbSize; RECT rcMonitor, rcWork; DWORD dwFlags; CHAR szDevice[CCHDEVICENAME]; } MONITORINFOEXA, MONITORINFOEX;
typedef struct _devicemodeA {
    BYTE dmDeviceName[32]; WORD dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra; DWORD dmFields;
    DWORD dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
} DEVMODEA, DEVMODE;
#define DM_PELSWIDTH 0x00080000L
#define DM_PELSHEIGHT 0x00100000L
#define DM_DISPLAYFREQUENCY 0x00400000L
#define ENUM_CURRENT_SETTINGS ((DWORD)-1)
extern "C" {
int ShowCursor(BOOL show);
BOOL MoveWindow(HWND w, int x, int y, int cx, int cy, BOOL repaint);
LONG SetWindowLongA(HWND w, int index, LONG v);
LONG GetWindowLongA(HWND w, int index);
BOOL SetWindowTextA(HWND w, LPCSTR s);
SHORT GetKeyState(int vk);
SHORT GetAsyncKeyState(int vk);
BOOL SetRectEmpty(LPRECT r);
BOOL PeekMessageA(LPMSG m, HWND w, UINT lo, UINT hi, UINT remove);
BOOL TranslateMessage(const MSG *m);
LRESULT DispatchMessageA(const MSG *m);
LRESULT DefWindowProcA(HWND w, UINT msg, WPARAM wp, LPARAM lp);
LRESULT CallWindowProcA(WNDPROC p, HWND w, UINT msg, WPARAM wp, LPARAM lp);
BOOL SetProcessDPIAware(void);
}
extern "C" {
BOOL GetMonitorInfoA(HMONITOR m, MONITORINFOEXA *mi);
LONG ChangeDisplaySettingsExA(LPCSTR dev, DEVMODEA *dm, HWND w, DWORD flags, LPVOID param);
BOOL AdjustWindowRect(LPRECT r, DWORD style, BOOL menu);
}
#define GetMonitorInfo GetMonitorInfoA
#define SetWindowLongPtr SetWindowLongA
#define SetWindowLongPtrA SetWindowLongA
#define MAKELRESULT(l, h) ((LRESULT)(DWORD)MAKELONG(l, h))
#define PASCAL
#define SetWindowLong SetWindowLongA
#define GetWindowLong GetWindowLongA
#define SetWindowText SetWindowTextA
#define PeekMessage PeekMessageA
#define DispatchMessage DispatchMessageA
#define DefWindowProc DefWindowProcA
#define CallWindowProc CallWindowProcA
#define PM_REMOVE 0x0001

// ---- process, modules, exceptions
#define DLL_PROCESS_DETACH 0
#define DLL_PROCESS_ATTACH 1
#define DLL_THREAD_ATTACH 2
#define DLL_THREAD_DETACH 3
#define ES_SYSTEM_REQUIRED 0x00000001
#define ES_DISPLAY_REQUIRED 0x00000002
#define ES_CONTINUOUS 0x80000000
typedef intptr_t (*FARPROC)(void);
typedef LONG (*PVECTORED_EXCEPTION_HANDLER)(PEXCEPTION_POINTERS);
typedef LONG (*LPTOP_LEVEL_EXCEPTION_FILTER)(PEXCEPTION_POINTERS);
struct IUnknown { virtual ~IUnknown() = default; };
typedef int errno_t;
extern "C" {
HMODULE LoadLibraryA(LPCSTR name);
FARPROC GetProcAddress(HMODULE m, LPCSTR name);
BOOL FreeLibrary(HMODULE m);
UINT GetSystemDirectoryA(LPSTR buf, UINT n);
LPTOP_LEVEL_EXCEPTION_FILTER SetUnhandledExceptionFilter(LPTOP_LEVEL_EXCEPTION_FILTER f);
PVOID AddVectoredExceptionHandler(ULONG first, PVECTORED_EXCEPTION_HANDLER h);
DWORD SetThreadExecutionState(DWORD flags);
int _setmaxstdio(int n);
}
#define LoadLibrary LoadLibraryA
#define GetSystemDirectory GetSystemDirectoryA

// ---- registry: there is none; every key is absent (ERROR_FILE_NOT_FOUND), as on a
// Windows machine where the game was never installed through its own installer.
#define HKEY_CURRENT_USER ((HKEY)0x80000001u)
#define HKEY_LOCAL_MACHINE ((HKEY)0x80000002u)
#define KEY_QUERY_VALUE 0x0001
#define KEY_SET_VALUE 0x0002
#define KEY_READ 0x20019
#define KEY_WOW64_32KEY 0x0200
#define KEY_WOW64_64KEY 0x0100
#define REG_SZ 1
#define REG_DWORD 4
extern "C" {
LSTATUS RegOpenKeyExA(HKEY k, LPCSTR sub, DWORD opt, REGSAM sam, PHKEY out);
LSTATUS RegQueryValueExA(HKEY k, LPCSTR name, LPDWORD reserved, LPDWORD type, LPBYTE data, LPDWORD cb);
LSTATUS RegCloseKey(HKEY k);
}
#define RegOpenKeyEx RegOpenKeyExA
#define RegQueryValueEx RegQueryValueExA

// ---- files (FFNx's own file checks; the game's go through win32hle)
#define GENERIC_READ 0x80000000u
#define GENERIC_WRITE 0x40000000u
#define FILE_SHARE_READ 0x00000001
#define FILE_SHARE_WRITE 0x00000002
#define CREATE_NEW 1
#define CREATE_ALWAYS 2
#define OPEN_EXISTING 3
#define OPEN_ALWAYS 4
#define FILE_ATTRIBUTE_NORMAL 0x80
#define FILE_BEGIN 0
#define DRIVE_UNKNOWN 0
#define DRIVE_NO_ROOT_DIR 1
#define DRIVE_FIXED 3
#define DRIVE_CDROM 5
#define CP_ACP 0
#define CP_UTF8 65001
#define ERROR_INSUFFICIENT_BUFFER 122L
#define HRESULT_CODE(hr) ((hr) & 0xFFFF)
typedef struct _WIN32_FIND_DATAA {
    DWORD dwFileAttributes; FILETIME ftCreationTime, ftLastAccessTime, ftLastWriteTime;
    DWORD nFileSizeHigh, nFileSizeLow, dwReserved0, dwReserved1; CHAR cFileName[MAX_PATH]; CHAR cAlternateFileName[14];
} WIN32_FIND_DATAA, WIN32_FIND_DATA, *LPWIN32_FIND_DATAA;
extern "C" {
HANDLE CreateFileA(LPCSTR name, DWORD access, DWORD share, LPSECURITY_ATTRIBUTES sa, DWORD disp, DWORD flags, HANDLE tmpl);
BOOL ReadFile(HANDLE h, LPVOID buf, DWORD n, LPDWORD got, void *ovl);
BOOL WriteFile(HANDLE h, LPCVOID buf, DWORD n, LPDWORD put, void *ovl);
DWORD GetFileSize(HANDLE h, LPDWORD high);
BOOL CloseHandle(HANDLE h);
BOOL DeleteFileA(LPCSTR name);
UINT GetDriveTypeA(LPCSTR root);
HANDLE FindFirstFileA(LPCSTR spec, LPWIN32_FIND_DATAA data);
BOOL FindNextFileA(HANDLE h, LPWIN32_FIND_DATAA data);
BOOL FindClose(HANDLE h);
int MultiByteToWideChar(UINT cp, DWORD flags, LPCSTR s, int n, LPWSTR w, int wn);
int WideCharToMultiByte(UINT cp, DWORD flags, LPCWSTR w, int wn, LPSTR s, int n, LPCSTR def, LPBOOL used);
}
#define CreateFile CreateFileA
#define DeleteFile DeleteFileA
#define GetDriveType GetDriveTypeA
#define FindFirstFile FindFirstFileA
#define FindNextFile FindNextFileA

// ---- mouse / cursor (FFNx's overlay and mouse input; touch arrives through SDL3/Android)
#define WM_LBUTTONDBLCLK 0x0203
#define WM_RBUTTONDBLCLK 0x0206
#define WM_MBUTTONDOWN 0x0207
#define WM_MBUTTONUP 0x0208
#define WM_MBUTTONDBLCLK 0x0209
#define WM_XBUTTONDOWN 0x020B
#define WM_XBUTTONUP 0x020C
#define WM_XBUTTONDBLCLK 0x020D
#define WM_DEVICECHANGE 0x0219
#define WHEEL_DELTA 120
#define XBUTTON1 0x0001
#define XBUTTON2 0x0002
#define GET_X_LPARAM(lp) ((int)(short)LOWORD(lp))
#define GET_Y_LPARAM(lp) ((int)(short)HIWORD(lp))
#define GET_WHEEL_DELTA_WPARAM(wp) ((short)HIWORD(wp))
#define GET_XBUTTON_WPARAM(wp) (HIWORD(wp))
#define MAKEINTRESOURCEA(i) ((LPSTR)((ULONG_PTR)((WORD)(i))))
#define MAKEINTRESOURCE MAKEINTRESOURCEA
#define IDC_ARROW MAKEINTRESOURCE(32512)
#define IDC_IBEAM MAKEINTRESOURCE(32513)
#define IDC_SIZENWSE MAKEINTRESOURCE(32642)
#define IDC_SIZENESW MAKEINTRESOURCE(32643)
#define IDC_SIZEWE MAKEINTRESOURCE(32644)
#define IDC_SIZENS MAKEINTRESOURCE(32645)
#define IDC_SIZEALL MAKEINTRESOURCE(32646)
#define IDC_NO MAKEINTRESOURCE(32648)
#define IDC_HAND MAKEINTRESOURCE(32649)
extern "C" {
HCURSOR LoadCursorA(HINSTANCE h, LPCSTR name);
HCURSOR SetCursor(HCURSOR c);
BOOL GetCursorPos(LPPOINT p);
BOOL SetCursorPos(int x, int y);
BOOL ScreenToClient(HWND w, LPPOINT p);
BOOL GetClientRect(HWND w, LPRECT r);
HWND GetCapture(void);
HWND SetCapture(HWND w);
BOOL ReleaseCapture(void);
HWND GetForegroundWindow(void);
HWND GetActiveWindow(void);
BOOL IsChild(HWND parent, HWND w);
BOOL WaitMessage(void);
LSTATUS RegSetValueExA(HKEY k, LPCSTR name, DWORD reserved, DWORD type, const BYTE *data, DWORD cb);
LSTATUS RegFlushKey(HKEY k);
BOOL MoveFileExA(LPCSTR from, LPCSTR to, DWORD flags);
}
#define LoadCursor LoadCursorA
#define RegSetValueEx RegSetValueExA
#define MoveFileEx MoveFileExA
#define MOVEFILE_REPLACE_EXISTING 0x1
#define MOVEFILE_WRITE_THROUGH 0x8
#define ERROR_PATH_NOT_FOUND 3L
#define MAXBYTE 0xff

// ---- MSVC CRT names (more)
#define _strcmpi strcasecmp
#define _MAX_DRIVE 3
#define _MAX_DIR 256
#define _MAX_FNAME 256
#define _MAX_EXT 256
#define _lseeki64 lseek64
#define _fileno fileno
#define _S_IREAD S_IRUSR
#define _S_IWRITE S_IWUSR
#define _SH_DENYNO 0x40
#define _O_CREAT O_CREAT
#define _O_TRUNC O_TRUNC
#define _O_WRONLY O_WRONLY
#define _O_RDWR O_RDWR
#include <cstdio>
#ifndef FFNX_HAVE_FOPEN_S
#define FFNX_HAVE_FOPEN_S
inline int fopen_s(FILE **f, const char *name, const char *mode) { *f = fopen(name, mode); return *f ? 0 : errno; }
#endif
// _splitpath: drive is always empty; dir keeps its trailing separator; ext includes the dot.
inline void _splitpath(const char *path, char *drive, char *dir, char *fname, char *ext) {
    const char *s = strrchr(path, '/'), *b = strrchr(path, '\\'); if (b > s) s = b;
    const char *name = s ? s + 1 : path;
    const char *dot = strrchr(name, '.');
    if (drive) drive[0] = 0;
    if (dir) { size_t n = (size_t)(name - path); memcpy(dir, path, n); dir[n] = 0; }
    if (fname) { size_t n = dot ? (size_t)(dot - name) : strlen(name); memcpy(fname, name, n); fname[n] = 0; }
    if (ext) strcpy(ext, dot ? dot : "");
}
#include <cerrno>
extern "C" char *StrStrIA(const char *hay, const char *needle);
#define _byteswap_ulong(x) __builtin_bswap32(x)
#define _byteswap_ushort(x) __builtin_bswap16(x)
#define _byteswap_uint64(x) __builtin_bswap64(x)
inline errno_t _splitpath_s(const char *path, char *drive, size_t, char *dir, size_t, char *fname, size_t, char *ext, size_t) {
    _splitpath(path, drive, dir, fname, ext);
    return 0;
}
extern "C" BOOL ClientToScreen(HWND w, LPPOINT p);

// ---- MSVC CRT names
#define _stricmp strcasecmp
#define _strnicmp strncasecmp
#define stricmp strcasecmp
#define strnicmp strncasecmp
#define _snprintf snprintf
#define _vsnprintf vsnprintf
#define sprintf_s(buf, n, ...) snprintf((buf), (n), __VA_ARGS__)
#define strcpy_s(dst, n, src) (snprintf((dst), (n), "%s", (src)), 0)
#define strcat_s(dst, n, src) (strncat((dst), (src), (n) - strlen(dst) - 1), 0)
#define _strdup strdup
#define _access access
#define _unlink unlink
#define _mkdir(p) mkdir((p), 0775)
#include <strings.h>
#include <unistd.h>
#include <sys/stat.h>
inline char *_strlwr(char *s) { for (char *p = s; *p; p++) if (*p >= 'A' && *p <= 'Z') *p += 32; return s; }
inline char *_strupr(char *s) { for (char *p = s; *p; p++) if (*p >= 'a' && *p <= 'z') *p -= 32; return s; }
