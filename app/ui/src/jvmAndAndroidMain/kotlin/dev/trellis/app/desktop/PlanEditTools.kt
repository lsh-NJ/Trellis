package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentUnitView
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.ResourceDraft
import dev.trellis.domain.plan.StageDraft
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.domain.plan.WeekDayItem
import dev.trellis.domain.plan.WeekView
import dev.trellis.feature.learning.DocResource
import dev.trellis.feature.learning.PlanDocStage
import dev.trellis.feature.learning.PlanDocStore
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 计划域唯一的一读一写两个工具。
 *
 * 这不是「又一个工具」：它把计划域原本十来个各自为政的工具收成
 * `read_plan` + `update_plan` 两个入口，模型不用再记「该调哪个工具」，
 * 只需要说清「我要读什么 / 我要把哪条数据改成什么」。
 *
 * `update_plan` 是一组结构化 op（insert / update / schedule_week / write_doc），
 * 每个 op 用 match + set 四段式表达，接近 SQL 但**不是 SQL**：
 * 版本、承诺、occurrence、完成只读守卫、撤销都在命令层，绕过它一定出错。
 *
 * 旧工具没有消失：[legacy] 里按名字持有它们的实例，op 内部复用同一套实现。
 */
internal class PlanEditTools(
    private val service: () -> LearningPlanService?,
    private val docStore: () -> PlanDocStore?,
    private val clock: TrellisClock,
    private val selectedPlanId: () -> String?,
    private val legacy: Map<String, AgentTool>,
    private val onChanged: () -> Unit,
    private val dbError: () -> String? = { null },
) {

    val guidance: String = listOf(
        "<how_to_edit_plan>",
        "1. 计划域只留两个工具：read_plan 读、update_plan 写。不要再找 get_learning_plan / plan_week /",
        "   create_learning_plan / write_plan_doc 这些旧名字——它们已经收进 update_plan 的 op 里。",
        "2. 动手前先 read_plan：拿到 planId、阶段、本周节点（occurrenceId）、待安排池和总文档。",
        "3. 写操作一律先给用户方案、拿到明确同意，再带 confirmed=true 调 update_plan；",
        "   不确定就先 ask_choices。没 confirmed 时工具会拒绝，并告诉你去问用户。",
        "4. op 是「四段式」：动词（insert/update/delete/schedule_week）+ 目标（plan/stages/units）+",
        "   match（按标题或 id 定位）+ set（要改的字段）。一次可以给多个 op，按顺序执行。",
        "5. 资源优先（项目聚合资源）：每个阶段/节点尽量带资源——课内学习（课程/考试/课本）每阶段至少 1 条，",
        "   实践类（设计/项目）也给参考资料；优先整门课/分集视频，其次教材、官方文档。资源写在总文档阶段块里，",
        "   schedule_week 排完会按标题就近挂到单元；也可以 update units set {resources:[{title,url}]} 手动指定。",
        "   找不到就留空并说明缺什么，**不要编造链接**。",
        "6. 总文档的 `## 阶段` 与计划的 plan_stage 同源：用 update_plan 改阶段会同时改库和文档。",
        "7. 早间重排：先 read_plan（include 带 overdue）看逾期与本周剩余容量，再用 update_plan 的",
        "   {op:\"rearrange\", extraMinutes, dryRun:true} 预览「逾期插到最前、今天原有整块后移、",
        "   排不下自然溢出下周」的方案；可调 extraMinutes 重算，应用后可再追加",
        "   update units match.occurrenceId set.day 微调个别项。rearrange 会重写受影响周的周文档与总文档「## 进度」。",
        "</how_to_edit_plan>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(ReadPlan(), UpdatePlan())

    // ---------------------------------------------------------------- 工具定义

    private inner class ReadPlan : AgentTool {
        override val name: String = "read_plan"
        override val description: String =
            "读一份学习计划：概要（状态、每天投入、待安排）、阶段、某一周的节点（带 occurrenceId）、" +
                "单元资源、逾期/欠账、以及总文档。所有「计划现在是什么样」的问题都先读它；不要凭记忆猜。"
        override val parameters: String = """
            planId: 可选，不传就用当前选中的计划
            include: 可选，要读的块：summary / stages / week / resources / doc / overdue（默认 summary+stages+doc）
            week: 可选，ISO 周（如 2026-W46）；传了才读那一周
            rearrangeExtraMinutes: 可选，0-240；传了就输出「今天多投这些分钟」的重排预览（不改数据）
        """.trimIndent()

        override suspend fun call(arguments: JsonObject): ToolResult = runCatching {
            val svc = service() ?: return@runCatching noService()
            val planId = resolvePlanId(arguments.str("planId"), svc)
                ?: return@runCatching ToolResult("现在没有选中的学习计划，先让用户打开一份计划，或传 planId。", isError = true)
            val include = arguments.strArr("include").ifEmpty { listOf("summary", "stages", "doc") }.toSet()
            val week = arguments.str("week")
            val text = buildString {
                val state = runCatching { svc.planState(planId) }.getOrNull()
                if ("summary" in include || state != null) {
                    append("计划 ").append(planId)
                    if (state != null) {
                        append("「").append(state.title.ifBlank { "未命名" }).append("」")
                        append("：状态 ").append(state.status)
                        append("，本周 ").append(state.week)
                        append("，每天投入 ").append(state.dailyMinutes?.let { "$it 分钟" } ?: "没设")
                    }
                    appendLine()
                }
                if ("stages" in include) {
                    val stages = runCatching { svc.stages(planId) }.getOrDefault(emptyList())
                    appendLine()
                    appendLine("阶段（").append(stages.size).append(" 个）：")
                    if (stages.isEmpty()) {
                        appendLine("  （还没有阶段）")
                    } else {
                        stages.forEach {
                            appendLine("  - ").append(it.name).append("（").append(it.weekStart).append(" – ")
                                .append(it.weekEnd).append("，完成 ").append(it.done).append("/").append(it.total)
                                .append("）").append(if (it.focus.isBlank()) "" else "：" + it.focus)
                        }
                    }
                }
                if ("week" in include || week != null) {
                    val target = week ?: isoWeekOf(clock.today())
                    val view = runCatching { svc.week(planId, target) }.getOrNull()
                    appendLine()
                    append("周 ").append(target).append("：")
                    if (view == null) {
                        appendLine("（读不到）")
                    } else {
                        appendLine()
                        view.days.filter { it.items.isNotEmpty() }.forEach { day ->
                            appendLine("  ").append(day.label).append("：")
                            day.items.forEach { item ->
                                appendLine("    - [").append(item.occurrenceId).append("] ").append(item.title)
                                    .append(" · ").append(item.minutes).append(" 分钟 · ").append(item.status)
                                    .append(if (item.resources.isEmpty()) "" else " · 资源 " + item.resources.size + " 条")
                            }
                        }
                        if (view.days.all { it.items.isEmpty() }) appendLine("  （这一周还没排）")
                    }
                }
                if ("resources" in include) {
                    val units = mutableListOf<CommitmentUnitView>()
                    val weeks = (runCatching { svc.week(planId, week ?: isoWeekOf(clock.today())) }.getOrNull()?.week?.let(::listOf)
                        ?: emptyList())
                    weeks.forEach { w ->
                        runCatching { svc.week(planId, w) }.getOrNull()?.commitment?.units?.let(units::addAll)
                    }
                    appendLine()
                    appendLine("单元资源：")
                    if (units.isEmpty()) appendLine("  （没有读到单元）")
                    units.forEach { unit ->
                        append("  - ").append(unit.title)
                        if (unit.resources.isEmpty()) {
                            appendLine("：无")
                        } else {
                            appendLine("：")
                            unit.resources.forEach { r ->
                                appendLine("      ").append(r.kind).append(" · ").append(r.title)
                                    .append(if (r.url.isBlank()) "" else "（" + r.url + "）")
                            }
                        }
                    }
                }
                if ("overdue" in include || arguments.int("rearrangeExtraMinutes") != null) {
                    appendLine()
                    appendLine("逾期与重排：")
                    append(buildOverdueText(svc, planId, arguments.int("rearrangeExtraMinutes")))
                }
                if ("doc" in include) {
                    val doc = docStore()?.read(planId)
                    appendLine()
                    if (doc.isNullOrBlank()) {
                        appendLine("总文档：还没有。")
                    } else {
                        appendLine("总文档：")
                        appendLine(doc.take(6000))
                    }
                }
            }.trimEnd()
            ToolResult(text)
        }.getOrElse { ToolResult("读计划失败：${it.message ?: it}", isError = true) }
    }

    private inner class UpdatePlan : AgentTool {
        override val name: String = "update_plan"
        override val description: String =
            "改学习计划：建计划（手写条目或调研生成）、定/改阶段、加单元、排某一周、改单元/某条 occurrence 的资源/日子/" +
                "完成状态、按逾期做整体重排、改每天投入、写总文档。一次传一组 op，按顺序执行；写操作必须 confirmed=true（用户同意后）。"
        override val parameters: String = """
            planId: 可选，默认当前选中计划
            confirmed: 用户已明确同意时才传 true
            dryRun: 可选，只校验不落库
            ops: 数组，每项是四段式 op：
              {"op":"insert","into":"plan","mode":"units|research","title":...,"goal":...,"dailyMinutes":...,"units":[...],"weeks":[...],"stages":[...]}
              {"op":"insert","into":"stages","stages":[{"name","weekStart","weekEnd","focus","deliverable","acceptance","resources":[{"title","url","note"}]}]}
              {"op":"insert","into":"units","week":"2026-W46","units":[{"title","chapter","minutes","kind","day"}]}
              {"op":"update","target":"plan","set":{"dailyMinutes":90}}
              {"op":"update","target":"stages","match":{"name":"..."},"set":{"name","weekStart","weekEnd","focus"}}
              {"op":"update","target":"units","match":{"title":"..."},"week":"可选","set":{"day":"2026-11-09","status":"done","resources":[{"title","url","kind","locator"}]}}
              {"op":"update","target":"units","match":{"occurrenceId":"occ_..."},"set":{"day":"2026-11-11"}}
              {"op":"schedule_week","week":"2026-W46","dailyMinutes":90,"replan":false}
              {"op":"rearrange","extraMinutes":30,"fromDay":"2026-11-10"}  // 逾期插最前、今天原有整块后移
              {"op":"write_doc","markdown":"# 目标与验收..."}
        """.trimIndent()

        override suspend fun call(arguments: JsonObject): ToolResult = runCatching {
            val svc = service() ?: return@runCatching noService()
            val confirmed = arguments.bool("confirmed") == true
            val dryRun = arguments.bool("dryRun") == true
            val ops = arguments.arr("ops")
            if (ops.isEmpty()) return@runCatching ToolResult("ops 是空的：告诉我要改什么。", isError = true)
            if (!confirmed && !dryRun) {
                return@runCatching ToolResult(
                    "这次没有动任何数据：改计划是写操作，需要用户明确同意后带 confirmed=true 再调。" +
                        "先给用户看方案、用 ask_choices 拿到同意。",
                    isError = true,
                )
            }
            val steps = mutableListOf<String>()
            var lastPlanId: String? = arguments.str("planId")
            for ((index, op) in ops.withIndex()) {
                val result = executeOp(svc, op, dryRun, lastPlanId)
                steps += "${index + 1}. ${result.text}"
                if (result.isError) {
                    return@runCatching ToolResult(
                        "第 ${index + 1} 个 op 失败：" + result.text +
                            if (steps.size > 1) "\n已完成的前面几步：\n" + steps.dropLast(1).joinToString("\n") else "",
                        isError = true,
                    )
                }
                result.planId?.let { lastPlanId = it }
            }
            if (!dryRun) onChanged()
            ToolResult(steps.joinToString("\n"))
        }.getOrElse { ToolResult("改计划失败：${it.message ?: it}", isError = true) }
    }

    // ---------------------------------------------------------------- op 引擎

    private data class OpResult(
        val text: String,
        val isError: Boolean = false,
        val planId: String? = null,
    )

    private suspend fun executeOp(
        svc: LearningPlanService,
        op: JsonObject,
        dryRun: Boolean,
        inheritedPlanId: String?,
    ): OpResult {
        val verb = op.str("op") ?: return OpResult("op 缺少 op 字段（insert/update/schedule_week/write_doc）", isError = true)
        val planId = op.str("planId") ?: inheritedPlanId ?: selectedPlanId()
        return when (verb) {
            "insert" -> when (op.str("into")) {
                "plan" -> insertPlan(svc, op, dryRun)
                "stages" -> insertStages(svc, op, dryRun, planId)
                "units" -> insertUnits(svc, op, dryRun, planId)
                else -> OpResult("insert 的 into 只能是 plan / stages / units。", isError = true)
            }
            "update" -> when (op.str("target")) {
                "plan" -> updatePlan(svc, op, dryRun, planId)
                "stages" -> updateStage(svc, op, dryRun, planId)
                "units" -> updateUnit(svc, op, dryRun, planId)
                else -> OpResult("update 的 target 只能是 plan / stages / units。", isError = true)
            }
            "schedule_week" -> scheduleWeek(svc, op, dryRun, planId)
            "rearrange" -> rearrange(svc, op, dryRun, planId)
            "write_doc" -> writeDoc(op, dryRun, planId)
            else -> OpResult("不认识的 op：$verb。可用 insert / update / schedule_week / rearrange / write_doc。", isError = true)
        }
    }

    // ---------------------------------------------------------------- insert

    private suspend fun insertPlan(svc: LearningPlanService, op: JsonObject, dryRun: Boolean): OpResult {
        val mode = op.str("mode") ?: if (op.str("goal") != null && op.arr("units").isEmpty()) "research" else "units"
        if (dryRun) return OpResult("（dryRun）会新建一份计划（$mode）")
        val before = runCatching { svc.plans().map { it.id }.toSet() }.getOrDefault(emptySet())
        val result = when (mode) {
            "research" -> {
                val goal = op.str("goal")
                    ?: return OpResult("research 模式需要 goal（用户想达到什么）。", isError = true)
                val args = buildJsonObject {
                    put("goal", goal)
                    op.str("deadline")?.let { put("deadline", it) }
                    op.int("dailyMinutes")?.let { put("dailyMinutes", it) }
                    op.bool("scheduleFirstWeek")?.let { put("scheduleFirstWeek", it) }
                    op.str("goalId")?.let { put("goalId", it) }
                }
                dispatch("research_plan", args)
            }
            else -> {
                val title = op.str("title")
                    ?: return OpResult("units 模式需要 title（计划名）。", isError = true)
                val values = mutableMapOf<String, JsonElement>()
                values["title"] = JsonPrimitive(title)
                op.str("bvid")?.let { values["bvid"] = JsonPrimitive(it) }
                op.int("fromPart")?.let { values["fromPart"] = JsonPrimitive(it) }
                op.int("toPart")?.let { values["toPart"] = JsonPrimitive(it) }
                op.int("dailyMinutes")?.let { values["dailyMinutes"] = JsonPrimitive(it) }
                op.str("deadline")?.let { values["deadline"] = JsonPrimitive(it) }
                op.str("goalId")?.let { values["goalId"] = JsonPrimitive(it) }
                op.arrElement("units")?.let { values["units"] = it }
                op.arrElement("weeks")?.let { values["weeks"] = it }
                dispatch("create_learning_plan", JsonObject(values))
            }
        }
        if (result.isError) return OpResult(result.content, isError = true)
        val after = runCatching { svc.plans().map { it.id }.toSet() }.getOrDefault(emptySet())
        val newPlanId = (after - before).singleOrNull()
        val extra = if (newPlanId == null) {
            ""
        } else {
            val stages = op.arr("stages")
            if (stages.isEmpty()) {
                ""
            } else {
                val stageResult = writeStages(svc, newPlanId, stages, dryRun = false)
                if (stageResult.isError) return OpResult(stageResult.text, isError = true)
                "\n" + stageResult.text
            }
        }
        return OpResult(result.content + extra, planId = newPlanId)
    }

    private suspend fun insertStages(
        svc: LearningPlanService,
        op: JsonObject,
        dryRun: Boolean,
        planId: String?,
    ): OpResult {
        val id = requirePlanId(planId) ?: return OpResult("没有选中计划，insert stages 需要 planId。", isError = true)
        val stages = op.arr("stages")
        if (stages.isEmpty()) return OpResult("stages 是空的。", isError = true)
        return writeStages(svc, id, stages, dryRun)
    }

    private suspend fun writeStages(
        svc: LearningPlanService,
        planId: String,
        stages: List<JsonObject>,
        dryRun: Boolean,
    ): OpResult {
        val specs = stages.map { stage ->
            val name = stage.str("name")?.takeIf { it.isNotBlank() }
                ?: return OpResult("每个阶段都要 name。", isError = true)
            val start = stage.str("weekStart") ?: return OpResult("阶段「$name」缺 weekStart。", isError = true)
            val end = stage.str("weekEnd") ?: return OpResult("阶段「$name」缺 weekEnd。", isError = true)
            StageDoc(
                name = name,
                weekStart = start,
                weekEnd = end,
                focus = stage.str("focus").orEmpty(),
                deliverable = stage.str("deliverable").orEmpty(),
                acceptance = stage.str("acceptance").orEmpty(),
                resources = stage.arr("resources").mapNotNull { r ->
                    val title = r.str("title") ?: return@mapNotNull null
                    DocResource(title, r.str("url").orEmpty(), r.str("note").orEmpty())
                },
            )
        }
        if (dryRun) return OpResult("（dryRun）会写 ${specs.size} 个阶段：" + specs.joinToString("、") { it.name })
        val drafts = specs.mapIndexed { index, spec ->
            StageDraft(name = spec.name, weekStart = spec.weekStart, weekEnd = spec.weekEnd, focus = spec.focus, sortOrder = index)
        }
        val result = runCatching { svc.setStages(env(planId, "tool.plan.edit.stages"), planId, drafts) }
            .getOrElse { return OpResult("写阶段失败：${it.message ?: it}", isError = true) }
        if (!result.ok) return OpResult("写阶段失败：${result.error?.message ?: "未知错误"}", isError = true)
        rewriteDocStages(planId, specs)
        // 总计划里写了资源，已经存在的节点就得挂上（节点找资源；分不到节点的整本书挂到每个节点）。
        val attached = attachStageResourcesToPlan(svc, docStore(), planId)
        return OpResult(
            "已写入 ${specs.size} 个阶段，并同步总文档的「## 阶段」：" + specs.joinToString("、") { it.name } +
                if (attached > 0) "；已把阶段资源挂到 $attached 个已有节点上" else "",
        )
    }

    private suspend fun insertUnits(
        svc: LearningPlanService,
        op: JsonObject,
        dryRun: Boolean,
        planId: String?,
    ): OpResult {
        val id = requirePlanId(planId) ?: return OpResult("没有选中计划，insert units 需要 planId。", isError = true)
        val week = op.str("week") ?: isoWeekOf(clock.today())
        val units = op.arr("units")
        if (units.isEmpty()) return OpResult("units 是空的。", isError = true)
        val drafts = units.map { unit ->
            UnitDraft(
                chapter = unit.str("chapter").orEmpty().ifBlank { "学习" },
                title = unit.str("title")?.takeIf { it.isNotBlank() }
                    ?: return OpResult("每个单元都要 title。", isError = true),
                estimateMinutes = (unit.int("minutes") ?: 25).coerceIn(10, 45),
                kind = unit.str("kind") ?: "study",
            )
        }
        if (dryRun) return OpResult("（dryRun）会往 $week 追加 ${drafts.size} 个单元")
        val appended = runCatching { svc.appendUnits(env(id, "tool.plan.edit.units"), id, drafts) }
            .getOrElse { return OpResult("加单元失败：${it.message ?: it}", isError = true) }
        if (!appended.ok) return OpResult("加单元失败：${appended.error?.message ?: "未知错误"}", isError = true)
        val ids = appended.value.orEmpty()
        var placed = 0
        units.forEachIndexed { index, unit ->
            val day = unit.str("day") ?: return@forEachIndexed
            val unitId = ids.getOrNull(index) ?: return@forEachIndexed
            val placedResult = runCatching { svc.placeUnit(env(id, "tool.plan.edit.place"), id, week, unitId, day) }
            if (placedResult.getOrNull()?.ok == true) placed++
        }
        return OpResult(
            "已往 $week 追加 ${drafts.size} 个单元" + (if (placed > 0) "，其中 $placed 个排到天" else "") + "。",
            planId = id,
        )
    }

    // ---------------------------------------------------------------- update

    private suspend fun updatePlan(
        svc: LearningPlanService,
        op: JsonObject,
        dryRun: Boolean,
        planId: String?,
    ): OpResult {
        val id = requirePlanId(planId) ?: return OpResult("没有选中计划，update plan 需要 planId。", isError = true)
        val set = op.obj("set") ?: return OpResult("update plan 缺 set。", isError = true)
        val daily = set.int("dailyMinutes")
        if (daily == null) return OpResult("update plan 目前只支持 set.dailyMinutes。", isError = true)
        if (dryRun) return OpResult("（dryRun）会把每天投入改成 $daily 分钟")
        val result = runCatching { svc.setPlanDailyMinutes(env(id, "tool.plan.edit.pace"), id, daily.coerceIn(15, 240)) }
            .getOrElse { return OpResult("改每天投入失败：${it.message ?: it}", isError = true) }
        if (!result.ok) return OpResult("改每天投入失败：${result.error?.message ?: "未知错误"}", isError = true)
        return OpResult("每天投入已改成 $daily 分钟。", planId = id)
    }

    private suspend fun updateStage(
        svc: LearningPlanService,
        op: JsonObject,
        dryRun: Boolean,
        planId: String?,
    ): OpResult {
        val id = requirePlanId(planId) ?: return OpResult("没有选中计划，update stages 需要 planId。", isError = true)
        val match = op.obj("match") ?: return OpResult("update stages 需要 match（按 name 定位）。", isError = true)
        val name = match.str("name") ?: return OpResult("update stages 的 match 只支持 name。", isError = true)
        val existing = runCatching { svc.stages(id) }.getOrDefault(emptyList())
        val target = existing.firstOrNull { it.name == name }
            ?: return OpResult("找不到阶段「$name」。现有：" + existing.joinToString("、") { it.name }, isError = true)
        val set = op.obj("set") ?: return OpResult("update stages 缺 set。", isError = true)
        val newName = set.str("name") ?: target.name
        val weekStart = set.str("weekStart") ?: target.weekStart
        val weekEnd = set.str("weekEnd") ?: target.weekEnd
        val focus = set.str("focus") ?: target.focus
        if (dryRun) return OpResult("（dryRun）会把阶段「$name」改成「$newName」（$weekStart – $weekEnd）")
        val drafts = existing.mapIndexed { index, stage ->
            if (stage.id == target.id) {
                StageDraft(newName, weekStart, weekEnd, focus, index)
            } else {
                StageDraft(stage.name, stage.weekStart, stage.weekEnd, stage.focus, index)
            }
        }
        val result = runCatching { svc.setStages(env(id, "tool.plan.edit.stage"), id, drafts) }
            .getOrElse { return OpResult("改阶段失败：${it.message ?: it}", isError = true) }
        if (!result.ok) return OpResult("改阶段失败：${result.error?.message ?: "未知错误"}", isError = true)
        val specs = drafts.map { StageDoc(it.name, it.weekStart, it.weekEnd, it.focus) }
        rewriteDocStages(id, specs)
        return OpResult("阶段「$name」已改成「$newName」。", planId = id)
    }

    private suspend fun updateUnit(
        svc: LearningPlanService,
        op: JsonObject,
        dryRun: Boolean,
        planId: String?,
    ): OpResult {
        val id = requirePlanId(planId) ?: return OpResult("没有选中计划，update units 需要 planId。", isError = true)
        val match = op.obj("match") ?: return OpResult("update units 需要 match（title / id / occurrenceId）。", isError = true)
        match.str("occurrenceId")?.let { occurrenceId ->
            return updateOccurrence(svc, op, dryRun, id, occurrenceId)
        }
        val week = op.str("week") ?: isoWeekOf(clock.today())
        val unitIds = resolveUnitIds(svc, id, week, match)
        if (unitIds.isEmpty()) return OpResult("在 $week 找不到匹配的单元。", isError = true)
        if (unitIds.size > 1) return OpResult("匹配到多个单元，请把 match 写得更具体。", isError = true)
        val unitId = unitIds.single()
        val set = op.obj("set") ?: return OpResult("update units 缺 set。", isError = true)
        val done = mutableListOf<String>()

        val resources = set.arr("resources")
        if (resources.isNotEmpty()) {
            val drafts = resources.mapNotNull { r ->
                val title = r.str("title") ?: return@mapNotNull null
                ResourceDraft(
                    kind = r.str("kind") ?: inferKind(r.str("url").orEmpty()),
                    title = title,
                    url = r.str("url").orEmpty(),
                    locator = r.str("locator").orEmpty(),
                    source = "agent",
                )
            }
            if (dryRun) {
                done += "（dryRun）会挂 ${drafts.size} 条资源到 $unitId"
            } else {
                val result = runCatching { svc.setUnitResources(env(id, "tool.plan.edit.resources"), id, unitId, drafts) }
                    .getOrElse { return OpResult("挂资源失败：${it.message ?: it}", isError = true) }
                if (!result.ok) return OpResult("挂资源失败：${result.error?.message ?: "未知错误"}", isError = true)
                done += "已挂 ${drafts.size} 条资源"
            }
        }

        val day = set.str("day")
        if (day != null) {
            if (dryRun) {
                done += "（dryRun）会把 $unitId 排到 $day"
            } else {
                val result = runCatching { svc.placeUnit(env(id, "tool.plan.edit.day"), id, week, unitId, day) }
                    .getOrElse { return OpResult("排到天失败：${it.message ?: it}", isError = true) }
                if (!result.ok) return OpResult("排到天失败：${result.error?.message ?: "未知错误"}", isError = true)
                done += "已排到 $day"
            }
        }

        val status = set.str("status")
        if (status != null) {
            if (status != "done") return OpResult("set.status 目前只支持 done。", isError = true)
            val item = findWeekItem(svc, id, week, unitId)
                ?: return OpResult("这条单元在 $week 没有排到天，没法标完成。", isError = true)
            if (dryRun) {
                done += "（dryRun）会把「${item.title}」标完成"
            } else {
                val result = dispatch(
                    "complete_plan_node",
                    buildJsonObject {
                        put("occurrenceId", item.occurrenceId)
                        put("confirmed", true)
                    },
                )
                if (result.isError) return OpResult(result.content, isError = true)
                done += "已标完成：" + result.content
            }
        }

        if (done.isEmpty()) return OpResult("这次 update 没有可执行的字段（支持 day / status / resources）。", isError = true)
        return OpResult(done.joinToString("；"), planId = id)
    }

    // ---------------------------------------------------------------- schedule / doc

    private suspend fun scheduleWeek(
        svc: LearningPlanService,
        op: JsonObject,
        dryRun: Boolean,
        planId: String?,
    ): OpResult {
        val id = requirePlanId(planId) ?: return OpResult("没有选中计划，schedule_week 需要 planId。", isError = true)
        val week = op.str("week") ?: return OpResult("schedule_week 需要 week（如 2026-W46）。", isError = true)
        val args = buildJsonObject {
            put("planId", id)
            put("week", week)
            op.int("dailyMinutes")?.let { put("dailyMinutes", it) }
            op.bool("replan")?.let { put("replan", it) }
        }
        if (dryRun) return OpResult("（dryRun）会排 $week")
        val result = dispatch("plan_week", args)
        if (result.isError) return OpResult(result.content, isError = true)
        val attached = attachStageResourcesToWeek(svc, docStore(), id, week)
        return OpResult(
            result.content + if (attached > 0) "\n按阶段资源给 $attached 个节点挂上了资料。" else "",
            planId = id,
        )
    }

    private suspend fun writeDoc(op: JsonObject, dryRun: Boolean, planId: String?): OpResult {
        val id = requirePlanId(planId) ?: return OpResult("没有选中计划，write_doc 需要 planId。", isError = true)
        val markdown = op.str("markdown") ?: return OpResult("write_doc 需要 markdown。", isError = true)
        if (dryRun) return OpResult("（dryRun）会覆盖总文档（${markdown.length} 字）")
        val result = dispatch(
            "write_plan_doc",
            buildJsonObject {
                put("planId", id)
                put("markdown", markdown)
                put("confirmed", true)
            },
        )
        return if (result.isError) OpResult(result.content, isError = true) else OpResult(result.content, planId = id)
    }


    // ---------------------------------------------------------------- 早间重排

    private data class RearrangeContext(
        val today: LocalDate,
        val dailyMinutes: Int,
        val extraMinutes: Int,
        val overdue: List<RearrangeItem>,
        val backlog: List<RearrangeItem>,
        val plan: RearrangePlan,
    )

    /**
     * 把计划当前状态翻译成重排输入：逾期 + 更早欠账 + 今天及以后已经排到天的 occurrence。
     * 顺序口径：更早欠账和本周逾期一起按路线顺序排；已排条目按日期/当天顺序排在后面。
     */
    private fun computeRearrange(
        svc: LearningPlanService,
        planId: String,
        extraMinutes: Int,
        today: LocalDate,
    ): RearrangeContext? {
        val overdueView = runCatching { svc.overdue(planId, today.toString()) }.getOrNull() ?: return null
        val daily = runCatching { svc.planState(planId).dailyMinutes }.getOrNull() ?: overdueView.dailyMinutes
        val overdue = overdueView.thisWeek
            .sortedWith(compareBy({ it.sortOrder }, { it.day }, { it.title }))
            .mapIndexed { index, item ->
                RearrangeItem(
                    key = item.occurrenceId,
                    title = item.title,
                    kind = RearrangeItemKind.Occurrence,
                    day = item.day,
                    minutes = item.minutes,
                    order = item.sortOrder * 10 + index,
                )
            }
        val backlog = overdueView.backlog
            .sortedBy { it.sortOrder }
            .mapIndexed { index, item ->
                RearrangeItem(
                    key = item.unitId,
                    title = item.title,
                    kind = RearrangeItemKind.Unit,
                    day = null,
                    minutes = item.estimateMinutes,
                    order = item.sortOrder * 10 + index,
                )
            }
        val scheduled = linkedMapOf<LocalDate, MutableList<RearrangeItem>>()
        var weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
        repeat(9) { weekOffset ->
            val week = isoWeekOf(weekStart)
            runCatching { svc.week(planId, week) }.getOrNull()?.days?.forEachIndexed { dayIndex, day ->
                val date = runCatching { LocalDate.parse(day.day) }.getOrNull() ?: return@forEachIndexed
                if (date.isBefore(today)) return@forEachIndexed
                day.items.filter { it.status == "planned" }.forEachIndexed { itemIndex, item ->
                    scheduled.getOrPut(date) { mutableListOf() } += RearrangeItem(
                        key = item.occurrenceId,
                        title = item.title,
                        kind = RearrangeItemKind.Occurrence,
                        day = day.day,
                        minutes = item.minutes,
                        order = 100_000 + weekOffset * 1_000 + dayIndex * 100 + itemIndex,
                    )
                }
            }
            weekStart = weekStart.plusWeeks(1)
        }
        val plan = buildRearrangePlan(
            overdue = overdue,
            backlog = backlog,
            scheduled = scheduled.mapValues { it.value.toList() },
            startDay = today,
            dailyMinutes = daily,
            extraMinutes = extraMinutes,
        )
        return RearrangeContext(today, daily, extraMinutes, overdue, backlog, plan)
    }

    private fun buildOverdueText(
        svc: LearningPlanService,
        planId: String,
        extraMinutes: Int?,
    ): String {
        val today = clock.today()
        val ctx = computeRearrange(svc, planId, extraMinutes ?: 0, today)
            ?: return "（读不到逾期数据；计划库可能没打开或计划还没排期。）"
        if (ctx.overdue.isEmpty() && ctx.backlog.isEmpty()) return "没有逾期项，不需要重排。"
        val text = StringBuilder()
        text.appendLine("逾期 ${ctx.overdue.size} 项、更早欠账 ${ctx.backlog.size} 项；每天参考 ${ctx.dailyMinutes} 分钟，今天 ${ctx.today}。")
        if (ctx.overdue.isNotEmpty()) {
            text.appendLine("本周逾期（越靠前越该先补）：")
            ctx.overdue.forEach {
                text.appendLine("  - [${it.key}] ${it.day}「${it.title}」${it.minutes} 分钟")
            }
        }
        if (ctx.backlog.isNotEmpty()) {
            text.appendLine("更早欠账（已回到待安排池）：")
            ctx.backlog.forEach {
                text.appendLine("  - [${it.key}]「${it.title}」约 ${it.minutes} 分钟")
            }
        }
        if (extraMinutes == null) {
            text.appendLine("需要方案就带 rearrangeExtraMinutes 再读一次（0-240，表示今天多投的分钟）。")
        } else {
            text.appendLine(renderRearrangePreview(ctx))
        }
        return text.toString()
    }

    private fun renderRearrangePreview(ctx: RearrangeContext): String {
        val moves = ctx.plan.moves
        if (moves.isEmpty()) {
            return "重排预览（今天多投 ${ctx.extraMinutes} 分钟）：没有需要移动的条目，现有安排装得下。"
        }
        val text = StringBuilder()
        text.appendLine("重排预览（今天多投 ${ctx.extraMinutes} 分钟，只预览、不改数据）：")
        moves.forEach { move ->
            val from = move.fromDay ?: "待安排"
            val minutes = ctx.plan.assignments.firstOrNull { it.item.key == move.key && it.item.kind == move.kind }
                ?.item?.minutes ?: 0
            text.appendLine("  - $from → ${move.toDay}「${move.title}」（${kindLabel(move.kind)}，约 $minutes 分钟）")
        }
        text.appendLine("共移动 ${moves.size} 项；其中 ${ctx.plan.overflowCount} 项溢出到下周。")
        return text.toString()
    }

    private fun kindLabel(kind: RearrangeItemKind): String = when (kind) {
        RearrangeItemKind.Occurrence -> "已排任务"
        RearrangeItemKind.Unit -> "待安排"
    }

    private suspend fun rearrange(
        svc: LearningPlanService,
        op: JsonObject,
        dryRun: Boolean,
        planId: String?,
    ): OpResult {
        val id = requirePlanId(planId) ?: return OpResult("没有选中计划，rearrange 需要 planId。", isError = true)
        val extra = (op.int("extraMinutes") ?: 0).coerceIn(0, 240)
        val today = op.str("fromDay")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: clock.today()
        val ctx = computeRearrange(svc, id, extra, today)
            ?: return OpResult("读不到逾期数据，这次没有动任何东西。", isError = true)
        if (ctx.overdue.isEmpty() && ctx.backlog.isEmpty()) {
            return OpResult("没有逾期项，不需要重排。", planId = id)
        }
        if (ctx.plan.moves.isEmpty()) {
            return OpResult("重排检查：今天加 ${extra} 分钟后现有安排装得下，不需要移动。", planId = id)
        }
        val preview = renderRearrangePreview(ctx)
        if (dryRun) return OpResult("（dryRun）\n$preview", planId = id)
        val failures = mutableListOf<String>()
        ctx.plan.moves.forEach { move ->
            val result = when (move.kind) {
                RearrangeItemKind.Occurrence -> runCatching {
                    svc.moveOccurrence(env(id, "tool.plan.edit.rearrange"), move.key, move.toDay, "AI 早间重排")
                }.getOrNull()
                RearrangeItemKind.Unit -> runCatching {
                    svc.placeUnit(
                        env(id, "tool.plan.edit.rearrange"),
                        id,
                        isoWeekOf(LocalDate.parse(move.toDay)),
                        move.key,
                        move.toDay,
                    )
                }.getOrNull()
            }
            if (result == null || !result.ok) {
                failures += "「${move.title}」→ ${move.toDay}：" + (result?.error?.message ?: "写入失败")
            }
        }
        rewriteRearrangedWeeks(svc, id, ctx)
        rewriteProgressDoc(id, ctx)
        return if (failures.isEmpty()) {
            OpResult("已重排：\n$preview", planId = id)
        } else {
            OpResult(
                "重排只做了一部分，下面这些没动成功：\n" + failures.joinToString("\n") +
                    "\n已经成功的移动：\n" + preview,
                isError = true,
            )
        }
    }

    private suspend fun updateOccurrence(
        svc: LearningPlanService,
        op: JsonObject,
        dryRun: Boolean,
        planId: String,
        occurrenceId: String,
    ): OpResult {
        val hit = findOccurrence(svc, planId, occurrenceId)
            ?: return OpResult("找不到 occurrence「$occurrenceId」；先用 read_plan 读本周节点拿到有效 id。", isError = true)
        val set = op.obj("set") ?: return OpResult("update units 缺 set。", isError = true)
        val done = mutableListOf<String>()
        val resources = set.arr("resources")
        if (resources.isNotEmpty()) {
            val unit = runCatching { svc.week(planId, hit.week) }.getOrNull()?.commitment?.units
                ?.firstOrNull { it.title == hit.title }
            if (unit == null) {
                return OpResult("「${hit.title}」在 ${hit.week} 找不到对应单元，没法挂资源。", isError = true)
            }
            val drafts = resources.mapNotNull { r ->
                val title = r.str("title") ?: return@mapNotNull null
                ResourceDraft(
                    kind = r.str("kind") ?: inferKind(r.str("url").orEmpty()),
                    title = title,
                    url = r.str("url").orEmpty(),
                    locator = r.str("locator").orEmpty(),
                    source = "agent",
                )
            }
            if (dryRun) {
                done += "（dryRun）会挂 ${drafts.size} 条资源到「${hit.title}」"
            } else {
                val result = runCatching { svc.setUnitResources(env(planId, "tool.plan.edit.resources"), planId, unit.id, drafts) }
                    .getOrElse { return OpResult("挂资源失败：${it.message ?: it}", isError = true) }
                if (!result.ok) return OpResult("挂资源失败：${result.error?.message ?: "未知错误"}", isError = true)
                done += "已挂 ${drafts.size} 条资源"
            }
        }
        set.str("day")?.let { day ->
            if (dryRun) {
                done += "（dryRun）会把「${hit.title}」从 ${hit.day} 挪到 $day"
            } else {
                val result = runCatching { svc.moveOccurrence(env(planId, "tool.plan.edit.move"), occurrenceId, day, "AI 重排微调") }
                    .getOrElse { return OpResult("挪动失败：${it.message ?: it}", isError = true) }
                if (!result.ok) return OpResult("挪动失败：${result.error?.message ?: "未知错误"}", isError = true)
                done += "已挪到 $day"
            }
        }
        if (set.str("status") != null) {
            if (set.str("status") != "done") return OpResult("set.status 目前只支持 done。", isError = true)
            if (dryRun) {
                done += "（dryRun）会把「${hit.title}」标完成"
            } else {
                val result = dispatch(
                    "complete_plan_node",
                    buildJsonObject {
                        put("occurrenceId", occurrenceId)
                        put("confirmed", true)
                    },
                )
                if (result.isError) return OpResult(result.content, isError = true)
                done += "已标完成：" + result.content
            }
        }
        if (done.isEmpty()) return OpResult("这次 update 没有可执行的字段（支持 day / status / resources）。", isError = true)
        return OpResult(done.joinToString("；"), planId = planId)
    }

    private data class OccurrenceHit(
        val occurrenceId: String,
        val title: String,
        val week: String,
        val day: String,
    )

    private fun findOccurrence(svc: LearningPlanService, planId: String, occurrenceId: String): OccurrenceHit? {
        val today = clock.today()
        var weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks(8)
        repeat(17) {
            val week = isoWeekOf(weekStart)
            val view = runCatching { svc.week(planId, week) }.getOrNull()
            view?.days?.forEach { day ->
                day.items.firstOrNull { it.occurrenceId == occurrenceId }?.let {
                    return OccurrenceHit(it.occurrenceId, it.title, week, day.day)
                }
            }
            weekStart = weekStart.plusWeeks(1)
        }
        return null
    }

    private fun rewriteRearrangedWeeks(svc: LearningPlanService, planId: String, ctx: RearrangeContext) {
        val store = docStore() ?: return
        val weeks = linkedSetOf(isoWeekOf(ctx.today))
        ctx.plan.moves.forEach { move ->
            weeks += isoWeekOf(LocalDate.parse(move.toDay))
            move.fromDay?.let { from -> weeks += isoWeekOf(LocalDate.parse(from)) }
        }
        weeks.forEach { week ->
            val view = runCatching { svc.week(planId, week) }.getOrNull() ?: return@forEach
            store.saveWeek(planId, week, renderWeekDoc(view))
        }
    }

    private fun renderWeekDoc(view: WeekView): String = buildString {
        appendLine("# ${view.week} 本周计划")
        view.days.forEach { day ->
            if (day.items.isEmpty()) return@forEach
            appendLine()
            appendLine("## ${day.label} ${day.day}")
            day.items.forEach { item ->
                appendLine("- ${item.title}（约 ${item.minutes} 分钟）")
            }
        }
    }

    private fun rewriteProgressDoc(planId: String, ctx: RearrangeContext) {
        val store = docStore() ?: return
        val doc = store.read(planId) ?: return
        val block = buildString {
            appendLine("## 进度")
            appendLine("- 最近重排：${ctx.today}（今天多投 ${ctx.extraMinutes} 分钟）")
            appendLine("- 移动 ${ctx.plan.moves.size} 项；其中 ${ctx.plan.overflowCount} 项排到下周。")
            val next = ctx.plan.assignments.groupBy { it.day }.toSortedMap().entries.take(3)
            next.forEach { (day, items) ->
                appendLine("- $day：" + items.joinToString("、") { it.item.title })
            }
        }
        store.save(planId, replaceSection(doc, "## 进度", block))
    }

    // ---------------------------------------------------------------- 文档阶段块

    private data class StageDoc(
        val name: String,
        val weekStart: String,
        val weekEnd: String,
        val focus: String = "",
        val deliverable: String = "",
        val acceptance: String = "",
        val resources: List<DocResource> = emptyList(),
    )

    private fun rewriteDocStages(planId: String, stages: List<StageDoc>) {
        val store = docStore() ?: return
        val existing = store.read(planId)
        val block = renderStages(stages)
        val updated = when {
            existing.isNullOrBlank() -> "# 目标与验收\n\n$block"
            else -> replaceSection(existing, "## 阶段", block)
        }
        store.save(planId, updated)
    }

    private fun renderStages(stages: List<StageDoc>): String = buildString {
        appendLine("## 阶段")
        stages.forEachIndexed { index, stage ->
            appendLine("### 阶段 ${index + 1} · ${stage.name}（${stage.weekStart} – ${stage.weekEnd}）")
            if (stage.focus.isNotBlank()) appendLine("- 聚焦：${stage.focus}")
            if (stage.deliverable.isNotBlank()) appendLine("- 交付物：${stage.deliverable}")
            if (stage.acceptance.isNotBlank()) appendLine("- 过关标准：${stage.acceptance}")
            if (stage.resources.isNotEmpty()) {
                appendLine("- 资源：")
                stage.resources.forEach { resource ->
                    val note = if (resource.note.isBlank()) "" else " — ${resource.note}"
                    if (resource.url.isBlank()) {
                        appendLine("  - ${resource.title}$note")
                    } else {
                        appendLine("  - [${resource.title}](${resource.url})$note")
                    }
                }
            }
            appendLine()
        }
    }

    private fun replaceSection(doc: String, heading: String, block: String): String {
        val lines = doc.lines()
        val start = lines.indexOfFirst { it.trim() == heading }
        val blockLines = block.trimEnd().lines()
        if (start < 0) {
            val insertAt = lines.indexOfFirst { it.trim().startsWith("## ") }
            return if (insertAt < 0) {
                doc.trimEnd() + "\n\n" + block.trimEnd() + "\n"
            } else {
                (lines.subList(0, insertAt) + blockLines + "" + lines.subList(insertAt, lines.size)).joinToString("\n")
            }
        }
        var end = lines.size
        for (index in start + 1 until lines.size) {
            if (lines[index].trim().startsWith("## ")) {
                end = index
                break
            }
        }
        return (lines.subList(0, start) + blockLines + lines.subList(end, lines.size)).joinToString("\n")
    }

    // ---------------------------------------------------------------- 公共小件

    private fun noService(): ToolResult {
        val reason = dbError()?.takeIf { it.isNotBlank() }
        return ToolResult(
            "计划库打不开，这次没动任何东西。" + (reason?.let { "具体原因：$it。" } ?: ""),
            isError = true,
        )
    }

    private suspend fun dispatch(name: String, args: JsonObject): ToolResult =
        legacy[name]?.call(args) ?: ToolResult("内部缺少工具 $name，这次没动数据。", isError = true)

    private fun env(planId: String, origin: String): CommandEnvelope = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.AGENT,
        planId = planId,
        origin = origin,
    )

    private fun resolvePlanId(explicit: String?, svc: LearningPlanService): String? =
        explicit?.takeIf { it.isNotBlank() }
            ?: selectedPlanId()
            ?: runCatching { svc.activePlanId() }.getOrNull()

    private fun requirePlanId(planId: String?): String? = planId?.takeIf { it.isNotBlank() }

    private fun resolveUnitIds(
        svc: LearningPlanService,
        planId: String,
        week: String,
        match: JsonObject,
    ): List<String> {
        val byId = match.str("id")
        val byTitle = match.str("title")
        if (byId == null && byTitle == null) return emptyList()
        val commitment = runCatching { svc.week(planId, week) }.getOrNull()?.commitment?.units.orEmpty()
        val pool = runCatching { svc.pool(planId) }.getOrDefault(emptyList())
        val fromCommitment = commitment.filter {
            (byId != null && it.id == byId) || (byId == null && byTitle != null && it.title == byTitle)
        }.map { it.id }
        if (fromCommitment.isNotEmpty()) return fromCommitment
        return pool.filter {
            (byId != null && it.id == byId) || (byId == null && byTitle != null && it.title == byTitle)
        }.map { it.id }
    }

    private fun findWeekItem(
        svc: LearningPlanService,
        planId: String,
        week: String,
        unitId: String,
    ): WeekDayItem? {
        val view = runCatching { svc.week(planId, week) }.getOrNull() ?: return null
        val unit = view.commitment.units.firstOrNull { it.id == unitId } ?: return null
        return view.days.flatMap { it.items }.firstOrNull { it.title == unit.title }
    }

    private fun inferKind(url: String): String = when {
        url.contains("bilibili.com") -> "bilibili"
        url.isBlank() -> "other"
        else -> "web"
    }

    // ---------------------------------------------------------------- JSON 小件

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arrElement(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.arr(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.strArr(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }.orEmpty()
}
