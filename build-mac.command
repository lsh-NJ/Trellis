#!/usr/bin/env bash
#
# Finder 双击入口（macOS 的 .command 会用终端打开并执行）。
#
# 默认等同 ./build-mac.sh —— 构建 .app + .dmg。
# 想双击就构建并启动打包产物，把下面改成：exec "$DIR/build-mac.sh" --launch "$@"
# 想双击就直接跑开发版（gradlew run，不打包），改成：exec "$DIR/build-mac.sh" --run "$@"
#
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/build-mac.sh" "$@"
