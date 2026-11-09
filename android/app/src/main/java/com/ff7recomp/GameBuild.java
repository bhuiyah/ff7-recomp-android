package com.ff7recomp;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds the game for this device from the player's own ff7_en.exe (the APK carries no game
 * code; tools/make_device_kit.py has the kit's layout):
 *   1. unpack the kit (assets/kit.zip) into files/kit, once per APK version
 *   2. check the exe is the 1.02 US build
 *   3. lift it to C with the lifter on Python for Android (files/build/gen)
 *   4. compile the C with the device clang, several files at a time (files/build/obj)
 *   5. link the objects with the runtime's archives into files/game/libff7.so, the way the
 *      PC build links it (kit link.args), which GameActivity loads
 * Each step's results stay until the kit or the exe changes, so an interrupted build carries
 * on where it stopped. State is static: BuildService runs it, the launcher shows it.
 */
final class GameBuild {
    private static final String TAG = "FF7Build";
    /** SHA-1 of the Steam release's ff7/resources/ff7_1.02/ff7_en (1.02 US). */
    static final String EXE_SHA1 = "ac306ae92615af75ff36bba6347c67ca1284151d";

    // ---- shared state (read by the launcher)
    static volatile boolean running;
    static volatile String stage = "";      // what is happening, for people
    static volatile int percent;            // of the whole build
    static volatile String error;           // the last build's failure, or null

    final Context ctx;
    final File kit, work, gen, obj, lib, log;
    final String nativeDir;

    GameBuild(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        File files = this.ctx.getFilesDir();
        kit = new File(files, "kit");
        work = new File(files, "build");
        gen = new File(work, "gen");
        obj = new File(work, "obj");
        lib = new File(files, "game/libff7.so");
        log = new File(this.ctx.getExternalFilesDir(null), "build.log");
        nativeDir = this.ctx.getApplicationInfo().nativeLibraryDir;
    }

    /** The kit this APK carries (assets/kit.version). */
    String kitVersion() {
        try (InputStream in = ctx.getAssets().open("kit.version")) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** The game is built for this APK's kit (or a dev APK bundles libff7.so itself). */
    boolean built() {
        if (new File(nativeDir, "libff7.so").isFile()) return true;
        return lib.isFile() && kitVersion().equals(readText(new File(lib.getParentFile(), "libff7.stamp")));
    }

    private void set(String what, int pct) {
        stage = what;
        percent = pct;
    }

    /** Run the build (on the caller's thread). Returns null, or what went wrong. */
    String run(File exe) {
        running = true;
        error = null;
        long t0 = System.currentTimeMillis();
        try (PrintWriter lw = new PrintWriter(new FileOutputStream(log), true)) {
            this.lw = lw;
            String ver = kitVersion();
            if (ver.isEmpty()) throw new IOException("this APK has no build kit");
            lw.println("kit " + ver);

            set("Checking your FF7", 1);
            if (!exe.isFile()) throw new IOException("ff7_en.exe is missing: import your game files first");
            String sha = sha1(exe);
            lw.println("exe " + sha);
            if (!EXE_SHA1.equals(sha))
                throw new IOException("this ff7_en is not the Steam release's 1.02 US exe (ff7/resources/ff7_1.02/ff7_en)");

            unpackKit(ver);

            String stamp = ver + " " + sha;
            if (!stamp.equals(readText(new File(work, "stamp")))) {
                deleteTree(work);
                work.mkdirs();
                writeText(new File(work, "stamp"), stamp);
            }
            if (!new File(work, "lifted").isFile()) {
                lift(exe);
                writeText(new File(work, "lifted"), "1");
            }
            compileAll();
            link(ver);
            deleteTree(gen);                 // the C (~500 MB); the objects stay for relinks
            set("Ready", 100);
            lw.println("done in " + (System.currentTimeMillis() - t0) / 1000 + " s");
            return null;
        } catch (Throwable e) {
            Log.e(TAG, "build failed", e);
            error = e.getMessage() != null ? e.getMessage() : e.toString();
            return error;
        } finally {
            running = false;
            lw = null;
        }
    }

    private PrintWriter lw;

    // ------------------------------------------------------------------ 1. the kit
    private void unpackKit(String ver) throws IOException {
        if (ver.equals(readText(new File(kit, "VERSION")))) return;
        set("Unpacking the build tools", 2);
        deleteTree(kit);
        File tmp = new File(kit.getPath() + ".tmp");
        deleteTree(tmp);
        tmp.mkdirs();
        byte[] buf = new byte[1 << 16];
        try (ZipInputStream z = new ZipInputStream(ctx.getAssets().open("kit.zip"))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.contains("..") || name.startsWith("/")) throw new IOException("bad kit entry " + name);
                File f = new File(tmp, name);
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) {
                    for (int n; (n = z.read(buf)) > 0; ) o.write(buf, 0, n);
                }
            }
        }
        if (!tmp.renameTo(kit)) throw new IOException("cannot install the build tools");
    }

    // ------------------------------------------------------------------ 3. lift
    private static final Pattern LIFTED = Pattern.compile("\\[\\*\\] (\\d+)/(\\d+) lifted");

    private void lift(File exe) throws IOException, InterruptedException {
        set("Translating the game's code", 4);
        deleteTree(gen);
        gen.mkdirs();
        List<String> manifests = new ArrayList<>();
        File[] ms = new File(kit, "lift/config/ffnx_manifests").listFiles();
        if (ms != null) {
            Arrays.sort(ms);
            for (File m : ms) if (m.getName().endsWith(".json")) manifests.add(m.getPath());
        }
        String catalog = new File(kit, "lift/config/catalog.json.gz").getPath();
        // --split 200: smaller files than the PC's 400 spread the compile over the cores
        python(new String[] { new File(kit, "lift/tools/run_lift.py").getPath(), "lift", exe.getPath(),
                catalog, gen.getPath(), "--split", "200", "--manifests", String.join(",", manifests) }, null,
                line -> {
                    Matcher m = LIFTED.matcher(line);
                    if (m.find()) set("Translating the game's code", 4 + 26 * Integer.parseInt(m.group(1)) / Integer.parseInt(m.group(2)));
                });
        set("Translating the game's code", 30);
        python(new String[] { new File(kit, "lift/tools/gen_retn_table.py").getPath() },
               new String[] { "FF7_CATALOG=" + catalog, "FF7_EXE=" + exe.getPath(),
                              "FF7_RETN_OUT=" + new File(gen, "recomp_retn.c").getPath() }, null);
        if (!new File(gen, "recomp_dispatch.c").isFile() || !new File(gen, "recomp_retn.c").isFile())
            throw new IOException("the translation wrote no code (see build.log)");
    }

    private interface Lines { void line(String l); }

    private void python(String[] args, String[] env, Lines lines) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(nativeDir, "libpyrun.so").getPath());
        cmd.add("-u");
        cmd.add("-B");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> e = pb.environment();
        e.put("PYTHONHOME", new File(kit, "py").getPath());
        e.put("PYTHONPATH", new File(kit, "lift/site").getPath());
        e.put("PCRECOMP", new File(kit, "lift/pcrecomp").getPath());
        e.put("LIBCAPSTONE_PATH", nativeDir);
        e.put("LD_LIBRARY_PATH", nativeDir);
        e.put("HOME", work.getPath());
        e.put("TMPDIR", ctx.getCacheDir().getPath());
        if (env != null) for (String kv : env) { int i = kv.indexOf('='); e.put(kv.substring(0, i), kv.substring(i + 1)); }
        run(pb, lines);
    }

    private void run(ProcessBuilder pb, Lines lines) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        lw.println("$ " + String.join(" ", pb.command()));
        Process p = pb.start();
        StringBuilder tail = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String l; (l = r.readLine()) != null; ) {
                lw.println(l);
                if (lines != null) lines.line(l);
                tail.append(l).append('\n');
                if (tail.length() > 4000) tail.delete(0, tail.length() - 2000);
            }
        }
        int rc = p.waitFor();
        if (rc != 0) {
            String t = tail.toString().trim();
            int cut = t.lastIndexOf('\n', t.length() - 1);
            throw new IOException(pb.command().get(0).replaceAll(".*/lib(\\w+)\\.so", "$1") + " failed (" + rc + "): "
                    + (cut >= 0 ? t.substring(cut + 1) : t));
        }
    }

    // ------------------------------------------------------------------ 4. compile
    private void compileAll() throws Exception {
        obj.mkdirs();
        List<String> flags = new ArrayList<>();
        for (String a : readText(new File(kit, "compile.args")).split("\n")) {
            if (a.isEmpty()) continue;
            flags.add(a.replace("@KIT@", kit.getPath()).replace("@GEN@", gen.getPath()));
        }
        File[] cs = gen.listFiles((d, n) -> n.startsWith("recomp_") && n.endsWith(".c"));
        if (cs == null || cs.length == 0) {
            if (objects().size() > 0 && new File(work, "compiled").isFile()) return;   // relink only
            throw new IOException("no translated code to compile");
        }
        // biggest first, so the long files do not start last
        Arrays.sort(cs, (a, b) -> Long.compare(b.length(), a.length()));
        long total = 0;
        for (File c : cs) total += c.length();
        AtomicLong done = new AtomicLong();
        int jobs = jobs();
        lw.println("compile: " + cs.length + " files, " + (total >> 20) + " MB, " + jobs + " at a time");
        set("Compiling for this device", 31);
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        List<Future<?>> fs = new ArrayList<>();
        final long tot = total;
        for (File c : cs) {
            fs.add(pool.submit(() -> {
                File o = new File(obj, c.getName().replace(".c", ".o"));
                if (!o.isFile() || o.lastModified() < c.lastModified()) {
                    File tmp = new File(o.getPath() + ".tmp");
                    List<String> cmd = new ArrayList<>();
                    cmd.add(new File(nativeDir, "libllvm.so").getPath());
                    cmd.add("clang");
                    cmd.addAll(flags);
                    cmd.add("-c");
                    cmd.add(c.getPath());
                    cmd.add("-o");
                    cmd.add(tmp.getPath());
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.environment().put("LD_LIBRARY_PATH", nativeDir);
                    runQuiet(pb, c.getName());
                    if (!tmp.renameTo(o)) throw new IOException("cannot write " + o);
                }
                long d = done.addAndGet(c.length());
                set("Compiling for this device", 31 + (int) (64 * d / tot));
                return null;
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> f : fs) f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            pool.shutdownNow();
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
        writeText(new File(work, "compiled"), "1");
    }

    /** One compile; its output goes to the log only when it fails (they run in parallel). */
    private void runQuiet(ProcessBuilder pb, String name) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = readAll(p.getInputStream());
        int rc = p.waitFor();
        synchronized (lw) {
            lw.println("cc " + name + " -> " + rc);
            if (rc != 0) lw.println(new String(out, StandardCharsets.UTF_8));
        }
        if (rc != 0) {
            String t = new String(out, StandardCharsets.UTF_8).trim();
            throw new IOException("compiling " + name + " failed (" + rc + "): " + (t.length() > 300 ? t.substring(0, 300) : t));
        }
    }

    /** Parallel compiles: one per big core, fewer when memory is short (~2 GB each at worst). */
    private int jobs() {
        int cores = Runtime.getRuntime().availableProcessors();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        int byMem = (int) Math.max(1, (mi.availMem - (1L << 30)) / (2L << 30));
        return Math.max(1, Math.min(Math.min(cores - 2, 6), byMem));
    }

    private List<File> objects() {
        List<File> out = new ArrayList<>();
        File[] os = obj.listFiles((d, n) -> n.endsWith(".o"));
        if (os != null) { Arrays.sort(os); out.addAll(Arrays.asList(os)); }
        return out;
    }

    // ------------------------------------------------------------------ 5. link
    private void link(String ver) throws IOException, InterruptedException {
        set("Linking", 96);
        File out = new File(work, "libff7.so");
        StringBuilder rsp = new StringBuilder();
        for (String a : readText(new File(kit, "link.args")).split("\n")) {
            if (a.isEmpty()) continue;
            if (a.equals("@GUEST@")) {
                for (File o : objects()) rsp.append(quote(o.getPath())).append('\n');
                continue;
            }
            rsp.append(quote(a.replace("@KIT@", kit.getPath()).replace("@OUT@", out.getPath()))).append('\n');
        }
        File rf = new File(work, "link.rsp");
        writeText(rf, rsp.toString());
        ProcessBuilder pb = new ProcessBuilder(new File(nativeDir, "libllvm.so").getPath(), "ld.lld", "@" + rf.getPath());
        pb.environment().put("LD_LIBRARY_PATH", nativeDir);
        run(pb, null);
        lib.getParentFile().mkdirs();
        File stampF = new File(lib.getParentFile(), "libff7.stamp");
        stampF.delete();
        lib.delete();
        if (!out.renameTo(lib)) throw new IOException("cannot install libff7.so");
        lib.setWritable(false);
        writeText(stampF, ver);
    }

    private static String quote(String s) {
        return s.matches("[A-Za-z0-9_./=@,+:-]+") ? s : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------------ helpers
    static String sha1(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n);
            StringBuilder s = new StringBuilder();
            for (byte x : md.digest()) s.append(String.format("%02x", x));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[1 << 16];
        for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
        return o.toByteArray();
    }

    static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    static void writeText(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
