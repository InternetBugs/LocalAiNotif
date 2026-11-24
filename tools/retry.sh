#!/usr/bin/env bash
# watchdog retry wrapper: retry.sh <max_tries> <timeout_s> <cmd...>
# 卡住(超时)或失败自动重试, 2s 起退避; 全部失败 exit 55, 超时消化为重试而非挂死.
set -uo pipefail
max="${1:?usage: retry.sh <max_tries> <timeout_s> <cmd...>}"
timeout_s="${2:?usage: retry.sh <max_tries> <timeout_s> <cmd...>}"
shift 2
rc=0
for ((a=1; a<=max; a++)); do
  timeout "$timeout_s" "$@"; rc=$?
  if [ "$rc" -eq 0 ]; then
    [ "$a" -gt 1 ] && echo "[watchdog] ok on attempt $a"
    exit 0
  fi
  if [ "$rc" -eq 124 ]; then
    echo "[watchdog] TIMEOUT after ${timeout_s}s (attempt $a/$max): $*" >&2
  else
    echo "[watchdog] FAIL rc=$rc (attempt $a/$max): $*" >&2
  fi
  [ "$a" -lt "$max" ] && sleep $((a * 2))
done
exit 55
