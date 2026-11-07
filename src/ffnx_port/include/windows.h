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
typedef intptr_t INT_PTR, LONG_PTR, LPARAM, LRESULT;
typedef uintptr_t UINT_PTR, ULONG_PTR, DWORD_PTR, WPARAM, SIZE_T;
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
