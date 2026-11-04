#!/system/bin/sh
# Magisk 在模块移除后的开机阶段运行一次，早于 TvService 启动，服务解析到的就是清理后的配置。
MODDIR=${0%/*}
. "$MODDIR/common.sh"

remove_flag
rm -rf "$DATA_DIR"
exit 0
