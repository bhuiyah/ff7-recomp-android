package com.ff7recomp;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import in.dragonbra.javasteam.enums.EDepotFileFlag;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.cdn.Server;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.DepotKeyCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList;
import in.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileDownloadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadBlockDetails;
import in.dragonbra.javasteam.steam.handlers.steamcloud.FileUploadInfo;
import in.dragonbra.javasteam.steam.handlers.steamcloud.HttpHeaders;
import in.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud;
import in.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent;
import in.dragonbra.javasteam.types.ChunkData;
import in.dragonbra.javasteam.types.DepotManifest;
import in.dragonbra.javasteam.types.FileData;
import in.dragonbra.javasteam.types.KeyValue;
import kotlinx.coroutines.GlobalScope;
import kotlinx.coroutines.future.FutureKt;

/**
 * The game's files and saves from Steam, over SteamLink's signed-in connection.
 *
 * Download: FINAL FANTASY VII (app 3837340) has one depot; from it come the files the folder
 * import takes (GameInstall.importGame): ff7/resources/ff7_1.02/ff7_en as ff7_en.exe and
 * ff7/workingdir/data (English, French, German and Spanish). Every file is checked against
 * Steam's SHA-1; files already here and intact are kept, so a download stopped half way goes on
 * where it was, and running it again repairs damaged files.
 *
 * Cloud saves: the game's Steam Cloud (Auto-Cloud: every *.ff7 under AppData\Local\FINAL
 * FANTASY VII Steam Edition\<SteamID>, 10 MB) carries FF7Recomp's save files (save00.ff7 ...
 * save09.ff7, the 1.02 format) in its own FF7Recomp folder there, so they go between the
 * Android devices signed in to the same account. The PC game's own saves (another format) are
 * not touched. A file changed on one side only goes to the other; changed on both, the newer
 * wins; a save about to be replaced is first copied to files/steam/cloud-backup.
 */
final class SteamFiles {
    private static final String TAG = "FF7SteamFiles";

    static {
        // JavaSteam decrypts depot chunks with the provider named "BC" and loads
        // org.bouncycastle's class for it. Android's own "BC" lacks AES since Android 9, so the
        // full BouncyCastle (bcprov) takes the name.
        try {
            java.security.Security.removeProvider("BC");
            java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        } catch (Throwable e) {
            Log.w(TAG, "BouncyCastle", e);
        }
    }

    interface Progress { void update(String what); }

    private final SteamLink link;

    SteamFiles(SteamLink link) { this.link = link; }

    // ================================================================== game download

    static final String NEED_RERELEASE = "this Steam account does not have FINAL FANTASY VII (the current version, "
            + "\"FINAL FANTASY VII\" without \"2013 Edition\"). Steam gives it free to everyone who owns the 2013 "
            + "Edition: add it to your library in Steam, then try again";

    /** A file of the depot and where it goes under the game folder. */
    private static final class Want { FileData f; String to; }

    private static final Pattern LANG = Pattern.compile("ff7/workingdir/data/lang-([a-z]+)/.*");

    /** Where a depot file goes under the game folder, or null when the game does not need it. */
    static String target(String depotName) {
        String n = depotName.replace('\\', '/');
        String l = n.toLowerCase(Locale.ROOT);
        if (l.equals("ff7/resources/ff7_1.02/ff7_en")) return "ff7_en.exe";
        if (!l.startsWith("ff7/workingdir/data/")) return null;
        java.util.regex.Matcher m = LANG.matcher(l);
        if (m.matches() && !Arrays.asList("en", "fr", "de", "es").contains(m.group(1))) return null;   // as importGame
        return "data/" + n.substring("ff7/workingdir/data/".length());
    }

    /**
     * Downloads the game's files into game (GameInstall.game). Returns null when they are all
     * there and intact, else what is wrong. cancel stops it (what was done stays).
     */
    String downloadGame(File game, Progress p, AtomicBoolean cancel) throws Exception {
        p.update("Connecting to Steam");
        try (SteamLink.Session s = link.open()) {
            if (!SteamLink.owns(s, SteamLink.APP_RERELEASE)) return NEED_RERELEASE;
            int app = SteamLink.APP_RERELEASE;
            p.update("Reading the game's file list from Steam");
            KeyValue depots = appInfo(s, app).get("depots");
            SteamApps apps = s.client.getHandler(SteamApps.class);
            SteamContent content = s.client.getHandler(SteamContent.class);
            List<Server> servers = new ArrayList<>();
            for (Server sv : FutureKt.asCompletableFuture(content.getServersForSteamPipe(s.cell, 20, GlobalScope.INSTANCE)).get(30, TimeUnit.SECONDS))
                if ("CDN".equals(sv.getType()) || "SteamCache".equals(sv.getType())) servers.add(sv);
            if (servers.isEmpty()) throw new IOException("Steam named no download server");

            Map<Integer, byte[]> keys = new HashMap<>();
            Map<String, Want> wants = new LinkedHashMap<>();
            Map<Want, Integer> depotOf = new HashMap<>();
            try (in.dragonbra.javasteam.steam.cdn.Client cdn = new in.dragonbra.javasteam.steam.cdn.Client(s.client)) {
                for (KeyValue d : depots.getChildren()) {
                    int id;
                    try { id = Integer.parseInt(d.getName()); } catch (NumberFormatException e) { continue; }
                    String os = d.get("config").get("oslist").getValue(), lang = d.get("config").get("language").getValue();
                    if (os != null && !os.contains("windows")) continue;
                    if (lang != null && !lang.isEmpty() && !lang.equals("english")) continue;
                    KeyValue pub = d.get("manifests").get("public");
                    String gid = pub.get("gid").getValue() != null ? pub.get("gid").getValue() : pub.getValue();
                    if (gid == null) continue;                              // shared redistributables
                    long manifestId = Long.parseUnsignedLong(gid);
                    DepotKeyCallback k = apps.getDepotDecryptionKey(id, app).toFuture().get(30, TimeUnit.SECONDS);
                    if (k.getResult() != EResult.OK) { Log.w(TAG, "depot " + id + " key: " + k.getResult()); continue; }
                    long code = FutureKt.asCompletableFuture(content.getManifestRequestCode(id, app, manifestId, "public", null, GlobalScope.INSTANCE))
                            .get(30, TimeUnit.SECONDS);
                    DepotManifest m = null;
                    Exception last = null;
                    for (int t = 0; t < Math.min(4, servers.size()) && m == null; t++) {
                        try { m = cdn.downloadManifestFuture(id, manifestId, code, servers.get(t), k.getDepotKey()).get(120, TimeUnit.SECONDS); }
                        catch (Exception e) { last = e; }
                    }
                    if (m == null) throw last;
                    if (m.getFilenamesEncrypted()) m.decryptFilenames(k.getDepotKey());
                    keys.put(id, k.getDepotKey());
                    for (FileData f : m.getFiles()) {
                        if (f.getFlags().contains(EDepotFileFlag.Directory)) continue;
                        String to = target(f.getFileName());
                        if (to == null || wants.containsKey(to.toLowerCase(Locale.ROOT))) continue;
                        Want w = new Want();
                        w.f = f;
                        w.to = to;
                        wants.put(to.toLowerCase(Locale.ROOT), w);
                        depotOf.put(w, id);
                    }
                }
                if (!wants.containsKey("ff7_en.exe")) throw new IOException("Steam's copy of the game has no ff7/resources/ff7_1.02/ff7_en");

                // what is already here and intact stays
                game.mkdirs();
                List<Want> todo = new ArrayList<>();
                long total = 0, have = 0;
                int checked = 0;
                for (Want w : wants.values()) {
                    if (cancel.get()) return "stopped";
                    if ((++checked & 63) == 0) p.update("Checking the files already here: " + checked + " of " + wants.size());
                    File dst = new File(game, w.to);
                    total += w.f.getTotalSize();
                    if (dst.isFile() && dst.length() == w.f.getTotalSize() && Arrays.equals(sha1(dst), w.f.getFileHash())) {
                        have += w.f.getTotalSize();
                        continue;
                    }
                    todo.add(w);
                }
                Log.i(TAG, wants.size() + " files, " + todo.size() + " to download, " + (total - have) + " bytes");

                AtomicLong done = new AtomicLong(have);
                AtomicInteger next = new AtomicInteger(), failed = new AtomicInteger();
                final List<String> errors = Collections.synchronizedList(new ArrayList<>());
                final long tot = total;
                ExecutorService pool = Executors.newFixedThreadPool(8);
                List<Future<?>> jobs = new ArrayList<>();
                for (Want w : todo) {
                    jobs.add(pool.submit(() -> {
                        if (cancel.get() || failed.get() > 0) return;
                        try {
                            fetch(cdn, servers, next, depotOf.get(w), keys.get(depotOf.get(w)), w, new File(game, w.to), done, cancel);
                        } catch (Exception e) {
                            Log.e(TAG, "download " + w.to, e);
                            errors.add(w.to + ": " + SteamLink.message(e));
                            failed.incrementAndGet();
                        }
                    }));
                }
                pool.shutdown();
                while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS))
                    p.update("Downloading from Steam: " + gb(done.get()) + " of " + gb(tot) + " GB");
                if (cancel.get()) return "stopped";
                if (!errors.isEmpty()) throw new IOException(errors.get(0));
            }
        }
        return null;
    }

    private static String gb(long b) { return String.format(Locale.ROOT, "%.2f", b / 1e9); }

    /** One file: its chunks, each from the next server (another on failure), then its SHA-1. */
    private static void fetch(in.dragonbra.javasteam.steam.cdn.Client cdn, List<Server> servers, AtomicInteger next, int depot, byte[] key,
                              Want w, File dst, AtomicLong done, AtomicBoolean cancel) throws Exception {
        File part = new File(dst.getPath() + ".part");
        dst.getParentFile().mkdirs();
        long got = 0;
        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            raf.setLength(w.f.getTotalSize());
            for (ChunkData c : w.f.getChunks()) {
                if (cancel.get()) return;
                // JavaSteam 1.8.0 wants room for the compressed chunk when a depot key is given (its
                // check is the other way round from SteamKit's): a small file that does not
                // compress (data/battle/mark.dat) is bigger compressed
                byte[] buf = new byte[Math.max(c.getUncompressedLength(), c.getCompressedLength())];
                int n = -1;
                Exception last = null;
                for (int t = 0; t < 6 && n < 0; t++) {
                    Server sv = servers.get(Math.floorMod(next.getAndIncrement(), servers.size()));
                    try { n = cdn.downloadDepotChunkFuture(depot, c, sv, buf, key).get(120, TimeUnit.SECONDS); }
                    catch (Exception e) { last = e; Thread.sleep(500L * (t + 1)); }
                }
                if (n < 0) throw last;
                raf.seek(c.getOffset());
                raf.write(buf, 0, n);
                done.addAndGet(n);
                got += n;
            }
        } catch (Exception e) {
            done.addAndGet(-got);
            throw e;
        }
        if (!Arrays.equals(sha1(part), w.f.getFileHash())) {
            part.delete();
            done.addAndGet(-got);
            throw new IOException("the downloaded file did not match Steam's checksum");
        }
        if (dst.exists() && !dst.delete()) throw new IOException("cannot replace " + dst);
        if (!part.renameTo(dst)) throw new IOException("cannot write " + dst);
    }

    // ================================================================== cloud saves

    /** FF7Recomp's own folder in the game's Steam Cloud (Android devices only). */
    static final String CLOUD_FOLDER = "FF7Recomp";
    /** The PC game's own saves: the account's folder itself. The current PC release writes the
     *  same save files as the 1.02 game (save00.ff7 ... save09.ff7: 15 slots of 4340 bytes, the
     *  same checksum, no metadata.xml), so they are shared as they are. */
    static final String PC_FOLDER = "";
    private static final Pattern SAVE = Pattern.compile("save\\d\\d\\.ff7");

    /** What a sync did, for the launcher. */
    static final class SyncResult {
        final List<String> up = new ArrayList<>(), down = new ArrayList<>(), conflicts = new ArrayList<>();
        @Override public String toString() {
            if (up.isEmpty() && down.isEmpty()) return "Your saves and Steam Cloud already match.";
            StringBuilder sb = new StringBuilder();
            if (!down.isEmpty()) sb.append("From Steam Cloud: ").append(names(down)).append(". ");
            if (!up.isEmpty()) sb.append("To Steam Cloud: ").append(names(up)).append(". ");
            if (!conflicts.isEmpty()) sb.append("Changed on both sides, so the newer was kept: ").append(names(conflicts))
                    .append(" (the other copy is in steam/cloud-backup).");
            return sb.toString().trim();
        }
        private static String names(List<String> l) {
            List<String> o = new ArrayList<>();
            for (String n : l) o.add("Save " + (Integer.parseInt(n.substring(4, 6)) + 1));
            return String.join(", ", o);
        }
    }

    /** The real save folder with the PC game's saves (withPc) or FF7Recomp's own cloud folder. */
    SyncResult syncSaves(File saveDir, boolean withPc) throws Exception {
        return withPc ? syncSaves(saveDir, new File(link.dir, "cloud-sync-pc.txt"), PC_FOLDER)
                      : syncSaves(saveDir, new File(link.dir, "cloud-sync.txt"), CLOUD_FOLDER);
    }

    /** saveNN.ff7 when full (a Steam Cloud name) is a save directly in folder ("" the account's
     *  own folder) of the account sid, else null. */
    static String cloudSave(String full, String sid, String folder) {
        String low = full.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = low.lastIndexOf('/');
        if (slash < 0) return null;
        String name = low.substring(slash + 1), dir = low.substring(0, slash);
        if (!SAVE.matcher(name).matches()) return null;
        String want = "/" + sid + (folder.isEmpty() ? "" : "/" + folder.toLowerCase(Locale.ROOT));
        return dir.endsWith(want) ? name : null;
    }

    /**
     * Two-way sync of saveDir's saveNN.ff7 with cloudFolder in the game's Steam Cloud. state
     * holds each file's SHA-1 when both sides last matched.
     */
    SyncResult syncSaves(File saveDir, File state, String cloudFolder) throws Exception {
        SyncResult r = new SyncResult();
        try (SteamLink.Session s = link.open()) {
            if (!SteamLink.owns(s, SteamLink.APP_RERELEASE)) throw new IOException(NEED_RERELEASE);
            int app = SteamLink.APP_RERELEASE;
            String sid = Long.toUnsignedString(s.client.getSteamID().convertToUInt64());
            String base = "%WinAppDataLocal%FINAL FANTASY VII Steam Edition/" + sid + "/" + (cloudFolder.isEmpty() ? "" : cloudFolder + "/");

            // the cloud's copies: name -> (full name, sha, time)
            AppFileChangeList l = cloud(s).getAppFileListChange(app).get(60, TimeUnit.SECONDS);
            Map<String, AppFileInfo> remote = new HashMap<>();
            Map<String, String> remoteName = new HashMap<>();
            for (AppFileInfo f : l.getFiles()) {
                String prefix = f.getPathPrefixIndex() < l.getPathPrefixes().size() ? l.getPathPrefixes().get(f.getPathPrefixIndex()) : "";
                String full = prefix + f.getFilename();
                String name = cloudSave(full, sid, cloudFolder);
                if (name == null) continue;
                if (f.getPersistState() == in.dragonbra.javasteam.protobufs.steamclient.Enums.ECloudStoragePersistState.k_ECloudStoragePersistStateDeleted) continue;
                remote.put(name, f);
                remoteName.put(name, full);
            }
            Log.i(TAG, "cloud: " + remote.keySet() + " (" + l.getFiles().size() + " files in all)");

            Map<String, String> synced = readState(state);
            TreeSet<String> names = new TreeSet<>(remote.keySet());
            String[] local = saveDir.list();
            if (local != null) for (String n : local) if (SAVE.matcher(n.toLowerCase(Locale.ROOT)).matches()) names.add(n.toLowerCase(Locale.ROOT));

            List<String> upload = new ArrayList<>();
            for (String n : names) {
                File f = new File(saveDir, n);
                String lsha = f.isFile() ? hex(sha1(f)) : null;
                AppFileInfo rf = remote.get(n);
                String rsha = rf != null ? hex(rf.getShaFile()) : null;
                String was = synced.get(n);
                if (lsha != null && lsha.equals(rsha)) { synced.put(n, lsha); continue; }
                boolean localChanged = lsha != null && !lsha.equals(was), remoteChanged = rsha != null && !rsha.equals(was);
                boolean takeRemote;
                if (rsha == null) takeRemote = false;
                else if (lsha == null) takeRemote = true;
                else if (remoteChanged && !localChanged) takeRemote = true;
                else if (localChanged && !remoteChanged) takeRemote = false;
                else {                                                         // changed on both sides
                    takeRemote = rf.getTimestamp().getTime() > f.lastModified();
                    r.conflicts.add(n);
                    if (!takeRemote) backup(state.getParentFile(), "cloud-" + n, downloadCloud(s, app, remoteName.get(n)));
                }
                if (takeRemote) {
                    byte[] b = downloadCloud(s, app, remoteName.get(n));
                    if (!hex(sha1(b)).equals(rsha)) throw new IOException("Steam Cloud's " + n + " did not match its checksum");
                    if (!PsxSaves.isPcSave(b)) throw new IOException("Steam Cloud's " + n + " is not a FINAL FANTASY VII save file");
                    if (f.isFile()) backup(state.getParentFile(), n, readFile(f));
                    saveDir.mkdirs();
                    File tmp = new File(saveDir, n + ".cloud");
                    try (OutputStream o = new FileOutputStream(tmp)) { o.write(b); }
                    if (f.exists() && !f.delete()) throw new IOException("cannot replace " + f);
                    if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
                    f.setLastModified(rf.getTimestamp().getTime());
                    synced.put(n, rsha);
                    r.down.add(n);
                } else {
                    upload.add(n);
                }
            }

            if (!upload.isEmpty()) {
                List<String> full = new ArrayList<>();
                for (String n : upload) full.add(base + n);
                SteamCloud c = cloud(s);
                long batch = c.beginAppUploadBatch(app, "FF7Recomp on " + android.os.Build.MODEL, full, Collections.emptyList(), clientId(), 0L)
                        .get(60, TimeUnit.SECONDS).getBatchID();
                EResult result = EResult.Fail;
                try {
                    for (String n : upload) {
                        byte[] b = readFile(new File(saveDir, n));
                        byte[] sha = sha1(b);
                        FileUploadInfo u = c.beginFileUpload(app, b.length, b.length, sha, new Date(new File(saveDir, n).lastModified()),
                                base + n, -1, s.cell, false, false, null, batch, GlobalScope.INSTANCE).get(60, TimeUnit.SECONDS);
                        boolean ok = true;
                        for (FileUploadBlockDetails blk : u.getBlockRequests()) ok &= put(blk, b);
                        if (!c.commitFileUpload(ok, app, sha, base + n).get(60, TimeUnit.SECONDS) || !ok)
                            throw new IOException("Steam Cloud did not take " + n);
                        synced.put(n, hex(sha));
                        r.up.add(n);
                    }
                    result = EResult.OK;
                } finally {
                    try { c.completeAppUploadBatch(app, batch, result).get(60, TimeUnit.SECONDS); } catch (Exception e) { Log.w(TAG, "complete batch", e); }
                }
            }
            writeState(state, synced);
        }
        Log.i(TAG, "sync: down " + r.down + ", up " + r.up + ", conflicts " + r.conflicts);
        return r;
    }

    /** Removes files of cloudFolder from the game's Steam Cloud (the test folder). */
    int deleteCloudFolder(String cloudFolder) throws Exception {
        if (cloudFolder.equals(CLOUD_FOLDER) || cloudFolder.isEmpty()) throw new IllegalArgumentException("real saves are never deleted");
        int n = 0;
        try (SteamLink.Session s = link.open()) {
            int app = SteamLink.APP_RERELEASE;
            AppFileChangeList l = cloud(s).getAppFileListChange(app).get(60, TimeUnit.SECONDS);
            String marker = ("/" + cloudFolder + "/").toLowerCase(Locale.ROOT);
            List<String> del = new ArrayList<>();
            for (AppFileInfo f : l.getFiles()) {
                String prefix = f.getPathPrefixIndex() < l.getPathPrefixes().size() ? l.getPathPrefixes().get(f.getPathPrefixIndex()) : "";
                String full = prefix + f.getFilename();
                if (full.replace('\\', '/').toLowerCase(Locale.ROOT).contains(marker)) del.add(full);
            }
            if (del.isEmpty()) return 0;
            SteamCloud c = cloud(s);
            long batch = c.beginAppUploadBatch(app, "FF7Recomp on " + android.os.Build.MODEL, Collections.emptyList(), del, clientId(), 0L)
                    .get(60, TimeUnit.SECONDS).getBatchID();
            // naming the files in the batch is not enough: each is deleted, as the Steam client does
            in.dragonbra.javasteam.rpc.service.Cloud svc = s.client.getHandler(
                    in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages.class)
                    .createService(in.dragonbra.javasteam.rpc.service.Cloud.class);
            for (String name : del)
                svc.clientDeleteFile(in.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient.CCloud_ClientDeleteFile_Request
                        .newBuilder().setAppid(app).setFilename(name).setIsExplicitDelete(true).setUploadBatchId(batch).build())
                        .toFuture().get(60, TimeUnit.SECONDS);
            c.completeAppUploadBatch(app, batch, EResult.OK).get(60, TimeUnit.SECONDS);
            n = del.size();
        }
        return n;
    }

    /** A listing of the game's Steam Cloud, one line per file (the test API). */
    String cloudListing() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (SteamLink.Session s = link.open()) {
            AppFileChangeList l = cloud(s).getAppFileListChange(SteamLink.APP_RERELEASE).get(60, TimeUnit.SECONDS);
            sb.append("change ").append(l.getCurrentChangeNumber()).append(" prefixes ").append(l.getPathPrefixes()).append('\n');
            for (AppFileInfo f : l.getFiles())
                sb.append(f.getPathPrefixIndex()).append(" | ").append(f.getFilename()).append(" | ").append(f.getRawFileSize())
                  .append(" | ").append(f.getTimestamp()).append(" | ").append(f.getPersistState()).append('\n');
        }
        return sb.toString();
    }

    private long clientId() {
        android.content.SharedPreferences p = link.prefs();
        long id = p.getLong("cloud_client", 0);
        if (id == 0) { id = new java.security.SecureRandom().nextLong() & Long.MAX_VALUE; p.edit().putLong("cloud_client", id).apply(); }
        return id;
    }

    private static SteamCloud cloud(SteamLink.Session s) { return s.client.getHandler(SteamCloud.class); }

    /** One Steam Cloud file's bytes (unzipped when Steam stored it compressed). */
    static byte[] downloadCloud(SteamLink.Session s, int app, String name) throws Exception {
        FileDownloadInfo d = cloud(s).clientFileDownload(app, name).get(60, TimeUnit.SECONDS);
        if (d.getUrlHost() == null || d.getUrlHost().isEmpty()) throw new IOException("Steam gave no download address for " + name);
        HttpURLConnection c = (HttpURLConnection) new URL((d.getUseHttps() ? "https://" : "http://") + d.getUrlHost() + d.getUrlPath()).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        for (HttpHeaders h : d.getRequestHeaders()) c.setRequestProperty(h.getName(), h.getValue());
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("Steam Cloud answered HTTP " + code + " for " + name);
        byte[] b;
        try (InputStream in = c.getInputStream()) { b = readAll(in); }
        if (d.getRawFileSize() != d.getFileSize() && b.length >= 4 && b[0] == 'P' && b[1] == 'K') {
            try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new ByteArrayInputStream(b))) {
                if (z.getNextEntry() != null) b = readAll(z);
            }
        }
        return b;
    }

    /** One upload block, as Steam asks for it. */
    private static boolean put(FileUploadBlockDetails blk, byte[] data) throws IOException {
        String[] methods = { "GET", "GET", "HEAD", "POST", "PUT", "DELETE" };
        String method = blk.getHttpMethod() >= 0 && blk.getHttpMethod() < methods.length ? methods[blk.getHttpMethod()] : "PUT";
        HttpURLConnection c = (HttpURLConnection) new URL((blk.getUseHttps() ? "https://" : "http://") + blk.getUrlHost() + blk.getUrlPath()).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);
        for (HttpHeaders h : blk.getRequestHeaders()) c.setRequestProperty(h.getName(), h.getValue());
        byte[] body = blk.getExplicitBodyData() != null && blk.getExplicitBodyData().length > 0 ? blk.getExplicitBodyData()
                : Arrays.copyOfRange(data, (int) blk.getBlockOffset(), (int) blk.getBlockOffset() + blk.getBlockLength());
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) Log.w(TAG, "upload block: HTTP " + code);
        return code >= 200 && code < 300;
    }

    private static void backup(File steamDir, String name, byte[] b) throws IOException {
        File dir = new File(steamDir, "cloud-backup");
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        try (OutputStream o = new FileOutputStream(new File(dir, stamp + "-" + name))) { o.write(b); }
    }

    private static Map<String, String> readState(File f) {
        Map<String, String> m = new HashMap<>();
        if (!f.isFile()) return m;
        try {
            for (String l : new String(readFile(f), StandardCharsets.UTF_8).split("\n")) {
                String[] p = l.trim().split(" ");
                if (p.length == 2) m.put(p[0], p[1]);
            }
        } catch (IOException ignored) {}
        return m;
    }

    private static void writeState(File f, Map<String, String> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String k : new TreeSet<>(m.keySet())) sb.append(k).append(' ').append(m.get(k)).append('\n');
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(sb.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
    }

    // ================================================================== Steam's app details

    static KeyValue appInfo(SteamLink.Session s, int app) throws Exception {
        SteamApps apps = s.client.getHandler(SteamApps.class);
        PICSTokensCallback t = apps.picsGetAccessTokens(app, null).toFuture().get(30, TimeUnit.SECONDS);
        Long token = t.getAppTokens().get(app);
        PICSRequest req = new PICSRequest(app, token != null ? token : 0L);
        List<PICSProductInfoCallback> res = apps.picsGetProductInfo(Collections.singletonList(req), Collections.emptyList())
                .toFuture().get(60, TimeUnit.SECONDS).getResults();
        for (PICSProductInfoCallback cb : res) {
            PICSProductInfo pi = cb.getApps().get(app);
            if (pi != null) return pi.getKeyValues();
        }
        throw new IOException("Steam returned no details for app " + app);
    }

    // ================================================================== helpers

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }

    static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    static byte[] sha1(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 20];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static byte[] sha1(byte[] b) {
        try { return MessageDigest.getInstance("SHA-1").digest(b); }
        catch (java.security.NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
