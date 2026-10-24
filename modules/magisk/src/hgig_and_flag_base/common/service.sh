#!/system/bin/sh
# Late-boot safety net. Normally post-fs-data.sh already did the work and this
# only confirms it. If TvService still reports the key as NULL (it parsed the
# file before the item was there), restart TvService once.
MODDIR=${0%/*}
. "$MODDIR/common.sh"

device_ok || exit 0

_i=0
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  _i=$((_i + 1))
  [ "$_i" -gt 150 ] && { log "service: boot_completed timeout"; exit 0; }
  sleep 2
done

ensure_flag
_rc=$?
[ "$_rc" = 1 ] && { log "service: config could not be fixed, giving up"; exit 0; }

query() {
  service call TvService 82 s16 "$KEY" 2>&1
}

_i=0
until service check TvService 2>/dev/null | grep -q ': found'; do
  _i=$((_i + 1))
  [ "$_i" -gt 60 ] && { log "service: TvService not registered, giving up"; exit 0; }
  sleep 2
done

_q=$(query)
case "$_q" in
  *Y.E.S*) log "service: TvService reports $KEY=YES"; exit 0 ;;
  *N.U.L.L*) ;;
  *) log "service: unexpected TvService reply, not touching it: $_q"; exit 0 ;;
esac

find_pid() {
  for _d in /proc/[0-9]*; do
    _c=$(tr '\0' ' ' < "$_d/cmdline" 2>/dev/null)
    case "$_c" in
      xiaomi.tvservice*) echo "${_d#/proc/}"; return 0 ;;
    esac
  done
  return 1
}

_old=$(find_pid) || { log "service: TvService pid not found, reboot will apply the fix"; exit 0; }
log "service: TvService reports NULL, restarting it once (pid $_old)"
kill "$_old"

_i=0
while [ "$_i" -lt 30 ]; do
  sleep 2
  _i=$((_i + 1))
  _new=$(find_pid) || continue
  [ "$_new" = "$_old" ] && continue
  service check TvService 2>/dev/null | grep -q ': found' || continue
  break
done

_q=$(query)
case "$_q" in
  *Y.E.S*) log "service: after restart $KEY=YES" ;;
  *) log "service: still not YES after restart: $_q" ;;
esac

am force-stop com.xiaomi.mitv.settings >/dev/null 2>&1
exit 0
