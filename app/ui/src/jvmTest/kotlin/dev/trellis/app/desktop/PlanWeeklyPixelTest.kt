package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.domain.plan.WeekDay
import dev.trellis.domain.plan.WeekDayItem
import dev.trellis.domain.plan.LearningPlanService
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
import kotlinx.coroutines.runBlocking

class PlanWeeklyPixelTest {

    private fun item(
        id: String,
        title: String,
        minutes: Int,
        status: String = "planned",
    ) = WeekDayItem(
        occurrenceId = id,
        title = title,
        status = status,
        minutes = minutes,
    )

    @Test
    fun weeklyItemsKeepOrderAndUseTheThreePlanBands() {
        val days = listOf(
            WeekDay(
                day = "2026-11-09",
                label = "周一",
                today = false,
                items = listOf(
                    item("a", "小任务", 15, status = "done"),
                    item("b", "中任务", 30),
                    item("c", "大任务", 45),
                ),
            ),
        )

        val tasks = planWeeklyPixelTasks(days, emptyList(), queueMode = false)

        assertEquals(listOf(1, 2, 3), tasks.map { it.weight })
        assertEquals(
            listOf(
                PlanWeeklyPixelState.DONE,
                PlanWeeklyPixelState.NEXT,
                PlanWeeklyPixelState.TODO,
            ),
            tasks.map { it.state },
        )
        assertEquals(listOf("a", "b", "c"), tasks.map { it.id })
    }

    @Test
    fun theRoundedTaskOutlineCoversTheWholeJoinedShape() {
        val path = roundedTaskPath(
            cells = listOf(
                PixelCell(0, 0),
                PixelCell(1, 0),
                PixelCell(0, 1),
            ),
            unit = 9f,
            gap = 3f,
            radius = 2f,
        )
        val bounds = path.getBounds()

        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(0f, bounds.top, 0.01f)
        // 两格宽：9 + 3 + 9 = 21；两格高同理。
        assertEquals(21f, bounds.right, 0.01f)
        assertEquals(21f, bounds.bottom, 0.01f)
    }

    @Test
    fun twoRowsArePreferredForTheWeeklyBar() {
        val tasks = sampleTasks()
        val packing = assertNotNull(packWeeklyPixelTasks(tasks))

        assertEquals(2, packing.height)
        assertEquals(8, packing.width)
        assertEquals(1, packing.holes)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun rendersTheWeeklyPixelBarOffscreen() {
        val scene = ImageComposeScene(width = 1800, height = 420, density = Density(3f)) {
            AppTheme(prefs = UiPrefs(), dark = false) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp),
                ) {
                    PlanWeeklyPixelProgress(sampleTasks())
                }
            }
        }
        try {
            val image = scene.render()
            val bytes = image.encodeToData()?.bytes
            assertTrue(bytes != null && bytes.isNotEmpty(), "周像素进度没有渲染出图")
            val out = File("build/ui-shot-plan-weekly-pixels.png")
            out.parentFile?.mkdirs()
            out.writeBytes(bytes!!)
            assertTrue(bytes.size > 4_000, "截图太小，八成是空白：" + bytes.size)
            println("  截图已写出: " + out.absolutePath + "  (" + bytes.size + " 字节)")
        } finally {
            scene.close()
        }
    }

    private fun sampleTasks(): List<PlanWeeklyPixelTask> = listOf(
        PlanWeeklyPixelTask("01", "跑通最小检索", 1, PlanWeeklyPixelState.DONE),
        PlanWeeklyPixelTask("02", "整理 chunking 笔记", 2, PlanWeeklyPixelState.DONE),
        PlanWeeklyPixelTask("03", "搭向量索引", 3, PlanWeeklyPixelState.DONE),
        PlanWeeklyPixelTask("04", "写评估问题", 2, PlanWeeklyPixelState.DONE),
        PlanWeeklyPixelTask("05", "回顾 RAG 链路", 1, PlanWeeklyPixelState.DONE),
        PlanWeeklyPixelTask("06", "跑 baseline 评估", 3, PlanWeeklyPixelState.NEXT),
        PlanWeeklyPixelTask("07", "记录失败案例", 2, PlanWeeklyPixelState.TODO),
        PlanWeeklyPixelTask("08", "周日复盘", 1, PlanWeeklyPixelState.TODO),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun rendersTheIntegratedWeeklyPixelBarOnThePlanPage() = runBlocking {
        val home = Files.createTempDirectory("trellis-weekly-pixel-screen").toFile()
        val runtime = AppRuntime.create(home)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val store: LearningPlanService = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { runtime.clock.nowMs() },
            zone,
        )
        fun env(planId: String? = null) = CommandEnvelope(
            commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
            actor = Actor.USER,
            planId = planId,
            origin = "test.plan.weekly-pixels",
        )
        fun <T> CommandResult<T>.unwrap(): T {
            if (!ok) error("命令失败：" + error?.code + " " + error?.message)
            return value!!
        }
        try {
            val created = store.createProcess(
                env(),
                "像素周进度验收",
                "给计划页接入算法生成的周像素条",
                weeklyCapacityMinutes = 60 * 7,
            ).unwrap()
            val minutes = listOf(15, 30, 45, 15, 30, 45, 15, 30)
            val units = minutes.mapIndexed { index, value ->
                UnitDraft(
                    chapter = "第 ${index + 1} 节",
                    title = "任务 ${index + 1}",
                    estimateMinutes = value,
                )
            }
            val week = "%04d-W%02d".format(
                today.get(IsoFields.WEEK_BASED_YEAR),
                today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
            )
            val draft = store.saveDraft(
                env(created.planId),
                created.planId,
                null,
                units,
                listOf(CommitmentDraft(week, units.indices.toList())),
            ).unwrap()
            store.acceptDraft(env(created.planId), draft.versionId, week).unwrap()

            runtime.refreshPlan()
            val pending = runtime.planDays.flatMap { it.items }
                .filter { it.status != "done" }
                .take(5)
            pending.forEach { runtime.completePlanOccurrence(it.occurrenceId) }
            runtime.refreshPlan()
            runtime.currentTab = TAB_PLAN

            val scene = ImageComposeScene(width = 1440, height = 900, density = Density(1f)) {
                AppTheme(
                    prefs = runtime.uiPrefs,
                    themeScheme = runtime.themeScheme(false),
                    dark = false,
                    chrome = runtime.chromeStyle(),
                    extra = runtime.extraColors(false),
                ) {
                    AppScreen(runtime)
                }
            }
            try {
                val bytes = scene.render().encodeToData()?.bytes
                assertTrue(bytes != null && bytes.isNotEmpty(), "计划页没有渲染出图")
                val out = File("build/ui-shot-plan-weekly-pixels-integrated.png")
                out.parentFile?.mkdirs()
                out.writeBytes(bytes!!)
                assertTrue(bytes.size > 5_000, "计划页截图太小，八成是空白：" + bytes.size)
                println("  截图已写出: " + out.absolutePath + "  (" + bytes.size + " 字节)")
            } finally {
                scene.close()
            }
        } finally {
            runtime.shutdown()
            store.close()
            home.deleteRecursively()
        }
    }
}
