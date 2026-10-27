#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort 'Install from the Magisk app or magisk --install-module.'
[ "$MAGISK_VER_CODE" -ge 24000 ] || abort 'Magisk 24 or newer is required.'
[ "$(getprop ro.product.device)" = beekeeper ] || abort 'This build is intended for the tested beekeeper display only.'
ui_print '- MiTV ADB Keeper: TCP 5555, check every 3 seconds'
ui_print '- Restores adb_enabled after boot and system changes'
ui_print '- Disable this module to stop keeping ADB enabled'
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in service.sh keeper.sh uninstall.sh; do
    set_perm "$MODPATH/$script" 0 0 0755
done
