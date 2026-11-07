package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.OccurrenceRef
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.domain.plan.fail
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.feature.learning.PlanDocStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `plan_week` 的回归测试：先把这一周的活**写进本周文档**，再把条目追加成单元、铺到天。
 *
 * 顺序是这条链路的核心不变量：文档写不成 → 整周不排；文档写了但落库失败 → 文档保留、明确报错。
 * 用 spy 包一层 [LearningPlanService]，在 appendUnits / placeUnit 那一刻读一次周文档，
 * 断言「那时候文档已经在了」——而不是只看最终状态（最终状态两种顺序都长得一样）。
 */
class PlanWeekToolsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-04 是周三，所在周 = 2026-W45。 */
    private val wednesday: LocalDate = LocalDate.parse("2026-11-04")
    private val thisWeek: String = "2026-W45"

    private class FixedClock(private val date: LocalDate, override val zone: ZoneId) : TrellisClock {
        private val millis: Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

        override fun nowMs(): Long = millis
        override fun monotonicMs(): Long = millis
        override fun today(): LocalDate = date
        override fun isoWeek(): String = "%04d-W%02d".format(
            date.get(IsoFields.WEEK_BASED_YEAR),
            date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
        override fun simulatedDate(): LocalDate? = null
    }

    private class FakeLlm(private val reply: String) : LlmClient {
        val seen = mutableListOf<List<LlmMessage>>()

        override suspend fun complete(messages: List<LlmMessage>): String {
            seen += messages
            return reply
        }
    }

    /** 记录「append/place 的那一刻，周文档在不在、有没有条目」。 */
    private class Spy(
        private val delegate: LearningPlanService,
        private val docStore: PlanDocStore,
        private val planId: String,
        private val week: String,
    ) : LearningPlanService by delegate {
        val calls = mutableListOf<String>()
        var docAtAppend: String? = null
        var docAtFirstPlace: String? = null

        override suspend fun appendUnits(
            env: CommandEnvelope,
            planId: String,
            drafts: List<UnitDraft>,
        ): CommandResult<List<String>> {
            docAtAppend = docStore.readWeek(this.planId, week)
            calls += "appendUnits"
            return delegate.appendUnits(env, planId, drafts)
        }

        override fun placeUnit(
            env: CommandEnvelope,
            planId: String,
            week: String,
            unitId: String,
            day: String,
        ): CommandResult<OccurrenceRef> {
            if (calls.none { it.startsWith("placeUnit") }) {
                docAtFirstPlace = docStore.readWeek(this.planId, this.week)
            }
            calls += "placeUnit:$day"
            return delegate.placeUnit(env, planId, week, unitId, day)
        }
    }

    private class Harness(
        val service: LearningPlanService,
        val docStore: PlanDocStore,
        val planId: String,
        val clock: TrellisClock,
        val llm: FakeLlm,
    )

    private fun env(): CommandEnvelope = CommandEnvelope(
        commandId = "cmd_test_" + UUID.randomUUID(),
        actor = Actor.USER,
        origin = "test",
    )

    private suspend fun newHarness(reply: String = weekItemsJson()): Harness {
        val home = Files.createTempDirectory("plan-week-tools").toFile()
        val clock = FixedClock(wednesday, zone)
        // 计划库用内存实现；周文档要落盘，给它一个临时 home。
        val base = LearningPlanStore.inMemory({ clock.nowMs() }, zone)
        val created = base.createShell(env(), "操作系统速通", "两周围绕进程与调度")
        assertTrue(created.ok, "建空壳计划失败：" + created.error?.message)
        val planId = created.value!!.planId
        return Harness(base, PlanDocStore(home), planId, clock, FakeLlm(reply))
    }

    private fun weekTool(h: Harness, service: LearningPlanService = h.service, onChanged: () -> Unit = {}) =
        PlanWeekTools({ service }, { h.docStore }, { 60 * 7 }, h.clock, h.llm, onChanged)
            .all.single { it.name == "plan_week" }

    /** 模型给的一周条目（3 条，共 85 分钟，一天装得下）。 */
    private fun weekItemsJson(includeFocus: Boolean = true): String = buildString {
        append("{")
        if (includeFocus) append("\"focus\": \"进程与调度\",")
        append(
            """
            "items": [
                {"title": "进程概念", "chapter": "第2章", "kind": "study", "minutes": 30},
                {"title": "调度算法", "chapter": "第2章", "kind": "practice", "minutes": 40},
                {"title": "本周复盘", "kind": "review", "minutes": 15}
            ]
            }
            """.trimIndent(),
        )
    }

    private fun weekDocAt(h: Harness, week: String): String? =
        runCatching { h.docStore.readWeek(h.planId, week) }.getOrNull()

    @Test
    fun theWeekDocIsWrittenBeforeUnitsAreAppendedOrPlaced() = runTest {
        val h = newHarness()
        val spy = Spy(h.service, h.docStore, h.planId, thisWeek)
        var refreshed = 0
        val tools = PlanWeekTools({ spy }, { h.docStore }, { 60 * 7 }, h.clock, h.llm, { refreshed++ })
        val tool = tools.all.single { it.name == "plan_week" }

        assertTrue(tools.guidance.contains("先写本周文档"), "guidance 要说清先写文档再排天：" + tools.guidance)

        val result = tool.call(buildJsonObject { put("week", thisWeek) })

        assertTrue(!result.isError, result.content)
        assertTrue(result.content.contains("本周文档也写好了"), result.content)
        assertEquals(1, refreshed, "排完要通知界面刷新计划页")

        // 核心不变量：append/place 的那一刻，周文档已经在盘上，而且已经写好了这一周的条目。
        assertNotNull(spy.docAtAppend, "appendUnits 之前就要先写本周文档")
        assertTrue(spy.docAtAppend!!.contains("进程概念"), "周文档里要先有这一周的条目：" + spy.docAtAppend)
        assertNotNull(spy.docAtFirstPlace, "placeUnit 之前周文档也要在")
        assertEquals("appendUnits", spy.calls.first(), "先追加单元，再铺天")
        assertTrue(spy.calls.drop(1).all { it.startsWith("placeUnit:") }, spy.calls.toString())

        // 计划库里真的有了「今天要做」的 occurrence。
        val week = spy.week(h.planId, thisWeek)
        val placedTitles = week.days.flatMap { it.items }.map { it.title }
        assertEquals(listOf("进程概念", "调度算法", "本周复盘"), placedTitles)
        assertTrue(spy.pool(h.planId).isEmpty(), "三条都铺到天了，不该还留在待安排池")

        // 周文档和计划库里写的是同一份安排。
        val doc = weekDocAt(h, thisWeek)
        assertNotNull(doc)
        assertTrue(doc.contains("进程概念") && doc.contains("调度算法") && doc.contains("本周复盘"), doc)
    }

    /** research_plan 排第一周时只给了阶段焦点（模型没给 focus）：周文档也要写「本周重点」。 */
    @Test
    fun theStageFocusIsWrittenIntoTheWeekDocWhenTheModelHasNone() = runTest {
        val h = newHarness(reply = weekItemsJson(includeFocus = false))
        val result = planOneWeek(
            plan = h.service,
            planDocStore = h.docStore,
            llm = h.llm,
            clock = h.clock,
            planId = h.planId,
            week = thisWeek,
            dailyMinutes = 60,
            stageFocus = "命令行、Python、数据处理",
        )

        assertEquals(null, result.error)
        assertTrue(result.placed > 0, "第一周要铺到天")
        val doc = weekDocAt(h, thisWeek)
        assertNotNull(doc)
        assertTrue(doc.contains("> 本周重点：命令行、Python、数据处理"), doc)
    }

    /** 非法周号（格式错、不对应真实周、已经过去）一律在写任何东西之前拒绝，也不叫模型。 */
    @Test
    fun anInvalidWeekIsRejectedWithoutWritingAnything() = runTest {
        val h = newHarness(reply = weekItemsJson())
        val tool = weekTool(h)

        for (bad in listOf("下周", "2026-W99", "2026-W40")) {
            val result = tool.call(buildJsonObject { put("week", bad) })
            assertTrue(result.isError, "「$bad」要拒绝：" + result.content)
            assertEquals(null, weekDocAt(h, bad), "「$bad」不该写出周文档")
        }

        assertTrue(h.llm.seen.isEmpty(), "周号不合法时不该叫模型")
        assertEquals(0, h.service.week(h.planId, thisWeek).days.sumOf { it.items.size }, "不该有 occurrence")
        assertTrue(h.service.pool(h.planId).isEmpty(), "不该往计划库里追加单元")
    }

    /** 周文档写成了、但追加单元失败：文档必须保留，错误里要说清「文档已写、库没动」。 */
    @Test
    fun aFailedAppendKeepsTheWeekDocAndSaysSo() = runTest {
        val h = newHarness()
        val failing = object : LearningPlanService by h.service {
            override suspend fun appendUnits(
                env: CommandEnvelope,
                planId: String,
                drafts: List<UnitDraft>,
            ): CommandResult<List<String>> = fail(ErrorCode.E_STORE, "disk full")
        }
        val tool = weekTool(h, service = failing)

        val result = tool.call(buildJsonObject { put("week", thisWeek) })

        assertTrue(result.isError, result.content)
        assertTrue(result.content.contains("周文档已经写了并保留着"), result.content)
        assertTrue(result.content.contains("disk full"), "错误要带上真实原因：" + result.content)

        val doc = weekDocAt(h, thisWeek)
        assertNotNull(doc, "追加失败时已经写好的周文档不能丢")
        assertTrue(doc.contains("进程概念"), doc)
        assertEquals(0, h.service.week(h.planId, thisWeek).days.sumOf { it.items.size }, "追加失败就不该铺天")
    }
}
