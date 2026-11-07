// shlobj.h - known folders. FFNx asks for "Documents" to find the Steam edition's user
// data; on Android that is the app's files directory (src/ffnx_port/win32_compat.cpp).
#pragma once
#include <windows.h>
typedef GUID KNOWNFOLDERID;
typedef const KNOWNFOLDERID &REFKNOWNFOLDERID;
extern const KNOWNFOLDERID FOLDERID_Documents;
#define KF_FLAG_DEFAULT 0
extern "C" {
HRESULT SHGetKnownFolderPath(REFKNOWNFOLDERID id, DWORD flags, HANDLE token, PWSTR *path);
void CoTaskMemFree(LPVOID p);
}
