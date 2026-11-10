package com.ff7recomp;

import android.app.ActivityManager;
import android.content.Context;
import android.os.PowerManager;
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
        log = new File(Storage.root(this.ctx), "build.log");
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

    /** What the lifted, compiled game depends on in this APK's kit (assets/kit.liftversion): the
     *  kit without the engine. Same lift version, new kit: only the link runs again. */
    String liftVersion() {
        try (InputStream in = ctx.getAssets().open("kit.liftversion")) {
            return new String(readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return kitVersion();
        }
    }

    /** Builds from APKs before the lift version (their work stamp holds the whole kit's
     *  version): kit version -> its lift version. */
    private static final java.util.Map<String, String> LEGACY_LIFT = java.util.Map.of(
            "78bd54ab776e925f", "4fccebb48f648461");    // 0.1.0, 0.1.1

    /** The game is built for this APK's kit (or a dev APK bundles libff7.so itself). */
    boolean built() {
        if (new File(nativeDir, "libff7.so").isFile()) return true;
        return lib.isFile() && kitVersion().equals(readText(new File(lib.getParentFile(), "libff7.stamp")));
    }

    /** Not built for this kit, but the lifted game is still good: only the link runs (an app
     *  update that changed the engine). */
    boolean relinkOnly() {
        if (built() || !new File(work, "lifted").isFile() || gen.isDirectory()) return false;
        String[] o = readText(new File(work, "stamp")).split(" ", 2);
        String lift = liftVersion();
        return o.length == 2 && (lift.equals(o[0]) || lift.equals(LEGACY_LIFT.get(o[0])));
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
                throw new IOException("this ff7_en is not the one the game is built from: import again. " + GameInstall.WHICH_FOLDER);

            unpackKit(ver);

            String lift = liftVersion();
            String stamp = lift + " " + sha;
            String old = readText(new File(work, "stamp"));
            String[] o = old.split(" ", 2);
            boolean same = stamp.equals(old)
                    || (o.length == 2 && o[1].equals(sha) && lift.equals(LEGACY_LIFT.get(o[0])));
            lw.println("lift " + lift + (same ? " (lifted objects kept)" : " (lifting from the start)"));
            if (!same) {
                deleteTree(work);
                work.mkdirs();
            }
            if (!stamp.equals(old)) writeText(new File(work, "stamp"), stamp);
            if (!new File(work, "lifted").isFile() || gen.isDirectory()) liftAndCompile(exe);
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

    // ------------------------------------------------------------------ 3+4. lift and compile
    /*
     * One pipeline: the lift runs as SHARDS Python processes (run_lift.py --shard, every Nth
     * function each, files of about CHUNK bytes written whole), and every C file is compiled as
     * soon as it appears. How many processes run at once follows the device's thermal headroom
     * (PowerManager.getThermalHeadroom): all of them while the device is cool, fewer as it nears
     * throttling, so the build runs about as fast as the device can stay cool and the fan quiet.
     */
    /* One lift process: it writes C several times faster than the compiles use it, and each
       extra shard repeats the lifter's setup (~35 s on a Thor). */
    private static final int SHARDS = 1;
    private static final int CHUNK = 4_000_000;
    private static final long EXPECTED_C = 520L << 20;   // the lift writes ~520 MB of C
    private static final Pattern LIFTED = Pattern.compile("\\[\\*\\] (\\d+)/(\\d+) lifted");

    /** A running child process; its output goes to the log (whole, when it ends). */
    private final class Job {
        final String name;
        final long bytes;           // for compiles: the C file's size
        final Thread thread;
        volatile boolean done;
        volatile int rc = -1;
        volatile String tail = "";
        volatile Process proc;

        Job(String name, long bytes, ProcessBuilder pb, Lines lines) {
            this.name = name;
            this.bytes = bytes;
            pb.redirectErrorStream(true);
            thread = new Thread(() -> {
                StringBuilder out = new StringBuilder();
                try {
                    proc = pb.start();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String l; (l = r.readLine()) != null; ) {
                            if (lines != null) lines.line(l);
                            out.append(l).append('\n');
                            if (out.length() > 20000) out.delete(0, out.length() - 10000);
                        }
                    }
                    rc = proc.waitFor();
                } catch (Exception e) {
                    out.append(e).append('\n');
                    rc = -2;
                }
                tail = out.toString().trim();
                synchronized (lw) {
                    lw.println("[" + name + "] exit " + rc);
                    if (rc != 0 || !name.startsWith("cc ")) lw.println(tail);
                }
                done = true;
            }, name);
        }

        Job start() { thread.start(); return this; }

        void kill() { Process p = proc; if (p != null) p.destroyForcibly(); }
    }

    private ProcessBuilder pythonPb(String[] args, String[] env) {
        List<String> cmd = new ArrayList<>(prefix);
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
        return pb;
    }

    private ProcessBuilder clangPb(List<String> flags, File c, File o) {
        List<String> cmd = new ArrayList<>(prefix);
        cmd.add(new File(nativeDir, "libllvm.so").getPath());
        cmd.add("clang");
        cmd.addAll(flags);
        cmd.add("-c");
        cmd.add(c.getPath());
        cmd.add("-o");
        cmd.add(o.getPath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LD_LIBRARY_PATH", nativeDir);
        return pb;
    }

    // ---- staying cool
    /** Before every build process: low priority (the launcher and anything else stay smooth)
     *  and only the middle cores. Measured on a Thor (8 Gen 2), compiles in 90 s / average CPU
     *  temperature: 4 on cores 0-6 30 / 84 °C, 6 on 0-6 33 / 88 °C, 4 on the four 2.8 GHz cores
     *  30 / 77 °C. The prime core does the least work per watt and the efficiency cores add
     *  little but heat (their slow compiles also hold up the end of the build). */
    private final List<String> prefix = new ArrayList<>();
    private int coreCount;
    private List<File> cpuZones;
    /** °C, the average CPU sensor: fewer at once above HOT, more again below COOL. */
    private static final float HOT = 86f, COOL = 80f;
    /** The player's "Quiet build": two at once and cooler limits, about twice as long. */
    static volatile boolean quiet;

    private void setupCooling() {
        prefix.clear();
        int n = Runtime.getRuntime().availableProcessors();
        long[] f = new long[n];
        long top = 0;
        for (int i = 0; i < n; i++) {
            try { f[i] = Long.parseLong(readText(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"))); }
            catch (NumberFormatException e) { f[i] = 0; }
            top = Math.max(top, f[i]);
        }
        long low = Long.MAX_VALUE;
        int atTop = 0;
        for (int i = 0; i < n; i++) { if (f[i] == top) atTop++; if (f[i] > 0) low = Math.min(low, f[i]); }
        long mask = 0;
        for (int i = 0; i < n; i++) if (f[i] > 0 && !(f[i] == top && atTop == 1) && f[i] != low) mask |= 1L << i;
        if (Long.bitCount(mask) < 4) {            // no middle cluster worth it: all but a lone prime
            mask = 0;
            for (int i = 0; i < n; i++) if (!(f[i] == top && atTop == 1)) mask |= 1L << i;
        }
        coreCount = n;
        if (top > 0 && Long.bitCount(mask) >= 2 && Long.bitCount(mask) < n && new File("/system/bin/taskset").exists()) {
            prefix.add("/system/bin/taskset");
            prefix.add(Long.toHexString(mask));
            coreCount = Long.bitCount(mask);
        }
        if (new File("/system/bin/nice").exists()) {
            prefix.add("/system/bin/nice");
            prefix.add("-n");
            prefix.add("10");
        }
        cpuZones = new ArrayList<>();
        File[] zs = new File("/sys/class/thermal").listFiles((d, nm) -> nm.startsWith("thermal_zone"));
        if (zs != null) for (File z : zs) {
            String type = readText(new File(z, "type"));
            if (type.startsWith("cpu") && !readText(new File(z, "temp")).isEmpty()) cpuZones.add(new File(z, "temp"));
        }
        lw.println("cooling: " + (prefix.isEmpty() ? "none" : String.join(" ", prefix)) + ", " + cpuZones.size() + " CPU sensors");
    }

    /** The average CPU sensor in °C (single cores spike to 90+ under any full load), or NaN
     *  when the device shows none: then the system's thermal headroom decides. */
    private float cpuTemp() {
        float sum = 0;
        int n = 0;
        for (File z : cpuZones) {
            try {
                float v = Long.parseLong(readText(z)) / 1000f;
                if (v > 0 && v < 150) { sum += v; n++; }
            } catch (NumberFormatException ignored) { }
        }
        return n > 0 ? sum / n : Float.NaN;
    }

    private void liftAndCompile(File exe) throws Exception {
        setupCooling();
        boolean lifted = new File(work, "lifted").isFile();
        if (!lifted) { deleteTree(gen); deleteTree(obj); }
        gen.mkdirs();
        obj.mkdirs();
        List<String> flags = new ArrayList<>();
        for (String a : readText(new File(kit, "compile.args")).split("\n"))
            if (!a.isEmpty()) flags.add(a.replace("@KIT@", kit.getPath()).replace("@GEN@", gen.getPath()));
        List<String> manifests = new ArrayList<>();
        File[] ms = new File(kit, "lift/config/ffnx_manifests").listFiles();
        if (ms != null) {
            Arrays.sort(ms);
            for (File m : ms) if (m.getName().endsWith(".json")) manifests.add(m.getPath());
        }
        String catalog = new File(kit, "lift/config/catalog.json.gz").getPath();
        String liftPy = new File(kit, "lift/tools/run_lift.py").getPath();

        int maxJobs = jobs();
        int target = maxJobs;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        lw.println("pipeline: " + SHARDS + " lift process, up to " + maxJobs + " at once" + (quiet ? " (quiet)" : ""));

        List<Job> lifts = new ArrayList<>();
        List<Job> running = new ArrayList<>();
        java.util.Set<String> queued = new java.util.HashSet<>();
        List<File> queue = new ArrayList<>();
        final float[] shardFrac = new float[SHARDS];
        Job extents = null;
        long compiledBytes = 0, seenBytes = 0;
        long lastThermal = 0, lastRaise = 0, lastLog = 0, tStart = System.currentTimeMillis();
        float headroom = Float.NaN;
        if (!lifted) {
            set("Translating the game's code", 4);
            for (int i = 0; i < SHARDS; i++) {
                final int si = i;
                lifts.add(new Job("lift " + i, 0, pythonPb(new String[] { liftPy, "lift", exe.getPath(), catalog, gen.getPath(),
                        "--shard", i + "/" + SHARDS, "--chunk-bytes", String.valueOf(CHUNK),
                        "--manifests", String.join(",", manifests) }, null), line -> {
                    Matcher m = LIFTED.matcher(line);
                    if (m.find()) shardFrac[si] = (float) Integer.parseInt(m.group(1)) / Integer.parseInt(m.group(2));
                }));
            }
            lifts.add(new Job("retn", 0, pythonPb(new String[] { new File(kit, "lift/tools/gen_retn_table.py").getPath() },
                    new String[] { "FF7_CATALOG=" + catalog, "FF7_EXE=" + exe.getPath(),
                                   "FF7_RETN_OUT=" + new File(gen, "recomp_retn.c").getPath() }), null));
            for (Job j : lifts) running.add(j.start());
        }
        try {
            while (true) {
                // finished processes
                for (java.util.Iterator<Job> it = running.iterator(); it.hasNext(); ) {
                    Job j = it.next();
                    if (!j.done) continue;
                    it.remove();
                    if (j.rc != 0) {
                        String t = j.tail;
                        int cut = t.lastIndexOf('\n');
                        throw new IOException(j.name + " failed (" + j.rc + "): " + (cut >= 0 ? t.substring(cut + 1) : t));
                    }
                    if (j.name.startsWith("cc ")) compiledBytes += j.bytes;
                }
                boolean liftsDone = true;
                for (Job j : lifts) liftsDone &= j.done;
                if (liftsDone && !lifted && extents == null) {
                    extents = new Job("extents", 0, pythonPb(new String[] { new File(kit, "lift/tools/gen_extents.py").getPath(), gen.getPath() }, null), null);
                    running.add(extents.start());
                }
                if (extents != null && extents.done && !lifted) {
                    if (!new File(gen, "recomp_dispatch.c").isFile() || !new File(gen, "recomp_retn.c").isFile()
                            || !new File(gen, "recomp_extents.c").isFile())
                        throw new IOException("the translation wrote no code (see build.log)");
                    writeText(new File(work, "lifted"), "1");
                    lifted = true;
                }
                // new C files: complete ones only (the lift writes .tmp and renames); everything
                // includes recomp_funcs.h, which shard 0 writes first
                if (new File(gen, "recomp_funcs.h").isFile()) {
                    File[] cs = gen.listFiles((d, n) -> n.startsWith("recomp_") && n.endsWith(".c"));
                    if (cs != null) for (File c : cs) {
                        if (!queued.add(c.getName())) continue;
                        seenBytes += c.length();
                        File o = new File(obj, c.getName().replace(".c", ".o"));
                        if (o.isFile() && o.lastModified() >= c.lastModified()) { compiledBytes += c.length(); continue; }
                        queue.add(c);
                    }
                    queue.sort((a, b) -> Long.compare(b.length(), a.length()));
                }
                // how many processes the device's temperature allows
                long now = System.currentTimeMillis();
                if (now - lastThermal >= 2000) {
                    lastThermal = now;
                    float t = cpuTemp();
                    boolean hot, cool;
                    if (!Float.isNaN(t)) { hot = t >= (quiet ? 74f : HOT); cool = t <= (quiet ? 68f : COOL); headroom = t; }
                    else if (android.os.Build.VERSION.SDK_INT >= 30 && !Float.isNaN(headroom = pm.getThermalHeadroom(10))) {
                        hot = headroom >= 0.85f; cool = headroom < 0.7f;
                    } else { hot = false; cool = true; }
                    if (hot && target > 2 && now - lastRaise >= 6000) { target--; lastRaise = now; }
                    else if (cool && target < maxJobs && now - lastRaise >= 10000) { target++; lastRaise = now; }
                    if (now - lastLog >= 10000) {
                        lastLog = now;
                        lw.println(String.format(java.util.Locale.ROOT, "t+%ds %.1f %s, %d at once", (now - tStart) / 1000,
                                headroom, Float.isNaN(t) ? "headroom" : "C", target));
                    }
                }
                while (running.size() < target && !queue.isEmpty()) {
                    File c = queue.remove(0);
                    File o = new File(obj, c.getName().replace(".c", ".o"));
                    File tmp = new File(o.getPath() + ".tmp");
                    Job j = new Job("cc " + c.getName(), c.length(), clangPb(flags, c, tmp), null);
                    running.add(j.start());
                    pendingRename.put(j, new File[] { tmp, o });
                }
                for (java.util.Iterator<Map.Entry<Job, File[]>> it = pendingRename.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<Job, File[]> e = it.next();
                    if (e.getKey().done && e.getKey().rc == 0) {
                        if (!e.getValue()[0].renameTo(e.getValue()[1])) throw new IOException("cannot write " + e.getValue()[1]);
                        it.remove();
                    }
                }
                // progress: lift ~15%, compile ~85% of the time
                float lf = 0;
                for (float f : shardFrac) lf += f / SHARDS;
                if (lifted) lf = 1;
                long total = lifted ? Math.max(seenBytes, 1) : Math.max(EXPECTED_C, seenBytes);
                float cf = Math.min(1f, (float) compiledBytes / total);
                set(lifted ? "Compiling for this device" : "Translating and compiling", 4 + (int) (92 * (0.15f * lf + 0.85f * cf)));
                if (lifted && queue.isEmpty() && running.isEmpty() && pendingRename.isEmpty()) break;
                Thread.sleep(250);
            }
        } finally {
            for (Job j : running) j.kill();
        }
        lw.println("compiled " + (compiledBytes >> 20) + " MB of C in " + (System.currentTimeMillis() - tStart) / 1000 + " s");
    }

    private final Map<Job, File[]> pendingRename = new java.util.HashMap<>();

    private interface Lines { void line(String l); }

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

    /** Processes at once at most: one per core but two, fewer when memory is short (~1.5 GB
     *  each at worst: a lift shard holds the catalog and the decoded exe). */
    private int jobs() {
        int cores = Runtime.getRuntime().availableProcessors();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        int byMem = (int) Math.max(2, (mi.availMem - (1L << 30)) / (3L << 29));
        if (quiet) return 2;
        return Math.max(2, Math.min(Math.min(coreCount < cores ? coreCount : cores - 2, 6), byMem));
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
        // --threads=1: lld's parallel link crashes on Android now and then (it frees a pointer
        // whose heap tag is gone: "Pointer tag ... was truncated"), every time for some inputs;
        // single-threaded it takes under a second on a Thor and writes the same library.
        ProcessBuilder pb = new ProcessBuilder(new File(nativeDir, "libllvm.so").getPath(), "ld.lld", "--threads=1", "@" + rf.getPath());
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
