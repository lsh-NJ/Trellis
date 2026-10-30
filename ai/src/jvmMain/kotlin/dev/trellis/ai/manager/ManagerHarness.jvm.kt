package dev.trellis.ai.manager

/**
 * 桌面端（JVM）读环境变量；`TRELLIS_MANAGER_STABLE_TOOLS=1` 才算打开。
 *
 * 刻意只认 "1"：写成 "true"/"yes" 不算 —— 验收脚本里变量拼错时，
 * 应该表现为「实验没打开」，而不是悄悄打开。
 */
actual fun stableToolsEnabled(): Boolean =
    System.getenv(STABLE_TOOLS_ENV)?.trim() == "1"
