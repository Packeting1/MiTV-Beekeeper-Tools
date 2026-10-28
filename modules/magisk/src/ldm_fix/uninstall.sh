#!/system/bin/sh
# Magisk runs this at boot after the module is removed: put the stock 3.0.109 ldm.ini back
# (takes effect on the following reboot) and delete the module's data, unless the restore failed.
MODDIR=${0%/*}
. "$MODDIR/ldm_lib.sh"
ldm_restore && rm -rf "$DATA_DIR"
exit 0
