// modinfo.hpp - a 7th Heaven mod's mod.xml (AppWrapper/Profile.cs ModInfo, MS-PL), and the
// conditions that decide which of its folders are active.
//
//   <ModInfo>
//     <ID/> <Name/> <Author/> <Version/> <Description/> <Category/> <Link/> <PreviewFile/>
//     <ConfigOption> <Type>Bool|List</Type> <Default/> <ID/> <Name/> <Description/>
//                    <Option Value="1" Name="..."/>... </ConfigOption>
//     <ModFolder Folder="x" ActiveWhen="opt = 1"/>        (or a nested <ActiveWhen> tree)
//     <Conditional Folder="y"> <ActiveWhen>...</ActiveWhen> <RuntimeVar .../>... </Conditional>
//     <FFNxConfig> <key>value</key>... </FFNxConfig>
//     <Variable Name="...">...</Variable>
//     <LoadLibrary/> <LoadAssembly/> <LoadPlugin/> <LoadPrograms/>   (Windows code: reported, not run)
//   </ModInfo>
// ActiveWhen: <And>/<Or>/<Not>/<Option>spec</Option>/<GameLanguage>lang</GameLanguage>, or the
// attribute form ActiveWhen="spec"; spec is "OPTIONID op NUMBER", op in = != < > <= >=.
#pragma once
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace ff7mods {

struct ActiveWhen {
    enum Kind { And, Or, Not, Option, GameLanguage } kind = Option;
    std::string text;                                   // Option spec / language
    std::vector<std::unique_ptr<ActiveWhen>> children;
};

// Option values by ID (case-insensitive: keys stored lower case).
using OptionValues = std::map<std::string, int>;

// Evaluates an ActiveWhen (null = always active) for the given option values and game
// language (e.g. "en").
bool is_active(const ActiveWhen *aw, const OptionValues &opts, const std::string &language);
// "OPTIONID op N" against the option values, as ProfileItem.IsConfigActive.
bool option_spec_active(const std::string &spec, const OptionValues &opts);

struct ConfigOption {
    std::string id, name, description, type;           // type: "Bool" or "List"
    int default_value = 0;
    std::vector<std::pair<int, std::string>> values;    // List choices (value, name)
};

struct ModFolder {
    std::string folder;                                 // backslash path inside the mod
    std::unique_ptr<ActiveWhen> active_when;
};

struct ConditionalFolder {
    std::string folder;
    std::unique_ptr<ActiveWhen> active_when;
    bool has_runtime_conditions = false;                // <RuntimeVar> etc. (game memory checks)
};

struct ModInfo {
    std::string id, name, author, version, description, category, link, preview_file;
    std::vector<ConfigOption> options;
    std::vector<ModFolder> mod_folders;
    std::vector<ConditionalFolder> conditionals;
    std::vector<std::pair<std::string, std::string>> ffnx_config;   // key, value (lists joined by ',')
    std::vector<std::pair<std::string, std::string>> variables;     // name, value
    std::vector<std::string> windows_code;               // LoadLibrary/Assembly/Plugin/Programs: unsupported

    // Option values with each option's default, overridden by `chosen`.
    OptionValues values(const OptionValues &chosen) const;
};

// Parse mod.xml text. false (with `error`) if it is not a ModInfo document.
bool parse_mod_xml(const std::string &xml, ModInfo &out, std::string &error);

}  // namespace ff7mods
