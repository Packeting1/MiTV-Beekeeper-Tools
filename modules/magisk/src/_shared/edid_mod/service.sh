#!/system/bin/sh
# 兜底：post-fs-data 没改成（分区还没挂载）时，现在改并重启让 EDID 重新加载。
# 最多自动重启 2 次，全部改好后计数清零。
MODDIR=${0%/*}
. "$MODDIR/edid_lib.sh"

device_ok || exit 0
COUNT=$DATA_DIR/reboot_tries

if [ "$(edid_count "$APPLY")" = 0 ]; then
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
