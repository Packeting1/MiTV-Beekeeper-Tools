#!/system/bin/sh
# 旧格式安装脚本：print_modname 留空，Magisk 不显示模块名和作者横幅。

SKIPMOUNT=true
PROPFILE=false
POSTFSDATA=true
LATESTARTSERVICE=false

print_modname() {
  :
}

on_install() {
  if [ "$(getprop ro.product.name)" != "beekeeper" ]; then
    abort "! 不支持此设备"
  fi
  # 补丁只适用于已知的 3.0.109 原文件；挂载生效后读到的是补丁版，所以两种都接受。
  # 前两个是 post-fs-data.sh 里的 ORIG_SHA / PATCHED_SHA；后两个是 libmtkapp.so 的其它已知版本，
  # post-fs-data.sh 不会对它们打补丁，这里只是放行安装。
  cur=$(sha256sum /mnt/vendor/linux_rootfs/lib/libmtkapp.so 2>/dev/null | cut -d' ' -f1)
  case "$cur" in
    3b8f3d898058a67b1dcb38611008dc2991aec773637b1c6b888b0231da90f43c) ;;
    2b098df1ad38e20c28eccc06f37fc5f47aa681fe3d5732f2edcdbb5d55463be9) ;;
    a430be442eae88b0aa5e559fefc675618fa97c8b8d60623e13d30f4b669f24cf) ;;
    40f5fe6980f9b6855e05db8c53fcfaf3d45dabc0ca1f3c91ca6b1e28efeb6c86) ;;
    *) abort "! 系统版本与补丁不匹配，已取消安装" ;;
  esac
  ui_print "- 安装完成，重启电视后生效"
}

set_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
  set_perm "$MODPATH/uninstall.sh" 0 0 0755
}
