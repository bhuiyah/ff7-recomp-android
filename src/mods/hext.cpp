// hext.cpp - see hext.hpp. Mirrors AppWrapper/HexPatch.cs (7th Heaven, MS-PL).
#include "hext.hpp"

#include <algorithm>
#include <cctype>
#include <stdexcept>

namespace ff7mods {
namespace {

std::string trim(const std::string &s) {
    size_t a = 0, b = s.size();
    while (a < b && std::isspace((unsigned char)s[a])) a++;
    while (b > a && std::isspace((unsigned char)s[b - 1])) b--;
    return s.substr(a, b - a);
}

bool istarts(const std::string &s, const char *p) {
    for (size_t i = 0; p[i]; i++)
        if (i >= s.size() || std::tolower((unsigned char)s[i]) != std::tolower((unsigned char)p[i])) return false;
    return true;
}

bool icontains(const std::string &s, const char *p) {
    std::string ls, lp(p);
    for (char c : s) ls += (char)std::tolower((unsigned char)c);
    for (char &c : lp) c = (char)std::tolower((unsigned char)c);
    return ls.find(lp) != std::string::npos;
}

// C# int.Parse(s, HexNumber): optional surrounding white space, hex digits only.
long long parse_hex(const std::string &raw) {
    std::string s = trim(raw);
    if (s.empty()) throw std::runtime_error("empty number");
    long long v = 0;
    for (char c : s) {
        int d;
        if (c >= '0' && c <= '9') d = c - '0';
        else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
        else throw std::runtime_error("not hex: " + s);
        v = v * 16 + d;
        if (v > 0xFFFFFFFFll) throw std::runtime_error("number too large: " + s);
    }
    return v;
}

std::vector<std::string> split_any(const std::string &s, const char *seps) {
    std::vector<std::string> out(1);
    for (char c : s) {
        bool sep = false;
        for (const char *p = seps; *p; p++) sep |= (c == *p);
        if (sep) out.emplace_back(); else out.back() += c;
    }
    return out;
}

std::vector<uint8_t> get_bytes(const std::string &spec) {
    std::vector<uint8_t> out;
    if (spec.find(':') != std::string::npos) {
        std::vector<std::string> parts = split_any(spec, ":");
        long long b = parse_hex(parts[0]);
        long long count = parse_hex(parts[1]);
        if (b > 0xFF) throw std::runtime_error("byte out of range");
        if (count > 0x100000) throw std::runtime_error("fill too long");
        out.assign((size_t)count, (uint8_t)b);
    } else {
        // C# splits on ' ', ',' and '\t' and parses every piece; runs of separators (an
        // empty piece) are tolerated here.
        for (const std::string &p : split_any(trim(spec), " ,\t")) {
            if (trim(p).empty()) continue;
            long long b = parse_hex(p);
            if (b > 0xFF) throw std::runtime_error("byte out of range: " + p);
            out.push_back((uint8_t)b);
        }
    }
    return out;
}

uint32_t get_address(const std::string &spec, int32_t offset, const HextRead32 &read32) {
    std::vector<std::string> sparts = split_any(spec, "+-");
    long long first;
    std::string s0 = trim(sparts[0]);
    if (!s0.empty() && s0.back() == '^') {
        uint32_t ptr = (uint32_t)(parse_hex(s0.substr(0, s0.size() - 1)) + offset);
        first = read32(ptr);
    } else {
        first = parse_hex(s0);
    }
    long long a = first;
    if (spec.find('+') != std::string::npos) a = first + parse_hex(sparts.at(1));
    else if (spec.find('-') != std::string::npos) a = first - parse_hex(sparts.at(1));
    return (uint32_t)(a + offset);
}

}  // namespace

HextResult hext_run(const std::string &text, bool honour_delay, const HextRead32 &read32, const HextWrite &write) {
    HextResult r;
    std::vector<std::string> lines;
    {
        std::string cur;
        for (char c : text) {
            if (c == '\n') { if (!cur.empty() && cur.back() == '\r') cur.pop_back(); lines.push_back(cur); cur.clear(); }
            else cur += c;
        }
        if (!cur.empty()) { if (cur.back() == '\r') cur.pop_back(); lines.push_back(cur); }
    }
    if (!lines.empty() && lines[0].size() >= 3 && (uint8_t)lines[0][0] == 0xEF && (uint8_t)lines[0][1] == 0xBB && (uint8_t)lines[0][2] == 0xBF)
        lines[0].erase(0, 3);

    if (honour_delay) {
        for (const std::string &l : lines) {
            std::string t = trim(l);
            if (!istarts(t, "Delay")) continue;
            std::string d = trim(t.substr(5));
            if (!d.empty() && d[0] == '=') d = trim(d.substr(1));
            // int.TryParse: optional sign, decimal digits.
            size_t i = (!d.empty() && (d[0] == '+' || d[0] == '-')) ? 1 : 0;
            bool ok = i < d.size();
            for (size_t k = i; k < d.size(); k++) ok &= std::isdigit((unsigned char)d[k]) != 0;
            if (!ok) { r.skipped = true; return r; }
            long long v = std::stoll(d);
            r.delay_ms = v < 0 ? 0 : (int)std::min(v, 0x7FFFFFFFll);
            return r;
        }
    }

    int32_t offset = 0;
    bool in_ml = false;
    for (size_t li = 1; li < lines.size(); li++) {          // the first line is the title
        std::string t = trim(lines[li]);
        if (t.empty()) continue;
        if (in_ml) { if (t.size() >= 2 && t.compare(t.size() - 2, 2, "}}") == 0) in_ml = false; continue; }
        if (t.rfind("{{", 0) == 0) { in_ml = true; continue; }
        if (t[0] == '#' || t[0] == '{') continue;
        try {
            if (t[0] == '+') offset = (int32_t)parse_hex(t.substr(1));
            else if (t[0] == '-') offset = -(int32_t)parse_hex(t.substr(1));
            else if (t.find('=') != std::string::npos && !icontains(t, "delay")) {
                size_t eq = t.find('=');
                std::string rhs = t.substr(eq + 1);
                if (rhs.find('=') != std::string::npos) rhs = rhs.substr(0, rhs.find('='));   // C# Split('=')[1]
                uint32_t addr = get_address(t.substr(0, eq), offset, read32);
                std::vector<uint8_t> bytes = get_bytes(rhs);
                if (!bytes.empty()) write(addr, bytes.data(), (uint32_t)bytes.size());
                r.writes++;
            } else if (t.find(':') != std::string::npos) {
                std::vector<std::string> parts = split_any(t, ":");
                (void)get_address(parts[0], offset, read32);
                (void)parse_hex(parts.at(1));                     // protection request: nothing to do
            }
        } catch (const std::exception &e) {
            r.error = t + " (" + e.what() + ")";
            return r;
        }
    }
    return r;
}

}  // namespace ff7mods
