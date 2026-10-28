#!/system/bin/sh
# Legacy Magisk module installer. Used instead of customize.sh because Magisk
# prints a "<name> / by <author>" banner for the modern format and nothing for
# this one, so the install log stays free of extra text.

SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=true
LATESTARTSERVICE=true

print_modname() {
  :
}

on_install() {
  if [ "$(getprop ro.product.name)" != "beekeeper" ]; then
    abort "! 不支持此设备"
  fi
  cp -af "$TMPDIR/common.sh" "$TMPDIR/flag.conf" "$MODPATH/" || abort "! 复制文件失败"
}

set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
  set_perm "$MODPATH/service.sh" 0 0 0755
  set_perm "$MODPATH/uninstall.sh" 0 0 0755
}
