package dev.trellis.ui

import dev.trellis.kernel.extension.DataContext
import java.time.Instant
import java.time.ZoneId

/**
 * 手机端的「当前数据上下文」。
 *
 * 和 JVM 版逐字相同：`java.time` 在 minSdk 26 上是系统 API，不需要 desugar。
 * 之所以各自实现而不是共享一个中间源集，理由和内核的时钟一样：
 * `actual` 必须落在目标自己的源集里，多一层中间源集不划算。
 */
actual fun currentDataContext(): DataContext {
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val today = now.atZone(zone).toLocalDate()
    return DataContext(
        nowMs = now.toEpochMilli(),
        dayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli(),
        dayEndMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}
