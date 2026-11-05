package dev.trellis.feature.learning

import dev.trellis.domain.plan.LearningPlanService
import java.nio.file.Path
import java.time.ZoneId

/**
 * 学习计划服务的对外入口：桌面端只认这一句，不直接摸 SQLite。
 *
 * 文件布局（单用户、单库）：`<trellis home>/plans/plan.db`。
 */
object LearningPlanStore {

    fun open(
        file: Path,
        clock: () -> Long = System::currentTimeMillis,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LearningPlanService = SqliteLearningPlanService(PlanDatabase.open(file), clock, zone)

    /** 测试用：内存库。 */
    fun inMemory(
        clock: () -> Long = System::currentTimeMillis,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LearningPlanService = SqliteLearningPlanService(PlanDatabase.inMemory(), clock, zone)
}
