"""Pack the on-device build kit (tools/build_device_kit.sh runs this after the kit's CMake build).

The APK carries no game code. On the player's device, BuildService.java lifts the player's own
ff7_en.exe with the lifter below, compiles the C with the device clang and links it with the
runtime's archives into libff7.so, the same way the PC build links it. This writes:

  build/device-kit/jni/arm64-v8a/   executables and libraries the device runs (APK jniLibs):
      libllvm.so      clang + lld (tools/build_device_clang.sh), run as `libllvm.so clang ...`
      libpyrun.so     Python's interpreter (src/android/pyrun.c), libpython3.14.so next to it
      libcapstone.so  the lifter's disassembler
  build/device-kit/assets/kit.zip   unpacked by the launcher into files/kit:
      VERSION                 hash of everything below (a new kit means a new build)
      compile.args, link.args the PC build's guest compile flags and libff7.so link, one
                              argument per line, paths as @KIT@ / @GEN@ / @OUT@ / @GUEST@
      sysroot/, clang/        the NDK headers, crt objects, stubs and static libraries the
                              compile and link use; device clang's builtin headers
      rt/                     the runtime's archives (debug info stripped) and the JNI entry
      include/                the headers the generated code includes (recomp32, portable_guest.h)
      py/                     Python's standard library (python.org's Android package)
      lift/                   run_lift.py, gen_retn_table.py, gen_extents.py, pcrecomp's lifter,
                              capstone's and pefile's Python modules, config/ (the catalog,
                              FFNx's patch manifests, override lists)

usage: py -I tools/make_device_kit.py <kit cmake build dir>
"""
import hashlib, io, os, re, shlex, shutil, subprocess, sys, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'build', 'device-kit')
NDK = os.environ['NDK']
NDK_BIN = os.environ['NDK_BIN']
PRE = os.path.join(NDK, 'toolchains', 'llvm', 'prebuilt', 'windows-x86_64')
SYSROOT = os.path.join(PRE, 'sysroot')
API = '29'
PYA = os.path.join(ROOT, 'external', 'python-android', 'prefix')
DEVCLANG = os.path.join(ROOT, 'build', 'device-clang')


def die(msg):
    sys.exit('make_device_kit: ' + msg)


def norm(p):
    return p.replace('\\', '/')


def ninja_cmd(build, target_pred):
    ninja = os.path.join(os.environ['SDK_CMAKE_DIR'], 'ninja.exe')
    out = subprocess.run([ninja, '-C', build, '-t', 'commands', 'libff7.so'], capture_output=True,
                         text=True, check=True).stdout.splitlines()
    for line in out:
        if target_pred(line):
            return line
    die('no matching command in ' + build)


def split_cmd(line):
    # Ninja on Windows: cmd.exe /C "cd . && <clang++> ... && cd ." for links
    m = re.search(r'/C "cd \. && (.*?) && cd \."', line)
    if m:
        line = m.group(1)
    return shlex.split(line.replace('\\', '/'), posix=True)


def guest_compile_args(build):
    line = ninja_cmd(build, lambda l: 'ff7_no_guest.c' in l and ' -c ' in l)
    a = split_cmd(line)[1:]
    out, skip = [], 0
    for i, x in enumerate(a):
        if skip:
            skip -= 1
            continue
        if x in ('-MT', '-MF', '-o', '-c'):
            skip = 1
            continue
        if x in ('-MD', '-g', '-fno-limit-debug-info') or x.startswith('-ffile-prefix-map='):
            continue   # (the PC's path maps mean nothing on the device)
        if x.startswith('--sysroot='):
            out.append('--sysroot=@KIT@/sysroot')
        elif x.startswith('-I'):
            p = norm(x[2:])
            if p.endswith('/runtime/recomp32'):
                out.append('-I@KIT@/include')
            elif p.endswith('/generated/recomp'):
                out.append('-I@GEN@')
            else:
                die('unexpected include dir ' + p)
        elif x == '-include':
            out.append(x)
        elif norm(x).endswith('/src/host/portable_guest.h'):
            out.append('@KIT@/include/portable_guest.h')
        elif '/' in x or ':' in x and not x.startswith('-'):
            die('unexpected path in compile command: ' + x)
        else:
            out.append(x)
    out.append('-resource-dir=@KIT@/clang')
    return out


def link_args(build, rt_files):
    line = ninja_cmd(build, lambda l: '-soname,libff7.so' in l)
    a = split_cmd(line)
    clangxx, drv = a[0], a[1:]
    # the JNI entry: ff7 is never built (EXCLUDE_FROM_ALL), ff7_main compiles the same file with
    # the same flags; clang drops inputs that do not exist, so name the built one
    drv = [x.replace('CMakeFiles/ff7.dir/', 'CMakeFiles/ff7_main.dir/') for x in drv]
    # the driver's own expansion to ld.lld, exactly as on the PC
    exp = subprocess.run([clangxx, '-###'] + drv, capture_output=True, text=True, cwd=build)
    lld = None
    for l in (exp.stderr + exp.stdout).splitlines():
        if 'ld.lld' in l and l.strip().startswith('"'):
            lld = shlex.split(l.replace('\\', '/'), posix=True)
    if not lld:
        die('clang++ -### printed no ld.lld line')
    out, outp = [], None
    for i, x in enumerate(lld[1:]):
        y = norm(x)
        y = re.sub(r'^(-L|--sysroot=)?.*?/toolchains/llvm/prebuilt/[^/]+/sysroot', lambda m: (m.group(1) or '') + '@KIT@/sysroot', y)
        y = re.sub(r'^(-L)?.*?/toolchains/llvm/prebuilt/[^/]+/lib/clang/\d+/lib', lambda m: (m.group(1) or '') + '@KIT@/clang/lib', y)
        if out and out[-1] == '-o':
            outp = y
            out.append('@OUT@')
            continue
        base = os.path.basename(y)
        if y.endswith('ff7_android_main.cpp.o'):
            y = '@KIT@/rt/ff7_android_main.o'
            rt_files['ff7_android_main.o'] = os.path.join(build, x)
        elif y.endswith('.a') and not y.startswith('@KIT@') and not y.startswith('-'):
            src = x if os.path.isabs(x) else os.path.join(build, x)
            if not os.path.isfile(src):
                die('archive not found: ' + src)
            rt_files[base] = src
            if base == 'libff7_core.a' and '@GUEST@' not in out:
                out += ['--whole-archive', '@GUEST@', '--no-whole-archive']
            y = '@KIT@/rt/' + base
        if (('/' in y and not y.startswith('@KIT@')) or re.match(r'^[A-Za-z]:', y)) and not y.startswith('-'):
            die('unmapped path in link command: ' + x)
        out.append(y)
    if '@KIT@/rt/ff7_android_main.o' not in out:
        die('the JNI entry object is not in the link')
    if '@GUEST@' not in out:
        die('libff7_core.a not in the link')
    return out


def add_tree(z, src, dst, skip=lambda rel: False):
    for dp, dn, fn in os.walk(src):
        dn[:] = sorted(d for d in dn if not skip(norm(os.path.relpath(os.path.join(dp, d), src)) + '/'))
        for f in sorted(fn):
            full = os.path.join(dp, f)
            rel = norm(os.path.relpath(full, src))
            if not skip(rel):
                z.write(full, dst + '/' + rel)


def main():
    build = sys.argv[1]
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    jni = os.path.join(OUT, 'jni', 'arm64-v8a')
    os.makedirs(jni)
    os.makedirs(os.path.join(OUT, 'assets'))

    # executables and libraries
    for src, name in [(os.path.join(DEVCLANG, 'llvm'), 'libllvm.so'),
                      (os.path.join(build, 'pyrun'), 'libpyrun.so'),
                      (os.path.join(PYA, 'lib', 'libpython3.14.so'), 'libpython3.14.so'),
                      (os.path.join(ROOT, 'build', 'capstone-android', 'libcapstone.so'), 'libcapstone.so')]:
        if not os.path.isfile(src):
            die('missing ' + src)
        shutil.copy2(src, os.path.join(jni, name))
    subprocess.run([os.path.join(NDK_BIN, 'llvm-strip.exe'), os.path.join(jni, 'libpyrun.so'),
                    os.path.join(jni, 'libcapstone.so')], check=True)

    compile_a = guest_compile_args(build)
    rt_files = {}
    link_a = link_args(build, rt_files)

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        z.writestr('compile.args', '\n'.join(compile_a) + '\n')
        z.writestr('link.args', '\n'.join(link_a) + '\n')
        # NDK: headers, crt objects, the stub libraries and static C++ runtime the link names
        add_tree(z, os.path.join(SYSROOT, 'usr', 'include'), 'sysroot/usr/include')
        libd = os.path.join(SYSROOT, 'usr', 'lib', 'aarch64-linux-android')
        for f in ('libc++_static.a', 'libc++abi.a'):
            z.write(os.path.join(libd, f), 'sysroot/usr/lib/aarch64-linux-android/' + f)
        for f in sorted(os.listdir(os.path.join(libd, API))):
            if f.endswith(('.so', '.o')) or f == 'libc++.a':
                z.write(os.path.join(libd, API, f), f'sysroot/usr/lib/aarch64-linux-android/{API}/{f}')
        rtlib = [d for d in os.listdir(os.path.join(PRE, 'lib', 'clang'))][0]
        rtlib = os.path.join(PRE, 'lib', 'clang', rtlib, 'lib', 'linux')
        z.write(os.path.join(rtlib, 'libclang_rt.builtins-aarch64-android.a'), 'clang/lib/linux/libclang_rt.builtins-aarch64-android.a')
        for f in ('libunwind.a', 'libatomic.a'):
            z.write(os.path.join(rtlib, 'aarch64', f), 'clang/lib/linux/aarch64/' + f)
        add_tree(z, os.path.join(DEVCLANG, 'include'), 'clang/include')
        # the runtime's archives, without debug info
        tmp = os.path.join(OUT, 'rt-tmp')
        os.makedirs(tmp)
        for name, src in sorted(rt_files.items()):
            dst = os.path.join(tmp, name)
            subprocess.run([os.path.join(NDK_BIN, 'llvm-strip.exe'), '--strip-debug', '-o', dst, src], check=True)
            z.write(dst, 'rt/' + name)
        shutil.rmtree(tmp)
        # headers of the generated code
        r32 = os.path.join(ROOT, 'external', 'pcrecomp', 'runtime', 'recomp32')
        for f in sorted(os.listdir(r32)):
            if f.endswith('.h'):
                z.write(os.path.join(r32, f), 'include/' + f)
        z.write(os.path.join(ROOT, 'src', 'host', 'portable_guest.h'), 'include/portable_guest.h')
        # Python's standard library
        junk = ('test/', 'idlelib/', 'tkinter/', 'turtledemo/', 'ensurepip/', 'lib2to3/', 'pydoc_data/',
                'config-3.14-aarch64-linux-android/', 'venv/', 'sqlite3/')
        add_tree(z, os.path.join(PYA, 'lib', 'python3.14'), 'py/lib/python3.14',
                 lambda rel: rel.startswith(junk) or '__pycache__' in rel or '/test' in rel and rel.endswith('/')
                 or rel.startswith('lib-dynload/') and ('_test' in rel or '_sqlite3' in rel or '_tkinter' in rel))
        # the lifter
        for f in ('run_lift.py', 'gen_retn_table.py', 'gen_extents.py'):
            z.write(os.path.join(ROOT, 'tools', f), 'lift/tools/' + f)
        pt = os.path.join(ROOT, 'external', 'pcrecomp', 'tools')
        for sub in ('pe', 'disasm', 'lift'):
            add_tree(z, os.path.join(pt, sub), f'lift/pcrecomp/tools/{sub}',
                     lambda rel: '__pycache__' in rel or not (rel.endswith('.py') or rel.endswith('/')))
        cs = os.path.join(ROOT, 'external', 'deps', 'capstone', 'bindings', 'python', 'capstone')
        add_tree(z, cs, 'lift/site/capstone', lambda rel: not rel.endswith('.py'))
        pf = os.path.join(ROOT, 'external', 'deps', 'pefile')
        z.write(os.path.join(pf, 'pefile.py'), 'lift/site/pefile.py')
        add_tree(z, os.path.join(pf, 'ordlookup'), 'lift/site/ordlookup', lambda rel: not rel.endswith('.py'))
        cfg = os.path.join(ROOT, 'config')
        for f in sorted(os.listdir(cfg)):
            if f.endswith('.txt'):
                z.write(os.path.join(cfg, f), 'lift/config/' + f)
        cat = os.path.join(cfg, 'catalog.json.gz')
        if not os.path.isfile(cat):
            die('config/catalog.json.gz missing')
        z.write(cat, 'lift/config/catalog.json.gz', compress_type=zipfile.ZIP_STORED)
        add_tree(z, os.path.join(cfg, 'ffnx_manifests'), 'lift/config/ffnx_manifests')
    data = buf.getvalue()
    # VERSION: the hash of every entry's name and content, in a zip of its own appended last
    h = hashlib.sha1()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for i in sorted(z.infolist(), key=lambda i: i.filename):
            h.update(i.filename.encode() + b'\0' + z.read(i))
    for f in sorted(os.listdir(jni)):
        h.update(open(os.path.join(jni, f), 'rb').read())
    with zipfile.ZipFile(buf, 'a') as z:
        z.writestr('VERSION', h.hexdigest()[:16] + '\n')
    open(os.path.join(OUT, 'assets', 'kit.zip'), 'wb').write(buf.getvalue())
    open(os.path.join(OUT, 'assets', 'kit.version'), 'w').write(h.hexdigest()[:16] + '\n')
    print(f'kit {h.hexdigest()[:16]}: {len(buf.getvalue()) >> 20} MB zip, {len(rt_files)} runtime files')


if __name__ == '__main__':
    main()
