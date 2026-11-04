#!/system/bin/sh
# 分区里的 ldm.ini 变回 3.0.109 原版（如同一 tvconfig 的 OTA）时重新写入 OS2 版。
# 驱动开机时读该文件，所以写完要重启；最多自动重启 2 次，文件正确后计数清零。
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
