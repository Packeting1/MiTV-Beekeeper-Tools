#!/system/bin/sh
#
# 修复 Zygisk Next 漏注入主 zygote64 的问题
#
# 背景：Zygisk Next 通过 hook init 的 execve 抓 zygote，但主 zygote64 启动早于 hook 安装完成，
#       导致 zygote64 整轮开机都不被注入，LSPosed 不生效。
# 做法：late_start 阶段检查 zygote64 是否已注入；没注入就重启一次 zygote。
#       重启 zygote 时 LSPosed 守护进程(lspd)可能正好在启动，被冲掉后不会再起，
#       表现为 LSPosed 没运行、模块不生效。所以重启后会检查 lspd，没起来就拉起并再重启一次 zygote
#       （守护进程先于 system_server 存活，LSPosed 才能钩住系统服务）。最多补救 2 次。
#
# 安全措施：
#   - 每次开机最多执行一轮（用 boot_id 做标记；加锁见下面 LOCK）
#   - Zygisk Next 模块被禁用/不存在时直接退出
#
# 日志：/data/adb/lsposed_zygote64_fix.log

LOG=/data/adb/lsposed_zygote64_fix.log
FLAG=/data/adb/.zygote64_fix_bootid
# LSPosed 模块目录：旧版叫 zygisk_lsposed，Vector 叫 zygisk_vector
LSP=/data/adb/modules/zygisk_lsposed
[ -d /data/adb/modules/zygisk_vector ] && LSP=/data/adb/modules/zygisk_vector

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$LOG"; }

# 日志过大时保留最近 200 行
if [ -f "$LOG" ] && [ "$(wc -c <"$LOG")" -gt 200000 ]; then
  tail -n 200 "$LOG" >"$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
fi

BOOTID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
if [ -n "$BOOTID" ] && [ "$(cat "$FLAG" 2>/dev/null)" = "$BOOTID" ]; then
  exit 0
fi

# Magisk with Zygisk Next runs the service stage twice per boot, ~0.3 s apart, so two copies of
# this script race. An atomic symlink lets exactly one of them do the work.
LOCK=/data/adb/.zygote64_fix_lock
if [ -n "$BOOTID" ]; then
  if [ -L "$LOCK" ] && [ "$(readlink "$LOCK")" != "$BOOTID" ]; then rm -f "$LOCK"; fi
  ln -s "$BOOTID" "$LOCK" 2>/dev/null || exit 0
fi

# 等 Zygisk Next 守护进程就绪，最多 60 秒
i=0
while [ $i -lt 120 ]; do
  # Magisk's busybox ps has no NAME column; pidof works in both shells
  pidof zn-daemon >/dev/null 2>&1 && break
  i=$((i + 1))
  sleep 0.5
done
if [ $i -ge 120 ]; then
  log "zn-daemon 未出现（Zygisk Next 可能被禁用），本次不处理"
  exit 0
fi
sleep 3

# 0 = lspd 在运行且已连上系统服务（或无法确认连接，按正常处理）；1 = lspd 没运行；2 = 在运行但没连上系统服务
lsp_state() {
  [ -d "$LSP" ] && [ ! -e "$LSP/disable" ] && [ ! -e "$LSP/remove" ] || return 0
  pidof lspd >/dev/null 2>&1 || return 1
  _o=$(timeout 20 sh "$LSP/lspctl" status 2>&1)
  case "$_o" in
    *"System server: not connected"*) return 2 ;;
  esac
  return 0
}

lsp_start() {
  pidof lspd >/dev/null 2>&1 && return 0
  log "lspd 没在运行，手动拉起"
  /data/adb/magisk/busybox setsid "$LSP/daemon" --force </dev/null >/dev/null 2>&1 &
  _i=0
  while [ $_i -lt 20 ]; do
    pidof lspd >/dev/null 2>&1 && return 0
    _i=$((_i + 1))
    sleep 1
  done
  return 1
}

wait_boot() {
  sleep 30
  _i=0
  while [ $_i -lt 60 ]; do
    [ "$(getprop sys.boot_completed)" = "1" ] && break
    _i=$((_i + 1))
    sleep 1
  done
  sleep 5
}

ZPID=$(pidof zygote64)
if [ -z "$ZPID" ]; then
  log "找不到 zygote64，放弃"
  exit 0
fi

if grep -qE 'zygisk_(lsposed|vector)' /proc/$ZPID/maps 2>/dev/null; then
  log "zygote64($ZPID) 已注入，跳过"
  [ -n "$BOOTID" ] && echo "$BOOTID" >"$FLAG"
  exit 0
fi

log "zygote64($ZPID) 未注入，重启 zygote 使其走 hook"
[ -n "$BOOTID" ] && echo "$BOOTID" >"$FLAG"
setprop ctl.restart zygote
wait_boot

ZPID=$(pidof zygote64)
if [ -n "$ZPID" ] && grep -qE 'zygisk_(lsposed|vector)' /proc/$ZPID/maps 2>/dev/null; then
  log "修复成功，zygote64($ZPID) 已注入"
else
  log "修复失败，zygote64($ZPID) 仍未注入，请把日志发给开发者"
  exit 0
fi

n=0
while [ $n -lt 2 ]; do
  lsp_state
  _s=$?
  [ $_s = 0 ] && { log "LSPosed 正常（lspd 在运行）"; exit 0; }
  n=$((n + 1))
  log "LSPosed 状态异常($_s)，第 $n 次补救：确保 lspd 在运行后再重启一次 zygote"
  lsp_start || { log "lspd 拉不起来，放弃"; exit 0; }
  sleep 5
  setprop ctl.restart zygote
  wait_boot
done

lsp_state
log "补救结束，LSPosed 状态码 $?"
