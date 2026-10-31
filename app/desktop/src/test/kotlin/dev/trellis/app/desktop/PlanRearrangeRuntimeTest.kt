package dev.trellis.app.desktop

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 拖拽重排的运行时验收：侧栏拖到某天、拖完按路线索引排序、过去/跨周要拒绝。
 *
 * 只串后端命令与 `AppRuntime`，不起界面；拖拽手势本身的坐标判定在 PlanRearrange.kt 里，
 * 这里验证的是「松手之后会发生什么」。
 */
class PlanRearrangeRuntimeTest {

    private class Harness(
        val runtime: AppRuntime,
        val store: LearningPlanService,
        val planId: String,
    )

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-plan-rearrange").toFile().apply { deleteOnExit() }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.plan.rearrange",
    )

    private fun weekOf(date: LocalDate): String =
        "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    /**
     * 起运行时 → 把「今天」钉住 → 在同一个 plan.db 里建一个计划并接受草稿。
     *
     * 时钟也钉在 [today]：铺天、逾期投影、结算都按这个日期算。
     */
    private suspend fun harness(
        home: File,
        today: LocalDate,
        units: List<UnitDraft>,
        commitments: List<CommitmentDraft>,
    ): Harness {
        val zone = ZoneId.systemDefault()
        val runtime = AppRuntime.create(home)
        runtime.applySimulatedToday(today)
        val store: LearningPlanService = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { today.atStartOfDay(zone).toInstant().toEpochMilli() },
            zone,
        )
        val created = store.createProcess(
            env(),
            "重排测试",
            "拖拽重排验收",
            weeklyCapacityMinutes = 60 * 7,
        ).unwrap()
        val draft = store.saveDraft(
            env(created.planId),
            created.planId,
            null,
            units,
            commitments,
        ).unwrap()
        store.acceptDraft(env(created.planId), draft.versionId, commitments.first().week).unwrap()
        return Harness(runtime, store, created.planId)
    }

    @Test
    fun theDayListFollowsTheRouteOrderNotCreationOrder() = runTest {
        val home = tempHome()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val today = monday
        val week = weekOf(today)
        val harness = harness(
            home = home,
            today = today,
            units = (1..3).map { UnitDraft("第 1 章", "第 $it 节", 20) },
            commitments = listOf(CommitmentDraft(week, listOf(0, 1, 2))),
        )
        try {
            harness.store.scheduleWeek(env(harness.planId), week).unwrap()
            harness.runtime.refreshPlan()
            harness.runtime.selectPlanDay(monday.toString())
            val before = assertNotNull(harness.runtime.planDayView, "周一应铺出三条")
                .occurrences.map { it.title }
            assertEquals(listOf("第 1 节", "第 2 节", "第 3 节"), before, "初始顺序就是路线顺序")

            // 把路线第一条挪到周二再挪回周一：occurrence 是新建的（created_at 最晚），
            // 但路线索引没变，所以它必须还排在最前面。
            val first = assertNotNull(harness.runtime.planDayView)
                .occurrences.first { it.title == "第 1 节" }
            harness.runtime.planDragOccurrenceToDay(first.id, monday.plusDays(1).toString())
            harness.runtime.selectPlanDay(monday.plusDays(1).toString())
            val moved = assertNotNull(harness.runtime.planDayView, "周二应收到挪过去的节点")
                .occurrences.first { it.title == "第 1 节" }
            harness.runtime.planDragOccurrenceToDay(moved.id, monday.toString())

            harness.runtime.selectPlanDay(monday.toString())
            assertEquals(
                listOf("第 1 节", "第 2 节", "第 3 节"),
                assertNotNull(harness.runtime.planDayView).occurrences.map { it.title },
                "重排之后仍按路线索引排，而不是按创建时间（hint=${harness.runtime.planRearrangeHint}）",
            )
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun draggingAnOverdueItemMovesItToTheTargetDay() = runTest {
        val home = tempHome()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val today = monday.plusDays(2)
        val week = weekOf(today)
        val harness = harness(
            home = home,
            today = today,
            units = (1..10).map { UnitDraft("第 1 章", "第 $it 节", 20) },
            commitments = listOf(CommitmentDraft(week, (0..9).toList())),
        )
        try {
            harness.store.scheduleWeek(env(harness.planId), week).unwrap()
            // 把最靠前的两条挪到周一/周二：它们就是「本周逾期」。
            val planned = harness.store.week(harness.planId, week).days
                .flatMap { day -> day.items.map { item -> item } }
                .filter { it.status == "planned" }
                .take(2)
            assertEquals(2, planned.size, "本周应至少铺出两条待做")
            planned.forEachIndexed { index, item ->
                harness.store.moveOccurrence(
                    env(harness.planId),
                    item.occurrenceId,
                    monday.plusDays(index.toLong()).toString(),
                    "测试：造一项逾期",
                ).unwrap()
            }

            harness.runtime.refreshPlan()
            val overdue = assertNotNull(harness.runtime.planOverdue, "刷新后应拿到逾期投影")
            assertEquals(2, overdue.thisWeek.size, "周一周二的两项应算逾期")
            val item = overdue.thisWeek.first()

            harness.runtime.planDragOccurrenceToDay(item.occurrenceId, today.toString())
            assertEquals(1, harness.runtime.planOverdue?.thisWeek?.size, "拖走的那项不该再算逾期")
            assertTrue(
                harness.runtime.planRearrangeHint?.contains("已挪到") == true,
                "成功提示要留在侧栏里：${harness.runtime.planRearrangeHint}",
            )
            assertTrue(
                assertNotNull(harness.runtime.planDayView, "选中的今天应有内容")
                    .occurrences.any { it.title == item.title },
                "拖到今天的节点应出现在今天的列表里",
            )
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun draggingAPoolUnitPlacesItOnThatDay() = runTest {
        val home = tempHome()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val today = monday.plusDays(2)
        val week = weekOf(today)
        val nextWeek = weekOf(monday.plusWeeks(1))
        val harness = harness(
            home = home,
            today = today,
            units = (1..10).map { UnitDraft("第 1 章", "第 $it 节", 20) },
            // 本周只承诺前三个、下周再占三个；剩下四个留在「待安排」池里等用户拖。
            // 下周的承诺必须显式给：节奏推进会给「没有承诺的下一周」自动从池里补人，
            // 池就会被抽空，看不出拖拽的效果。
            commitments = listOf(
                CommitmentDraft(week, listOf(0, 1, 2)),
                CommitmentDraft(nextWeek, listOf(3, 4, 5)),
            ),
        )
        try {
            harness.store.scheduleWeek(env(harness.planId), week).unwrap()
            harness.runtime.refreshPlan()
            val pool = harness.runtime.planPool
            assertTrue(pool.isNotEmpty(), "只承诺三个单元时池里应还有剩余")
            val unit = pool.first()

            harness.runtime.planPlaceUnitToDay(unit.id, today.toString())
            assertTrue(
                assertNotNull(harness.runtime.planDayView, "选中的今天应有内容")
                    .occurrences.any { it.title == unit.title },
                "拖进来的单元应出现在今天的列表里",
            )
            assertTrue(
                harness.runtime.planPool.none { it.id == unit.id },
                "排上之后不该再留在「待安排」里",
            )

            // 再拖一次：已经有 planned occurrence 了，应给提示而不是重复铺一条。
            harness.runtime.planPlaceUnitToDay(unit.id, today.toString())
            assertTrue(
                harness.runtime.planRearrangeHint?.contains("已经排") == true,
                "重复拖应提示已经排过：${harness.runtime.planRearrangeHint}",
            )
            assertTrue(harness.runtime.planRearrangeHintIsError, "重复拖应标成错误色")
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun pastDaysAndCrossWeekDropsAreRejected() = runTest {
        val home = tempHome()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val today = monday.plusDays(2)
        val week = weekOf(today)
        val harness = harness(
            home = home,
            today = today,
            units = (1..10).map { UnitDraft("第 1 章", "第 $it 节", 20) },
            commitments = listOf(CommitmentDraft(week, (0..9).toList())),
        )
        try {
            harness.store.scheduleWeek(env(harness.planId), week).unwrap()
            val planned = harness.store.week(harness.planId, week).days
                .flatMap { day -> day.items.map { item -> item } }
                .filter { it.status == "planned" }
                .take(2)
            planned.forEachIndexed { index, item ->
                harness.store.moveOccurrence(
                    env(harness.planId),
                    item.occurrenceId,
                    monday.plusDays(index.toLong()).toString(),
                    "测试：造一项逾期",
                ).unwrap()
            }
            harness.runtime.refreshPlan()
            val item = assertNotNull(harness.runtime.planOverdue).thisWeek.first()

            harness.runtime.planDragOccurrenceToDay(item.occurrenceId, monday.minusDays(1).toString())
            assertTrue(
                harness.runtime.planMessage?.contains("过去的日期不能重排") == true,
                "拖到过去日期应拒绝：${harness.runtime.planMessage}",
            )
            assertEquals(2, harness.runtime.planOverdue?.thisWeek?.size, "被拒绝的拖拽不该改数据")

            harness.runtime.planDragOccurrenceToDay(item.occurrenceId, monday.plusDays(7).toString())
            assertTrue(
                harness.runtime.planRearrangeHint?.contains("同一周") == true,
                "跨周拖拽应提示只在同一周里挪：${harness.runtime.planRearrangeHint}",
            )
            assertEquals(2, harness.runtime.planOverdue?.thisWeek?.size, "跨周被拒绝后数据不变")
        } finally {
            harness.runtime.shutdown()
        }
    }
}
