#!/system/bin/sh
# Magisk runs this at boot after the module is removed: put the files back to the state without this
# module (takes effect on the following reboot) and delete the module's data.
MODDIR=${0%/*}
. "$MODDIR/edid_lib.sh"
edid_restore
rm -rf "$DATA_DIR"
exit 0
