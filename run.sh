#!/usr/bin/env bash
#
# 便捷入口：自动定位 JDK，然后原样转发给 gradlew。
#
#   ./run.sh check            # 全部测试 + 架构断言
#   ./run.sh :demo:run        # 控制台演示
#   ./run.sh verifyKernelPurity
#
set -euo pipefail
cd "$(dirname "$0")"

# 优先用 PATH 上的 java；没有就找 ~/.dev/tools 下的绿色版 JDK
if ! command -v java >/dev/null 2>&1; then
  for candidate in "$HOME"/.dev/tools/jdk-*; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi

if ! command -v java >/dev/null 2>&1; then
  cat >&2 <<'MSG'
找不到 JDK。二选一：

  1) 系统级安装
       sudo apt update && sudo apt install -y openjdk-17-jdk-headless

  2) 免安装绿色版（推荐，不动系统）
       curl -sSL -o /tmp/jdk.tgz \
         https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_linux-x64_bin.tar.gz
       mkdir -p ~/.dev/tools && tar -xzf /tmp/jdk.tgz -C ~/.dev/tools
MSG
  exit 1
fi

if [ "$#" -eq 0 ]; then
  echo "用法: ./run.sh <gradle task>"
  echo
  echo "常用:"
  echo "  ./run.sh check             全部测试 + 架构断言"
  echo "  ./run.sh :demo:run         控制台演示（轨道 A 声明式插件）"
  echo "  ./run.sh jvmTest           只跑测试"
  exit 0
fi

exec ./gradlew "$@"