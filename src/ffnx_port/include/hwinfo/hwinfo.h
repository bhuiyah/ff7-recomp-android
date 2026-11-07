// hwinfo/hwinfo.h - the hwinfo API FFNx uses for its "PC SPECS" log (common.cpp).
// Not available on Android here: every query throws, which FFNx's own try/catch turns
// into "[CPU]: Unknown" etc. The types exist only so the call sites compile.
#pragma once
#include <cstdint>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace hwinfo {

enum class FrequencyUnit { Hz, KHz, MHz, GHz };
enum class ByteUnit { B, KB, MB, GB, KiB, MiB, GiB };

struct Frequency { double to(FrequencyUnit) const { return 0; } };
struct Bytes {
    uint64_t v = 0;
    double to(ByteUnit) const { return 0; }
    Bytes operator-(const Bytes &o) const { return { v - o.v }; }
};
struct PCI { uint16_t vendor_id = 0, device_id = 0; };

struct CPU { std::string model; };
struct GPU {
    std::string name;
    std::optional<PCI> pci;
    std::optional<Frequency> max_frequency;
    std::optional<Bytes> dedicated_memory, shared_memory;
    std::optional<std::string> driver_version;
};
struct Memory { Bytes total, free; };
struct OS { std::string name, marketing_name, version, kernel; int bits = 64; };

[[noreturn]] inline void unavailable() { throw std::runtime_error("hwinfo unavailable on this platform"); }
inline std::optional<std::vector<CPU>> cpus() { unavailable(); }
inline std::optional<std::vector<GPU>> gpus() { unavailable(); }
inline std::optional<Memory> memory() { unavailable(); }
inline std::optional<OS> os() { unavailable(); }

}  // namespace hwinfo
