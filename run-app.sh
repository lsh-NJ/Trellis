#!/usr/bin/env bash
#
# 启动桌面应用。需要 WSLg（见 ~/.wslconfig 里的 guiApplications）。
#
#   ./run-app.sh          # 直接跑
#   ./run-app.sh --check  # 只编译不运行
#
set -uo pipefail
cd "$(dirname "$0")"

# JDK
if ! command -v java >/dev/null 2>&1; then
  for c in "$HOME"/.dev/tools/jdk-*; do
    [ -x "$c/bin/java" ] && export JAVA_HOME="$c" && export PATH="$JAVA_HOME/bin:$PATH" && break
  done
fi
if ! command -v java >/dev/null 2>&1; then
  echo "找不到 JDK，先跑一次 ./run.sh 看看提示" >&2
  exit 1
fi

# WSLg 的显示环境
if [ -z "${DISPLAY:-}" ]; then
  export DISPLAY=:0
fi
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
if [ -z "${XDG_RUNTIME_DIR:-}" ] || [ ! -d "${XDG_RUNTIME_DIR:-}" ]; then
  export XDG_RUNTIME_DIR=/mnt/wslg/runtime-dir
fi

if [ ! -S "/tmp/.X11-unix/X${DISPLAY#:}" ] && [ ! -S "/mnt/wslg/.X11-unix/X${DISPLAY#:}" ] && [ "${1:-}" != "--check" ]; then
  cat >&2 <<'MSG'
连不上显示服务。

  WSLg 没在跑。检查 Windows 的 C:\Users\<你>\.wslconfig：

      [wsl2]
      guiApplications=true

  改完之后在 Windows PowerShell 里执行：

      wsl --shutdown

  等十秒，WSL 再启动时 WSLg 就会起来，然后重新跑这个脚本。
MSG
  exit 1
fi

# WSLg 没有硬件 OpenGL：Skiko 会尝试建 GL 上下文并失败（RenderException）。
# 检测到 WSLg 就切软件渲染。真实桌面上不会走到这里。
if [ -d /mnt/wslg ] && [ -z "${SKIKO_RENDER_API:-}" ]; then
  export SKIKO_RENDER_API=SOFTWARE_FAST
  echo "(WSLg 环境：使用软件渲染 SKIKO_RENDER_API=SOFTWARE_FAST)"
fi

if [ "${1:-}" = "--check" ]; then
  exec ./gradlew :app:desktop:compileKotlin --console=plain
fi

exec ./gradlew :app:desktop:run --console=plain