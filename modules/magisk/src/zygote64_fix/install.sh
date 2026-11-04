#!/system/bin/sh
# 旧格式安装脚本：print_modname 留空，Magisk 不显示模块名和作者横幅。

SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=true

print_modname() {
  :
}

on_install() {
  :
}

set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  set_perm "$MODPATH/service.sh" 0 0 0755
  set_perm "$MODPATH/uninstall.sh" 0 0 0755
}
