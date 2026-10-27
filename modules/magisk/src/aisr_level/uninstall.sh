#!/system/bin/sh
# Magisk runs this once at boot when the module is removed, before TvService
# starts, so the cleaned config is what the service parses.
MODDIR=${0%/*}
. "$MODDIR/common.sh"

remove_flag
rm -rf "$DATA_DIR"
exit 0
