package com.ff7recomp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a mod into the library (files/mods), as 7th Heaven's catalog installs do
 * (7th-Heaven AppCore/Install.cs, FileDownloadTask.cs).
 *
 * Links (7th Heaven's LocationType): iros://Url/https$host/path (plain HTTP(S), `$` = "://"),
 * iros://GDrive/<file id> (Google Drive, through its "can't scan for viruses" page),
 * iros://ExternalUrl/... (a web page: opened in a browser), plain http(s). A mod's links are
 * alternatives, tried in order. Downloads resume (files/downloads/<id>.part, HTTP Range) and
 * are checked against the free space first.
 *
 * The download is recognised by its first bytes: an IRO ("IROS") is the mod; a .zip or .7z is
 * unpacked and the .iro inside it, or else the folder with mod.xml (or the whole archive), is
 * the mod. RAR is reported as not supported yet.
 */
final class ModDownloader {
    interface Progress { void update(String what, long done, long total); }

    static final class Result {
        String file;          // the mod's name in files/mods
        String error;         // or why not
    }

    private final File modsDir, downloads;
    /** Set (from any thread) to stop the running install; it returns error CANCELLED. */
    volatile boolean cancel;
    static final String CANCELLED = "cancelled";

    ModDownloader(File filesDir, File modsDir) {
        this.modsDir = modsDir;
        this.downloads = new File(filesDir, "downloads");
        downloads.mkdirs();
    }

    /** iros:// links to fetchable URLs (null: needs a browser). */
    static String resolve(String link) {
        if (link == null) return null;
        String l = link.trim();
        String lower = l.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return l.replace(" ", "%20");
        if (lower.startsWith("iros://url/")) {
            String rest = l.substring("iros://url/".length());
            // scheme$host/path, or (some catalog entries) a plain scheme://host/path
            String rl = rest.toLowerCase(Locale.ROOT);
            String u;
            if (rl.startsWith("http://") || rl.startsWith("https://")) u = rest;
            else if (rl.startsWith("http$") || rl.startsWith("https$")) {
                int d = rest.indexOf('$');
                u = rest.substring(0, d) + "://" + rest.substring(d + 1);
            } else u = "https://" + rest;
            // catalog URLs can hold raw spaces and brackets
            return u.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D").replace("&amp;", "&");
        }
        if (lower.startsWith("iros://gdrive/")) {
            String id = l.substring("iros://gdrive/".length());
            return "https://docs.google.com/uc?id=" + id + "&export=download";
        }
        return null;                                         // ExternalUrl, MegaSharedFolder, ...
    }

    /** The web page for a link that needs a browser (ExternalUrl), or null. */
    static String browserUrl(String link) {
        if (link == null) return null;
        String l = link.trim();
        if (l.toLowerCase(Locale.ROOT).startsWith("iros://externalurl/")) {
            String rest = l.substring("iros://externalurl/".length());
            String rl = rest.toLowerCase(Locale.ROOT);
            if (rl.startsWith("http$") || rl.startsWith("https$")) {
                int d = rest.indexOf('$');
                return rest.substring(0, d) + "://" + rest.substring(d + 1);
            }
            return rest;
        }
        return null;
    }

    /** A link a person has to open in a browser (Mega, shared folders, web pages). */
    static boolean needsBrowser(String link) {
        return resolve(link) == null;
    }

    /** Download the first link that works, and install it as `baseName` (no extension). */
    Result install(String id, String baseName, String[] links, long expectedSize, Progress p) {
        Result r = new Result();
        cancel = false;
        long free = modsDir.getUsableSpace();
        if (expectedSize > 0 && free < expectedSize * 2 + (64L << 20)) {
            r.error = "Not enough free space: needs about " + human(expectedSize * 2) + ", " + human(free) + " free";
            return r;
        }
        String lastError = "no usable download link";
        for (String link : links) {
            String url = resolve(link);
            if (url == null) { lastError = "this mod's download needs a browser (" + host(link) + ")"; continue; }
            File part = new File(downloads, safe(id) + ".part");
            File marker = installingMarker(id);
            try {
                String name = fetch(url, part, p);
                p.update("Installing", 0, 0);
                // left behind only if the app dies while unpacking (DownloadQueue.restore)
                try { marker.createNewFile(); } catch (IOException ignored) { }
                r.file = place(part, name, baseName, p);
                part.delete();
                return r;
            } catch (Exception e) {
                if (cancel) { part.delete(); r.error = CANCELLED; return r; }
                android.util.Log.w("FF7Mods", "install " + baseName, e);
                lastError = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                marker.delete();
            }
        }
        r.error = lastError;
        return r;
    }

    /** Stop the running download or unpack (from any thread). */
    void requestCancel() {
        cancel = true;
        nativeUn7zCancel();
    }

    /** Exists while a mod unpacks: found at start-up, the app died installing it. */
    File installingMarker(String id) { return new File(downloads, safe(id) + ".installing"); }

    /** At start-up, before any download runs: unpack folders left by an install that did not
     *  finish (the app was closed or ran out of memory; a big mod's can be ~19 GB) and partial
     *  downloads of mods no longer in the queue take space for nothing. Returns the bytes freed. */
    long sweep(java.util.Set<String> queuedIds) {
        File[] fs = downloads.listFiles();
        if (fs == null) return 0;
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (String id : queuedIds) keep.add(safe(id) + ".part");
        long freed = 0;
        for (File f : fs) {
            String n = f.getName();
            boolean stale = n.endsWith(".unpack") || (n.endsWith(".part") && !keep.contains(n));
            if (!stale) continue;
            freed += sizeOf(f);
            deleteTree(f);
        }
        return freed;
    }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] c = f.listFiles();
        if (c != null) for (File x : c) n += sizeOf(x);
        return n;
    }

    /** Drop a mod's partial download (a cancelled or abandoned install). */
    void discard(String id) {
        new File(downloads, safe(id) + ".part").delete();
        installingMarker(id).delete();
    }

    // ---- HTTP -----------------------------------------------------------------------------------

    /** Fetch url into part (resuming what is there); returns the server's file name. */
    private String fetch(String url, File part, Progress p) throws IOException {
        String name = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            long have = part.isFile() ? part.length() : 0;
            HttpURLConnection c = open(url, have);
            int code = c.getResponseCode();
            if (code == 416) { c.disconnect(); return name != null ? name : guessName(url); }   // already complete
            if (code != 200 && code != 206) { c.disconnect(); throw new IOException("download failed: HTTP " + code); }
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
            String disp = c.getHeaderField("Content-Disposition");
            if (name == null) name = fileName(disp, c.getURL().toString());
            if (type.startsWith("text/html") && (url.contains("drive.google.com") || url.contains("docs.google.com"))) {
                // Google Drive's "can't scan this file for viruses" page: follow its download form
                String html = readAll(c.getInputStream());
                c.disconnect();
                if (html.contains("Quota exceeded")) throw new IOException("Google Drive: this file's download quota is used up for today; try later");
                String next = driveConfirm(html, c.getURL().toString());
                if (next == null) throw new IOException("Google Drive did not offer the file (permissions)");
                url = next;
                part.delete();
                continue;
            }
            boolean append = code == 206 && have > 0;
            long total = c.getContentLengthLong();
            if (total > 0 && append) total += have;
            if (total > 0 && modsDir.getUsableSpace() < (total - (append ? have : 0)) * 2) {
                c.disconnect();
                throw new IOException("not enough free space for " + human(total));
            }
            long done = append ? have : 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16];
                long lastShown = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel) throw new IOException(CANCELLED);
                    out.write(buf, 0, n);
                    done += n;
                    if (done - lastShown > (1 << 20)) { p.update("Downloading", done, total); lastShown = done; }
                }
            } finally {
                c.disconnect();
            }
            if (total > 0 && done < total) continue;         // cut off: resume
            return name != null ? name : guessName(url);
        }
        throw new IOException("download kept stopping; try again to resume");
    }

    static HttpURLConnection open(String url, long from) throws IOException {
        // follow redirects ourselves: HttpURLConnection will not cross http -> https
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "FF7Recomp-Launcher");
            if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 14];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /** The download URL behind Google Drive's virus-scan warning page: its form (newer pages),
     *  or the page's own URL plus the confirm/uuid fields (as 7th Heaven does). */
    private static String driveConfirm(String html, String pageUrl) {
        Matcher form = Pattern.compile("<form[^>]*id=\"download-form\"[^>]*action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html);
        String base = form.find() ? form.group(1).replace("&amp;", "&") : null;
        StringBuilder q = new StringBuilder();
        Matcher in = Pattern.compile("<input[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"").matcher(html);
        boolean confirm = false;
        while (in.find()) {
            if (base == null && !in.group(1).equals("confirm") && !in.group(1).equals("uuid")) continue;
            if (in.group(1).equals("confirm")) confirm = true;
            q.append('&').append(in.group(1)).append('=').append(in.group(2));
        }
        if (base != null) return base + (base.contains("?") ? "" : "?") + q.substring(base.contains("?") ? 0 : 1);
        if (confirm) return pageUrl + q;
        Matcher href = Pattern.compile("href=\"(/uc\\?export=download[^\"]+)\"").matcher(html);
        if (href.find()) return "https://drive.google.com" + href.group(1).replace("&amp;", "&");
        return null;
    }

    private static String fileName(String disposition, String url) {
        if (disposition != null) {
            Matcher m = Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (m.find()) try { return URLDecoder.decode(m.group(1), "UTF-8"); } catch (Exception ignored) { }
            m = Pattern.compile("filename=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(disposition);
            if (m.find()) return m.group(1).trim();
        }
        return guessName(url);
    }

    private static String guessName(String url) {
        String path = url.replaceAll("[?#].*$", "");
        String n = path.substring(path.lastIndexOf('/') + 1);
        try { n = URLDecoder.decode(n, "UTF-8"); } catch (Exception ignored) { }
        return n.isEmpty() ? "mod" : n;
    }

    // ---- into the library -----------------------------------------------------------------------

    /** Move the download into files/mods (unpacking archives). Returns the mod's name there. */
    private String place(File part, String serverName, String baseName, Progress p) throws IOException {
        String base = safe(baseName);
        byte[] sig = new byte[6];
        try (InputStream in = new java.io.FileInputStream(part)) { if (in.read(sig) < 4) throw new IOException("empty download"); }
        if (sig[0] == 'I' && sig[1] == 'R' && sig[2] == 'O' && sig[3] == 'S') return placeIro(part, base);
        boolean zip = sig[0] == 'P' && sig[1] == 'K' && sig[2] == 3 && sig[3] == 4;
        boolean sevenZ = (sig[0] & 0xFF) == 0x37 && (sig[1] & 0xFF) == 0x7A && (sig[2] & 0xFF) == 0xBC && (sig[3] & 0xFF) == 0xAF;
        if (sig[0] == 'R' && sig[1] == 'a' && sig[2] == 'r' && sig[3] == '!')
            throw new IOException(serverName + ": RAR archives are not supported yet");
        if (!zip && !sevenZ) {
            String head = new String(sig, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            if (head.startsWith("<!doc") || head.startsWith("<html")) throw new IOException("the download link gave a web page, not the mod");
            throw new IOException(serverName + ": not an .iro, .zip or .7z file");
        }
        File tmp = new File(downloads, base + ".unpack");
        deleteTree(tmp);
        tmp.mkdirs();
        try {
            if (zip) unzip(part, tmp); else un7z(part, tmp, p);
        } catch (IOException e) {
            deleteTree(tmp);                                 // a half-unpacked mod can be gigabytes
            if (cancel) throw new IOException(CANCELLED);
            throw e;
        }
        String result;
        File iro = findFirst(tmp, ".iro");
        if (iro != null) {
            result = placeIro(iro, base);
        } else {
            File modXml = findFirstNamed(tmp, "mod.xml");
            File root = modXml != null ? modXml.getParentFile() : soleChild(tmp);
            File dst = new File(modsDir, base);
            deleteTree(dst);
            if (!root.renameTo(dst)) throw new IOException("cannot move the mod folder into place");
            result = dst.getName();
        }
        deleteTree(tmp);
        return result;
    }

    private String placeIro(File src, String base) throws IOException {
        File dst = new File(modsDir, base + ".iro");
        File old = new File(modsDir, base + ".iro.old");
        old.delete();
        if (dst.exists() && !dst.renameTo(old)) throw new IOException("cannot replace " + dst.getName());
        if (!src.renameTo(dst)) { old.renameTo(dst); throw new IOException("cannot move the mod into the mod folder"); }
        old.delete();
        return dst.getName();
    }

    /** An archive that is one folder holding everything: that folder. */
    private static File soleChild(File dir) {
        File[] fs = dir.listFiles();
        return fs != null && fs.length == 1 && fs[0].isDirectory() ? fs[0] : dir;
    }

    private static File target(File root, String canon, String name) throws IOException {
        File out = new File(root, name);
        if (!out.getCanonicalPath().startsWith(canon)) throw new IOException("bad path in archive: " + name);
        return out;
    }

    private void unzip(File zip, File into) throws IOException {
        String canon = into.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = z.getNextEntry()) != null) {
                if (cancel) throw new IOException(CANCELLED);
                File out = target(into, canon, e.getName());
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = z.read(buf)) > 0) o.write(buf, 0, n);
                }
            }
        }
    }

    static { System.loadLibrary("ff7launcher"); }
    private static native String nativeUn7z(String archive, String outDir);
    private static native void nativeUn7zCancel();
    private static native long[] nativeUn7zProgress();

    /**
     * In native code (src/android/un7z.c): Java's 7z decoder holds the LZMA window in one Java
     * array, 256 MB for 7-Zip's Ultra preset (ChaOS), the whole default heap; the large heap is
     * not an option (it moves ART's heap over the game's guest arena). Progress is polled from
     * the unpacker once a second (a large texture mod unpacks for minutes).
     */
    private void un7z(File archive, File into, Progress p) throws IOException {
        final boolean[] running = { true };
        Thread poll = new Thread(() -> {
            while (true) {
                synchronized (running) {
                    if (!running[0]) return;
                    try { running.wait(1000); } catch (InterruptedException e) { return; }
                    if (!running[0]) return;
                }
                long[] v = nativeUn7zProgress();
                if (v != null && v[1] > 0) p.update("Installing", v[0], v[1]);
            }
        }, "un7z-progress");
        poll.start();
        String err;
        try {
            err = nativeUn7z(archive.getAbsolutePath(), into.getAbsolutePath());
        } finally {
            synchronized (running) { running[0] = false; running.notifyAll(); }
        }
        if (err != null) throw new IOException(err);
    }

    private static File findFirst(File dir, String ext) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(ext)) return f;
        for (File f : fs) if (f.isDirectory()) { File x = findFirst(f, ext); if (x != null) return x; }
        return null;
    }

    private static File findFirstNamed(File dir, String name) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
        for (File f : fs) if (f.isDirectory()) { File x = findFirstNamed(f, name); if (x != null) return x; }
        return null;
    }

    static void deleteTree(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static String safe(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return t.isEmpty() ? "mod" : (t.length() > 80 ? t.substring(0, 80) : t);
    }

    private static String host(String link) {
        String l = link.toLowerCase(Locale.ROOT);
        if (l.contains("mega")) return "Mega";
        if (l.startsWith("iros://")) return l.substring(7, Math.max(7, l.indexOf('/', 7)));
        return "web page";
    }

    static String human(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.US, "%.0f MB", bytes / (double) (1L << 20));
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    static byte[] get(String url) throws IOException {
        HttpURLConnection c = open(url, 0);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    @SuppressWarnings("unused")
    private static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
}
