package dev.trellis.ai.manager

/**
 * 安卓上没有「环境变量」这个东西：`System.getenv` 在应用进程里几乎恒为 null，
 * 也没有 `.env` 文件可读。
 *
 * 所以这个实验开关在手机上**恒为关闭**，返回值写成明确的 `false` 而不是
 * 去读一个注定读不到的变量 —— 后者会让下一个人以为「设个变量就能开」。
 */
actual fun stableToolsEnabled(): Boolean = false
