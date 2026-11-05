package dev.trellis.app.desktop

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

/**
 * 计划页「待处理」的手动动作验收：本周到期没做完的两项，一项「今天做」，另一项靠
 * 「加时」补进今天；加时的答复要落进 `study.json` 供下次默认。
 *
 * 用模拟时间把「今天」钉在本周三，这样周一周二的两项一定是逾期；不起界面，只串后端与运行时。
 */
class PlanAdjustRuntimeTest {

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-plan-adjust").toFile().apply { deleteOnExit() }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.plan.adjust",
    )

    private fun weekOf(date: LocalDate): String =
        "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    @Test
    fun overdueItemsCanBeDoneTodayOrCaughtUpInOneTap() = runTest {
        val home = tempHome()
        val zone = ZoneId.systemDefault()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val today = monday.plusDays(2)
        val week = weekOf(today)

        val runtime = AppRuntime.create(home)
        try {
            runtime.applySimulatedToday(today)

            // 用同一个数据库文件、时钟也钉在模拟日期，这样铺天从本周三开始。
            val store = LearningPlanStore.open(
                File(home, "plans/plan.db").toPath(),
                { today.atStartOfDay(zone).toInstant().toEpochMilli() },
                zone,
            )
            val created = store.createProcess(
                env(),
                "待处理测试",
                "把逾期项一键补上",
                weeklyCapacityMinutes = 60 * 7,
            ).unwrap()
            val units = (1..10).map { UnitDraft("第 1 章", "第 $it 节", 20) }
            val draft = store.saveDraft(
                env(created.planId),
                created.planId,
                null,
                units,
                listOf(CommitmentDraft(week, (0..9).toList())),
            ).unwrap()
            store.acceptDraft(env(created.planId), draft.versionId, week).unwrap()
            store.scheduleWeek(env(created.planId), week).unwrap()

            // 把最靠前的两个单元挪到周一/周二：它们就是「到期没做完」的两项。
            val planned = store.week(created.planId, week).days
                .flatMap { day -> day.items.map { item -> day.day to item } }
                .filter { it.second.status == "planned" }
                .take(2)
            assertEquals(2, planned.size, "本周应当至少铺出两个待做项")
            planned.forEachIndexed { index, (_, item) ->
                store.moveOccurrence(
                    env(created.planId),
                    item.occurrenceId,
                    monday.plusDays(index.toLong()).toString(),
                    "测试：造一项逾期",
                ).unwrap()
            }

            runtime.refreshPlan()
            val overdue = assertNotNull(runtime.planOverdue, "刷新后应拿到逾期投影")
            assertEquals(2, overdue.thisWeek.size, "周一周二的两项应算逾期：${overdue.thisWeek.map { it.title to it.day }}")
            assertEquals(
                listOf(monday.toString(), monday.plusDays(1).toString()),
                overdue.thisWeek.map { it.day },
                "逾期项按路线顺序摆放",
            )

            // ① 点「今天做」：这一项立刻不再算逾期。
            runtime.planDoToday(overdue.thisWeek.first().occurrenceId)
            assertEquals(1, runtime.planOverdue?.thisWeek?.size, "做过的一项不该再出现在待处理里")

            // ② 点「+45 分钟」：剩下那项补进今天，并把加时记下来。
            runtime.planCatchUpToday(45)
            assertEquals(0, runtime.planOverdue?.thisWeek?.size, "容量够时应把剩下的逾期项补进今天")
            assertEquals(45, StudyPrefsStore(home).load().catchUpMinutes, "用户答复的加时要落盘")
        } finally {
            runtime.shutdown()
        }
    }
}
