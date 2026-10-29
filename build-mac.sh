#!/usr/bin/env bash
#
# macOS 一键构建 / 启动（Trellis）
#
#   ./build-mac.sh             构建 macOS 安装包（.app + .dmg，默认）
#   ./build-mac.sh --run       编译并启动桌面应用
#   ./build-mac.sh --check     只编译，不启动、不打包
#   ./build-mac.sh --test      全量测试 + 架构断言 verifyKernelPurity
#   ./build-mac.sh --help      显示这份说明
#
# 为什么 macOS 要单独一个脚本：
#   - 仓库原来的入口 run-app.ps1 / run-app.cmd 是 Windows 的；
#   - run-app.sh 是 WSLg 的，在 macOS 上会报「连不上显示服务」；
#   - run.sh 只是 gradlew 的薄包装，找 JDK 的逻辑偏 Linux；
#   - macOS 上 JDK 17 通常不在 PATH（brew 装在 /opt/homebrew/opt/openjdk@17）；
#   - Compose 1.7.x 在 macOS 上只接受 Dmg/Pkg 打包格式（仓库已按 OS 条件化）。
#
# 依赖：JDK 17（Temurin 或 `brew install --cask temurin@17` / `brew install openjdk@17`）。
# 找不到时脚本会直接给安装提示。
#
set -uo pipefail
cd "$(dirname "$0")" || exit 1

usage() {
    cat <<'MSG'
用法:
  ./build-mac.sh             构建 macOS 安装包（.app + .dmg，默认）
  ./build-mac.sh --run       编译并启动桌面应用
  ./build-mac.sh --check     只编译
  ./build-mac.sh --test      全量测试 + 架构断言
  ./build-mac.sh --help      显示这份说明
MSG
}

MODE="package"
case "${1:-}" in
    ""|--package|package) MODE="package" ;;
    --run|run)            MODE="run" ;;
    --check|check)        MODE="check" ;;
    --test|test)          MODE="test" ;;
    -h|--help|help)       usage; exit 0 ;;
    *)
        echo "未知参数：$1" >&2
        echo
        usage
        exit 2
        ;;
esac

if [ "$(uname -s)" != "Darwin" ]; then
    echo "这个脚本是给 macOS 的；Windows 用 run-app.cmd，Linux/WSL 用 run-app.sh。" >&2
    exit 1
fi

# ---------------------------------------------------------------- JDK 17 ----
java_major() {
    "$1" -version 2>&1 | sed -n 's/^[^"]*version "\([0-9][0-9]*\)\..*/\1/p' | head -1
}

find_jdk17() {
    local candidates=() cand home
    [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME")
    candidates+=("$HOME/.jdks/jdk17")   # Windows 习惯路径，mac 上一般是空的
    for cand in \
        "$PWD"/.toolchain/jdk-17*/Contents/Home \
        /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
        /opt/homebrew/opt/openjdk@17 \
        /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
        "$HOME"/Library/Java/JavaVirtualMachines/*17*/Contents/Home \
        /Library/Java/JavaVirtualMachines/*17*/Contents/Home \
        /opt/homebrew/Cellar/openjdk@17/*/libexec/openjdk.jdk/Contents/Home
    do
        [ -e "$cand" ] && candidates+=("$cand")
    done
    # Intel/brew 与 /usr/libexec 的官方查询方式
    home="$(/usr/libexec/java_home -v 17 2>/dev/null)" && [ -n "$home" ] && candidates+=("$home")

    for cand in "${candidates[@]}"; do
        [ -n "$cand" ] || continue
        [ -x "$cand/bin/java" ] || continue
        if [ "$(java_major "$cand/bin/java")" = "17" ]; then
            printf '%s\n' "$cand"
            return 0
        fi
    done
    return 1
}

if ! JDK_HOME="$(find_jdk17)"; then
    cat >&2 <<'MSG'
找不到 JDK 17。二选一：

  1) Homebrew（推荐）
       brew install --cask temurin@17        # 或：brew install openjdk@17

  2) 免安装压缩版
       https://adoptium.net/temurin/releases/?version=17

装完重跑本脚本；也可以手动 export JAVA_HOME=<JDK 17 目录>。
MSG
    exit 1
fi

export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
echo "JDK: $(java -version 2>&1 | head -1)"
echo "JAVA_HOME: $JAVA_HOME"

# ------------------------------------------------------------- 基础检查 ----
if [ ! -x gradlew ]; then
    chmod +x gradlew 2>/dev/null || true
fi
if [ ! -x gradlew ]; then
    echo "gradlew 不可执行，且 chmod 失败：$(pwd)/gradlew" >&2
    exit 1
fi

free_gb="$(df -Pk . | awk 'NR==2 {printf "%.0f", $4/1024/1024}')"
if [ "${free_gb:-0}" -lt 6 ]; then
    echo "警告：当前磁盘只剩 ${free_gb}GB，编译/打包可能失败（建议 ≥ 6GB）。" >&2
fi

# ---------------------------------------------------------------- 执行 ----
case "$MODE" in
    check)
        exec "$PWD/gradlew" :app:desktop:compileKotlin --console=plain
        ;;
    test)
        exec "$PWD/gradlew" check --console=plain
        ;;
    run)
        echo
        echo "启动中：窗口关闭后命令才返回；应用开着的时候不要再编译（见 README 的「铁律」）。"
        exec "$PWD/gradlew" :app:desktop:run --console=plain
        ;;
    package)
        "$PWD/gradlew" :app:desktop:createDistributable :app:desktop:packageDmg --console=plain
        status=$?
        if [ "$status" -ne 0 ]; then
            echo
            echo "打包失败。如果 jpackage/hdiutil 报错，先执行 ./gradlew --stop 再重试。" >&2
            exit "$status"
        fi
        app_path="$(ls -d app/desktop/build/compose/binaries/main/app/*.app 2>/dev/null | head -1)"
        dmg_path="$(ls -d app/desktop/build/compose/binaries/main/dmg/*.dmg 2>/dev/null | head -1)"
        echo
        echo "构建完成："
        [ -n "$app_path" ] && echo "  .app : $app_path  ($(du -sh "$app_path" | cut -f1))"
        [ -n "$dmg_path" ] && echo "  .dmg : $dmg_path  ($(du -sh "$dmg_path" | cut -f1))"
        [ -n "$dmg_path" ] && echo "  安装 : open \"$dmg_path\""
        echo
        echo "注意：本地构建是 ad-hoc 签名，本机可开；分发到别人的 Mac 需要"
        echo "      Developer ID 签名 + 公证（notarize），否则会被 Gatekeeper 拦。"
        ;;
esac
