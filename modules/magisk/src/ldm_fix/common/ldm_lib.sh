#!/system/bin/sh
# 公共函数。LDM_TARGET / LDM_DATA_DIR / LDM_MODDIR 可被覆盖，用于离线测试。
MODDIR=${LDM_MODDIR:-${0%/*}}
TARGET=${LDM_TARGET:-/vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/ldm.ini}
MNT=/vendor/tvconfig
DATA_DIR=${LDM_DATA_DIR:-/data/adb/ldm_fix}
LOG=$DATA_DIR/ldm_fix.log
H_ORIG=473d2cd705f47fb0754bc75cc701e8601dcfed54c20650c612ecb4aa5471b2c4
H_NEW=282d2ad2991845cd79660bb13111cba1da82e8116f1519e2b395606b27eb6dcf

mkdir -p "$DATA_DIR" 2>/dev/null
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$LOG"; }

device_ok() { [ "$(getprop ro.product.name)" = "beekeeper" ]; }

sha() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }

# 输出：new | orig | other | missing
ldm_state() {
  [ -f "$TARGET" ] || { echo missing; return; }
  case "$(sha "$TARGET")" in
    $H_NEW) echo new ;;
    $H_ORIG) echo orig ;;
    *) echo other ;;
  esac
}

# 直写分区的备用方案：原版 ldm.ini 的数据在 tvconfig 的固定偏移处（同大小，原地覆盖）。
# 在 /vendor/tvconfig 无法重新挂载为可写时使用（某些 Magisk app 安装环境下会遇到）。
BLK=${LDM_BLOCK:-/dev/block/by-name/tvconfig$(getprop ro.boot.slot_suffix)}
RAW_OFF=20971520
RAW_LEN=17040
raw_sha() { dd if="$BLK" bs=1 skip=$RAW_OFF count=$RAW_LEN 2>/dev/null | sha256sum | cut -d' ' -f1; }

diag() {
  log "diag: id=$(id -u) ctx=$(cat /proc/self/attr/current 2>/dev/null) selinux=$(getenforce 2>/dev/null) ns=$(readlink /proc/self/ns/mnt 2>/dev/null)"
  log "diag: $(grep ' /vendor/tvconfig ' /proc/mounts 2>/dev/null)"
}

# ldm_write_fs <源文件> <期望 sha>：重新挂载为可写，原地覆盖（同大小），再挂回只读。
ldm_write_fs() {
  _src=$1
  _want=$2
  [ "$LDM_FAIL_FS" = 1 ] && return 1
  if [ -z "$LDM_TARGET" ]; then
    mount -o remount,rw "$MNT" 2>/dev/null || { log "write: remount rw refused"; diag; return 1; }
  fi
  cat "$_src" >"$TARGET"
  sync
  [ -z "$LDM_TARGET" ] && mount -o remount,ro "$MNT" 2>/dev/null
  [ "$(sha "$TARGET")" = "$_want" ]
}

# ldm_write_raw <源文件> <期望 sha> <偏移处当前的 sha>
ldm_write_raw() {
  [ -e "$BLK" ] || { log "raw: $BLK not found"; return 1; }
  [ "$(raw_sha)" = "$3" ] || { log "raw: bytes at offset are not the expected ones, refusing"; return 1; }
  # Android 在块设备层把分区设为只读，仅在这次写入时临时解除。
  _dev=$(readlink -f "$BLK" 2>/dev/null)
  [ -b "$_dev" ] && blockdev --setrw "$_dev" 2>/dev/null
  dd if="$1" of="$BLK" bs=1 seek=$RAW_OFF conv=notrunc 2>/dev/null
  sync
  [ -b "$_dev" ] && blockdev --setro "$_dev" 2>/dev/null
  [ "$(raw_sha)" = "$2" ] || { log "raw: verify failed"; return 1; }
  log "raw: block patch written (live file updates after reboot)"
}

# ldm_write <源文件> <期望 sha> <写入前偏移处的 sha>
ldm_write() {
  [ "$(sha "$1")" = "$2" ] || { log "write: source hash mismatch"; return 1; }
  ldm_write_fs "$1" "$2" && return 0
  log "write: falling back to raw block patch"
  ldm_write_raw "$1" "$2" "$3"
}

# 只在已知的 3.0.109 原文件上写入 OS2 参数，其它情况一律不动。
# 返回 0 已写入，2 已是修复版，3 未知文件（未改动），1 失败
ldm_apply() {
  case "$(ldm_state)" in
    new) return 2 ;;
    orig)
      if ldm_write "$MODDIR/ldm_os2.ini" "$H_NEW" "$H_ORIG"; then
        log "apply: ldm.ini now OS2 values"
        return 0
      fi
      log "apply: failed"
      [ "$(ldm_state)" = other ] && ldm_write "$MODDIR/ldm_orig.ini" "$H_ORIG" "$H_NEW"
      return 1
      ;;
    *) log "apply: ldm.ini is not the known 3.0.109 original, untouched"; return 3 ;;
  esac
}

ldm_restore() {
  _raw=$(raw_sha)
  case "$(ldm_state)" in
    new)
      if ldm_write "$MODDIR/ldm_orig.ini" "$H_ORIG" "$H_NEW"; then
        log "restore: original 3.0.109 ldm.ini written back"
        return 0
      fi
      log "restore: failed"
      return 1
      ;;
    *)
      # 直写分区的补丁在重启前挂载的文件系统里看不到，所以也要一并撤销
      if [ "$_raw" = "$H_NEW" ] && [ "$(ldm_state)" = orig ]; then
        ldm_write_raw "$MODDIR/ldm_orig.ini" "$H_ORIG" "$H_NEW" || return 1
        log "restore: raw patch undone"
      fi
      return 0 ;;
  esac
}
