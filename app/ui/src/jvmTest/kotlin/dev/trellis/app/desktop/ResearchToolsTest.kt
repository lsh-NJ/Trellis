package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.feature.learning.PlanDocStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `research_plan` 的回归测试：调研结果要真的落成「计划 + 计划文档」。
 *
 * 这里只用假的 LLM：工具的价值在于校验与落库，模型输出质量不在单测里评价。
 * 失败路径必须明确报错且不留半成品计划 —— 演示时最怕「AI 说建好了，计划页是空的」。
 */
class ResearchToolsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-04 是周三，所在周 = 2026-W45。 */
    private val wednesday: LocalDate = LocalDate.parse("2026-11-04")
    private val thisWeek: String = "2026-W45"
    private val nextWeek: String = "2026-W46"

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

    private class FakeLlm(private val reply: String, private val delayMs: Long = 0) : LlmClient {
        override suspend fun complete(messages: List<LlmMessage>): String {
            if (delayMs > 0) delay(delayMs)
            return reply
        }
    }

    private class Harness(
        val home: File,
        val clock: FixedClock,
        val service: LearningPlanService,
        val tools: ResearchTools,
    )

    private fun harness(llmReply: String, delayMs: Long = 0): Harness {
        val home = Files.createTempDirectory("research-tools").toFile()
        val clock = FixedClock(wednesday, zone)
        val service = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { clock.nowMs() },
            zone,
        )
        val tools = ResearchTools(
            service = { service },
            planDocStore = { PlanDocStore(home) },
            planDbError = { null },
            weeklyCapacity = { 60 * 7 },
            clock = clock,
            llm = FakeLlm(llmReply, delayMs),
        )
        return Harness(home, clock, service, tools)
    }

    private fun draftJson(weeks: Boolean = true): String = buildString {
        append(
            """{"title":"数据结构速通","units":[""",
        )
        append("""{"title":"线性表基础","minutes":25,"chapter":"第1章"},""")
        append("""{"title":"栈与队列","minutes":25,"chapter":"第2章"},""")
        append("""{"title":"二叉树","minutes":30,"chapter":"第3章"},""")
        append("""{"title":"图","minutes":30,"chapter":"第4章"},""")
        append("""{"title":"排序","minutes":30,"chapter":"第5章"},""")
        append("""{"title":"查找","minutes":25,"chapter":"第6章"}]""")
        if (weeks) {
            append(""","weeks":{"$thisWeek":["线性表基础","栈与队列","二叉树"],"$nextWeek":["图","排序","查找"]}""")
        }
        append("}")
    }

    private suspend fun call(harness: Harness) = harness.tools.all
        .single { it.name == "research_plan" }
        .call(
            buildJsonObject {
                put("goal", "两周内复习完数据结构")
                put("dailyMinutes", 60)
            },
        )

    @Test
    fun validDraftCreatesPlanAndDoc() = runTest {
        val harness = harness(draftJson())

        val result = call(harness)
        assertFalse(result.isError, result.content)

        val planId = harness.service.activePlanId()
        assertNotNull(planId)
        assertEquals("数据结构速通", harness.service.planState(planId).title)
        assertEquals(listOf("数据结构速通"), harness.service.plans().map { it.title })

        // 本周、下周各 3 个单元。
        assertEquals(3, harness.service.week(planId, thisWeek).commitment.units.size)
        assertEquals(3, harness.service.week(planId, nextWeek).commitment.units.size)

        // 计划文档要能回读（用户在计划页「计划文档」里看到的就是它）。
        val doc = File(harness.home, "plans/docs/$planId.md")
        assertTrue(doc.exists(), "计划文档没写出来：${doc.path}")
        val text = doc.readText()
        assertTrue(text.contains("目标与验收"), text)
        assertTrue(text.contains("## $thisWeek"), text)
        assertTrue(text.contains("线性表基础"), text)
        assertTrue(result.content.contains("数据结构速通"), result.content)
    }

    @Test
    fun invalidReplyIsErrorAndCreatesNothing() = runTest {
        val harness = harness("这周先不排了，你先看看资料吧。")

        val result = call(harness)
        assertTrue(result.isError)
        assertTrue(result.content.contains("没解析成合法计划"), result.content)
        assertTrue(harness.service.plans().isEmpty(), "失败时不能留下半成品计划")
    }

    @Test
    fun missingWeeksAutoPacksByBudget() = runTest {
        val harness = harness(draftJson(weeks = false))

        val result = call(harness)
        assertFalse(result.isError, result.content)

        val planId = harness.service.activePlanId()
        assertNotNull(planId)
        // 预算 60×7×0.8 = 336 分钟，6 个单元共 165 分钟 → 全排本周，下周空。
        assertEquals(6, harness.service.week(planId, thisWeek).commitment.units.size)
        assertEquals(0, harness.service.week(planId, nextWeek).commitment.units.size)
    }

    @Test
    fun timeoutIsErrorAndCreatesNothing() = runTest {
        // runTest 的虚拟时间会把 30s 超时立刻推到；这里不需要真的等。
        val harness = harness(draftJson(), delayMs = 120_000)

        val result = call(harness)
        assertTrue(result.isError)
        assertTrue(result.content.contains("没跑起来"), result.content)
        assertTrue(harness.service.plans().isEmpty())
    }
}
