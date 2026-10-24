#!/system/bin/sh
# Fallback: if post-fs-data could not patch (partition not mounted yet), patch now and reboot so the
# EDID is reloaded. At most 2 automatic reboots; the counter resets once everything is patched.
MODDIR=${0%/*}
. "$MODDIR/edid_lib.sh"

device_ok || exit 0
COUNT=$DATA_DIR/reboot_tries

if [ "$(edid_count orig)" = 0 ]; then
  rm -f "$COUNT"
  exit 0
fi

n=$(cat "$COUNT" 2>/dev/null)
[ -z "$n" ] && n=0
if [ "$n" -ge 2 ]; then
  log "service: already rebooted $n times without effect, giving up"
  exit 0
fi

edid_apply
if [ $? = 0 ]; then
  echo $((n + 1)) >"$COUNT"
  log "service: patched late, rebooting (try $((n + 1)))"
  sleep ${EDID_SLEEP:-20}
  ${EDID_REBOOT_CMD:-reboot}
fi
exit 0
