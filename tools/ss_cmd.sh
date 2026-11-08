#!/usr/bin/env bash
# Ask the running game on the Thor to save or load a state (src/host/ff7_savestate.c).
# usage: bash tools/ss_cmd.sh save|load [slot = quick]
ADB="/c/Users/%USERNAME%/AppData/Local/Android/Sdk/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
D=/sdcard/Android/data/com.ff7recomp/files/savestates
"$ADB" shell "mkdir -p $D && echo '$1 ${2:-quick}' > $D/cmd && chmod 666 $D/cmd"
