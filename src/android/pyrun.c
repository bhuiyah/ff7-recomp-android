/* pyrun: python.org's Python for Android as a command-line interpreter, for the on-device
 * build (the lifter, tools/run_lift.py). Packed as jniLibs/arm64-v8a/libpyrun.so so the
 * launcher can execute it from the app's native library folder; libpython3.14.so sits next
 * to it (RUNPATH $ORIGIN). PYTHONHOME points at the stdlib the launcher unpacked. */
#include <Python.h>

int main(int argc, char **argv) { return Py_BytesMain(argc, argv); }
