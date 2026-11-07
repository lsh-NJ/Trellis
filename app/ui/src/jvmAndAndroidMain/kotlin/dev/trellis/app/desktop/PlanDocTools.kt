package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.PlanDocStore
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 计划文档的 AI 之手：读 / 写 `<home>/plans/docs/<planId>.md`，并把某一周的条目**确认后**追加成计划单元。
 *
 * 为什么先有文档再落库：
 * - 文档是「想法」层：AI 可以先写目标、按周拆条目、把没想清楚的丢进「待安排」，用户改起来就是一篇文章；
 * - 计划库是「执行」层：`appendUnits` 只 INSERT、不可撤销，所以必须用户点过 / 明确说过才动；
 * - 两边不实时双向同步 —— 由这张工具 + 计划页按钮把文档「拉」进计划，避免两份数据互相打架。
 *
 * 写操作规矩沿用 [PlanAdminTools]：不抛异常、失败原话回给模型、覆盖已有文档要 `confirmed=true`。
 */
class PlanDocTools(
    private val service: () -> LearningPlanService?,
    private val docStore: () -> PlanDocStore?,
    private val clock: TrellisClock = SystemTrellisClock(),
    private val onChanged: () -> Unit = {},
) {

    val guidance: String = listOf(
        "<how_to_plan_doc>",
        "1. 给用户「由面到点」排计划时，优先写计划文档（write_plan_doc）而不是直接建一堆节点：",
        "   文档里有 # 目标与验收、## YYYY-Www 每周条目、## 待安排 三块；条目一行一条，可带「（约 90 分钟）」。",
        "2. 先调 read_plan_doc 看现有文档；覆盖非空文档必须用户明确同意后带 confirmed=true。",
        "3. 用户同意把某周条目落进计划时调 sync_plan_week（会追加到计划单元的待安排池，不可撤销）；",
        "   之后用拖拽重排 / placeUnit / 计划管理员工具把单元排到具体天，不要重复追加。",
        "4. 文档只写用户的目标、验收和条目；不要写「已完成」「落后」这类状态 —— 那些以计划库为准。",
        "</how_to_plan_doc>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(
        ReadPlanDoc(),
        WritePlanDoc(),
        SyncPlanWeek(),
    )

    private fun planToday(): LocalDate = clock.today()

    private fun isoWeekOf(date: LocalDate): String =
        "%04d-W%02d".format(
            date.get(IsoFields.WEEK_BASED_YEAR),
            date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )

    private fun env(planId: String?, origin: String) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.AGENT,
        planId = planId,
        origin = origin,
    )

    private suspend fun withActivePlan(block: suspend (LearningPlanService, String) -> ToolResult): ToolResult {
        val svc = service() ?: return ToolResult(
            "计划库打不开，这次没动任何东西。先调 get_diagnostics 看清楚环境再决定怎么办。",
            isError = true,
        )
        val planId = svc.activePlanId()
            ?: return ToolResult(
                "现在没有活跃的学习计划，没有可写的计划文档。先在「计划」页建一份计划。",
                isError = true,
            )
        return block(svc, planId)
    }

    private fun store(): PlanDocStore? = docStore()

    private inner class ReadPlanDoc : AgentTool {
        override val name: String = "read_plan_doc"
        override val description: String =
            "读当前学习计划的计划文档（Markdown：目标与验收、按周条目、待安排）。改文档前先读一次。"
        override val parameters: String = "不需要参数。"

        override suspend fun call(arguments: JsonObject): ToolResult = withActivePlan { _, planId ->
            val store = store() ?: return@withActivePlan ToolResult("计划文档存储没准备好。", isError = true)
            val markdown = store.read(planId)
            if (markdown.isNullOrBlank()) {
                ToolResult(
                    "计划「$planId」还没有文档。写第一版用 write_plan_doc，格式：\n" +
                        PLAN_DOC_TEMPLATE,
                )
            } else {
                val doc = runCatching { store.parse(markdown) }.getOrNull()
                val summary = buildString {
                    append("计划文档（").append(markdown.length).append(" 字）：\n")
                    if (doc != null) {
                        append("目标 ").append(doc.targets.size).append(" 条；")
                        append("按周 ").append(doc.weeks.keys.joinToString("、").ifBlank { "无" }).append("；")
                        append("待安排 ").append(doc.unscheduled.size).append(" 条。\n\n")
                    }
                    append(markdown)
                }
                ToolResult(summary)
            }
        }
    }

    private inner class WritePlanDoc : AgentTool {
        override val name: String = "write_plan_doc"
        override val description: String =
            "写/覆盖当前学习计划的计划文档（整份 Markdown）。已有非空文档时覆盖必须带 confirmed=true（先问用户）。" +
                "格式：# 目标与验收（bullet） / ## YYYY-Www（该周条目 bullet） / ## 待安排（bullet）。"
        override val parameters: String =
            "markdown（必须，整份文档内容）；confirmed（可选，覆盖已有文档时必填 true）。"

        override suspend fun call(arguments: JsonObject): ToolResult = withActivePlan { _, planId ->
            val store = store() ?: return@withActivePlan ToolResult("计划文档存储没准备好。", isError = true)
            val markdown = arguments["markdown"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (markdown.isBlank()) {
                return@withActivePlan ToolResult(
                    "markdown 为空，没写。至少要有 # 目标与验收 和一条 ## 待安排。",
                    isError = true,
                )
            }
            val existing = store.read(planId)
            val confirmed = arguments["confirmed"]?.jsonPrimitive?.booleanOrNull == true
            if (!existing.isNullOrBlank() && existing.trim() != markdown && !confirmed) {
                return@withActivePlan ToolResult(
                    "这份计划已经有文档了，覆盖会把原内容整份替掉。先让用户确认，再带 confirmed=true 重试；" +
                        "只想补几条时也可以只改其中一段（整份写回）。",
                    isError = true,
                )
            }
            val parsed = runCatching { store.parse(markdown) }.getOrNull()
            val file = store.save(planId, markdown)
            onChanged()
            ToolResult(
                buildString {
                    append("已保存计划文档（").append(markdown.length).append(" 字）：").append(file.name)
                    if (parsed != null) {
                        append("。目标 ").append(parsed.targets.size).append(" 条、按周 ").append(parsed.weeks.keys.size)
                            .append(" 个、待安排 ").append(parsed.unscheduled.size).append(" 条。")
                    }
                    append("\n要落到计划里，用 sync_plan_week 把某一周的条目追加成单元（不可撤销，先确认）。")
                },
            )
        }
    }

    private inner class SyncPlanWeek : AgentTool {
        override val name: String = "sync_plan_week"
        override val description: String =
            "把计划文档里某一周的条目追加成计划单元（进入待安排池，不自动铺天；不可撤销）。" +
                "注意：如果目标周的下一周还没有承诺，节奏引擎可能从池里自动补下周。" +
                "必须先确认：不带 confirmed=true 会拒绝。"
        override val parameters: String =
            "week（可选，ISO 周如 2026-W45；不传=当前周）；confirmed（写操作必须 true）。"

        override suspend fun call(arguments: JsonObject): ToolResult = withActivePlan { svc, planId ->
            val store = store() ?: return@withActivePlan ToolResult("计划文档存储没准备好。", isError = true)
            if (arguments["confirmed"]?.jsonPrimitive?.booleanOrNull != true) {
                return@withActivePlan ToolResult(
                    "追加单元会真写进计划库且不能撤销。先问用户「把这周的条目落进计划吗」，确认后带 confirmed=true 再调。",
                    isError = true,
                )
            }
            val requested = arguments["week"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            val week = requested ?: isoWeekOf(planToday())
            val doc = store.load(planId)
            if (doc == null) {
                return@withActivePlan ToolResult("计划「$planId」还没有文档；先 write_plan_doc。", isError = true)
            }
            val match = doc.weeks.entries.firstOrNull { it.key.replace(" ", "").equals(week.replace(" ", ""), ignoreCase = true) }
            if (match == null || match.value.isEmpty()) {
                val available = doc.weeks.keys.joinToString("、").ifBlank { "无" }
                return@withActivePlan ToolResult(
                    "文档里没有 $week 这一周，没动。现有周：$available。",
                    isError = true,
                )
            }
            val drafts = match.value.map { item ->
                UnitDraft(
                    chapter = "计划文档",
                    title = item.text,
                    estimateMinutes = (item.minutesHint ?: 25).coerceIn(10, 45),
                )
            }
            val result = svc.appendUnits(env(planId, "chat.plan.doc"), planId, drafts)
            if (!result.ok) {
                return@withActivePlan ToolResult(
                    "追加失败：" + (result.error?.message ?: "未知原因"),
                    isError = true,
                )
            }
            val ids = result.value.orEmpty()
            onChanged()
            ToolResult(
                "已把 $week 的 ${ids.size} 条追加进计划，落在「待安排」池里（不会自动铺天）。" +
                    "单元 id：${ids.joinToString("、")}。" +
                    "接下来可以用拖拽重排把它们放到某天，或者让用户决定先排哪几件。",
            )
        }
    }

    companion object {
        const val PLAN_DOC_TEMPLATE: String =
            "# 目标与验收\n- （这份计划想达成什么）\n- 验收：（怎么算做到了）\n\n## 待安排\n- （还没排时间的条目）\n"
    }
}
