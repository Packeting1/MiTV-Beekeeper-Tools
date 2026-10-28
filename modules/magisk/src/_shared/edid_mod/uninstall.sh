#!/system/bin/sh
# Magisk runs this at boot after the module is removed: put the files back to the state without this
# module (takes effect on the following reboot) and delete the module's data, unless the restore failed
# (2 = nothing to restore, which counts as done).
MODDIR=${0%/*}
. "$MODDIR/edid_lib.sh"
edid_restore
[ $? = 1 ] || rm -rf "$DATA_DIR"
exit 0
