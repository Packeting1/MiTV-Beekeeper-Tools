#!/system/bin/sh
#
# 修复 Zygisk Next 漏注入主 zygote64 的问题
#
# 背景：Zygisk Next 通过 hook init 的 execve 抓 zygote，但主 zygote64 启动（约 4.6s）
#       早于 hook 安装完成（约 5.2s），导致 zygote64 整轮开机都不被注入，
#       而 LSPosed 是纯 64 位框架，于是管理器报「Zygisk 未启用」。
#
# 逻辑：late_start service 阶段（hook 已就绪）检查 zygote64 是否已注入；
#       已注入 -> 什么都不做；未注入 -> 重启一次 zygote（soft reboot），
#       新起的 zygote64 就会走 hook 被正常注入。
#
# 安全措施：
#   - 每次开机最多执行一轮（用 boot_id 做标记），不会反复重启框架
#   - 最多重启 1 次 + 校验，失败只写日志，不做更多动作
#   - Zygisk Next 模块被禁用/不存在时直接退出
#
# 日志：/data/adb/lsposed_zygote64_fix.log
# 卸载：删掉本文件即可

LOG=/data/adb/lsposed_zygote64_fix.log
FLAG=/data/adb/.zygote64_fix_bootid

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >>"$LOG"; }

# A copy in service.d would run the same logic concurrently; let it own the job.
OLD_COPY="${ZFIX_OLD_SCRIPT:-/data/adb/service.d/99-zygote64-lsposed-fix.sh}"
if [ -f "$OLD_COPY" ]; then
  log "service.d 中已有同名脚本，本模块不重复执行"
  exit 0
fi

# 日志过大时保留最近 200 行
if [ -f "$LOG" ] && [ "$(wc -c <"$LOG")" -gt 200000 ]; then
  tail -n 200 "$LOG" >"$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
fi

BOOTID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
if [ -n "$BOOTID" ] && [ "$(cat "$FLAG" 2>/dev/null)" = "$BOOTID" ]; then
  exit 0
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

ZPID=$(pidof zygote64)
if [ -z "$ZPID" ]; then
  log "找不到 zygote64，放弃"
  exit 0
fi

if grep -q 'zygisk_lsposed' /proc/$ZPID/maps 2>/dev/null; then
  log "zygote64($ZPID) 已注入，跳过"
  [ -n "$BOOTID" ] && echo "$BOOTID" >"$FLAG"
  exit 0
fi

log "zygote64($ZPID) 未注入，重启 zygote 使其走 hook"
[ -n "$BOOTID" ] && echo "$BOOTID" >"$FLAG"
setprop ctl.restart zygote

# 等 framework 重启完成
sleep 30

ZPID=$(pidof zygote64)
if [ -n "$ZPID" ] && grep -q 'zygisk_lsposed' /proc/$ZPID/maps 2>/dev/null; then
  log "修复成功，zygote64($ZPID) 已注入"
else
  log "修复失败，zygote64($ZPID) 仍未注入，请把日志发给开发者"
fi
