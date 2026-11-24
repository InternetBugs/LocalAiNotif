#!/usr/bin/env bash
# adb watchdog: 保证无线 adb 在线后执行命令, 掉线自动 connect 重试; 命令本身带 3 次重试超时保护.
# usage: adbw.sh [timeout_s] <adb args...>   (默认 timeout 120s)
set -uo pipefail
SER="192.168.1.140:37439"
PORT=5038
if [[ "$1" =~ ^[0-9]+$ ]]; then timeout_s="$1"; shift; else timeout_s=120; fi
ADB="adb -P $PORT"
ensure_conn() {
  for a in 1 2 3 4 5; do
    if $ADB get-state 2>/dev/null | grep -q device; then return 0; fi
    echo "[adb-watchdog] device offline, reconnecting (attempt $a)..." >&2
    $ADB disconnect >/dev/null 2>&1
    $ADB connect "$SER" >/dev/null 2>&1
    sleep $((a * 2))
  done
  return 1
}
ensure_conn || { echo "[adb-watchdog] cannot reach $SER after 5 tries" >&2; exit 56; }
exec "$(dirname "$0")/retry.sh" 3 "$timeout_s" $ADB "$@"
