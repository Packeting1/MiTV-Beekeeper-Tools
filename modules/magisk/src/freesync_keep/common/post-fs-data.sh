#!/system/bin/sh
#
# 开 FreeSync / 杜比信号带游戏标志时，不自动把图像模式切成游戏模式
#
# libmtkapp.so 在只读 erofs 里，但由 Android 侧的 init 服务 dtv-svc 加载，所以在它启动之前
# （这里的 post-fs-data）把打过补丁的副本 bind mount 上去，分区不动，重启即恢复。
# 开机后再挂没用：dtv-svc 已经加载了原文件。
# 只在原文件 sha256 是已知的 3.0.109 版本时才打补丁，OTA 之后文件变了就什么都不做。
#
# 补丁（ARM 指令，文件偏移 = 虚拟地址）：
#   0x34a500  开 FreeSync 时跳过切游戏模式
#   0x349ac4  换时序时把 FreeSync 当关
#   0x28a7a8 / 0x28a844  杜比游戏标志出现时，目标模式取当前模式（不切到杜比游戏）
#   0x28a43c  游戏标志消失时，不再恢复旧保存的模式
#   0x33abf0 / 0x33ae90 / 0x33ae9c  开关 HDR/杜比时，游戏标志、杜比游戏、ALLM 成立也保持当前模式
#   0x281ef8  HDR 类型变化时，FreeSync 开着也不按游戏模式配置覆盖目标模式
#
# 日志：/data/adb/freesync_keep/log.txt；在模块目录放一个 disable 文件即可停用

MODDIR=${0%/*}
SRC=/mnt/vendor/linux_rootfs/lib/libmtkapp.so
DIR=/data/adb/freesync_keep
DST=$DIR/libmtkapp.so
LOG=$DIR/log.txt
ORIG_SHA=3b8f3d898058a67b1dcb38611008dc2991aec773637b1c6b888b0231da90f43c
PATCHED_SHA=2b098df1ad38e20c28eccc06f37fc5f47aa681fe3d5732f2edcdbb5d55463be9

log() { echo "$(date '+%F %T') $*" >>"$LOG"; }
sha() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }

mkdir -p "$DIR"
[ -f "$MODDIR/disable" ] && exit 0
grep "$SRC" /proc/mounts | grep -q userdata && exit 0

cur=$(sha "$SRC")
if [ "$cur" != "$ORIG_SHA" ]; then
  log "源文件 sha 不是已知版本 ($cur)，跳过"
  exit 0
fi

if [ "$(sha "$DST")" != "$PATCHED_SHA" ]; then
  cp "$SRC" "$DST.tmp" || { log "复制失败"; exit 0; }
  put() { printf "$2" | dd of="$DST.tmp" bs=1 seek=$(($1)) conv=notrunc 2>/dev/null; }
  put 0x34a500 '\026\000\000\352'
  put 0x349ac4 '\000\000\240\343'
  put 0x28a7a8 '\360\120\326\241'
  put 0x28a844 '\360\120\326\341'
  put 0x28a43c '\360\120\326\341'
  put 0x33abf0 '\244\000\000\352'
  put 0x33ae90 '\377\000\000\032'
  put 0x33ae9c '\374\000\000\012'
  put 0x281ef8 '\000\060\240\343'
  if [ "$(sha "$DST.tmp")" != "$PATCHED_SHA" ]; then
    log "补丁后 sha 不对，跳过"
    rm -f "$DST.tmp"
    exit 0
  fi
  mv "$DST.tmp" "$DST"
fi

chmod 755 "$DST"
chcon u:object_r:vendor_file:s0 "$DST"
if mount -o bind "$DST" "$SRC"; then
  log "已挂载补丁版 libmtkapp.so"
else
  log "挂载失败"
fi
