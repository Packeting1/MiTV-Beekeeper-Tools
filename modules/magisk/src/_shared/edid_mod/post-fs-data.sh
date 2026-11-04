#!/system/bin/sh
# 在电视服务启动前运行，这次开机加载的就是改过的 EDID。
MODDIR=${0%/*}
. "$MODDIR/edid_lib.sh"

device_ok || exit 0
edid_apply
log "post-fs-data: rc=$?"
exit 0
