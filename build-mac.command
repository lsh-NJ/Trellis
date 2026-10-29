#!/usr/bin/env bash
#
# Finder 双击入口（macOS 的 .command 会用终端打开并执行）。
#
# 默认等同 ./build-mac.sh —— 构建 .app + .dmg。
# 想双击就直接启动应用，把下面的 "$@" 前加上 --run：
#   exec "$DIR/build-mac.sh" --run "$@"
#
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/build-mac.sh" "$@"
