// shlwapi.h - the path helpers FFNx uses, on POSIX paths.
#pragma once
#include <windows.h>
extern "C" {
BOOL PathAppendA(LPSTR path, LPCSTR more);
BOOL PathFileExistsA(LPCSTR path);
LPSTR PathFindFileNameA(LPCSTR path);
BOOL PathRemoveFileSpecA(LPSTR path);
}
#define PathAppend PathAppendA
#define PathFileExists PathFileExistsA
#define PathFindFileName PathFindFileNameA
#define PathRemoveFileSpec PathRemoveFileSpecA
