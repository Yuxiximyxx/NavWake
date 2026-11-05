#!/usr/bin/env bash
# 校验 APK：LSPosed 声明三件套 + 签名 + 基本信息。用法：scripts/verify-apk.sh <apk>
set -euo pipefail

APK="${1:?用法: scripts/verify-apk.sh <apk>}"
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/android-sdk"; do
    [[ -d "$d" ]] && export ANDROID_HOME="$d" && break
  done
fi
: "${ANDROID_HOME:?请设置 ANDROID_HOME 指向你的 Android SDK}"
BT="$ANDROID_HOME/build-tools/34.0.0"

# 一次性取条目列表：不要用 `unzip -l | grep -q`（grep 提前退出会让 unzip 吃 SIGPIPE，
# 在 pipefail 下整个管道判失败，表现为「文件明明存在却报缺失」）。
ENTRIES="$(unzip -Z1 "$APK")"
fail=0

check_zip() {
  if grep -qx "META-INF/xposed/$1" <<<"$ENTRIES"; then
    echo "  [OK] META-INF/xposed/$1"
  else
    echo "  [FAIL] 缺少 META-INF/xposed/$1" >&2
    fail=1
  fi
}

echo "校验 $APK"
check_zip java_init.list
check_zip module.prop
check_zip scope.list

echo "作用域："
unzip -p "$APK" META-INF/xposed/scope.list | sed 's/^/  /'
echo "入口类："
unzip -p "$APK" META-INF/xposed/java_init.list | sed 's/^/  /'

echo "签名："
"$BT/apksigner" verify --print-certs "$APK" | head -3 | sed 's/^/  /'
echo "基本信息："
"$BT/aapt2" dump badging "$APK" 2>/dev/null | grep -E "^package|launchable-activity" | sed 's/^/  /'

if [[ "$fail" -ne 0 ]]; then
  echo "校验失败" >&2
  exit 1
fi
echo "校验通过"
