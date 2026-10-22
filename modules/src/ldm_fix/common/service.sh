#!/system/bin/sh
# Re-applies the OS2 ldm.ini if the partition got the stock 3.0.109 file back
# (e.g. an OTA of the same tvconfig). The driver reads the file at boot, so a reboot
# follows; at most 2 automatic reboots, the counter resets once the file is correct.
MODDIR=${0%/*}
. "$MODDIR/ldm_lib.sh"

device_ok || exit 0
COUNT=$DATA_DIR/reboot_tries

case "$(ldm_state)" in
  new) rm -f "$COUNT"; exit 0 ;;
  orig) ;;
  *) log "service: ldm.ini is not the known original, not touching it"; exit 0 ;;
esac

n=$(cat "$COUNT" 2>/dev/null)
[ -z "$n" ] && n=0
if [ "$n" -ge 2 ]; then
  log "service: already rebooted $n times without effect, giving up"
  exit 0
fi

ldm_apply
if [ $? = 0 ]; then
  echo $((n + 1)) >"$COUNT"
  log "service: re-applied, rebooting (try $((n + 1)))"
  sleep ${LDM_SLEEP:-20}
  ${LDM_REBOOT_CMD:-reboot}
fi
exit 0
