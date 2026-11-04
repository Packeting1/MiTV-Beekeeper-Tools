#!/system/bin/sh
# Magisk 在模块移除后的开机阶段运行本脚本。bind mount 随重启消失，只剩补丁副本需要删。
rm -rf /data/adb/freesync_keep
exit 0
