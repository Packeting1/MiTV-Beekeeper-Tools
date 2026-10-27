#!/system/bin/sh
# Shared helpers for the beekeeper "AISR强度调节" module (sourced, not executed).
#
# What it maintains: one <item> in /data/system/mitv.config.xml. TvService's
# ConfigParser reads that file first at service start and the first value for a
# key wins, so this YES overrides the NO in /system/etc/mitv.config_product.xml.
# Key: support_display_AISR_level
#
# The FLAG_* variables exist so the logic can be tested off-device.

DATA_DIR="${FLAG_DIR:-/data/adb/beekeeper_aisr_level}"
CFG="${FLAG_CFG:-/data/system/mitv.config.xml}"
LOG="$DATA_DIR/beekeeper_aisr_level.log"
BK="$DATA_DIR/mitv.config.xml.orig"
KEY="support_display_AISR_level"
ITEM='<item key="support_display_AISR_level" value="YES"/>'

log() {
  mkdir -p "$DATA_DIR" 2>/dev/null
  if [ -f "$LOG" ]; then
    _sz=$(wc -c < "$LOG" 2>/dev/null | tr -d ' ')
    [ "${_sz:-0}" -gt 65536 ] && : > "$LOG"
  fi
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null
}

device_ok() {
  [ -n "$FLAG_TEST" ] && return 0
  [ "$(getprop ro.product.name)" = "beekeeper" ]
}

# Known-good full file (same items as the stock one), used only when the
# config file is missing or unusable.
write_template() {
  cat <<'EOF'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<items token="1497612014330">
    <item key="support_bigbig_test" value="YES"/>
    <item key="support_xiaomi_tvlock" value="YES" platformid="1285"/>
    <item key="support_cold_bootup_input_source" value="NO" platformid="1330,1331,1332,1334,1337,1338"/>
    <item key="support_display_AISR_level" value="YES"/>
</items>
EOF
}

# Xiaomi's config downloader deletes this file and re-downloads it whenever its
# md5 differs from the cloud copy, which silently drops our item. The immutable
# attribute makes that delete (and any rename over it) fail. Every edit must
# unlock first and lock again afterwards.
unlock_cfg() {
  [ -e "$CFG" ] && chattr -i "$CFG" 2>/dev/null
  return 0
}

lock_cfg() {
  [ -f "$CFG" ] && chattr +i "$CFG" 2>/dev/null
  return 0
}

cfg_usable() {
  [ -s "$CFG" ] && grep -q '<items' "$CFG" && grep -q '</items>' "$CFG"
}

cfg_is_yes() {
  [ -f "$CFG" ] && grep -q "key=\"$KEY\" value=\"YES\"" "$CFG"
}

# Insert ITEM before the closing </items>. A closing tag alone on its line gets
# the item on its own line (so remove_item restores the file byte for byte);
# a closing tag sharing a line with other content gets it inline.
insert_item() {
  _done=0
  while IFS= read -r _l || [ -n "$_l" ]; do
    case "$_l" in
      *"</items>"*)
        if [ "$_done" = 0 ]; then
          _done=1
          _pre="${_l%%</items>*}"
          case "$_pre" in
            *[![:space:]]*) printf '%s%s%s\n' "$_pre" "$ITEM" "${_l#"$_pre"}" ;;
            *) printf '    %s\n%s\n' "$ITEM" "$_l" ;;
          esac
        else
          printf '%s\n' "$_l"
        fi
        ;;
      *) printf '%s\n' "$_l" ;;
    esac
  done
}

# Drop ITEM (whole line if it is alone on it, otherwise just the item text).
remove_item() {
  while IFS= read -r _l || [ -n "$_l" ]; do
    case "$_l" in
      *"$ITEM"*)
        _rest="${_l%%"$ITEM"*}${_l#*"$ITEM"}"
        case "$_rest" in
          *[![:space:]]*) printf '%s\n' "$_rest" ;;
        esac
        ;;
      *) printf '%s\n' "$_l" ;;
    esac
  done
}

valid_candidate() {
  [ -s "$1" ] || return 1
  head -c 5 "$1" | grep -q '<?xml' || return 1
  [ "$(grep -c '<items' "$1")" = 1 ] || return 1
  [ "$(grep -c '</items>' "$1")" = 1 ] || return 1
  grep -q "key=\"$KEY\" value=\"YES\"" "$1"
}

# Move a validated candidate into place with the owner/mode/label the system
# service needs. The caller's tmp file lives next to CFG so mv is atomic.
install_candidate() {
  _tmp="$1"
  if [ -z "$FLAG_TEST" ]; then
    chown 1000:1000 "$_tmp" && chmod 600 "$_tmp" || { rm -f "$_tmp"; log "ERR chown/chmod failed, config untouched"; return 1; }
    if [ -x /system/bin/restorecon ]; then
      /system/bin/restorecon "$_tmp" 2>/dev/null
    fi
  fi
  unlock_cfg
  mv -f "$_tmp" "$CFG" || { rm -f "$_tmp"; lock_cfg; log "ERR mv failed"; return 1; }
  if [ -z "$FLAG_TEST" ] && [ -x /system/bin/restorecon ]; then
    /system/bin/restorecon "$CFG" 2>/dev/null
  fi
  lock_cfg
  return 0
}

# Returns 0 = already correct, 10 = file changed, 1 = error (nothing changed).
ensure_flag() {
  _dir="${CFG%/*}"
  [ -d "$_dir" ] || { log "ERR $_dir missing"; return 1; }
  mkdir -p "$DATA_DIR" 2>/dev/null
  _tmp="$CFG.beekeeper_aisr_level.tmp"
  rm -f "$_tmp"

  if cfg_usable; then
    if cfg_is_yes; then lock_cfg; return 0; fi
    [ -f "$BK" ] || cp "$CFG" "$BK" 2>/dev/null
    if grep -q "key=\"$KEY\"" "$CFG"; then
      sed "s|<item key=\"$KEY\"[^>]*/>|$ITEM|" "$CFG" > "$_tmp"
      _mode=replace
    else
      insert_item < "$CFG" > "$_tmp"
      _mode=insert
    fi
  else
    [ -f "$CFG" ] && [ ! -f "$BK" ] && cp "$CFG" "$BK" 2>/dev/null
    write_template > "$_tmp"
    _mode=template
  fi

  if ! valid_candidate "$_tmp"; then
    rm -f "$_tmp"
    log "ERR candidate failed validation (mode=$_mode), config untouched"
    return 1
  fi
  install_candidate "$_tmp" || return 1
  log "OK config updated (mode=$_mode)"
  return 10
}

# Undo ensure_flag. Used by uninstall.sh.
remove_flag() {
  [ -f "$CFG" ] || return 0
  grep -q "key=\"$KEY\"" "$CFG" || return 0
  unlock_cfg
  _tmp="$CFG.beekeeper_aisr_level.tmp"
  rm -f "$_tmp"
  remove_item < "$CFG" > "$_tmp"
  if [ -s "$_tmp" ] && grep -q '<items' "$_tmp" && grep -q '</items>' "$_tmp" && ! grep -q "key=\"$KEY\"" "$_tmp"; then
    if [ -z "$FLAG_TEST" ]; then
      chown 1000:1000 "$_tmp" && chmod 600 "$_tmp" || { rm -f "$_tmp"; return 1; }
      [ -x /system/bin/restorecon ] && /system/bin/restorecon "$_tmp" 2>/dev/null
    fi
    mv -f "$_tmp" "$CFG" || { rm -f "$_tmp"; return 1; }
    [ -z "$FLAG_TEST" ] && [ -x /system/bin/restorecon ] && /system/bin/restorecon "$CFG" 2>/dev/null
    log "OK item removed"
    return 0
  fi
  rm -f "$_tmp"
  log "ERR could not strip item cleanly, config untouched"
  return 1
}
