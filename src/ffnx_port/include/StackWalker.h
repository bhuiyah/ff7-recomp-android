// StackWalker.h - the StackWalker base class FFNx derives FFNxStackWalker from (crashdump.h).
// Windows-only (dbghelp). Here call stacks come from the host crash handler
// (src/host/ff7_crash.c: lifted-function entry ring, guest registers), so ShowCallstack
// just says where to look; LoadModules reports the guest image bounds.
#pragma once
#include <windows.h>

class StackWalker {
public:
    StackWalker() = default;
    virtual ~StackWalker() = default;
    BOOL LoadModules() {
        OnLoadModule("ff7_en.exe", "ff7_en.exe", 0x00400000ull, 0x0076F000u, 0, "", "", 0);
        return TRUE;
    }
    BOOL ShowCallstack(HANDLE = 0, const void * = nullptr, void * = nullptr, void * = nullptr) {
        OnOutput("(call stack: see the FF7Recomp crash report / entry trace)\n");
        return TRUE;
    }
protected:
    virtual void OnLoadModule(LPCSTR, LPCSTR, DWORD64, DWORD, DWORD, LPCSTR, LPCSTR, ULONGLONG) {}
    virtual void OnDbgHelpErr(LPCSTR, DWORD, DWORD64) {}
    virtual void OnOutput(LPCSTR) {}
};
