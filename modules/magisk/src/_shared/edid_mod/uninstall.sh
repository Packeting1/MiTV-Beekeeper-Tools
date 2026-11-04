#!/system/bin/sh
# Magisk 在模块移除后的开机阶段运行本脚本：把文件恢复成没装本模块时的状态（下次重启生效），
# 并删除模块数据；恢复失败则保留数据（返回 2 表示无需恢复，算完成）。
MODDIR=${0%/*}
. "$MODDIR/edid_lib.sh"
edid_restore
[ $? = 1 ] || rm -rf "$DATA_DIR"
exit 0
