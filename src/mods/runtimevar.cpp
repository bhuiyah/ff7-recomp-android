// runtimevar.cpp - see runtimevar.hpp.
#include "runtimevar.hpp"
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <random>
#include <set>
#include <vector>

namespace ff7mods {

namespace {
// FF7 1.02 image: game variables live in its data sections (0xCC0000..0xDC1000 in
// 7thHeaven.var); anything outside the mapped image is refused rather than faulted on.
bool read_in_place(uint32_t va, void *out, size_t n) {
    if (va < 0x00400000u || (uint64_t)va + n > 0x01000000u) return false;
    std::memcpy(out, reinterpret_cast<const void *>(static_cast<uintptr_t>(va)), n);
    return true;
}
MemoryReader g_reader = read_in_place;

std::string lower(std::string s) { for (char &c : s) c = (char)std::tolower((unsigned char)c); return s; }
std::string trim(const std::string &s) {
    size_t a = s.find_first_not_of(" \t\r\n"), b = s.find_last_not_of(" \t\r\n");
    return a == std::string::npos ? std::string() : s.substr(a, b - a + 1);
}
std::vector<std::string> split(const std::string &s, char sep) {
    std::vector<std::string> out;
    size_t i = 0;
    for (;;) {
        size_t j = s.find(sep, i);
        out.push_back(s.substr(i, j == std::string::npos ? std::string::npos : j - i));
        if (j == std::string::npos) break;
        i = j + 1;
    }
    return out;
}
long long parse_num(const std::string &in) {
    std::string s = trim(in);
    if (s.size() > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) return std::strtoll(s.c_str() + 2, nullptr, 16);
    return std::strtoll(s.c_str(), nullptr, 10);
}
// RuntimeVar.CompareInt: "a..b" inclusive, else a comma list.
bool compare_int(long long v, const std::string &values) {
    size_t r = values.find("..");
    if (r != std::string::npos) return v >= parse_num(values.substr(0, r)) && v <= parse_num(values.substr(r + 2));
    for (const std::string &x : split(values, ',')) if (!trim(x).empty() && parse_num(x) == v) return true;
    return false;
}
std::mt19937 &rng() { static std::mt19937 r{ std::random_device{}() }; return r; }
int rand_below(int n) { return n > 0 ? (int)(rng()() % (uint32_t)n) : 0; }
// FFText.Translate: 0x00-0x5E -> ' '..'~', 0xFF ends the string, the rest are spaces.
std::string ff_text(const uint8_t *p, size_t n) {
    std::string s;
    for (size_t i = 0; i < n; i++) {
        if (p[i] == 0xFF) break;
        s += p[i] < 0x5F ? (char)(p[i] + 32) : ' ';
    }
    return s;
}
}  // namespace

void set_memory_reader(MemoryReader r) { g_reader = r ? r : read_in_place; }

void RuntimeVars::load_aliases(const std::string &text) {
    for (const std::string &line : split(text, '\n')) {
        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string name = trim(line.substr(0, eq)), spec = trim(line.substr(eq + 1));
        if (!name.empty() && !spec.empty() && name[0] != '#') add_alias(name, spec);
    }
}

void RuntimeVars::add_alias(const std::string &name, const std::string &spec) { aliases_[lower(name)] = trim(spec); }

bool RuntimeVars::eval(const RuntimeCondition *c) {
    if (!c) return true;
    switch (c->kind) {
    case RuntimeCondition::And:
        for (auto &ch : c->children) if (!eval(ch.get())) return false;
        return true;
    case RuntimeCondition::Or:
        for (auto &ch : c->children) if (eval(ch.get())) return true;
        return false;
    case RuntimeCondition::Not:
        return !c->children.empty() && !eval(c->children[0].get());
    case RuntimeCondition::Var:
    default:
        return eval_var(c->var, c->values);
    }
}

bool RuntimeVars::eval_var(const std::string &var_in, const std::string &values) {
    std::string spec = trim(var_in);
    auto al = aliases_.find(lower(spec));
    if (al != aliases_.end()) spec = al->second;
    std::vector<std::string> parts = split(spec, ':');
    const std::string type = lower(parts[0]);
    const std::string name = parts.size() > 1 ? parts[1] : std::string();
    const long long size = parts.size() > 2 ? parse_num(parts[2]) : 0;

    if (type == "sys") {
        std::time_t t = std::time(nullptr);
        std::tm tm{};
#if defined(_WIN32)
        localtime_s(&tm, &t);
#else
        localtime_r(&t, &tm);
#endif
        std::string w = lower(name);
        long long v = w == "day" ? tm.tm_mday : w == "month" ? tm.tm_mon + 1 : w == "year" ? tm.tm_year + 1900
                    : w == "hour" ? tm.tm_hour : w == "minute" ? tm.tm_min : w == "second" ? tm.tm_sec : -1;
        return compare_int(v, values);
    }
    if (type == "counter") return size > 0 && compare_int(counters_[lower(name)] % size, values);
    if (type == "counteradv") {
        int &c = counters_[lower(name)];
        c++;
        return size > 0 && compare_int(c % size, values);
    }
    if (type == "counterrnd") { int v = rand_below((int)size); counters_[lower(name)] = v; return compare_int(v, values); }
    if (type == "random") return compare_int(rand_below((int)size), values);
    if (type == "randomvaronce") {
        auto it = randoms_.find(lower(name));
        if (it == randoms_.end()) it = randoms_.emplace(lower(name), Random{ rand_below((int)size), true, (int64_t)std::time(nullptr) }).first;
        return compare_int(it->second.value, values);
    }
    if (type == "randomvar") {
        const int64_t now = (int64_t)std::time(nullptr);
        const int64_t ttl = parts.size() > 3 ? parse_num(parts[3]) : 0;
        auto it = randoms_.find(lower(name));
        if (it == randoms_.end()) it = randoms_.emplace(lower(name), Random{ rand_below((int)size), true, now }).first;
        else if (!it->second.locked && now > it->second.generated + ttl) { it->second.value = rand_below((int)size); it->second.generated = now; }
        it->second.locked = true;
        bool hit = compare_int(it->second.value, values);
        if (hit) it->second.locked = false;            // GetUnlockRandomVarWrapper: a match unlocks
        return hit;
    }
    const uint32_t addr = (uint32_t)parse_num(name);
    if (type == "ffstring") {
        std::vector<uint8_t> buf((size_t)(size > 0 ? size : 0));
        if (buf.empty() || !g_reader(addr, buf.data(), buf.size())) return false;
        std::string s = trim(ff_text(buf.data(), buf.size()));
        for (const std::string &v : split(values, '|')) if (lower(trim(v)) == lower(s)) return true;
        return false;
    }
    const long long mask = size ? size : -1;
    if (type == "byte") { uint8_t v; return g_reader(addr, &v, 1) && compare_int(v & mask, values); }
    if (type == "short") { int16_t v; return g_reader(addr, &v, 2) && compare_int(v & mask, values); }
    if (type == "int") { int32_t v; return g_reader(addr, &v, 4) && compare_int(v & mask, values); }
    return false;                                       // unknown spec: inactive
}

}  // namespace ff7mods
