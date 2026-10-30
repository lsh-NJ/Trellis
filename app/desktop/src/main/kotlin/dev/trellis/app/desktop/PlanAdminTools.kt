package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.ReviewView
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 学习计划的「管理员之手」：让 AI 从「只会建计划」变成「能读状态、排下周、勾节点、顺延、复盘、调节奏」。
 *
 * 和 [PlanTools] 的分工：
 * - [PlanTools] 的 `create_learning_plan` 负责**新建**一份计划（用户点「排成学习计划」）；
 * - 这里负责**维护已经有的那份**：读现状、推进周节奏、勾完成、顺延、复盘、改每天投入。
 *
 * 写操作统一走三条规矩（都被真实事故逼出来过）：
 * 1. 计划库打不开时给人话，不抛异常 —— 工具挂了不能把这一轮对话弄崩；
 * 2. 命令失败把 `error.message` 原样交回模型，它才知道下一步该怎么改；
 * 3. 只有用户明确同意才动数据：`confirmed=false` 直接拒绝，并告诉模型去调 `ask_choices`。
 */
class PlanAdminTools(
    private val service: () -> LearningPlanService?,
    private val onChanged: () -> Unit,
    /** 计划库打不开时的具体原因（AppRuntime 存好的）；给模型看，别让它对用户说「打不开」。 */
    private val dbError: () -> String? = { null },
    /** 时间源：今天/本周从这里算（开发者模式拨日期后跟着变）。 */
    private val clock: TrellisClock = SystemTrellisClock(),
) {

    /** 今天（注入时钟的时区，与计划后端同口径）。 */
    private fun planToday(): LocalDate = clock.today()

    val guidance: String = listOf(
        "<how_to_manage_learning_plan>",
        "1. 用户要看/调整**已经排好的**学习计划时先调 get_learning_plan：它给出本周/今天的节点 id（occurrenceId）、",
        "   待安排数量、上周复盘和 carryPending。勾完成、顺延都用这个 id，不要自己编。",
        "2. 完成/顺延/改每天投入都会改用户数据：先得到明确同意，再带 confirmed=true 调；",
        "   不确定就先 ask_choices 问一句，别替用户拍板。",
        "3. 排下周/跨周对账调 plan_next_week（系统节奏，幂等，会补承诺 + 结算 + 复盘 + 铺天），",
        "   不要自己算周号、也不要为了排下周重建计划；复盘调 review_last_week；一次只动一个计划。",
        "4. 文案不羞辱：说「上周完成 2/4，这周先排 3 个」，不说「落后 N 天」「欠了 N 个」。",
        "5. 新建计划走 create_learning_plan（「找资源 → 排成学习计划」那条路）；这里只维护已有的。",
        "6. 计划库打不开时先调 get_diagnostics 看具体原因（缺 JDK 模块/文件坏/权限），再决定怎么办；",
        "   不要把「打不开」原样丢给用户让他自己修。",
        "</how_to_manage_learning_plan>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(
        GetLearningPlan(),
        PlanNextWeek(),
        CompletePlanNode(),
        MovePlanNode(),
        ReviewLastWeek(),
        SetPlanPace(),
    )

    // ---------------------------------------------------------------- 公共外壳

    private fun noService(): ToolResult {
        val reason = dbError()?.takeIf { it.isNotBlank() }
        val detail = if (reason == null) "" else "具体原因：$reason。"
        return ToolResult(
            "计划库打不开，这次没动任何东西。$detail" +
                "先调 get_diagnostics 看清楚环境（缺运行时模块、文件坏了还是权限问题），" +
                "再决定重试、绕开还是让用户做一个真能执行的修复动作。",
            isError = true,
        )
    }

    /** 所有工具都对着「当前唯一活跃计划」：一次只动一个计划，这是提示词和工具一起守的边界。 */
    private fun withActivePlan(block: (LearningPlanService, String) -> ToolResult): ToolResult {
        val svc = service() ?: return noService()
        val planId = svc.activePlanId() ?: return ToolResult(
            "现在没有活跃的学习计划，没法管。先在「计划」页建一份；" +
                "如果用户想从 B 站课程排一份计划，那是「排成学习计划」（另一条路）。",
            isError = true,
        )
        return block(svc, planId)
    }

    private fun env(planId: String?, origin: String) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.AGENT,
        planId = planId,
        origin = origin,
    )

    private data class NodeInfo(
        val occurrenceId: String,
        val title: String,
        val day: String,
        val minutes: Int,
    )

    /**
     * 找一个节点的标题/日期，只为把回复说清楚；找不到返回 null，不挡命令本身。
     * 先看今天及逾期，再看本周、下周（提前铺好的那些）。
     */
    private fun findNode(svc: LearningPlanService, planId: String, occurrenceId: String): NodeInfo? {
        val today = planToday()
        svc.openNodes(planId, today.toString())
            .firstOrNull { it.occurrenceId == occurrenceId }
            ?.let { return NodeInfo(it.occurrenceId, it.title, it.day, it.minutes) }
        for (week in listOf(isoWeekOf(today), isoWeekOf(today.plusWeeks(1)))) {
            for (day in svc.week(planId, week).days) {
                day.items.firstOrNull { it.occurrenceId == occurrenceId }?.let {
                    return NodeInfo(it.occurrenceId, it.title, day.day, it.minutes)
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 读现状

    private inner class GetLearningPlan : AgentTool {
        override val name: String = "get_learning_plan"
        override val description: String =
            "读当前学习计划：状态、本周和今天的节点（带 occurrenceId，勾完成/顺延要用它）、待安排数量、上周复盘摘要、待结转数量。" +
                "管计划前先调它，不要凭记忆猜用户排了什么。"
        override val parameters: String = "不需要参数。"

        override suspend fun call(arguments: JsonObject): ToolResult = withActivePlan { svc, planId ->
            val today = planToday()
            val todayStr = today.toString()
            val week = isoWeekOf(today)
            val lastWeek = isoWeekOf(today.minusWeeks(1))
            val state = svc.planState(planId)
            val weekView = svc.week(planId, week)
            val todayView = svc.today(planId, todayStr)
            val pool = svc.pool(planId)
            val review = svc.review(planId, lastWeek)

            val text = buildString {
                append("计划「").append(state.title.ifBlank { "未命名" }).append("」")
                append("：状态 ").append(state.status)
                append("，本周 ").append(week)
                append("，每天投入 ").append(state.dailyMinutes?.let { "$it 分钟" } ?: "没设（按默认节奏）")
                appendLine()
                appendLine()
                append("今天 ").append(todayStr).append("（").append(todayView.weekdayLabel).append("）：")
                append(todayView.headline)
                val todayItems = weekView.days.firstOrNull { it.day == todayStr }?.items.orEmpty()
                if (todayItems.isEmpty()) {
                    appendLine()
                    append("  （今天没有排到天的节点）")
                } else {
                    todayItems.forEach { item ->
                        appendLine()
                        append("  - [").append(item.occurrenceId).append("] ").append(item.title)
                        append(" · ").append(item.minutes).append(" 分钟 · ").append(item.status)
                        if (item.carried) append(" · 从上周结转")
                    }
                }
                if (todayView.completed.isNotEmpty()) {
                    appendLine()
                    append("  今天已完成：").append(todayView.completed.joinToString("、") { it.title })
                }
                // 本周其余天：今天已经在上面列过，不重复占 token。
                val rest = weekView.days.filter { it.day != todayStr && it.items.isNotEmpty() }
                if (rest.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    append("本周其余节点（").append(week).append("）：")
                    rest.forEach { day ->
                        appendLine()
                        append("  ").append(day.label).append("：")
                        day.items.forEach { item ->
                            appendLine()
                            append("    - [").append(item.occurrenceId).append("] ").append(item.title)
                            append(" · ").append(item.minutes).append(" 分钟 · ").append(item.status)
                            if (item.carried) append(" · 结转")
                        }
                    }
                }
                appendLine()
                appendLine()
                append("待安排池：").append(pool.size).append(" 个单元")
                if (pool.isNotEmpty()) {
                    append("（").append(pool.take(5).joinToString("、") { it.title })
                    append(if (pool.size > 5) "…" else "").append("）")
                }
                appendLine()
                append("待结转（carryPending）：").append(state.carryPending).append(" 个")
                appendLine()
                append("上周复盘（").append(lastWeek).append("）：")
                if (review.state == "ready") {
                    append(review.narrative)
                    review.suggestion?.let {
                        append(" 建议：").append(it.action)
                        append("（成本 ").append(it.cost).append("，预期 ").append(it.expectedEffect).append("）")
                    }
                    if (review.question.isNotBlank()) append(" 想问你：").append(review.question)
                } else {
                    append("还没生成（可以调 review_last_week 生成/查看）。")
                }
            }.trimEnd()
            ToolResult(text)
        }
    }

    // ---------------------------------------------------------------- 推进周节奏

    private inner class PlanNextWeek : AgentTool {
        override val name: String = "plan_next_week"
        override val description: String =
            "让计划按周节奏往前滚一步（系统命令，幂等）：结算已经结束的周、从待安排池给本周/下周补承诺、" +
                "生成复盘、把该铺的周铺到天。用户说「排下周」「这周先这样，下周排一下」时调它；不用自己算周号。"
        override val parameters: String = "不需要参数。"

        override suspend fun call(arguments: JsonObject): ToolResult = withActivePlan { svc, planId ->
            val result = svc.advanceRhythm(env(planId, "chat.plan"), planId)
            val value = result.value
            if (!result.ok || value == null) {
                return@withActivePlan ToolResult("节奏没推进：" + (result.error?.message ?: "未知原因"), isError = true)
            }
            onChanged()
            val note = value.note.ifBlank { "节奏检查过了，没有需要动的地方（${value.week}）。" }
            val detail = buildString {
                append("周：").append(value.week)
                if (value.settledWeeks.isNotEmpty()) {
                    append("；结算 ").append(value.settledWeeks.joinToString("、"))
                }
                if (value.reviewedWeeks.isNotEmpty()) {
                    append("；生成复盘 ").append(value.reviewedWeeks.joinToString("、"))
                }
                if (value.appendedWeeks.isNotEmpty()) {
                    append("；补承诺 ").append(value.appendedWeeks.joinToString("、"))
                    append("（").append(value.appendedUnits).append(" 个单元）")
                }
                if (value.scheduledWeeks.isNotEmpty()) {
                    append("；铺天 ").append(value.scheduledWeeks.joinToString("、"))
                }
                append("；待安排剩 ").append(value.poolRemaining).append(" 个")
                if (value.carried > 0) append("；结转 ").append(value.carried).append(" 个")
                if (value.missed > 0) append("；未完成 ").append(value.missed).append(" 个")
            }
            val warnings = if (result.warnings.isEmpty()) "" else "\n注意：" + result.warnings.joinToString("；")
            ToolResult(note + "\n（" + detail + "）" + warnings)
        }
    }

    // ---------------------------------------------------------------- 勾节点

    private inner class CompletePlanNode : AgentTool {
        override val name: String = "complete_plan_node"
        override val description: String =
            "把计划里的一个节点标成完成（会改用户数据）。occurrenceId 用 get_learning_plan 返回的那个；" +
                "只有用户明确说「做完了/勾掉」时才带 confirmed=true。"
        override val parameters: String =
            "occurrenceId：节点 id（get_learning_plan 里的 [occ_…]）；note：可选，用户的一句备注；" +
                "confirmed：布尔，只有用户明确确认完成时才传 true。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val confirmed = arguments["confirmed"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!confirmed) {
                return ToolResult(
                    "还没得到用户确认，没有勾节点。\n" +
                        "先问一句「这个节点做完了吗？」，或者调 ask_choices 让用户选「勾掉 / 等会儿 / 顺延」；" +
                        "用户点了、或者明确说做完了，再带 confirmed=true 调一次。",
                    isError = true,
                )
            }
            val occurrenceId = arguments["occurrenceId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (occurrenceId.isEmpty()) {
                return ToolResult("要告诉我是哪个节点：occurrenceId 用 get_learning_plan 返回的 [occ_…]", isError = true)
            }
            val note = arguments["note"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            return withActivePlan { svc, planId ->
                val node = findNode(svc, planId, occurrenceId)
                val result = svc.completeOccurrence(env(planId, "chat.plan"), occurrenceId, note)
                if (!result.ok) {
                    return@withActivePlan ToolResult("没勾成：" + (result.error?.message ?: "未知原因"), isError = true)
                }
                onChanged()
                val what = node?.let { "「${it.title}」（${it.day}）" } ?: "节点 $occurrenceId"
                ToolResult(
                    "勾掉了 $what" + (note?.let { "，备注：$it" } ?: "") + "。计划页里它已经变成已完成。",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 顺延

    private inner class MovePlanNode : AgentTool {
        override val name: String = "move_plan_node"
        override val description: String =
            "把计划里的一个节点顺延到另一天（会改用户数据，只移不新增学习量）。" +
                "occurrenceId 用 get_learning_plan 返回的那个；只有用户明确要求顺延时才带 confirmed=true。"
        override val parameters: String =
            "occurrenceId：节点 id（get_learning_plan 里的 [occ_…]）；toDay：顺延到哪天，ISO 日期如 2026-10-30；" +
                "reason：可选，一句原因（会记进计划事件）；confirmed：布尔，只有用户明确确认顺延时才传 true。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val confirmed = arguments["confirmed"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!confirmed) {
                return ToolResult(
                    "还没得到用户确认，没有顺延。\n" +
                        "先问清楚要顺延到哪天，或者调 ask_choices 让用户在「明天 / 后天 / 这周末」里选；" +
                        "用户点了再带 confirmed=true 调一次。",
                    isError = true,
                )
            }
            val occurrenceId = arguments["occurrenceId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (occurrenceId.isEmpty()) {
                return ToolResult("要告诉我是哪个节点：occurrenceId 用 get_learning_plan 返回的 [occ_…]", isError = true)
            }
            val toDay = arguments["toDay"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (!toDay.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
                return ToolResult("toDay 要写成 ISO 日期（例：2026-10-30），现在是「$toDay」", isError = true)
            }
            val reason = arguments["reason"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            return withActivePlan { svc, planId ->
                val node = findNode(svc, planId, occurrenceId)
                val result = svc.moveOccurrence(env(planId, "chat.plan"), occurrenceId, toDay, reason)
                if (!result.ok) {
                    return@withActivePlan ToolResult("没顺延成：" + (result.error?.message ?: "未知原因"), isError = true)
                }
                onChanged()
                val what = node?.title ?: occurrenceId
                val from = node?.day ?: "原日期"
                ToolResult(
                    "把「$what」从 $from 顺延到了 $toDay" + (reason?.let { "（原因：$it）" } ?: "") + "。",
                )
            }
        }
    }

    // ---------------------------------------------------------------- 复盘

    private inner class ReviewLastWeek : AgentTool {
        override val name: String = "review_last_week"
        override val description: String =
            "生成/读取上一周的复盘：完成单元数、投入时间、动手天数和一条下周建议。已经生成过就直接读，不重复写。"
        override val parameters: String = "不需要参数。"

        override suspend fun call(arguments: JsonObject): ToolResult = withActivePlan { svc, planId ->
            val today = planToday()
            val week = isoWeekOf(today.minusWeeks(1))
            val existing = svc.review(planId, week)
            if (existing.state == "ready") {
                return@withActivePlan ToolResult(
                    renderReview(week, existing) + "\n（这周的复盘之前已经生成过，直接读的。）",
                )
            }
            val generated = svc.generateReview(env(planId, "chat.plan"), planId, week, today.toString())
            if (!generated.ok) {
                return@withActivePlan ToolResult(
                    "复盘没生成：" + (generated.error?.message ?: "未知原因"),
                    isError = true,
                )
            }
            onChanged()
            val view = svc.review(planId, week)
            ToolResult(
                if (view.state == "ready") {
                    renderReview(week, view)
                } else {
                    "复盘命令跑完了，但没读出内容（$week）。可以去「计划」页看一眼周复盘。"
                },
            )
        }
    }

    private fun renderReview(week: String, view: ReviewView): String = buildString {
        append("上周复盘（").append(week).append("）：").append(view.narrative)
        if (view.facts.isNotEmpty()) {
            appendLine()
            append("数字：").append(view.facts.joinToString("、") { "${it.label} ${it.value}" })
        }
        view.suggestion?.let {
            appendLine()
            append("建议：").append(it.action)
            append("（成本 ").append(it.cost).append("，预期 ").append(it.expectedEffect).append("）")
        }
        if (view.question.isNotBlank()) {
            appendLine()
            append("想问用户：").append(view.question)
        }
    }.trimEnd()

    // ---------------------------------------------------------------- 每天投入

    private inner class SetPlanPace : AgentTool {
        override val name: String = "set_plan_pace"
        override val description: String =
            "改这份计划「每天投入多少分钟」（15–240）。会改用户数据，也影响之后补承诺/铺天的容量；" +
                "只有用户明确说改、且确认了数字时才带 confirmed=true。"
        override val parameters: String =
            "dailyMinutes：每天投入分钟数（15–240 的整数）；confirmed：布尔，只有用户确认后才传 true。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val confirmed = arguments["confirmed"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!confirmed) {
                return ToolResult(
                    "还没得到用户确认，没有改每天投入。\n" +
                        "先问一句「每天大概能投入多少分钟」（15–240），或者调 ask_choices 摆几个档位；" +
                        "用户选好之后再带 confirmed=true 调一次。",
                    isError = true,
                )
            }
            val raw = arguments["dailyMinutes"]?.jsonPrimitive
            val dailyMinutes = raw?.intOrNull ?: raw?.contentOrNull?.trim()?.toIntOrNull()
                ?: return ToolResult("dailyMinutes 要写成 15–240 之间的数字。", isError = true)
            if (dailyMinutes !in 15..240) {
                return ToolResult("每天投入要在 15–240 分钟之间，现在是 $dailyMinutes。", isError = true)
            }
            return withActivePlan { svc, planId ->
                val result = svc.setPlanDailyMinutes(env(planId, "chat.plan"), planId, dailyMinutes)
                if (!result.ok) {
                    return@withActivePlan ToolResult(
                        "没改成：" + (result.error?.message ?: "未知原因"),
                        isError = true,
                    )
                }
                onChanged()
                ToolResult(
                    "每天投入改成 $dailyMinutes 分钟（大约每周 ${dailyMinutes * 7} 分钟）。" +
                        "之后补承诺、铺天会按新的容量来；已经排好的节点不会被删。",
                )
            }
        }
    }
}
