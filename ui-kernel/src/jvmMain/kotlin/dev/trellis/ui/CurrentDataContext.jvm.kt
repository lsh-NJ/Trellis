package dev.trellis.ui

import dev.trellis.kernel.extension.DataContext
import java.time.Instant
import java.time.ZoneId

actual fun currentDataContext(): DataContext {
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val today = now.atZone(zone).toLocalDate()
    return DataContext(
        nowMs = now.toEpochMilli(),
        dayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli(),
        // 「明天零点」而不是「今天 23:59:59.999」 半开区间，不会有边界上的重复计数
        dayEndMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}
