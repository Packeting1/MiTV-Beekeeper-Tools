#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort 'Install from Magisk or magisk --install-module.'
[ "$(getprop ro.product.device)" = beekeeper ] || abort 'This module is for beekeeper only.'
[ "$(getprop ro.boot.platform_id)" = 1380 ] || abort 'Expected platform 1380.'
[ "$(getprop ro.build.version.incremental)" = OS3.0.109.0.UFFMAMN ] || abort 'This package is verified against OS3.0.109.0.UFFMAMN only.'
expected=$(cat "$MODPATH/base-config.sha256")
actual=$(sha256sum /system/etc/mitv.config_product.xml | cut -d ' ' -f 1)
if [ "$expected" != "$actual" ]; then
  inst=/data/adb/modules/mitv_4kui_switch
  [ -f "$inst/system/etc/mitv.config_product.xml" ] \
    && [ "$(sha256sum "$inst/system/etc/mitv.config_product.xml" | cut -d ' ' -f 1)" = "$actual" ] \
    && [ "$(cat "$inst/base-config.sha256" 2>/dev/null)" = "$expected" ] \
    || abort 'Base configuration differs. Rebuild the overlay from the current firmware.'
fi
ui_print '- Adding platform 1380 to the two existing 4K UI support entries'
ui_print '- Policy: partial 4K, matching the vendor whitelist design'
ui_print '- No automatic resolution change; use the native settings switch'
set_perm_recursive "$MODPATH" 0 0 0755 0644
