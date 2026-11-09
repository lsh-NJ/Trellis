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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 计划页 phase 2 的运行时验收：周总结 / 放弃计划两条新链路都只经过 `AppRuntime` 的计划页状态，
 * 页面本身不直接摸后端。阶段条只做展示（后端现算 done/total），这里不重复测后端。
 */
class PlanPageRuntimeTest {

    private class Harness(
        val runtime: AppRuntime,
        val store: LearningPlanService,
        val planId: String,
    )

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-plan-page").toFile().apply { deleteOnExit() }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.plan.page",
    )

    private fun weekOf(date: LocalDate): String =
        "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    /** 起运行时 → 把「今天」钉住 → 在同一个 plan.db 里建一个计划（当前周有两个单元）。 */
    private suspend fun harness(home: File, today: LocalDate): Harness {
        val zone = ZoneId.systemDefault()
        val runtime = AppRuntime.create(home)
        runtime.applySimulatedToday(today)
        val store: LearningPlanService = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { runtime.clock.nowMs() },
            zone,
        )
        val created = store.createProcess(
            env(),
            "计划页验收",
            "周总结与放弃计划",
            weeklyCapacityMinutes = 60 * 7,
        ).unwrap()
        val week = weekOf(today)
        val draft = store.saveDraft(
            env(created.planId),
            created.planId,
            null,
            listOf(
                UnitDraft("第 1 章", "第 1 节", 20),
                UnitDraft("第 2 章", "第 2 节", 20),
            ),
            listOf(CommitmentDraft(week, listOf(0, 1))),
        ).unwrap()
        store.acceptDraft(env(created.planId), draft.versionId, week).unwrap()
        return Harness(runtime, store, created.planId)
    }

    /** 放弃之后：列表状态映射成 abandoned、页面给一句明确反馈，这是左栏灰行与页头提示的依据。 */
    @Test
    fun abandoningAPlanMarksItAbandoned() = runTest {
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val harness = harness(tempHome(), monday)
        try {
            harness.runtime.refreshPlan()
            harness.runtime.abandonPlan(harness.planId)
            val listed = harness.runtime.planAll.firstOrNull { it.id == harness.planId }
            assertEquals("abandoned", listed?.status, "放弃后 plans() 要把它映射成 abandoned")
            assertTrue(
                harness.runtime.planMessage?.contains("已放弃") == true,
                "页面要告诉用户这个计划已经放弃：${harness.runtime.planMessage}",
            )
        } finally {
            harness.runtime.shutdown()
        }
    }

    /** 周日刷新后 `planReviewableWeek` 就是本周；直接生成总结，选中周立刻能读到复盘正文。 */
    @Test
    fun sundayOffersThisWeekForReviewAndGeneration() = runTest {
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sunday = monday.plusDays(6)
        val harness = harness(tempHome(), sunday)
        try {
            harness.runtime.refreshPlan()
            val week = weekOf(sunday)
            assertEquals(week, harness.runtime.planReviewableWeek, "周日当天应把本周交出来做总结")
            harness.runtime.generateWeekReview(week)
            assertTrue(
                harness.runtime.planMessage?.contains("本周总结") == true,
                "生成成功要有反馈：${harness.runtime.planMessage}",
            )
            val review = assertNotNull(harness.runtime.planReview, "生成后选中周应拿到复盘正文")
            assertEquals("ready", review.state)
        } finally {
            harness.runtime.shutdown()
        }
    }

    /**
     * 切到「还没有任何一周」的空壳计划：上一份计划的周/天状态必须清掉。
     * 现场 bug：头部写「0 周」，周一却还挂着上一份计划的「6 件」，点开又说「这一天还没有排内容」。
     */
    @Test
    fun switchingToAShellPlanClearsThePreviousPlansDays() = runTest {
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val h = harness(tempHome(), monday)
        h.runtime.selectPlan(h.planId)
        assertTrue(h.runtime.planDays.isNotEmpty(), "预置计划本周有内容，先让页面有残留状态")

        val shell = h.store.createShell(env(), "空壳计划", "只有目标和大纲").unwrap()
        h.runtime.refreshPlan(focus = shell.planId)

        assertEquals(shell.planId, h.runtime.planFocusId)
        assertTrue(h.runtime.planDays.isEmpty(), "空壳计划不该沿用上一份计划的天数据")
        assertEquals(null, h.runtime.planSelectedWeek)
        assertEquals(null, h.runtime.planSelectedDay)
        assertEquals(null, h.runtime.planDayView)
    }
}

/**
 * 右栏显示条件的回归：计划页被外壳限宽在 1120dp（再减左右各 20dp 内边距），
 * 1440pt 的窗口里页面最多量到 1080dp——拿页面宽度判会误判成「窄屏」，必须用窗口宽度。
 */
class PlanStageRailVisibilityTest {

    @Test
    fun railNeedsABroadWindowAndAtLeastOneStage() {
        // 窗口宽度 1440pt（页面内容列只有 1080dp）：旧写法拿 maxWidth 判会落到这里，误判成不显示。
        assertTrue(shouldShowStageRail(windowWidthDp = 1440f, stageCount = 3), "宽窗 + 有阶段要显示右栏")
        assertTrue(shouldShowStageRail(windowWidthDp = 1100f, stageCount = 1), "刚好到断点也算宽窗")
        // 窗口本身不到断点、或者没有阶段：不显示。
        assertFalse(shouldShowStageRail(windowWidthDp = 1099f, stageCount = 3))
        assertFalse(shouldShowStageRail(windowWidthDp = 1080f, stageCount = 3))
        assertFalse(shouldShowStageRail(windowWidthDp = 1440f, stageCount = 0))
    }
}
