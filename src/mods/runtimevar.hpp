// runtimevar.hpp - 7th Heaven's run-time variables (AppWrapper/RuntimeVar.cs, MS-PL): what a
// Conditional folder's <RuntimeVar Var=".." Values=".."/> checks when a file is opened.
//
//   spec  Byte|Short|Int:ADDRESS[:MASK]   the game's memory (1.02 addresses; here guest VA =
//                                         host address, so read in place)
//         FFString:ADDRESS:LENGTH         FF7-encoded text, Values = a|b|c
//         Sys:Day|Month|Year|Hour|Minute|Second
//         Counter:NAME:MOD  CounterAdv:NAME:MOD  CounterRnd:NAME:N  Random:_:N
//         RandomVarOnce:NAME:N  RandomVar:NAME:N[:TTL seconds]
//   alias a name from 7th Heaven's 7thHeaven.var or a mod's <Variable Name="..">spec</Variable>
//   Values  "1,2,0x10" (any of) or "3..7" (inclusive range)
#pragma once
#include <cstdint>
#include <map>
#include <string>
#include "modinfo.hpp"

namespace ff7mods {

// Reads n bytes of game memory at a guest address; false if it is not readable. The default
// reads in place within the game image's data; tests install their own.
using MemoryReader = bool (*)(uint32_t va, void *out, size_t n);
void set_memory_reader(MemoryReader r);

class RuntimeVars {
public:
    // "Name=spec" lines (7thHeaven.var).
    void load_aliases(const std::string &text);
    void add_alias(const std::string &name, const std::string &spec);
    bool eval(const RuntimeCondition *c);
    bool eval_var(const std::string &var, const std::string &values);

private:
    struct Random { int value = 0; bool locked = true; int64_t generated = 0; };
    std::map<std::string, std::string> aliases_;     // lower-case name -> spec
    std::map<std::string, int> counters_;
    std::map<std::string, Random> randoms_;
};

}  // namespace ff7mods
