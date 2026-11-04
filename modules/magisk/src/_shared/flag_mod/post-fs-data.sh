#!/system/bin/sh
# 在 zygote/system_server 之前运行，即早于 TvService 解析配置。
MODDIR=${0%/*}
. "$MODDIR/common.sh"

device_ok || { log "post-fs-data: not beekeeper, skipped"; exit 0; }
ensure_flag
log "post-fs-data: ensure_flag rc=$?"
exit 0
