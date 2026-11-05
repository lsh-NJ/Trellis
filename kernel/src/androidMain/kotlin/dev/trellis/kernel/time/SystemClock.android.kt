package dev.trellis.kernel.time

/**
 * 安卓上的时间源：和 JVM 是同一套调用。
 *
 * 两个都用系统 API，不引任何安卓依赖 —— 内核不应该因为跑在手机上就多认识一个平台包。
 * 分开写而不是共享，是因为 `actual` 必须落在各自目标的源集里；
 * 共享的办法是把它放进一个 jvm+android 的中间源集，那层间接在这里不值。
 */
actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformMonotonicTimeMs(): Long = System.nanoTime() / 1_000_000L
