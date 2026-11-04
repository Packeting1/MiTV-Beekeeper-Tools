#!/system/bin/sh
# Magisk 在模块移除后的开机阶段运行本脚本：把 3.0.109 原版 ldm.ini 放回去（下次重启生效），
# 并删除模块数据；放回失败则保留数据。
MODDIR=${0%/*}
. "$MODDIR/ldm_lib.sh"
ldm_restore && rm -rf "$DATA_DIR"
exit 0
