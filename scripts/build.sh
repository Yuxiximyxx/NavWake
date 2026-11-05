#!/usr/bin/env bash
# 构建 release APK 并校验 LSPosed 声明。用法：scripts/build.sh [版本号]
set -euo pipefail

cd "$(dirname "$0")/.."

# 必须用 JDK 17+（AGP 要求）。不写死任何本机路径，但会主动挑一个够新的 JDK：
# 1) 现有 JAVA_HOME 够新就用它；2) 否则问 macOS 要 21；3) 再否则扫常见安装位置。
jdk_major() {
  [[ -x "$1/bin/java" ]] || { echo 0; return; }
  "$1/bin/java" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/'
}

if [[ -n "${JAVA_HOME:-}" ]] && (( $(jdk_major "$JAVA_HOME") < 17 )); then
  unset JAVA_HOME
fi

# 注意：/usr/libexec/java_home -v 21 在没装 21 时会「回落到」已装的旧版本，
# 所以拿到候选后必须自己校验版本，否则会静默用上 JDK 11（踩过）。
if [[ -z "${JAVA_HOME:-}" ]] && [[ -x /usr/libexec/java_home ]]; then
  candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  if [[ -n "$candidate" ]] && (( $(jdk_major "$candidate") >= 17 )); then
    export JAVA_HOME="$candidate"
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  for d in "$HOME"/jdk-2*/Contents/Home "$HOME"/jdk-2* \
           "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home \
           /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if [[ -x "$d/bin/java" ]] && (( $(jdk_major "$d") >= 17 )); then
      export JAVA_HOME="$d"
      break
    fi
  done
fi
: "${JAVA_HOME:?请设置 JAVA_HOME 指向 JDK 17+}"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/android-sdk"; do
    [[ -d "$d" ]] && export ANDROID_HOME="$d" && break
  done
fi
: "${ANDROID_HOME:?请设置 ANDROID_HOME 指向你的 Android SDK}"

# DSH 的 file sandbox 只允许写工作区内，而默认 GRADLE_USER_HOME=~/.gradle 在工作区外，
# 会被拦成 "Operation not permitted"。这里优先用工作区内的副本（scripts/setup-gradle-home.sh 生成）。
if [[ -d "$PWD/.gradle-home" ]]; then
  export GRADLE_USER_HOME="$PWD/.gradle-home"
fi
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

./gradlew :app:assembleRelease --console=plain

APK="app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || { echo "构建产物不存在：$APK" >&2; exit 1; }

mkdir -p dist
VERSION="$(grep -m1 'versionName = ' app/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')"
NAME="NavWake-${1:-$VERSION}-release.apk"
cp "$APK" "dist/$NAME"
echo "产物：dist/$NAME"
./scripts/verify-apk.sh "dist/$NAME"
