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

// A run-time condition of a Conditional folder, checked when a file is opened (Profile.cs
// Conditional: And / Or / Not / RuntimeVar Var="spec or alias" Values="1,2 | a..b").
struct RuntimeCondition {
    enum Kind { And, Or, Not, Var } kind = Var;
    std::string var, values;                            // Var: spec ("Byte:0xDC08EB[:mask]") or alias
    std::vector<std::unique_ptr<RuntimeCondition>> children;
};

struct ConditionalFolder {
    std::string folder;
    std::unique_ptr<ActiveWhen> active_when;
    // ApplyTo (file path inside the folder, lower case, '/' separators; "" = every file)
    // -> its condition. No entry for a file and no "" entry: always active.
    std::map<std::string, std::unique_ptr<RuntimeCondition>> conditions;
    bool has_runtime_conditions = false;
};

struct ModInfo {
    std::string id, name, author, version, description, category, link, preview_file;
    std::vector<ConfigOption> options;
    std::vector<ModFolder> mod_folders;
    std::vector<ConditionalFolder> conditionals;
    std::vector<std::pair<std::string, std::string>> ffnx_config;   // key, value (lists joined by ',')
    // per ffnx_config entry: its attributes, option conditions that must all hold for it to apply
    // (<internal_resolution_scale esuiscale="1">4</...>: only while the option esuiscale is 1)
    std::vector<std::vector<std::pair<std::string, std::string>>> ffnx_config_when;
    std::vector<std::pair<std::string, std::string>> variables;     // name, value
    std::vector<std::string> windows_code;               // LoadLibrary/Assembly/Plugin/Programs: unsupported
    // <Compatibility> <Require ModID="guid">why</Require> <Forbid ModID="guid">why</Forbid>
    // (Profile.cs Compatibility): other mods this one needs / does not work with.
    struct CompatRef { std::string mod_id, description; };
    std::vector<CompatRef> compat_requires, compat_forbids;
    // <Compatibility> <Setting> (Profile.cs CompatSetting): while this mod's option MyID is MyValue
    // (or always, without MyID), mod ModID's option TheirID must be Require / must not be any Forbid.
    // 7th Heaven sets the other mod's option so at launch (GameLauncher.SanityCheckSettings).
    struct CompatSetting {
        std::string my_id, mod_id, their_id;            // lower case
        int my_value = 0;
        bool has_require = false;
        int require = 0;
        std::vector<int> forbid;
    };
    std::vector<CompatSetting> compat_settings;
    // <OrderConstraints> <Before>guid</Before> <After>guid</After> (Profile.cs OrderBefore /
    // OrderAfter): mods this one must be above / below in the load order (lower case GUIDs).
    std::vector<std::string> order_before, order_after;

    // Option values with each option's default, overridden by `chosen`.
    OptionValues values(const OptionValues &chosen) const;
};

// Parse mod.xml text. false (with `error`) if it is not a ModInfo document.
bool parse_mod_xml(const std::string &xml, ModInfo &out, std::string &error);

}  // namespace ff7mods
