#!/system/bin/sh
# Shared helpers. LDM_TARGET / LDM_DATA_DIR / LDM_MODDIR may be overridden for offline tests.
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

# prints: new | orig | other | missing
ldm_state() {
  [ -f "$TARGET" ] || { echo missing; return; }
  case "$(sha "$TARGET")" in
    $H_NEW) echo new ;;
    $H_ORIG) echo orig ;;
    *) echo other ;;
  esac
}

keep_backup() {
  [ "$(sha "$DATA_DIR/ldm.ini.3.0.109")" = "$H_ORIG" ] || cp -f "$MODDIR/ldm_orig.ini" "$DATA_DIR/ldm.ini.3.0.109"
}

# Raw fallback: the stock ldm.ini data sits at a fixed byte offset of tvconfig (same size, in place).
# Used when remounting /vendor/tvconfig read-write is refused (seen from some Magisk app install contexts).
BLK=${LDM_BLOCK:-/dev/block/by-name/tvconfig$(getprop ro.boot.slot_suffix)}
RAW_OFF=20971520
RAW_LEN=17040
raw_sha() { dd if="$BLK" bs=1 skip=$RAW_OFF count=$RAW_LEN 2>/dev/null | sha256sum | cut -d' ' -f1; }

diag() {
  log "diag: id=$(id -u) ctx=$(cat /proc/self/attr/current 2>/dev/null) selinux=$(getenforce 2>/dev/null) ns=$(readlink /proc/self/ns/mnt 2>/dev/null)"
  log "diag: $(grep ' /vendor/tvconfig ' /proc/mounts 2>/dev/null)"
}

# ldm_write_fs <source> <expected sha>: remount rw, overwrite in place (same size), remount ro.
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

# ldm_write_raw <source> <expected sha> <sha currently at the offset>
ldm_write_raw() {
  [ -e "$BLK" ] || { log "raw: $BLK not found"; return 1; }
  [ "$(raw_sha)" = "$3" ] || { log "raw: bytes at offset are not the expected ones, refusing"; return 1; }
  # Android marks partitions read-only at the block layer; lift that only for this write.
  _dev=$(readlink -f "$BLK" 2>/dev/null)
  [ -b "$_dev" ] && blockdev --setrw "$_dev" 2>/dev/null
  dd if="$1" of="$BLK" bs=1 seek=$RAW_OFF conv=notrunc 2>/dev/null
  sync
  [ -b "$_dev" ] && blockdev --setro "$_dev" 2>/dev/null
  [ "$(raw_sha)" = "$2" ] || { log "raw: verify failed"; return 1; }
  log "raw: block patch written (live file updates after reboot)"
}

# ldm_write <source> <expected sha> <sha at raw offset before the write>
ldm_write() {
  [ "$(sha "$1")" = "$2" ] || { log "write: source hash mismatch"; return 1; }
  ldm_write_fs "$1" "$2" && return 0
  log "write: falling back to raw block patch"
  ldm_write_raw "$1" "$2" "$3"
}

# Writes the OS2 values only over the known 3.0.109 original. Anything else is left alone.
# returns 0 applied, 2 already applied, 3 unknown file (untouched), 1 failure
ldm_apply() {
  case "$(ldm_state)" in
    new) return 2 ;;
    orig)
      keep_backup
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
      # a raw patch is invisible to the mounted fs until reboot: undo it too
      if [ "$_raw" = "$H_NEW" ] && [ "$(ldm_state)" = orig ]; then
        ldm_write_raw "$MODDIR/ldm_orig.ini" "$H_ORIG" "$H_NEW" && log "restore: raw patch undone"
      fi
      return 0 ;;
  esac
}
