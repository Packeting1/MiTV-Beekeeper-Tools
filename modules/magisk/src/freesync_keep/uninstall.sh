#!/system/bin/sh
# Magisk runs this at boot after the module is removed. The bind mount is gone with the reboot;
# only the patched copy is left to delete.
rm -rf /data/adb/freesync_keep
exit 0
