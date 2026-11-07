// steamworkssdk/steam_api.h - the Steamworks surface FFNx uses for achievements.
// There is no Steam on the device: SteamAPI_Init() fails (FFNx then runs without
// achievements, as it does on Windows when Steam is not running) and every query says no.
#pragma once
#include <cstdint>

typedef int32_t int32;
typedef uint32_t uint32;
typedef uint64_t uint64;
typedef uint32_t AppId_t;
enum EResult { k_EResultOK = 1, k_EResultFail = 2 };

struct UserStatsReceived_t { uint64 m_nGameID; EResult m_eResult; uint64 m_steamIDUser; };
struct UserStatsStored_t { uint64 m_nGameID; EResult m_eResult; };
struct UserAchievementStored_t { uint64 m_nGameID; bool m_bGroupAchievement; char m_rgchAchievementName[128]; uint32 m_nCurProgress, m_nMaxProgress; };

class ISteamUserStats {
public:
    bool GetAchievement(const char *, bool *achieved) { if (achieved) *achieved = false; return false; }
    bool SetAchievement(const char *) { return false; }
    const char *GetAchievementDisplayAttribute(const char *, const char *) { return ""; }
    bool GetStat(const char *, int32 *v) { if (v) *v = 0; return false; }
    bool GetStat(const char *, float *v) { if (v) *v = 0; return false; }
    bool SetStat(const char *, int32) { return false; }
    bool SetStat(const char *, float) { return false; }
    bool StoreStats() { return false; }
    bool IndicateAchievementProgress(const char *, uint32, uint32) { return false; }
    bool RequestCurrentStats() { return false; }
};
class ISteamUtils { public: AppId_t GetAppID() { return 0; } };
class CGameID {
    uint64 id_ = 0;
public:
    CGameID() = default;
    explicit CGameID(uint64 id) : id_(id) {}
    explicit CGameID(AppId_t app) : id_(app) {}
    uint64 ToUint64() const { return id_; }
    AppId_t AppID() const { return (AppId_t)(id_ & 0xFFFFFFu); }
    bool operator==(const CGameID &o) const { return id_ == o.id_; }
    bool operator!=(const CGameID &o) const { return id_ != o.id_; }
};
class ISteamUser { public: bool BLoggedOn() { return false; } };

inline ISteamUserStats *SteamUserStats() { static ISteamUserStats s; return &s; }
inline ISteamUtils *SteamUtils() { static ISteamUtils s; return &s; }
inline ISteamUser *SteamUser() { static ISteamUser s; return &s; }
inline bool SteamAPI_Init() { return false; }
inline void SteamAPI_Shutdown() {}
inline void SteamAPI_RunCallbacks() {}
inline bool SteamAPI_RestartAppIfNecessary(uint32) { return false; }

// STEAM_CALLBACK(class, method, param, member): a member function the Steam client would
// call back; nothing calls it here.
template <class C, class P> struct CCallbackStub { CCallbackStub(C *, void (C::*)(P *)) {} };
#define STEAM_CALLBACK(thisclass, func, param, var) \
    CCallbackStub<thisclass, param> var{ this, &thisclass::func }; \
    void func(param *pParam)
