#!/system/bin/sh
# 配置开关类模块（HGIG Fix、HDR10+、IMAX、AISR 强度、音量均衡）共用，被 source，不直接执行。
# 每个模块在 flag.conf 里给出 NAME（数据目录和日志名）和 KEY。
#
# 维护 /data/system/mitv.config.xml 里的一个 <item key="KEY" value="YES"/>：TvService 启动时先读这个文件，
# 同一个 key 先出现的值生效，所以这里的 YES 能盖过 /system/etc/mitv.config_product.xml 里的 NO（或缺项）。
# 设置应用向 TvService 查询 KEY（binder txn 82）。

. "$MODDIR/flag.conf"
DATA_DIR=/data/adb/$NAME
CFG=/data/system/mitv.config.xml
LOG=$DATA_DIR/$NAME.log
ITEM="<item key=\"$KEY\" value=\"YES\"/>"

log() {
  mkdir -p "$DATA_DIR" 2>/dev/null
  if [ -f "$LOG" ]; then
    _sz=$(wc -c < "$LOG" 2>/dev/null | tr -d ' ')
    [ "${_sz:-0}" -gt 65536 ] && : > "$LOG"
  fi
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null
}

device_ok() {
  [ "$(getprop ro.product.name)" = "beekeeper" ]
}

# 配置文件缺失或不可用时才用的完整模板（条目与原厂文件一致）。
write_template() {
  cat <<EOF
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<items token="1497612014330">
    <item key="support_bigbig_test" value="YES"/>
    <item key="support_xiaomi_tvlock" value="YES" platformid="1285"/>
    <item key="support_cold_bootup_input_source" value="NO" platformid="1330,1331,1332,1334,1337,1338"/>
    $ITEM
</items>
EOF
}

# 小米的配置下载器发现 md5 和云端不同就会删掉这个文件重下，我们的条目随之丢失。
# 不可变属性让删除和改名覆盖都失败，所以每次修改都要先解锁、改完再上锁。
unlock_cfg() {
  [ -e "$CFG" ] && chattr -i "$CFG" 2>/dev/null
  return 0
}

lock_cfg() {
  [ -f "$CFG" ] && chattr +i "$CFG" 2>/dev/null
  return 0
}

cfg_usable() {
  _f="${1:-$CFG}"
  [ -s "$_f" ] && grep -q '<items' "$_f" && grep -q '</items>' "$_f"
}

cfg_is_yes() {
  [ -f "$CFG" ] && grep -q "key=\"$KEY\" value=\"YES\"" "$CFG"
}

# 在结尾的 </items> 前插入 ITEM。结尾标签独占一行时 ITEM 另起一行（这样 remove_item 能逐字节还原），
# 和别的内容在同一行时就插在同一行。
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

# 删掉 ITEM（独占一行就整行删，否则只删条目文字）。
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

# 把校验过的候选文件换进去，并设好系统服务需要的属主、权限、标签；换完重新上锁。
# 候选文件和 CFG 在同一目录，mv 是原子的。
install_candidate() {
  _tmp="$1"
  chown 1000:1000 "$_tmp" && chmod 600 "$_tmp" || { rm -f "$_tmp"; log "ERR chown/chmod failed, config untouched"; return 1; }
  unlock_cfg
  mv -f "$_tmp" "$CFG" || { rm -f "$_tmp"; lock_cfg; log "ERR mv failed"; return 1; }
  [ -x /system/bin/restorecon ] && /system/bin/restorecon "$CFG" 2>/dev/null
  lock_cfg
  return 0
}

# 返回 0 = 本来就对，10 = 文件已改，1 = 出错（没动文件）。
ensure_flag() {
  _dir="${CFG%/*}"
  [ -d "$_dir" ] || { log "ERR $_dir missing"; return 1; }
  _tmp="$CFG.$NAME.tmp"
  rm -f "$_tmp"

  if cfg_usable; then
    if cfg_is_yes; then lock_cfg; return 0; fi
    if grep -q "key=\"$KEY\"" "$CFG"; then
      sed "s|<item key=\"$KEY\"[^>]*/>|$ITEM|" "$CFG" > "$_tmp"
      _mode=replace
    else
      insert_item < "$CFG" > "$_tmp"
      _mode=insert
    fi
  else
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

# 还有别的启用中的开关模块（有 flag.conf、没被标记删除或停用）时返回 0。
others_active() {
  for _f in /data/adb/modules/*/flag.conf; do
    [ -f "$_f" ] || continue
    _d="${_f%/*}"
    [ "$_d" != "$MODDIR" ] && [ ! -e "$_d/remove" ] && [ ! -e "$_d/disable" ] && return 0
  done
  return 1
}

# 撤销 ensure_flag，卸载时用。还有别的开关模块在用这个文件就保持上锁，否则解锁。
remove_flag() {
  grep -q "key=\"$KEY\"" "$CFG" 2>/dev/null || return 0
  _tmp="$CFG.$NAME.tmp"
  remove_item < "$CFG" > "$_tmp"
  if cfg_usable "$_tmp" && ! grep -q "key=\"$KEY\"" "$_tmp"; then
    install_candidate "$_tmp" || return 1
    others_active || unlock_cfg
    log "OK item removed"
    return 0
  fi
  rm -f "$_tmp"
  log "ERR could not strip item cleanly, config untouched"
  return 1
}
