#!/system/bin/sh
MODDIR=${0%/*}
LIB=/vendor/lib64/libbspmserver_config.so
setprop sys.beekeeper_rgb.native 0
[ "$(sha256sum $LIB | cut -d ' ' -f 1)" = 5aac620745177370f59b0627e9b2f75dd95be2a4cf428d2658f8b5b7e44b4f11 ] || exit 0
for i in $(seq 1 45); do
    pid=$(pidof bspmserver)
    [ -n "$pid" ] && break
    sleep 1
done
[ -n "$pid" ] || exit 0
# 灯控服务必须加载的是补丁后的库：映射的 inode 与磁盘文件不一致就重启一次服务。
mapped() { awk '/\/libbspmserver_config.so$/ { print $5; exit }' /proc/$pid/maps; }
disk_inode=$(stat -c %i $LIB)
if [ "$disk_inode" != "$(mapped)" ]; then
    setprop ctl.restart bms
    sleep 3
    pid=$(pidof bspmserver)
    [ -n "$pid" ] || exit 0
fi
[ "$disk_inode" = "$(mapped)" ] || exit 0
chmod 0700 "$MODDIR"/bin/*
setprop sys.beekeeper_rgb.native 1
# 网络守护进程自带文件锁，重复启动会直接退出。
export CLASSPATH="$MODDIR/app/DecorativeLight.apk:/vendor/framework/com.mitv.mtvapiclientwrapper.jar:/system/framework/android.hidl.base-V1.0-java.jar:/system/framework/android.hidl.manager-V1.0-java.jar"
nohup /system/bin/app_process /system/bin --nice-name=beekeeper-rgb-network com.beekeeper.decorativelight.NetworkDaemon "$MODDIR" > /data/local/tmp/beekeeper-rgb-network.log 2>&1 &
