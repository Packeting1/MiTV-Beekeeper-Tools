#!/system/bin/sh
MODDIR=${0%/*}
export ASH_STANDALONE=1
# 与启动它的 ADB/Magisk 会话脱离，adbd 重启时才不会被一并杀掉。
/data/adb/magisk/busybox setsid /data/adb/magisk/busybox sh "$MODDIR/keeper.sh" </dev/null >/dev/null 2>&1 &
