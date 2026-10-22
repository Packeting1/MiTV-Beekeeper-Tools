#!/system/bin/sh
# Runs before the TV services start, so the patched EDID is what gets loaded on this boot.
MODDIR=${0%/*}
. "$MODDIR/edid_lib.sh"

device_ok || exit 0
edid_apply
log "post-fs-data: rc=$?"
exit 0
