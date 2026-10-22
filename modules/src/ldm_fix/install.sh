#!/system/bin/sh
# Legacy Magisk module installer (no name/author banner, same format as the other modules).

SKIPMOUNT=true
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=true

print_modname() {
  :
}

on_install() {
  if [ "$(getprop ro.product.name)" != "beekeeper" ]; then
    abort "! 不支持此设备"
  fi
  for f in ldm_lib.sh ldm_os2.ini ldm_orig.ini; do
    cp -af "$TMPDIR/$f" "$MODPATH/$f" || abort "! 复制文件失败"
  done
  LDM_MODDIR="$MODPATH"
  . "$MODPATH/ldm_lib.sh"
  ldm_apply
  case $? in
    0) ui_print "ldm.ini 已写入，请重启电视生效" ;;
    2) ui_print "ldm.ini 已是修复后的版本" ;;
    3) abort "! ldm.ini 不是 3.0.109 原始文件，已跳过，未做任何修改" ;;
    *) ui_print "写入失败，模块已安装，开机时会自动重试。日志: /data/adb/ldm_fix/ldm_fix.log" ;;
  esac
}

set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  set_perm "$MODPATH/service.sh" 0 0 0755
  set_perm "$MODPATH/uninstall.sh" 0 0 0755
}
