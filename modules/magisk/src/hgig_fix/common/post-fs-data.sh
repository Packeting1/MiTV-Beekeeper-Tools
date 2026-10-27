#!/system/bin/sh
# Runs before zygote/system_server, so before TvService parses the config.
MODDIR=${0%/*}
. "$MODDIR/common.sh"

device_ok || { log "post-fs-data: not beekeeper, skipped"; exit 0; }
ensure_flag
log "post-fs-data: ensure_flag rc=$?"
exit 0
