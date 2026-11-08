// iro.hpp - reader for 7th Heaven's IRO mod archives (format of AppWrapper/IrosArc.cs, MS-PL).
//
//   header      "IROS" (0x534F5249), version (0x10000..0x10002), flags, directory offset
//   directory   int32 count (-1: an int64 offset to the real directory follows), then per
//               entry: u16 entry size, u16 name bytes, UTF-16LE name, i32 flags,
//               offset (i32 before version 0x10001, i64 after), i32 stored length
//   data        raw, LZS (flags & 0xF == 1) or LZMA (== 2: i32 size, i32 props length,
//               props, raw LZMA stream)
// Names use backslashes and are matched case-insensitively. Archives are read in place
// (texture packs run to many gigabytes); only entries asked for are read.
#pragma once
#include <cstdint>
#include <cstdio>
#include <string>
#include <unordered_map>
#include <vector>

namespace ff7mods {

class IroArchive {
public:
    struct Entry {
        std::string name;      // as stored (UTF-8), backslashes
        uint32_t flags = 0;
        uint64_t offset = 0;
        uint32_t length = 0;   // stored length
    };

    ~IroArchive();
    // false (with error()) if the file is not a readable IRO archive.
    bool open(const std::string &path);
    const std::string &error() const { return err_; }
    const std::string &path() const { return path_; }

    // Lookups take any case and either slash.
    const Entry *find(const std::string &name) const;
    // Unpacked size, or -1 if missing / unreadable.
    int64_t size(const std::string &name) const;
    bool read(const std::string &name, std::vector<uint8_t> &out) const;
    const std::vector<Entry> &entries() const { return entries_; }

    static std::string key(const std::string &name);   // lower case, backslashes

private:
    bool read_at(uint64_t off, void *buf, size_t n) const;
    std::FILE *f_ = nullptr;
    std::string path_, err_;
    std::vector<Entry> entries_;
    std::unordered_map<std::string, size_t> lookup_;
};

}  // namespace ff7mods
