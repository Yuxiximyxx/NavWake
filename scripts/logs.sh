#!/usr/bin/env bash
# 看 NavWake 的阶段 0 日志。用法：scripts/logs.sh [lspd|logcat|all]
#   lspd   —— 读 LSPosed 框架日志（模块有没有被注入、探针挂载情况）【默认】
#   logcat —— 跟 adb logcat，看长按瞬间的时序
set -uo pipefail

MODE="${1:-lspd}"

case "$MODE" in
  lspd)
    echo "== LSPosed 框架日志（Ctrl-C 退出）=="
    echo "   关心：ENTRY LOADED / installed N/M / 探针就绪 / ★★ 命中"
    adb shell su -c 'ls -t /data/adb/lspd/log/modules_*.log | head -1' | tr -d '\r' | while read -r f; do
      echo "   正在跟：$f"
      adb shell su -c "tail -f '$f'" | grep --line-buffered -E "NavWake|HookEntry|ENTRY LOADED|installed |探针|★★"
    done
    ;;
  logcat)
    echo "== logcat 时序（Ctrl-C 退出）=="
    adb logcat -c 2>/dev/null || true
    adb logcat -v time | grep --line-buffered -iE "NavWake|Assist|startActivity|KeyAction|brain|magicvoice|AndroidRuntime|FATAL"
    ;;
  all)
    "$0" lspd & "$0" logcat
    ;;
  *)
    echo "用法: scripts/logs.sh [lspd|logcat|all]" >&2; exit 1
    ;;
esac
