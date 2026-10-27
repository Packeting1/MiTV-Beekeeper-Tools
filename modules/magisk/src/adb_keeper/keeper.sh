#!/system/bin/sh
MODDIR=${0%/*}
MODID=mitv_adb_keeper
ACTIVE_DIR=/data/adb/modules/$MODID
LOCKDIR=/dev/$MODID.lock
LOGFILE=$MODDIR/keeper.log
BB=/data/adb/magisk/busybox
export ASH_STANDALONE=1
TCP_PORT=5555
CHECK_INTERVAL=3
BOOT_DELAY=10
[ -f "$MODDIR/config.sh" ] && . "$MODDIR/config.sh"

valid_number() {
    case "$1" in ''|*[!0-9]*|0*) return 1 ;; esac
    [ "$1" -ge "$2" ] 2>/dev/null && [ "$1" -le "$3" ] 2>/dev/null
}
valid_number "$TCP_PORT" 1024 65535 || exit 1
valid_number "$CHECK_INTERVAL" 1 60 || exit 1
valid_number "$BOOT_DELAY" 1 120 || exit 1
PORT_HEX=$(printf '%04X' "$TCP_PORT")

enabled() {
    [ -d "$MODDIR" ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ] &&
        [ ! -f "$ACTIVE_DIR/disable" ] && [ ! -f "$ACTIVE_DIR/remove" ]
}
is_keeper() {
    valid_number "$1" 1 4194304 && kill -0 "$1" 2>/dev/null &&
        tr '\000' ' ' <"/proc/$1/cmdline" 2>/dev/null | grep -Fq "/$MODID/keeper.sh"
}
enabled || exit 0
if ! mkdir "$LOCKDIR" 2>/dev/null; then
    oldpid=$(cat "$LOCKDIR/pid" 2>/dev/null)
    is_keeper "$oldpid" && exit 0
    rm -f "$LOCKDIR/pid"
    rmdir "$LOCKDIR" 2>/dev/null || exit 0
    mkdir "$LOCKDIR" 2>/dev/null || exit 0
fi
echo "$$" >"$LOCKDIR/pid"
cleanup() {
    if [ "$(cat "$LOCKDIR/pid" 2>/dev/null)" = "$$" ]; then
        rm -f "$LOCKDIR/pid"
        rmdir "$LOCKDIR" 2>/dev/null
    fi
}
trap cleanup EXIT
trap 'exit 0' TERM INT HUP
log() {
    if [ -f "$LOGFILE" ] && [ "$(wc -c <"$LOGFILE")" -ge 65536 ]; then
        mv -f "$LOGFILE" "$LOGFILE.1"
    fi
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$LOGFILE"
}
setting() {
    # Binder's settings service cannot use an stderr FD backed by /data/adb.
    # Keep its descriptors on pipes or /dev/null, and write our own log separately.
    "$BB" timeout 8 /system/bin/settings "$@" 2>/dev/null
}
listening() {
    awk -v suffix=":$PORT_HEX" '$2 ~ (suffix "$") && $4 == "0A" { found=1 } END { exit !found }' /proc/net/tcp /proc/net/tcp6 2>/dev/null
}
log "keeper started pid=$$ port=$TCP_PORT interval=$CHECK_INTERVAL module=$MODDIR"
while enabled && [ "$(/system/bin/getprop sys.boot_completed)" != 1 ]; do sleep 3; done
enabled || exit 0
sleep "$BOOT_DELAY"
tick=0
last_error=''
while enabled; do
    adb_flag=$(setting get global adb_enabled)
    case "$adb_flag" in
        0|1) last_error='' ;;
        *)
            if [ "$last_error" != settings ]; then log 'settings provider unavailable; waiting'; fi
            last_error=settings
            sleep "$CHECK_INTERVAL"
            continue
            ;;
    esac
    restart_needed=0
    if [ "$(/system/bin/getprop service.adb.tcp.port)" != "$TCP_PORT" ]; then
        if /system/bin/setprop service.adb.tcp.port "$TCP_PORT" 2>>"$LOGFILE"; then
            log "restored service.adb.tcp.port=$TCP_PORT"
            restart_needed=1
        fi
    fi
    if [ "$adb_flag" = 0 ]; then
        if setting put global adb_enabled 1; then
            log 'restored adb_enabled=1 (system had turned it off)'
            # Let the framework finish its asynchronous USB/ADB configuration.
            sleep 2
        fi
    fi
    daemon_state=$(/system/bin/getprop init.svc.adbd)
    if [ "$daemon_state" != running ]; then
        /system/bin/setprop ctl.start adbd 2>>"$LOGFILE" && log "requested adbd start (was $daemon_state)"
    elif [ "$restart_needed" = 1 ]; then
        /system/bin/setprop ctl.restart adbd 2>>"$LOGFILE" && log 'requested adbd restart for corrected TCP port'
    elif [ "$tick" -eq 0 ] && ! listening; then
        /system/bin/setprop ctl.restart adbd 2>>"$LOGFILE" && log "requested adbd restart: TCP $TCP_PORT listener missing"
    fi
    tick=$(( (tick+1) % 10 ))
    sleep "$CHECK_INTERVAL"
done
log 'keeper stopped because module was disabled or marked for removal'
