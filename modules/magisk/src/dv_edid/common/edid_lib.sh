#!/system/bin/sh
# Shared helpers. EDID_TARGET / EDID_DATA_DIR / EDID_MODDIR may be overridden for offline tests.
MODDIR=${EDID_MODDIR:-${0%/*}}
TARGET_DIR=${EDID_TARGET:-/vendor/tvconfig/bsp/common/EDID_BIN}
MNT=/vendor/tvconfig
DATA_DIR=${EDID_DATA_DIR:-/data/adb/dv_edid}
LOG=$DATA_DIR/dv_edid.log
LIST=$MODDIR/edid/list

mkdir -p "$DATA_DIR" 2>/dev/null
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$LOG"; }

device_ok() { [ "$(getprop ro.product.name)" = "beekeeper" ]; }

sha() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }

# In the Magisk installer/boot context a plain remount is refused with "Permission denied", while
# the same command run through nsenter into init's mount namespace is accepted.
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

# edid_count <orig|new>: how many listed files currently match that version
edid_count() {
  _n=0
  while read -r name osha nsha; do
    [ -f "$TARGET_DIR/$name" ] || continue
    _c=$(sha "$TARGET_DIR/$name")
    if [ "$1" = orig ] && [ "$_c" = "$osha" ]; then _n=$((_n + 1)); fi
    if [ "$1" = new ] && [ "$_c" = "$nsha" ]; then _n=$((_n + 1)); fi
  done <"$LIST"
  echo $_n
}

# edid_switch <from: orig|new> : rewrite every file that matches <from> with the other version,
# in place and same size. Files with any other content are left alone.
# returns 0 changed all, 2 nothing to change, 1 failure
edid_switch() {
  _from=$1
  [ "$(edid_count "$_from")" -gt 0 ] || return 2
  remount_rw || { log "switch: remount rw refused: $(grep " $MNT " /proc/mounts)"; return 1; }
  mkdir -p "$DATA_DIR/orig" 2>/dev/null
  _fail=0
  _done=0
  while read -r name osha nsha; do
    t="$TARGET_DIR/$name"
    [ -f "$t" ] || continue
    if [ "$_from" = orig ]; then _want=$osha; _to=$nsha; _src="$MODDIR/edid/n_$name"; else _want=$nsha; _to=$osha; _src="$MODDIR/edid/o_$name"; fi
    [ "$(sha "$t")" = "$_want" ] || continue
    [ "$_from" = orig ] && [ ! -f "$DATA_DIR/orig/$name" ] && cp -f "$MODDIR/edid/o_$name" "$DATA_DIR/orig/$name"
    cat "$_src" >"$t"
    sync
    if [ "$(sha "$t")" = "$_to" ]; then
      _done=$((_done + 1))
    else
      _fail=$((_fail + 1))
      log "switch: verify failed for $name, putting the previous content back"
      if [ "$_from" = orig ]; then cat "$MODDIR/edid/o_$name" >"$t"; else cat "$MODDIR/edid/n_$name" >"$t"; fi
      sync
    fi
  done <"$LIST"
  remount_ro
  log "switch from $_from: changed=$_done failed=$_fail"
  [ "$_fail" = 0 ]
}

edid_apply() { edid_switch orig; }
edid_restore() { edid_switch new; }
