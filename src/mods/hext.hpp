// hext.hpp - 7th Heaven's hext patch files (AppWrapper/HexPatch.cs, MS-PL), applied the same
// way: the first line is a title and skipped; blank lines, `#...`, `{...}` and `{{ ... }}`
// blocks are comments; `+off` / `-off` sets the offset added to every address; `addr=bytes`
// writes (bytes "8B 45 FC", "90,90", "90:6" = 6 times 90; addr "45A0B0", "45A0B0+10",
// "DC0E00^+4" = the dword at DC0E00 plus 4); `addr:len` is a page-protection request (no-op);
// a `Delay=<ms>` line anywhere delays the whole file. An error stops the file there (as 7th
// Heaven's exception does) and is reported.
#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace ff7mods {

struct HextResult {
    int delay_ms = -1;              // >= 0: the file asks to be applied that long after start
    bool skipped = false;           // a Delay line that is not a number: never applied (as there)
    int writes = 0;                 // memory writes made
    std::string error;              // the line that failed, if any
};

using HextRead32 = std::function<uint32_t(uint32_t va)>;
using HextWrite = std::function<void(uint32_t va, const uint8_t *bytes, uint32_t n)>;

// Parse `text` and make its writes. With honour_delay a delayed file only reports its delay
// (call again with honour_delay = false when it is due).
HextResult hext_run(const std::string &text, bool honour_delay, const HextRead32 &read32, const HextWrite &write);

}  // namespace ff7mods
