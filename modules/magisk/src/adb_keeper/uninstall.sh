#!/system/bin/sh
pid=$(cat /dev/mitv_adb_keeper.lock/pid 2>/dev/null)
case "$pid" in ''|*[!0-9]*) exit 0 ;; esac
if tr '\000' ' ' <"/proc/$pid/cmdline" 2>/dev/null | grep -Fq '/mitv_adb_keeper/keeper.sh'; then
    kill "$pid" 2>/dev/null
fi
# Do not close the user's current ADB session during module removal.
