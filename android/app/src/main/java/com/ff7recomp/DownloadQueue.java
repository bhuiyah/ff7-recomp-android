package com.ff7recomp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The launcher's download queue: catalog mods are installed one at a time, in the order they
 * were added, on a worker thread, while the launcher stays usable. One queue per process (the
 * launcher activity can be recreated while a download runs).
 *
 * Waiting and running items are kept in files/downloads/queue.txt ("id<TAB>update" lines), so
 * a queue cut short (the launcher closed or killed) carries on the next time the launcher
 * opens; the running download resumes from its .part file.
 */
final class DownloadQueue {
    private static final String TAG = "FF7Launcher";

    enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    static final class Item {
        final ModCatalog.Mod mod;
        final boolean update;
        volatile State state = State.QUEUED;
        volatile String phase = "Waiting";     // Downloading / Installing
        volatile long got, total;
        volatile String error;
        Item(ModCatalog.Mod mod, boolean update) { this.mod = mod; this.update = update; }

        /** 0..100, or -1 when the size is unknown. */
        int percent() {
            long t = total > 0 ? total : mod.sizeKb * 1024;
            return t > 0 ? (int) Math.min(100, got * 100 / t) : -1;
        }
    }

    /** What the queue needs from the launcher (called on the worker thread). */
    interface Host {
        /** A mod finished installing as `file` in files/mods. */
        void installed(ModCatalog.Mod m, String file, boolean wasUpdate);
    }

    private final ModCatalog catalog;
    private final ModDownloader downloader;
    private final Host host;
    private final File saveFile;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();      // guarded by this
    private Thread worker;
    private Runnable listener;                               // main thread only
    private boolean notifyPosted;

    DownloadQueue(ModCatalog catalog, ModDownloader downloader, File downloadsDir, Host host) {
        this.catalog = catalog;
        this.downloader = downloader;
        this.host = host;
        downloadsDir.mkdirs();
        this.saveFile = new File(downloadsDir, "queue.txt");
    }

    /** Re-queue what was waiting when the launcher last closed. */
    void restore() {
        try {
            // what the queue still holds, so the sweep keeps their partial downloads
            java.util.Set<String> ids = new java.util.HashSet<>();
            if (saveFile.isFile())
                for (String line : new String(Files.readAllBytes(saveFile.toPath()), StandardCharsets.UTF_8).split("\n")) {
                    String[] f = line.trim().split("\t");
                    if (f.length > 0 && !f[0].isEmpty()) ids.add(f[0]);
                }
            long freed = downloader.sweep(ids);
            if (freed > 0) Log.i(TAG, "freed " + (freed >> 20) + " MB of unfinished downloads and unpack folders");
            if (!saveFile.isFile()) return;
            for (String line : new String(Files.readAllBytes(saveFile.toPath()), StandardCharsets.UTF_8).split("\n")) {
                String[] f = line.trim().split("\t");
                if (f.length == 0 || f[0].isEmpty()) continue;
                ModCatalog.Mod m = catalog.byId(f[0]);
                if (m == null) continue;
                boolean update = f.length > 1 && f[1].equals("1");
                File marker = downloader.installingMarker(m.id);
                if (marker.exists()) {
                    // the app died while unpacking this mod: starting it again would only loop
                    marker.delete();
                    Item it = new Item(m, update);
                    synchronized (this) { items.add(it); }
                    finish(it, State.FAILED, "the launcher closed while installing this mod. Retry, or remove it from the list");
                    continue;
                }
                add(m, update);
            }
        } catch (Exception e) {
            Log.w(TAG, "download queue unreadable: " + e);
        }
    }

    /** Main-thread callback run (coalesced) whenever an item changes. */
    void setListener(Runnable r) { listener = r; }

    synchronized List<Item> snapshot() { return new ArrayList<>(items); }

    /** The waiting or running item for this mod, or null. */
    synchronized Item pending(ModCatalog.Mod m) {
        for (Item it : items)
            if (it.mod.id.equals(m.id) && (it.state == State.QUEUED || it.state == State.RUNNING)) return it;
        return null;
    }

    synchronized int pendingCount() {
        int n = 0;
        for (Item it : items) if (it.state == State.QUEUED || it.state == State.RUNNING) n++;
        return n;
    }

    synchronized Item running() {
        for (Item it : items) if (it.state == State.RUNNING) return it;
        return null;
    }

    /** Queue a mod (no-op when it is already waiting or running). Returns true when added. */
    synchronized boolean add(ModCatalog.Mod m, boolean update) {
        if (pending(m) != null) return false;
        // a finished / failed entry for the same mod gives way to the new one
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).mod.id.equals(m.id)) items.remove(i);
        items.add(new Item(m, update));
        save();
        changed();
        if (worker == null) {
            worker = new Thread(this::work, "mod-downloads");
            worker.start();
        }
        return true;
    }

    /** Cancel a waiting item, or stop the running one (its partial download is deleted). */
    synchronized void cancel(Item it) {
        if (it.state == State.QUEUED) { it.state = State.CANCELLED; save(); changed(); }
        else if (it.state == State.RUNNING) downloader.requestCancel();   // the download or the unpack
    }

    /** Move a waiting item up (-1) or down (+1) among the waiting ones. */
    synchronized void move(Item it, int dir) {
        int i = items.indexOf(it);
        int j = i + dir;
        if (i < 0 || j < 0 || j >= items.size() || it.state != State.QUEUED || items.get(j).state != State.QUEUED) return;
        items.set(i, items.get(j));
        items.set(j, it);
        save();
        changed();
    }

    synchronized void retry(Item it) {
        if (it.state == State.FAILED || it.state == State.CANCELLED) add(it.mod, it.update);
    }

    /** Drop finished, failed and cancelled entries from the list (and failed ones' partial downloads). */
    synchronized void clearFinished() {
        for (int i = items.size() - 1; i >= 0; i--) {
            State s = items.get(i).state;
            if (s == State.FAILED) downloader.discard(items.get(i).mod.id);
            if (s != State.QUEUED && s != State.RUNNING) items.remove(i);
        }
        changed();
    }

    private Item next() {
        synchronized (this) {
            for (Item it : items) if (it.state == State.QUEUED) { it.state = State.RUNNING; it.phase = "Starting"; changed(); return it; }
            worker = null;          // the next add() starts a new worker
            return null;
        }
    }

    private void work() {
        Item it;
        while ((it = next()) != null) {
            final Item cur = it;
            ModCatalog.Mod m = cur.mod;
            ModCatalog.Installed in = catalog.installedOf(m);
            if (in != null && !catalog.updateAvailable(m)) { finish(cur, State.DONE, null); continue; }
            // an update keeps the library name, so the load order and options stay
            String base = in != null ? in.file.replaceAll("(?i)\\.iro$", "") : m.name;
            ModDownloader.Result r;
            try {
                r = downloader.install(m.id, base, m.links.toArray(new String[0]), m.sizeKb * 1024, (what, got, total) -> {
                    cur.phase = what;
                    if (got > 0) cur.got = got;
                    if (total > 0) cur.total = total;
                    changed();
                });
            } catch (Throwable e) {        // incl. OutOfMemoryError: fail the item, never the app
                r = new ModDownloader.Result();
                r.error = String.valueOf(e);
            }
            if (ModDownloader.CANCELLED.equals(r.error)) { finish(cur, State.CANCELLED, null); continue; }
            if (r.error != null) { finish(cur, State.FAILED, r.error); continue; }
            try {
                catalog.markInstalled(m, r.file);
                host.installed(m, r.file, in != null);
                finish(cur, State.DONE, null);
            } catch (Exception e) {
                finish(cur, State.FAILED, String.valueOf(e));
            }
        }
    }

    private void finish(Item it, State s, String error) {
        synchronized (this) {
            it.error = error;
            it.state = s;
            if (error != null) android.util.Log.w("FF7Mods", it.mod.name + ": " + error);
            it.phase = s == State.DONE ? "Installed" : s == State.CANCELLED ? "Cancelled" : "Failed";
            save();
            changed();
        }
    }

    /** queue.txt: the waiting and running items, in order. */
    private void save() {
        StringBuilder b = new StringBuilder();
        for (Item it : items)
            if (it.state == State.QUEUED || it.state == State.RUNNING)
                b.append(it.mod.id).append('\t').append(it.update ? "1" : "0").append('\n');
        try {
            if (b.length() == 0) { saveFile.delete(); return; }
            try (FileOutputStream o = new FileOutputStream(saveFile)) { o.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception e) {
            Log.w(TAG, "cannot save the download queue: " + e);
        }
    }

    /** Tell the launcher, at most once per frame-ish (progress arrives every megabyte). */
    private void changed() {
        synchronized (this) {
            if (notifyPosted) return;
            notifyPosted = true;
        }
        main.postDelayed(() -> {
            synchronized (DownloadQueue.this) { notifyPosted = false; }
            if (listener != null) listener.run();
        }, 100);
    }
}
