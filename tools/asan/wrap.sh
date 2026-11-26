#!/system/bin/sh
# -Pasan=ON builds only (tools/asan/setup.sh): the NDK wrap.sh/asan.sh, overridable from /data/local/tmp/ff7wrap.sh
HERE=$(cd "$(dirname "$0")" && pwd)
cmd=$1
shift
export ASAN_OPTIONS=log_to_syslog=true,allow_user_segv_handler=1,detect_leaks=0,detect_container_overflow=0,verify_asan_link_order=0
ASAN_LIB=$(ls "$HERE"/libclang_rt.asan-*-android.so)
PRE="$HERE/libprctlshim.so $ASAN_LIB"   # the shim first (tools/asan/prctl_shim.c)
JDWP="-XjdwpProvider:adbconnection -XjdwpOptions:suspend=n,server=y"
[ -f /data/local/tmp/ff7wrap.sh ] && . /data/local/tmp/ff7wrap.sh
log -t ff7wrap "PRE=$PRE ASAN_OPTIONS=$ASAN_OPTIONS"
[ -n "$PRE" ] && export LD_PRELOAD="$PRE"
exec $cmd $JDWP "$@"
