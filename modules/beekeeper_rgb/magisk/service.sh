#!/system/bin/sh
MODDIR=${0%/*}
setprop sys.beekeeper_rgb.native 0
setprop sys.beekeeper_rgb.effects 0
setprop sys.beekeeper_rgb.network 0
expected=5aac620745177370f59b0627e9b2f75dd95be2a4cf428d2658f8b5b7e44b4f11
actual=$(sha256sum /vendor/lib64/libbspmserver_config.so | cut -d ' ' -f 1)
[ "$actual" = "$expected" ] || exit 0
for i in $(seq 1 45); do
    pid=$(pidof bspmserver)
    [ -n "$pid" ] && break
    sleep 1
done
[ -n "$pid" ] || exit 0
disk_inode=$(stat -c %i /vendor/lib64/libbspmserver_config.so)
mapped_inode=$(awk '/\/libbspmserver_config.so$/ { print $5; exit }' /proc/$pid/maps)
if [ "$disk_inode" != "$mapped_inode" ]; then
    setprop ctl.restart bms
    sleep 3
    pid=$(pidof bspmserver)
    [ -n "$pid" ] || exit 0
    mapped_inode=$(awk '/\/libbspmserver_config.so$/ { print $5; exit }' /proc/$pid/maps)
fi
if [ "$disk_inode" = "$mapped_inode" ]; then
    chmod 0700 "$MODDIR/bin/om2-pixel-writer" "$MODDIR/bin/om2-single-writer" "$MODDIR/bin/om2-flash-transport" "$MODDIR/bin/om2-flash-read"
    if [ "$(cat /data/adb/beekeeper-rgb/om2-pixel-firmware.sha256 2>/dev/null)" = 69f4c469f391dabeef52489d01b3c41fbe7fe16297b4ebea144e0afdef793b5f ]; then
        settings put global beekeeper_net_count 24
        settings put global beekeeper_net_capacity 24
    else
        settings put global beekeeper_net_count 1
        settings put global beekeeper_net_capacity 1
    fi
    setprop sys.beekeeper_rgb.native 1
    setprop sys.beekeeper_rgb.effects 1
    setprop sys.beekeeper_rgb.network 1
    net_pid=$(cat /data/local/tmp/beekeeper-rgb-network.pid 2>/dev/null)
    if [ -n "$net_pid" ] && [ -r "/proc/$net_pid/cmdline" ] && tr '\000' ' ' < "/proc/$net_pid/cmdline" | grep -q beekeeper-rgb-network; then
        exit 0
    fi
    export CLASSPATH="$MODDIR/app/BeekeeperRGB.apk:/vendor/framework/com.mitv.mtvapiclientwrapper.jar:/system/framework/android.hidl.base-V1.0-java.jar:/system/framework/android.hidl.manager-V1.0-java.jar"
    nohup /system/bin/app_process /system/bin --nice-name=beekeeper-rgb-network io.github.rin.beekeeper.rgb.NetworkDaemon "$MODDIR/bin/om2-pixel-writer" > /data/local/tmp/beekeeper-rgb-network.log 2>&1 &
    echo $! > /data/local/tmp/beekeeper-rgb-network.pid
fi
