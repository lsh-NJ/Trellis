package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.feature.learning.PlanDocStore
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `read_plan` / `update_plan` 的验收：计划域只留一读一写之后，
 * 读要能一次读到阶段/本周节点/资源/总文档，写要能通过 op 数组真正落库，
 * 并且没 confirmed 时绝不写、dryRun 不落库。
 */
class PlanEditToolsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.parse("2026-11-04")
    private val week: String = "2026-W45"

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

    private fun env(): CommandEnvelope =
        CommandEnvelope(commandId = "cmd_test_" + UUID.randomUUID(), actor = Actor.USER, planId = null, origin = "test")

    private fun tool(name: String, block: suspend (JsonObject) -> ToolResult): AgentTool = object : AgentTool {
        override val name: String = name
        override val description: String = "test fake"
        override val parameters: String = "{}"
        override suspend fun call(arguments: JsonObject): ToolResult = block(arguments)
        override suspend fun call(arguments: JsonObject, onProgress: (String) -> Unit): ToolResult = call(arguments)
    }

    private class Harness(
        val service: LearningPlanService,
        val docStore: PlanDocStore,
        val planId: String,
        val clock: TrellisClock,
        val tools: PlanEditTools,
    )

    private suspend fun harness(): Harness {
        val home = Files.createTempDirectory("plan-edit-tools").toFile()
        val clock = FixedClock(today, zone)
        val service = LearningPlanStore.inMemory({ clock.nowMs() }, zone)
        val created = service.createProcess(env(), "RAG 服务化", "把 RAG 封装成服务", weeklyCapacityMinutes = 7 * 60)
        assertTrue(created.ok, "建计划失败：" + created.error?.message)
        val planId = created.value!!.planId
        val draft = service.saveDraft(
            env(),
            planId,
            null,
            listOf(UnitDraft("第 1 章", "封装检索接口", 30)),
            listOf(CommitmentDraft(week, listOf(0))),
        )
        assertTrue(draft.ok, "存草稿失败：" + draft.error?.message)
        val accepted = service.acceptDraft(env(), draft.value!!.versionId, week)
        assertTrue(accepted.ok, "接受计划失败：" + accepted.error?.message)
        val docStore = PlanDocStore(home)

        // 旧的 plan_week 仍负责「生成一周条目」，这里用最小 fake：排一条能匹配阶段资源名的节点。
        val legacy = mapOf(
            "plan_week" to tool("plan_week") {
                val appended = service.appendUnits(
                    env(),
                    planId,
                    listOf(UnitDraft("第 2 章", "服务化部署", 30)),
                )
                if (!appended.ok) return@tool ToolResult("排周失败：" + appended.error?.message, isError = true)
                val unitId = appended.value.orEmpty().firstOrNull()
                    ?: return@tool ToolResult("排周失败：没有拿到 unitId", isError = true)
                val placed = service.placeUnit(env(), planId, week, unitId, today.toString())
                if (!placed.ok) return@tool ToolResult("排周失败：" + placed.error?.message, isError = true)
                ToolResult("本周文档也写好了（fake）", done = true)
            },
        )
        val tools = PlanEditTools(
            service = { service },
            docStore = { docStore },
            clock = clock,
            selectedPlanId = { planId },
            legacy = legacy,
            onChanged = {},
        )
        return Harness(service, docStore, planId, clock, tools)
    }

    private fun stageOp(): JsonObject = buildJsonObject {
        put("op", "insert")
        put("into", "stages")
        putJsonArray("stages") {
            addJsonObject {
                put("name", "检索基础")
                put("weekStart", week)
                put("weekEnd", week)
                put("focus", "检索链路与索引")
                put("deliverable", "把检索接口封成模块")
                put("acceptance", "能回答一个 RAG 问题")
                putJsonArray("resources") {
                    addJsonObject {
                        put("title", "服务化部署")
                        put("url", "https://example.com/deploy")
                        put("note", "官方部署文档")
                    }
                }
            }
        }
    }

    private fun updateTool(h: Harness) = h.tools.all.single { it.name == "update_plan" }
    private fun readTool(h: Harness) = h.tools.all.single { it.name == "read_plan" }

    @Test
    fun insertStagesWritesBothPlanStagesAndTheDoc() = runTest {
        val h = harness()

        val result = updateTool(h).call(
            buildJsonObject {
                put("confirmed", true)
                put("planId", h.planId)
                putJsonArray("ops") { add(stageOp()) }
            },
        )
        assertFalse(result.isError, result.content)

        val stages = h.service.stages(h.planId)
        assertEquals(listOf("检索基础"), stages.map { it.name })
        val doc = h.docStore.read(h.planId).orEmpty()
        assertTrue(doc.contains("### 阶段 1 · 检索基础"), doc)
        assertTrue(doc.contains("[服务化部署](https://example.com/deploy)"), doc)

        val read = readTool(h).call(buildJsonObject { put("planId", h.planId) })
        assertFalse(read.isError, read.content)
        assertTrue(read.content.contains("检索基础"), read.content)
        assertTrue(read.content.contains("https://example.com/deploy"), read.content)
    }

    @Test
    fun updateUnitResourcesLandsOnTheCommitmentUnit() = runTest {
        val h = harness()

        val result = updateTool(h).call(
            buildJsonObject {
                put("confirmed", true)
                put("planId", h.planId)
                putJsonArray("ops") {
                    addJsonObject {
                        put("op", "update")
                        put("target", "units")
                        putJsonObject("match") { put("title", "封装检索接口") }
                        putJsonObject("set") {
                            putJsonArray("resources") {
                                addJsonObject {
                                    put("title", "RAG 教程")
                                    put("url", "https://example.com/rag")
                                    put("kind", "link")
                                }
                            }
                        }
                    }
                }
            },
        )
        assertFalse(result.isError, result.content)

        val unit = h.service.week(h.planId, week).commitment.units.single { it.title == "封装检索接口" }
        assertEquals(listOf("https://example.com/rag"), unit.resources.map { it.url })
    }

    @Test
    fun scheduleWeekAttachesStageResourcesToMatchingUnit() = runTest {
        val h = harness()
        // 先把阶段（带资源）写进库和总文档。
        val stages = updateTool(h).call(
            buildJsonObject {
                put("confirmed", true)
                put("planId", h.planId)
                putJsonArray("ops") { add(stageOp()) }
            },
        )
        assertFalse(stages.isError, stages.content)

        val scheduled = updateTool(h).call(
            buildJsonObject {
                put("confirmed", true)
                put("planId", h.planId)
                putJsonArray("ops") {
                    addJsonObject {
                        put("op", "schedule_week")
                        put("week", week)
                    }
                }
            },
        )
        assertFalse(scheduled.isError, scheduled.content)

        val units = h.service.week(h.planId, week).commitment.units
        val deploy = units.single { it.title == "服务化部署" }
        assertTrue(
            deploy.resources.any { it.url == "https://example.com/deploy" },
            "排周后要把阶段资源挂到匹配的节点上，实际：" + deploy.resources,
        )
    }

    @Test
    fun rearrangePreviewsThenMovesOverdueToTheFrontAndKeepsEveryItem() = runTest {
        val h = harness()
        val view = h.service.week(h.planId, week)!!
        val first = view.days.flatMap { it.items }.first()
        val overdue = h.service.moveOccurrence(env(), first.occurrenceId, "2026-11-02", "test overdue")
        assertTrue(overdue.ok, "造逾期失败：" + overdue.error?.message)

        val appended = h.service.appendUnits(env(), h.planId, listOf(UnitDraft("第 3 章", "今天的任务", 90)))
        assertTrue(appended.ok, "加单元失败：" + appended.error?.message)
        val todayUnitId = appended.value.orEmpty().single()
        val placed = h.service.placeUnit(env(), h.planId, week, todayUnitId, today.toString())
        assertTrue(placed.ok, "排今天失败：" + placed.error?.message)

        val read = h.tools.all.first { it.name == "read_plan" }.call(
            buildJsonObject {
                put("include", buildJsonArray { add(JsonPrimitive("summary")); add(JsonPrimitive("week")); add(JsonPrimitive("overdue")) })
                put("rearrangeExtraMinutes", 0)
            },
        )
        assertTrue(read.content.contains("重排预览"), read.content)
        assertTrue(read.content.contains("→"), read.content)

        val dryRun = h.tools.all.first { it.name == "update_plan" }.call(
            buildJsonObject {
                put("dryRun", true)
                put(
                    "ops",
                    buildJsonArray {
                        addJsonObject {
                            put("op", "rearrange")
                            put("extraMinutes", 0)
                        }
                    },
                )
            },
        )
        assertTrue(dryRun.content.contains("（dryRun）"), dryRun.content)
        // moveOccurrence 会生成新的 occurrence，不能再用旧 occurrenceId 查；按标题找。
        val stillOverdue = h.service.week(h.planId, week)!!.days
            .first { day -> day.items.any { it.title == first.title } }
        assertTrue(stillOverdue.items.first { it.title == first.title }.status == "planned")
        assertTrue(stillOverdue.day == "2026-11-02", "dryRun 不能改库，实际：" + stillOverdue.day)

        val applied = h.tools.all.first { it.name == "update_plan" }.call(
            buildJsonObject {
                put("confirmed", true)
                put(
                    "ops",
                    buildJsonArray {
                        addJsonObject {
                            put("op", "rearrange")
                            put("extraMinutes", 0)
                        }
                    },
                )
            },
        )
        assertTrue(!applied.isError, applied.content)
        val after = h.service.week(h.planId, week)!!
        val todayTitles = after.days.first { it.day == today.toString() }.items.map { it.title }
        assertTrue(todayTitles.contains(first.title), "逾期项应先补到今天：" + applied.content)
        val tomorrowTitles = after.days.first { it.day == today.plusDays(1).toString() }.items.map { it.title }
        assertTrue(tomorrowTitles.contains("今天的任务"), "今天原有任务应整体后移：" + applied.content)
    }

    @Test
    fun updatePlanRefusesWithoutConfirmedAndDryRunWritesNothing() = runTest {
        val h = harness()

        val refused = updateTool(h).call(
            buildJsonObject {
                put("planId", h.planId)
                putJsonArray("ops") { add(stageOp()) }
            },
        )
        assertTrue(refused.isError)
        assertTrue(refused.content.contains("confirmed"), refused.content)
        assertTrue(h.service.stages(h.planId).isEmpty(), "被拒绝时不能写阶段")
        assertTrue(!h.docStore.read(h.planId).orEmpty().contains("### 阶段 1"), "被拒绝时不能写文档")

        val dry = updateTool(h).call(
            buildJsonObject {
                put("dryRun", true)
                put("planId", h.planId)
                putJsonArray("ops") { add(stageOp()) }
            },
        )
        assertFalse(dry.isError, dry.content)
        assertTrue(h.service.stages(h.planId).isEmpty(), "dryRun 不能写阶段")
        assertTrue(!h.docStore.read(h.planId).orEmpty().contains("### 阶段 1"), "dryRun 不能写文档")
    }
}
