#!/system/bin/sh
# Legacy Magisk module installer (same format as the other modules).

SKIPMOUNT=true
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
  mkdir -p "$MODPATH/edid" || abort "! 创建目录失败"
  cp -af "$TMPDIR/edid_lib.sh" "$TMPDIR/edid_cfg.sh" "$MODPATH/" || abort "! 复制文件失败"
  for f in "$TMPDIR"/?_GPro_* "$TMPDIR/list"; do
    cp -af "$f" "$MODPATH/edid/" || abort "! 复制文件失败"
  done
  EDID_MODDIR="$MODPATH"
  . "$MODPATH/edid_lib.sh"
  edid_apply
  case $? in
    0) ui_print "EDID 已写入，请重启电视，并让 Windows 重新读取 EDID（重新插拔线或重启电脑）" ;;
    2) ui_print "EDID 已经是修改后的版本，或与已知原文件不同，已跳过" ;;
    *) ui_print "写入失败，模块已安装，开机时会自动重试。日志: $LOG" ;;
  esac
}

set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
  set_perm "$MODPATH/service.sh" 0 0 0755
  set_perm "$MODPATH/uninstall.sh" 0 0 0755
}
