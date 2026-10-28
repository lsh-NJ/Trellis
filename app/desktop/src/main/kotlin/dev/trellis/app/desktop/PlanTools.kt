package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.UnitDraft
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 今天所在的 ISO 周，比如 "2026-W44"。 */
private fun currentIsoWeek(): String = "%04d-W%02d".format(
    java.time.LocalDate.now().get(java.time.temporal.IsoFields.WEEK_BASED_YEAR),
    java.time.LocalDate.now().get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR),
)

/**
 * 学习计划的手：把「第 1–12 集排成计划」真正写进计划页。
 *
 * 这里和 `WorkflowTools.set_plan` 是两件不同的事，以前混为一谈：
 * - `set_plan` 是**这一轮的动作清单**（搜→挑→排），只活在对话里；
 * - 计划页读的是 `:features:learning-process` 的 SQLite，必须走
 *   `createProcess → saveDraft → acceptDraft` 三条命令才有数据。
 *
 * 所以用户点了「排成学习计划」之后，模型必须调这个工具；只调 `set_plan`
 * 会得到「聊天里排好了、计划页还是空的」这种最难解释的状态。
 */
class PlanTools(
    private val service: () -> LearningPlanService?,
    private val onChanged: () -> Unit,
    /** 设置页里的「每天预计投入」×7；没设置就是 null，计划就只记标题和目标。 */
    private val weeklyCapacity: () -> Int? = { null },
) {

    val guidance: String = listOf(
        "<how_to_create_learning_plan>",
        "1. `set_plan` 只写这一轮的动作；**计划页要的是 create_learning_plan**。",
        "   用户点「排成学习计划」、或者说「排个计划 / 安排每周」时，调 create_learning_plan。",
        "2. 参数是结构与承诺：units 写单元（章节 + 标题 + 分钟），weeks 写每周承诺哪些单元；",
        "   每周 1–7 个单元，单元总时长 10–45 分钟一个，别把 80 集一次塞进来。",
        "3. 只排用户看得完的那一段（比如前 12 集 / 前两周），剩下的说「想继续往后排跟我说」。",
        "4. 第一周必须是**本周**（今天所在的那一周，例如今天是 2026-10-27 就写 2026-W44）：",
        "   计划从今天开始排，今天之后的日子才铺任务；不要从下周开始（周一再开始也违反这条）。",
        "5. 写之前先说清你要怎么拆（一句），写完说清「计划页第 X 周有多少单元」，不要去改用户别的数据。",
        "</how_to_create_learning_plan>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(CreateLearningPlan())

    private inner class CreateLearningPlan : AgentTool {
        override val name: String = "create_learning_plan"
        override val description: String =
            "在「计划」页建一份学习计划：units 是单元清单，weeks 是每周承诺哪几个单元。" +
                "第一周必须写本周（今天所在周），计划从今天开始排。" +
                "会写用户数据，只在用户要「排成计划」时调用。"
        override val parameters: String =
            "title：计划标题（如「操作系统速通」）；" +
                "units：数组，每项 {title, minutes?, chapter?}（title 必填，minutes 10–45，默认 25）；" +
                "weeks：数组，每项 {week: \"2026-W44\", units: [单元 title...]}（1–12 周，每周 1–7 个）。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val plan = service() ?: return ToolResult(
                "计划库打不开，没写成。用户可以在「计划」页看一眼错误提示。",
                isError = true,
            )
            val title = arguments["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                .ifEmpty { "学习计划" }

            val rawUnits = arguments["units"] as? JsonArray
                ?: return ToolResult("units 要写成数组：[{title, minutes}, …]", isError = true)
            if (rawUnits.size > 60) return ToolResult("一次最多 60 个单元，现在有 ${rawUnits.size} 个", isError = true)

            val units = rawUnits.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { UnitDraft(chapter = "未分章", title = it.take(40), estimateMinutes = 25) }

                    is JsonObject -> {
                        val name = element["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                        if (name.isEmpty()) return@mapNotNull null
                        val minutes = element["minutes"]?.jsonPrimitive?.contentOrNull
                            ?.toIntOrNull()?.coerceIn(10, 45) ?: 25
                        val chapter = element["chapter"]?.jsonPrimitive?.contentOrNull?.trim()
                            ?.takeIf { it.isNotEmpty() } ?: "未分章"
                        UnitDraft(chapter = chapter, title = name.take(40), estimateMinutes = minutes)
                    }

                    else -> null
                }
            }
            if (units.isEmpty()) return ToolResult("units 里没有可用的单元（每项至少要有 title）", isError = true)

            val rawWeeks = arguments["weeks"] as? JsonArray
                ?: return ToolResult("weeks 要写成数组：[{week, units: [标题...]}, …]", isError = true)
            if (rawWeeks.size > 12) return ToolResult("最多 12 周，现在有 ${rawWeeks.size} 周", isError = true)

            val indexByTitle = units.mapIndexed { index, unit -> unit.title to index }.toMap()
            val commitments = rawWeeks.mapIndexedNotNull { position, element ->
                val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                val week = obj["week"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (!week.matches(Regex("""\d{4}-W\d{2}"""))) {
                    return@call ToolResult(
                        "第 ${position + 1} 周的 week 要写成 ISO 周（例：2026-W44），现在是「$week」",
                        isError = true,
                    )
                }
                val thisWeek = currentIsoWeek()
                if (position == 0 && week != thisWeek) {
                    return@call ToolResult(
                        "第 1 周要写本周 $thisWeek（计划从今天开始排）；现在写的是 $week。" +
                            "请把后面的周依次顺延，比如 $thisWeek、下一周……",
                        isError = true,
                    )
                }
                if (week < thisWeek) {
                    return@call ToolResult(
                        "第 ${position + 1} 周写的是 $week，已经过去了；排计划要从本周 $thisWeek 开始",
                        isError = true,
                    )
                }
                val names = (obj["units"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                if (names.size !in 1..7) {
                    return@call ToolResult("$week 这一周要有 1–7 个单元，现在有 ${names.size} 个", isError = true)
                }
                val indexes = names.map { name ->
                    indexByTitle[name] ?: return@call ToolResult(
                        "weeks 里写的「$name」在 units 里找不到；两边的标题要完全一致",
                        isError = true,
                    )
                }
                if (indexes.distinct().size != indexes.size) {
                    return@call ToolResult("$week 里同一个单元写了两次", isError = true)
                }
                CommitmentDraft(week = week, unitIndexes = indexes)
            }
            if (commitments.isEmpty()) {
                return ToolResult("weeks 里每周至少要有一个单元", isError = true)
            }
            val weekOrder = commitments.map { it.week }
            if (weekOrder != weekOrder.sorted() || weekOrder.distinct().size != weekOrder.size) {
                return ToolResult("weeks 要按时间从早到晚写、不能重复；现在是：${weekOrder.joinToString("、")}", isError = true)
            }

            val created = plan.createProcess(
                env(null, "plan.create"),
                title = title,
                goal = title,
                weeklyCapacityMinutes = weeklyCapacity(),
            )
            val createdValue = created.value
                ?: return ToolResult("建计划失败：" + (created.error?.message ?: "未知原因"), isError = true)

            val saved = plan.saveDraft(
                env(createdValue.planId, "plan.save"),
                planId = createdValue.planId,
                versionId = null,
                units = units,
                commitments = commitments,
            )
            val savedValue = saved.value
                ?: return ToolResult("存草稿失败：" + (saved.error?.message ?: "未知原因"), isError = true)

            val accepted = plan.acceptDraft(
                env(createdValue.planId, "plan.accept"),
                versionId = savedValue.versionId,
                firstWeek = commitments.first().week,
            )
            if (accepted.value == null) {
                return ToolResult("生效失败：" + (accepted.error?.message ?: "未知原因"), isError = true)
            }
            onChanged()

            val weekLines = commitments.joinToString("；") { draft ->
                val count = draft.unitIndexes.size
                "${draft.week} $count 个单元"
            }
            return ToolResult(
                "计划「$title」建好了：${units.size} 个单元，分 ${commitments.size} 周（$weekLines）。" +
                    "第一周已经排到天，去「计划」页能看到每周卡片和每天安排。",
            )
        }
    }

    private fun env(planId: String?, origin: String) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.AGENT,
        planId = planId,
        origin = origin,
    )
}
