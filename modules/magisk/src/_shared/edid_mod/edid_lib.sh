#!/system/bin/sh
# EDID 类模块（Support LLDV、Support DP Audio）共用。各模块在 edid_cfg.sh 里设置 DATA_DIR、APPLY、
# RESTORE。EDID_TARGET / EDID_DATA_DIR / EDID_MODDIR 可被覆盖，用于离线测试。
#
# edid/list：每行 "name o n a b"，是文件在四种状态下的 sha256：o = 原版，n = LLDV，a = DP 音频，
# b = 两者都有（"-" 表示该状态不存在）。各状态的内容在 edid/<o|n|a|b>_<name>。
# APPLY / RESTORE 是空格分隔的 "from:to" 列号对，如 "1:2 3:4" 表示 o->n 和 a->b。
MODDIR=${EDID_MODDIR:-${0%/*}}
. "$MODDIR/edid_cfg.sh"
TARGET_DIR=${EDID_TARGET:-/vendor/tvconfig/bsp/common/EDID_BIN}
MNT=/vendor/tvconfig
LOG=$DATA_DIR/edid.log
LIST=$MODDIR/edid/list

mkdir -p "$DATA_DIR" 2>/dev/null
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$LOG"; }

device_ok() { [ "$(getprop ro.product.name)" = "beekeeper" ]; }

sha() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }

# Magisk 安装/开机环境下直接 remount 会被拒绝（Permission denied），
# 同一条命令经 nsenter 进入 init 的挂载命名空间执行则可以。
remount_rw() {
  [ -n "$EDID_TARGET" ] && return 0
  mount -o remount,rw "$MNT" 2>/dev/null && return 0
  nsenter --mount=/proc/1/ns/mnt mount -o remount,rw "$MNT" 2>/dev/null
}
remount_ro() {
  [ -n "$EDID_TARGET" ] && return 0
  mount -o remount,ro "$MNT" 2>/dev/null && return 0
  nsenter --mount=/proc/1/ns/mnt mount -o remount,ro "$MNT" 2>/dev/null
}

# col <n>：当前 list 行第 n 列的 sha（由调用方的 read 设置）
col() { case $1 in 1) echo "$c1";; 2) echo "$c2";; 3) echo "$c3";; 4) echo "$c4";; esac; }
# usable <p>：当前文件的 "from:to" 两个状态都存在（缺失的状态记为 "-"）
usable() { [ "$(col "${1%:*}")" != - ] && [ "$(col "${1#*:}")" != - ]; }
state_name() { case $1 in 1) echo o;; 2) echo n;; 3) echo a;; 4) echo b;; esac; }

# edid_count <map>：当前处于 map 中某个 "from" 状态的文件数
edid_count() {
  _n=0
  while read -r name c1 c2 c3 c4; do
    [ -f "$TARGET_DIR/$name" ] || continue
    _c=$(sha "$TARGET_DIR/$name")
    for p in $1; do
      usable "$p" && [ "$_c" = "$(col "${p%:*}")" ] && { _n=$((_n + 1)); break; }
    done
  done <"$LIST"
  echo $_n
}

# edid_switch <map>：把处于 map 中 "from" 状态的文件原地（同大小）改写成对应的 "to" 状态，
# 内容是其它任何值的文件不动。
# 返回 0 全部改完，2 无需改动，1 失败
edid_switch() {
  [ "$(edid_count "$1")" -gt 0 ] || return 2
  remount_rw || { log "switch: remount rw refused: $(grep " $MNT " /proc/mounts)"; return 1; }
  _fail=0
  _done=0
  while read -r name c1 c2 c3 c4; do
    t="$TARGET_DIR/$name"
    [ -f "$t" ] || continue
    _c=$(sha "$t")
    for p in $1; do
      usable "$p" || continue
      _f=${p%:*}
      _t=${p#*:}
      [ "$_c" = "$(col "$_f")" ] || continue
      _fs="$MODDIR/edid/$(state_name "$_f")_$name"
      _ts="$MODDIR/edid/$(state_name "$_t")_$name"
      cat "$_ts" >"$t"
      sync
      if [ "$(sha "$t")" = "$(col "$_t")" ]; then
        _done=$((_done + 1))
      else
        _fail=$((_fail + 1))
        log "switch: verify failed for $name, putting the previous content back"
        cat "$_fs" >"$t"
        sync
      fi
      break
    done
  done <"$LIST"
  remount_ro
  log "switch: changed=$_done failed=$_fail"
  [ "$_fail" = 0 ]
}

edid_apply() { edid_switch "$APPLY"; }
edid_restore() { edid_switch "$RESTORE"; }
