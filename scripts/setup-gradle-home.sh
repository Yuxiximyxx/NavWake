#!/usr/bin/env bash
# 把 ~/.gradle 的 wrapper 分发与依赖缓存复制到工作区内，供沙箱环境构建使用。
# 只需跑一次；重复跑会跳过已存在的部分。
set -euo pipefail
cd "$(dirname "$0")/.."
GH="$PWD/.gradle-home"
SRC="${SRC_GRADLE_HOME:-$HOME/.gradle}"
mkdir -p "$GH"
[[ -f "$GH/gradle.properties" ]] || cp "$SRC/gradle.properties" "$GH/gradle.properties"
[[ -d "$GH/wrapper" ]] || cp -R "$SRC/wrapper" "$GH/wrapper"
[[ -d "$GH/caches" ]] || cp -R "$SRC/caches" "$GH/caches"
du -sh "$GH"
echo "GRADLE_USER_HOME=$GH"
