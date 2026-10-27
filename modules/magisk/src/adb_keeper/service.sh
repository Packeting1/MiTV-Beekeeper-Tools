#!/system/bin/sh
MODDIR=${0%/*}
export ASH_STANDALONE=1
# Detach from the launching ADB/Magisk session so an adbd restart cannot kill us.
/data/adb/magisk/busybox setsid /data/adb/magisk/busybox sh "$MODDIR/keeper.sh" </dev/null >/dev/null 2>&1 &
