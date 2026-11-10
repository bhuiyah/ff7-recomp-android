package com.ff7recomp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import in.dragonbra.javasteam.base.ClientMsgProtobuf;
import in.dragonbra.javasteam.enums.EMsg;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats;
import in.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientStoreUserStats2;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged;
import in.dragonbra.javasteam.steam.authentication.QrAuthSession;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.Stats;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.types.SteamID;

/**
 * Steam achievements for FINAL FANTASY VII on Steam (the 2026 re-release, app 3837340, or the 2013
 * release, app 39140: whichever the account owns), with JavaSteam.
 *
 * The player signs in once by scanning a QR code with the Steam mobile app: no password is typed
 * here. Steam's refresh token (and the account name) stay in this app's private storage.
 *
 * FFNx's own FF7 achievement code decides when an achievement is earned; it talks to
 * src/ffnx_port/steam_android.cpp, which reads files/steam/state.txt (written here: every FF7
 * achievement and whether this account has it) and appends unlocks to files/steam/pending.txt.
 * sync() sends those to Steam (ClientStoreUserStats2, as the Steam client does), reads the
 * stats back to confirm, and rewrites state.txt. Unlocks made offline wait in pending.txt.
 */
final class SteamLink {
    /** FINAL FANTASY VII on Steam: the 2026 re-release (which carries the 1.02 exe in
     *  ff7/resources/ff7_1.02) and the 2013 release. Their achievement lists differ; FFNx has both. */
    static final int APP_RERELEASE = 3837340, APP_2013 = 39140;
    private static final String TAG = "FF7Steam";
    private static final int LOGIN_ID = 0x0F7F;          // our own session id: other Steam clients stay signed in
    private static final Object LOCK = new Object();

    interface QrListener {
        void qr(String challengeUrl);
        void done(String error);           // null: signed in
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    final File dir, state, pending, sending;

    SteamLink(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("steam", Context.MODE_PRIVATE);
        dir = new File(Storage.root(ctx), "steam");
        state = new File(dir, "state.txt");
        pending = new File(dir, "pending.txt");
        sending = new File(dir, "pending.sending");
    }

    boolean signedIn() { return !prefs.getString("token", "").isEmpty(); }
    String account() { return prefs.getString("account", ""); }

    /** Steam achievements are on for the game: signed in and the account's state is known. */
    boolean active() { return signedIn() && state.isFile(); }

    /** {unlocked, total} from the cached state, or null. */
    int[] counts() {
        if (!state.isFile()) return null;
        int got = 0, all = 0;
        for (String l : readLines(state)) {
            String[] f = l.split(" ", 4);
            if (f.length < 3 || !f[0].equals("ach")) continue;
            all++;
            if (f[2].equals("1")) got++;
        }
        return new int[] { got, all };
    }

    int waiting() { return readLines(pending).size() + readLines(sending).size(); }

    void signOut() {
        synchronized (LOCK) {
            prefs.edit().clear().apply();
            state.delete();
        }
    }

    // ------------------------------------------------------------------ a Steam connection

    /** Steam no longer takes the saved sign-in: the player signs in again. */
    static final class TokenRejected extends IOException {
        TokenRejected(EResult r) { super("Steam asks you to sign in again (" + r + ")"); }
    }

    private static final class Session implements AutoCloseable {
        final SteamClient client = new SteamClient();
        final CallbackManager mgr = new CallbackManager(client);
        final SteamUser user = client.getHandler(SteamUser.class);
        final CompletableFuture<Boolean> connected = new CompletableFuture<>();
        final CompletableFuture<LoggedOnCallback> loggedOn = new CompletableFuture<>();
        volatile boolean running = true;
        boolean playingSent;
        int app;                                         // the FF7 this account owns

        Session() {
            mgr.subscribe(ConnectedCallback.class, cb -> connected.complete(true));
            mgr.subscribe(DisconnectedCallback.class, cb -> {
                IOException e = new IOException("disconnected from Steam");
                connected.completeExceptionally(e);
                loggedOn.completeExceptionally(e);
            });
            mgr.subscribe(LoggedOnCallback.class, loggedOn::complete);
            Thread pump = new Thread(() -> { while (running) mgr.runWaitCallbacks(250L); }, "steam-callbacks");
            pump.setDaemon(true);
            pump.start();
            client.connect();
        }

        void awaitConnected() throws Exception {
            connected.get(30, TimeUnit.SECONDS);
        }

        void logOn(String account, String refreshToken) throws Exception {
            LogOnDetails d = new LogOnDetails();
            d.setUsername(account);
            d.setAccessToken(refreshToken);
            d.setLoginID(LOGIN_ID);
            d.setShouldRememberPassword(true);
            user.logOn(d);
            LoggedOnCallback cb = loggedOn.get(30, TimeUnit.SECONDS);
            if (cb.getResult() == EResult.AccessDenied || cb.getResult() == EResult.InvalidPassword
                    || cb.getResult() == EResult.Expired || cb.getResult() == EResult.Revoked)
                throw new TokenRejected(cb.getResult());
            if (cb.getResult() != EResult.OK)
                throw new IOException("Steam did not sign in (" + cb.getResult() + ")");
        }

        @Override public void close() {
            try { user.logOff(); } catch (Throwable ignored) {}
            try { client.disconnect(); } catch (Throwable ignored) {}
            running = false;
        }
    }

    // ------------------------------------------------------------------ sign in

    /**
     * Signs in by QR code (runs on the caller's thread until the code is scanned, cancelled with
     * cancel[0].cancel(true), or it times out), then reads the account's FF7 achievements.
     */
    void signInQr(QrListener l, Future<?>[] cancel) {
        String err = null;
        try (Session s = new Session()) {
            s.awaitConnected();
            AuthSessionDetails d = new AuthSessionDetails();
            d.deviceFriendlyName = "FF7Recomp";
            d.persistentSession = true;               // a token that stays valid for later sign-ins
            QrAuthSession qs = new SteamAuthentication(s.client).beginAuthSessionViaQR(d).get(30, TimeUnit.SECONDS);
            qs.setChallengeUrlChanged(new IChallengeUrlChanged() {
                @Override public void onChanged(QrAuthSession q) { l.qr(q.getChallengeUrl()); }
            });
            l.qr(qs.getChallengeUrl());
            CompletableFuture<AuthPollResult> poll = qs.pollingWaitForResult();
            if (cancel != null) cancel[0] = poll;
            AuthPollResult r = poll.get();
            s.logOn(r.getAccountName(), r.getRefreshToken());
            synchronized (LOCK) {
                prefs.edit().putString("account", r.getAccountName()).putString("token", r.getRefreshToken()).apply();
                try {
                    exchange(s);
                } catch (Throwable e) {
                    Log.e(TAG, "first sync failed", e);
                    err = "signed in, but your FF7 achievements could not be read yet: " + message(e)
                            + ". Press Sync now to try again.";
                }
            }
        } catch (java.util.concurrent.CancellationException e) {
            err = "cancelled";
        } catch (Throwable e) {
            Log.e(TAG, "sign-in failed", e);
            err = message(e);
        }
        l.done(err);
    }

    // ------------------------------------------------------------------ sync

    /**
     * Sends waiting unlocks and refreshes state.txt. Returns the display names newly unlocked on
     * Steam (empty when nothing was waiting). Throws when Steam cannot be reached; what was
     * waiting stays waiting.
     */
    List<String> sync() throws Exception {
        synchronized (LOCK) {
            if (!signedIn()) throw new IOException("not signed in to Steam");
            try (Session s = new Session()) {
                s.awaitConnected();
                try {
                    s.logOn(account(), prefs.getString("token", ""));
                } catch (TokenRejected e) {
                    prefs.edit().clear().apply();          // what was earned stays waiting in pending.txt
                    throw e;
                }
                return exchange(s);
            }
        }
    }

    private static final class Ach { int stat, bit; String name, display; }

    /** Tells Steam this account is (or no longer is) playing FF7, as the Steam client does while
     *  the game runs: Steam keeps a game's stats ready only for a running game. */
    private static boolean owns(Session s, int app) {
        try {
            EResult r = s.client.getHandler(in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps.class)
                    .getAppOwnershipTicket(app).toFuture().get(30, TimeUnit.SECONDS).getResult();
            Log.i(TAG, "app " + app + " ownership: " + r);
            return r == EResult.OK;
        } catch (Exception e) {
            Log.w(TAG, "app " + app + " ownership: " + message(e));
            return false;
        }
    }

    /** Which FF7 this account owns (remembered): the re-release first, then the 2013 release. */
    private int ownedApp(Session s) throws IOException {
        int known = prefs.getInt("appid", 0);
        if (known != 0) return known;
        int app = owns(s, APP_RERELEASE) ? APP_RERELEASE : owns(s, APP_2013) ? APP_2013 : 0;
        if (app == 0) throw new IOException("this Steam account does not own FINAL FANTASY VII on Steam, so it has no FF7 achievements");
        prefs.edit().putInt("appid", app).apply();
        return app;
    }

    private static void playing(Session s, boolean on) {
        ClientMsgProtobuf<SteammessagesClientserver.CMsgClientGamesPlayed.Builder> m =
                new ClientMsgProtobuf<>(SteammessagesClientserver.CMsgClientGamesPlayed.class, EMsg.ClientGamesPlayedWithDataBlob);
        if (on) m.getBody().addGamesPlayed(SteammessagesClientserver.CMsgClientGamesPlayed.GamePlayed.newBuilder().setGameId(s.app));
        s.client.send(m);
    }

    /** ClientGetUserStats as the Steam client sends it: no cached schema (version -1, crc 0). */
    private static UserStatsCallback stats(SteamClient client, int app, SteamID me) throws Exception {
        ClientMsgProtobuf<SteammessagesClientserverUserstats.CMsgClientGetUserStats.Builder> msg =
                new ClientMsgProtobuf<>(SteammessagesClientserverUserstats.CMsgClientGetUserStats.class, EMsg.ClientGetUserStats);
        in.dragonbra.javasteam.types.JobID job = client.getNextJobID();
        msg.setSourceJobID(job);
        msg.getBody().setGameId(app).setSteamIdForUser(me.convertToUInt64()).setCrcStats(0).setSchemaLocalVersion(-1);
        in.dragonbra.javasteam.types.AsyncJobSingle<UserStatsCallback> pending =
                new in.dragonbra.javasteam.types.AsyncJobSingle<>(client, job);
        client.send(msg);
        UserStatsCallback st = pending.toFuture().get(30, TimeUnit.SECONDS);
        Log.i(TAG, "user stats: " + st.getResult() + ", schema " + st.getSchema().size() + " bytes, "
                + st.getStats().size() + " stats, crc " + Integer.toHexString(st.getCrcStats()));
        return st;
    }

    private List<String> exchange(Session s) throws Exception {
        try {
            return exchangeStats(s);
        } finally {
            if (s.playingSent) playing(s, false);
        }
    }

    private List<String> exchangeStats(Session s) throws Exception {
        SteamUserStats us = s.client.getHandler(SteamUserStats.class);
        SteamID me = s.client.getSteamID();
        s.app = ownedApp(s);
        UserStatsCallback st = stats(s.client, s.app, me);
        if (st.getResult() != EResult.OK) {
            playing(s, true);
            s.playingSent = true;
            Thread.sleep(2000);
            st = stats(s.client, s.app, me);
        }
        if (st.getResult() != EResult.OK) throw new IOException("Steam did not return your FF7 achievements (" + st.getResult() + ")");
        Map<String, Ach> schema = parseSchema(st.getSchemaKeyValues());
        if (schema.isEmpty()) throw new IOException("Steam returned no FF7 achievement list");
        Map<Integer, Integer> values = values(st);

        // what the game unlocked: pending.txt, moved aside so the game can keep appending
        dir.mkdirs();
        if (pending.isFile() && !sending.isFile()) pending.renameTo(sending);
        else if (pending.isFile()) { append(sending, readLines(pending)); pending.delete(); }
        Set<String> waiting = new LinkedHashSet<>();
        for (String l : readLines(sending)) {
            String n = l.split("\t", 2)[0].trim();
            if (!n.isEmpty()) waiting.add(n);
        }

        Map<Integer, Integer> push = new HashMap<>();
        List<String> newly = new ArrayList<>();
        for (String n : waiting) {
            Ach a = schema.get(n);
            if (a == null) { Log.w(TAG, "not an FF7 Steam achievement: " + n); continue; }
            int v = values.containsKey(a.stat) ? values.get(a.stat) : 0;
            if ((v >>> a.bit & 1) != 0) continue;          // already on Steam
            v |= 1 << a.bit;
            values.put(a.stat, v);
            push.put(a.stat, v);
            newly.add(a.display);
        }
        if (!push.isEmpty()) {
            ClientMsgProtobuf<CMsgClientStoreUserStats2.Builder> msg =
                    new ClientMsgProtobuf<>(CMsgClientStoreUserStats2.class, EMsg.ClientStoreUserStats2);
            msg.getBody().setGameId(s.app)
                    .setSettorSteamId(me.convertToUInt64())
                    .setSetteeSteamId(me.convertToUInt64())
                    .setCrcStats(st.getCrcStats())
                    .setExplicitReset(false);
            for (Map.Entry<Integer, Integer> e : push.entrySet())
                msg.getBody().addStats(CMsgClientStoreUserStats2.Stats.newBuilder().setStatId(e.getKey()).setStatValue(e.getValue()));
            s.client.send(msg);
            Log.i(TAG, "sent " + newly.size() + " achievement(s) to Steam: " + newly);
            // read back: Steam answers the store asynchronously
            Map<Integer, Integer> back = null;
            for (int tries = 0; tries < 5; tries++) {
                Thread.sleep(1500);
                UserStatsCallback again = us.getUserStats(s.app, me).toFuture().get(30, TimeUnit.SECONDS);
                if (again.getResult() != EResult.OK) continue;
                back = values(again);
                boolean all = true;
                for (Map.Entry<Integer, Integer> e : push.entrySet()) {
                    Integer b = back.get(e.getKey());
                    if (b == null || (b & e.getValue()) != e.getValue()) { all = false; break; }
                }
                if (all) break;
                back = null;
            }
            if (back == null) throw new IOException("Steam did not take the achievement(s) yet; they will be sent again");
            values = back;
        }
        writeState(s.app, schema, values);
        sending.delete();
        if (!newly.isEmpty()) notifyUnlocked(newly);
        return newly;
    }

    private static Map<Integer, Integer> values(UserStatsCallback st) {
        Map<Integer, Integer> m = new HashMap<>();
        for (Stats s : st.getStats()) m.put(s.getStatId(), s.getStatValue());
        return m;
    }

    /** The achievement bits of the stats schema: name -> (stat id, bit, display name). */
    private static Map<String, Ach> parseSchema(KeyValue root) {
        Map<String, Ach> out = new LinkedHashMap<>();
        KeyValue stats = find(root, "stats", 3);
        if (stats == null) return out;
        for (KeyValue stat : stats.getChildren()) {
            KeyValue bits = child(stat, "bits");
            if (bits == null) continue;                        // an ordinary stat
            int statId;
            try { statId = Integer.parseInt(stat.getName()); } catch (NumberFormatException e) { continue; }
            for (KeyValue b : bits.getChildren()) {
                KeyValue nm = child(b, "name");
                if (nm == null || nm.getValue() == null) continue;
                Ach a = new Ach();
                a.stat = statId;
                try { a.bit = Integer.parseInt(b.getName()); } catch (NumberFormatException e) { continue; }
                a.name = nm.getValue();
                a.display = a.name;
                KeyValue disp = child(child(b, "display"), "name");
                if (disp != null) {
                    if (disp.getValue() != null && !disp.getValue().isEmpty()) a.display = disp.getValue();
                    KeyValue en = child(disp, "english");
                    if (en != null && en.getValue() != null) a.display = en.getValue();
                }
                out.put(a.name, a);
            }
        }
        return out;
    }

    private static KeyValue child(KeyValue kv, String name) {
        if (kv == null) return null;
        for (KeyValue c : kv.getChildren()) if (name.equalsIgnoreCase(c.getName())) return c;
        return null;
    }

    private static KeyValue find(KeyValue kv, String name, int depth) {
        if (kv == null) return null;
        if (name.equalsIgnoreCase(kv.getName())) return kv;
        if (depth == 0) return null;
        for (KeyValue c : kv.getChildren()) {
            KeyValue f = find(c, name, depth - 1);
            if (f != null) return f;
        }
        return null;
    }

    private void writeState(int app, Map<String, Ach> schema, Map<Integer, Integer> values) throws IOException {
        StringBuilder sb = new StringBuilder("appid ").append(app).append('\n');
        for (Ach a : schema.values()) {
            int v = values.containsKey(a.stat) ? values.get(a.stat) : 0;
            sb.append("ach ").append(a.name).append(' ').append((v >>> a.bit & 1) != 0 ? 1 : 0)
              .append(' ').append(a.display.replace('\n', ' ')).append('\n');
        }
        dir.mkdirs();
        File tmp = new File(dir, "state.tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(sb.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(state)) throw new IOException("cannot write " + state);
    }

    private void notifyUnlocked(List<String> names) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("steam", "Steam achievements", NotificationManager.IMPORTANCE_DEFAULT));
            String text = String.join(", ", names);
            nm.notify(0x57EA, new Notification.Builder(ctx, "steam")
                    .setSmallIcon(android.R.drawable.star_on)
                    .setContentTitle(names.size() == 1 ? "Steam achievement unlocked" : names.size() + " Steam achievements unlocked")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build());
        } catch (Throwable e) {
            Log.w(TAG, "notification", e);
        }
    }

    // ------------------------------------------------------------------ files

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try {
            for (String l : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n"))
                if (!l.trim().isEmpty()) out.add(l.trim());
        } catch (IOException ignored) {}
        return out;
    }

    private static void append(File f, List<String> lines) throws IOException {
        try (OutputStream o = new FileOutputStream(f, true)) {
            for (String l : lines) o.write((l + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    static String message(Throwable e) {
        while (e.getCause() != null && (e instanceof java.util.concurrent.ExecutionException || e.getMessage() == null)) e = e.getCause();
        if (e instanceof java.util.concurrent.TimeoutException) return "Steam did not answer in time (no internet?)";
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
