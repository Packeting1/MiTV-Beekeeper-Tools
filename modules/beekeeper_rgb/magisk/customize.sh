#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "请在启动后的 Magisk 中安装"
[ "$(getprop ro.product.device)" = beekeeper ] || abort "仅支持 beekeeper"
[ "$(getprop ro.build.version.incremental)" = OS3.0.109.0.UFFMAMN ] || abort "固件版本不匹配"
stock=986aa78c1761d22142463183b69db729f40be10ba45485e9aca68e2d5972a992
legacy=baf6fd8cc263f9e2f0e3b70fe8eb08bfe1699b80bf519974ff6f51820a198c98
effects=953cdecef15b309b1b9a8608c1d101aa1a68d2aac13228b4dc5d792075cce97c
patched=5aac620745177370f59b0627e9b2f75dd95be2a4cf428d2658f8b5b7e44b4f11
actual=$(sha256sum /vendor/lib64/libbspmserver_config.so | cut -d ' ' -f 1)
[ "$actual" = "$stock" ] || [ "$actual" = "$legacy" ] || [ "$actual" = "$effects" ] || [ "$actual" = "$patched" ] || abort "灯控库校验不匹配，未安装"
[ -d /data/adb/modules/zygisk_lsposed ] || abort "需要已安装的 LSPosed"
[ ! -f /data/adb/modules/zygisk_lsposed/disable ] || abort "请先启用 LSPosed"
ui_print "- 已验证设备、固件与灯控库"
pm install -r --user 0 "$MODPATH/app/BeekeeperRGB.apk" || abort "配套 APK 安装失败"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/vendor/lib64/libbspmserver_config.so" 0 0 0644 u:object_r:same_process_hal_file:s0
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/bin/om2-pixel-writer" 0 0 0700
set_perm "$MODPATH/bin/om2-single-writer" 0 0 0700
set_perm "$MODPATH/bin/om2-flash-transport" 0 0 0700
set_perm "$MODPATH/bin/om2-flash-read" 0 0 0700
set_perm_recursive "$MODPATH/firmware" 0 0 0700 0600
ui_print "- 请在 LSPosed 启用“装饰灯自定义颜色”及推荐作用域"
ui_print "- 重启后，在系统装饰灯设置中选择“自定义颜色”"
