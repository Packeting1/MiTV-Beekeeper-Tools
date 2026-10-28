#!/system/bin/sh
# Shared by the EDID modules (Support LLDV, Support DP Audio). Each module sets DATA_DIR, APPLY and
# RESTORE in edid_cfg.sh. EDID_TARGET / EDID_DATA_DIR / EDID_MODDIR may be overridden for offline tests.
#
# edid/list: "name o n a b", the sha256 of the file in four states: o = stock, n = LLDV, a = DP audio,
# b = both ("-" = state does not exist). The state's content is edid/<o|n|a|b>_<name>.
# APPLY / RESTORE are space separated "from:to" column pairs, e.g. "1:2 3:4" = o->n and a->b.
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

# col <n>: sha in column n of the current list line (set by read in the callers)
col() { case $1 in 1) echo "$c1";; 2) echo "$c2";; 3) echo "$c3";; 4) echo "$c4";; esac; }
# usable <p>: both states of the "from:to" pair exist for the current file (a missing state is "-")
usable() { [ "$(col "${1%:*}")" != - ] && [ "$(col "${1#*:}")" != - ]; }
state_name() { case $1 in 1) echo o;; 2) echo n;; 3) echo a;; 4) echo b;; esac; }

# edid_count <map>: files that are currently in a "from" state of the map
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

# edid_switch <map>: rewrite every file that is in a "from" state of the map with its "to" state, in
# place and same size. Files with any other content are left alone.
# returns 0 changed all, 2 nothing to change, 1 failure
edid_switch() {
  [ "$(edid_count "$1")" -gt 0 ] || return 2
  remount_rw || { log "switch: remount rw refused: $(grep " $MNT " /proc/mounts)"; return 1; }
  mkdir -p "$DATA_DIR/orig" 2>/dev/null
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
      [ "$_f" = 1 ] && [ ! -f "$DATA_DIR/orig/$name" ] && cp -f "$_fs" "$DATA_DIR/orig/$name"
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
