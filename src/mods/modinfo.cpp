// modinfo.cpp - see modinfo.hpp.
#include "modinfo.hpp"
#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include "pugixml.hpp"

namespace ff7mods {

namespace {
std::string lower(std::string s) {
    for (char &c : s) c = (char)std::tolower((unsigned char)c);
    return s;
}
std::string trim(const std::string &s) {
    size_t a = s.find_first_not_of(" \t\r\n"), b = s.find_last_not_of(" \t\r\n");
    return a == std::string::npos ? std::string() : s.substr(a, b - a + 1);
}
bool ieq(const char *a, const char *b) {
    while (*a && *b) if (std::tolower((unsigned char)*a++) != std::tolower((unsigned char)*b++)) return false;
    return *a == *b;
}
// Child element by name, ignoring case (7th Heaven's XPath is case-sensitive, but mods in
// the wild vary; exact match is tried first).
pugi::xml_node child(pugi::xml_node n, const char *name) {
    if (pugi::xml_node c = n.child(name)) return c;
    for (pugi::xml_node c : n.children()) if (c.type() == pugi::node_element && ieq(c.name(), name)) return c;
    return {};
}
std::string text(pugi::xml_node n, const char *name) { return trim(child(n, name).text().get()); }

std::unique_ptr<ActiveWhen> parse_aw_node(pugi::xml_node n) {
    auto aw = std::make_unique<ActiveWhen>();
    const char *k = n.name();
    if (ieq(k, "And")) aw->kind = ActiveWhen::And;
    else if (ieq(k, "Or")) aw->kind = ActiveWhen::Or;
    else if (ieq(k, "Not")) aw->kind = ActiveWhen::Not;
    else if (ieq(k, "GameLanguage")) aw->kind = ActiveWhen::GameLanguage;
    else aw->kind = ActiveWhen::Option;
    if (aw->kind == ActiveWhen::Option || aw->kind == ActiveWhen::GameLanguage) {
        aw->text = trim(n.text().get());
    } else {
        for (pugi::xml_node c : n.children())
            if (c.type() == pugi::node_element) aw->children.push_back(parse_aw_node(c));
    }
    return aw;
}

std::unique_ptr<RuntimeCondition> parse_condition(pugi::xml_node n) {
    auto c = std::make_unique<RuntimeCondition>();
    const char *k = n.name();
    if (ieq(k, "And")) c->kind = RuntimeCondition::And;
    else if (ieq(k, "Or")) c->kind = RuntimeCondition::Or;
    else if (ieq(k, "Not")) c->kind = RuntimeCondition::Not;
    else {
        c->kind = RuntimeCondition::Var;                  // RuntimeVar
        c->var = trim(n.attribute("Var").value());
        c->values = trim(n.attribute("Values").value());
        return c;
    }
    for (pugi::xml_node ch : n.children())
        if (ch.type() == pugi::node_element) c->children.push_back(parse_condition(ch));
    return c;
}

std::string norm_path(std::string s) {
    for (char &c : s) c = c == '\\' ? '/' : (char)std::tolower((unsigned char)c);
    while (!s.empty() && s[0] == '/') s.erase(0, 1);
    return s;
}

// ActiveWhen.Parse: a nested <ActiveWhen> element (its first child), else the attribute.
std::unique_ptr<ActiveWhen> parse_active_when(pugi::xml_node n) {
    if (pugi::xml_node x = child(n, "ActiveWhen")) {
        for (pugi::xml_node c : x.children())
            if (c.type() == pugi::node_element) return parse_aw_node(c);
        return nullptr;
    }
    if (pugi::xml_attribute a = n.attribute("ActiveWhen")) {
        auto aw = std::make_unique<ActiveWhen>();
        aw->kind = ActiveWhen::Option;
        aw->text = trim(a.value());
        return aw;
    }
    return nullptr;
}
}  // namespace

bool option_spec_active(const std::string &spec_in, const OptionValues &opts) {
    std::string spec = trim(spec_in);
    if (spec.empty()) return true;
    static const char *ops[] = { "!=", "<=", ">=", "=", "<", ">" };
    size_t at = std::string::npos; const char *op = nullptr;
    for (const char *o : ops) {
        size_t p = spec.find(o);
        if (p != std::string::npos && (at == std::string::npos || p < at || (p == at && std::strlen(o) > std::strlen(op)))) { at = p; op = o; }
    }
    if (!op) return false;
    std::string id = lower(trim(spec.substr(0, at)));
    std::string num = trim(spec.substr(at + std::strlen(op)));
    auto it = opts.find(id);
    if (it == opts.end()) return false;
    char *end = nullptr;
    long v = std::strtol(num.c_str(), &end, 10);
    if (num.empty() || *end) return false;
    const int cur = it->second;
    // ProfileItem.IsConfigActive compares the literal against the option: val OP conf.
    std::string o = op;
    if (o == "=") return v == cur;
    if (o == "!=") return v != cur;
    if (o == "<") return v < cur;
    if (o == ">") return v > cur;
    if (o == "<=") return v <= cur;
    if (o == ">=") return v >= cur;
    return false;
}

bool is_active(const ActiveWhen *aw, const OptionValues &opts, const std::string &language) {
    if (!aw) return true;
    switch (aw->kind) {
    case ActiveWhen::And:
        return std::all_of(aw->children.begin(), aw->children.end(), [&](auto &c) { return is_active(c.get(), opts, language); });
    case ActiveWhen::Or:
        return std::any_of(aw->children.begin(), aw->children.end(), [&](auto &c) { return is_active(c.get(), opts, language); });
    case ActiveWhen::Not:
        return !aw->children.empty() && !is_active(aw->children[0].get(), opts, language);
    case ActiveWhen::GameLanguage:
        return lower(aw->text) == lower(language);
    case ActiveWhen::Option:
    default:
        return option_spec_active(aw->text, opts);
    }
}

OptionValues ModInfo::values(const OptionValues &chosen) const {
    OptionValues v;
    for (const ConfigOption &o : options) v[lower(o.id)] = o.default_value;
    for (const auto &kv : chosen) v[lower(kv.first)] = kv.second;
    return v;
}

bool parse_mod_xml(const std::string &xml, ModInfo &m, std::string &error) {
    pugi::xml_document doc;
    pugi::xml_parse_result r = doc.load_buffer(xml.data(), xml.size());
    if (!r) { error = std::string("mod.xml: ") + r.description(); return false; }
    pugi::xml_node root = child(doc, "ModInfo");
    if (!root) { error = "mod.xml: no <ModInfo>"; return false; }
    m.id = text(root, "ID");
    m.name = text(root, "Name");
    m.author = text(root, "Author");
    m.version = text(root, "Version");
    m.description = text(root, "Description");
    m.category = text(root, "Category");
    m.link = text(root, "Link");
    m.preview_file = text(root, "PreviewFile");
    for (pugi::xml_node n : root.children()) {
        if (n.type() != pugi::node_element) continue;
        const char *k = n.name();
        if (ieq(k, "ConfigOption")) {
            ConfigOption o;
            o.type = text(n, "Type");
            o.default_value = std::atoi(text(n, "Default").c_str());
            o.name = text(n, "Name");
            o.id = text(n, "ID");
            o.description = text(n, "Description");
            for (pugi::xml_node v : n.children())
                if (v.type() == pugi::node_element && ieq(v.name(), "Option"))
                    o.values.emplace_back(v.attribute("Value").as_int(), v.attribute("Name").value());
            m.options.push_back(std::move(o));
        } else if (ieq(k, "ModFolder")) {
            ModFolder f;
            f.folder = n.attribute("Folder").value();
            f.active_when = parse_active_when(n);
            m.mod_folders.push_back(std::move(f));
        } else if (ieq(k, "Conditional")) {
            ConditionalFolder c;
            c.folder = n.attribute("Folder").value();
            c.active_when = parse_active_when(n);
            // Every other child is a condition for the file named in its ApplyTo ("" = all).
            for (pugi::xml_node x : n.children()) {
                if (x.type() != pugi::node_element || ieq(x.name(), "ActiveWhen")) continue;
                c.conditions[norm_path(x.attribute("ApplyTo").value())] = parse_condition(x);
                c.has_runtime_conditions = true;
            }
            m.conditionals.push_back(std::move(c));
        } else if (ieq(k, "FFNxConfig")) {
            for (pugi::xml_node c : n.children()) {
                if (c.type() != pugi::node_element) continue;
                std::string v;
                bool list = false;
                for (pugi::xml_node it : c.children())
                    if (it.type() == pugi::node_element) { if (list) v += ","; v += trim(it.text().get()); list = true; }
                if (!list) v = trim(c.text().get());
                m.ffnx_config.emplace_back(c.name(), v);
                std::vector<std::pair<std::string, std::string>> when;
                for (pugi::xml_attribute a : c.attributes()) when.emplace_back(lower(a.name()), trim(a.value()));
                m.ffnx_config_when.push_back(std::move(when));
            }
        } else if (ieq(k, "Compatibility")) {
            for (pugi::xml_node c : n.children()) {
                if (c.type() != pugi::node_element) continue;
                ModInfo::CompatRef r{ lower(trim(c.attribute("ModID").value())), trim(c.text().get()) };
                if (r.mod_id.empty()) continue;
                if (ieq(c.name(), "Require")) m.compat_requires.push_back(r);
                else if (ieq(c.name(), "Forbid")) m.compat_forbids.push_back(r);
            }
        } else if (ieq(k, "Variable")) {
            m.variables.emplace_back(n.attribute("Name").value(), trim(n.text().get()));
        } else if (ieq(k, "LoadLibrary") || ieq(k, "LoadAssembly") || ieq(k, "LoadPlugin") || ieq(k, "LoadPrograms")) {
            m.windows_code.push_back(std::string(k) + ": " + trim(n.text().get()));
        }
    }
    return true;
}

}  // namespace ff7mods
