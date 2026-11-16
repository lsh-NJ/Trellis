package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.feature.learning.PlanDocStore
import dev.trellis.feature.learning.PlanWorkflowStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `create_plan`：目标式建计划的三步流程，每一步都停在「等用户确认」。
 *
 * 为什么拆成三步：调研、写大纲、排本周是三种不同性质的写入，
 * 以前 research_plan 一次全干完，用户看到调研结果时计划库里已经写死了（单元 append 不可撤销）。
 * 这里把前两步做成「可重做」的草稿，第三步才把节点追加进本周队列。
 *
 * 状态存在 `job` / `job_step`（[PlanWorkflowStore]）：换一轮对话、关一次应用都能接着走，
 * 不靠模型记忆。`advance` 必须带 confirmed=true —— 这是「每一步都要用户点头」的硬闸门。
 */
internal class CreatePlanTools(
    private val service: () -> LearningPlanService?,
    private val workflow: () -> PlanWorkflowStore?,
    private val research: () -> ResearchTools?,
    private val clock: TrellisClock,
    private val dispatch: suspend (String, JsonObject) -> ToolResult,
    private val onChanged: () -> Unit,
    private val dbError: () -> String? = { null },
    private val bilibili: BilibiliClient = BilibiliClient(),
    private val docStore: () -> PlanDocStore? = { null },
) {

    val guidance: String = listOf(
        "<how_to_create_plan>",
        "1. 用户只说了目标、没有现成材料时，用 create_plan 建计划：它固定三步，每一步都要停下来让用户看结果、确认了才走下一步。",
        "   第一步 调研（查资料 + 出阶段表）→ 第二步 建计划 + 写总文档 → 第三步 排本周（周文档 + 本周节点进队列，不排到天）。",
        "2. 第一次调 action=\"start\"（带 goal，deadline/dailyMinutes 用户说清才传）；之后每一步都用 action=\"advance\" + confirmed=true 推进。",
        "   不要跳步，也不要自己调 update_plan 去建计划绕过这个流程。用户要改当前这一步用 action=\"revise\"（可带 feedback）。",
        "3. 每次工具会返回「这一步产出了什么」和「下一步会做什么」。先把结果用人话讲给用户看，再问一句要不要继续；",
        "   用户说继续、同意、可以，下一次工具调用才带 confirmed=true。",
        "4. 节点标题写「具体内容/知识点」（如「TCP 三次握手」「RAG 的召回与重排」），不写「手写/读完/掌握」这类动作或指标；",
        "   节点进本周队列后不排到具体天，用户完成后系统自己记到当天。",
        "5. 用户中途不要了用 action=\"cancel\"；想看走到哪一步用 action=\"status\"。",
        "</how_to_create_plan>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(CreatePlan())

    private val stepOrder: List<String> = listOf("research", "plan", "week")

    private fun stepLabel(step: String): String = when (step) {
        "research" -> "调研目标与资料"
        "plan" -> "建计划并写总文档"
        "week" -> "排本周任务"
        else -> step
    }

    private inner class CreatePlan : AgentTool {
        override val name: String = "create_plan"
        override val description: String =
            "按三步建一份学习计划，每一步都停下来等用户确认：调研 → 建计划+总文档 → 排本周队列。" +
                "第一次 action=start（带 goal）；之后 action=advance + confirmed=true 推进一步；action=revise 按反馈重做当前步。"
        override val parameters: String = """
            action: start | advance | revise | status | cancel（默认 start）
            goal: start 时必填，一句话目标
            deadline: 可选，YYYY-MM-DD（用户说了才传）
            dailyMinutes: 可选，30–240（用户说清每天投入才传）
            bvid / bvids: 可选，B 站课程（只作为资源入库，不直接生成节点）
            urls / text: 可选，外部链接或用户粘贴的清单（同样只进资源池）
            jobId: 可选，不传就用最近一个还没结束的建计划流程
            confirmed: advance 必须为 true（用户已明确同意进入下一步）
            feedback: revise 时可选，用户对当前这一步的修改意见
            markdown: revise 第二步时可选，整份替换总文档
        """.trimIndent()

        override suspend fun call(arguments: JsonObject): ToolResult = runCatching {
            val wf = workflow() ?: return@runCatching ToolResult("计划库打不开，这次没开始建计划。", isError = true)
            when (val action = arguments.str("action") ?: "start") {
                "start" -> start(wf, arguments)
                "advance" -> advance(wf, arguments)
                "revise" -> revise(wf, arguments)
                "status" -> status(wf, arguments)
                "cancel" -> cancel(wf, arguments)
                else -> ToolResult("action 只能是 start / advance / revise / status / cancel。", isError = true)
            }
        }.getOrElse { ToolResult("建计划流程出错：" + (it.message ?: it), isError = true) }
    }

    // ---------------------------------------------------------------- 四个动作

    private suspend fun start(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val open = wf.latestOpen("create_plan")
        if (open != null) {
            return ToolResult(
                "已经有一份建计划流程在进行中（jobId=" + open.id + "，当前：" +
                    (open.steps.firstOrNull { it.state == "running" }?.let { stepLabel(it.step) } ?: "进行中") +
                    "）。先推进或取消它，再开新的一份。",
                isError = true,
            )
        }
        val goal = arguments.str("goal")?.trim().orEmpty()
        if (goal.isEmpty()) return ToolResult("start 需要 goal（一句话目标）。", isError = true)
        val deadline = arguments.str("deadline")?.trim().orEmpty()
        val daily = arguments.int("dailyMinutes")?.coerceIn(30, 240)
        val materials = materialsOf(arguments)
        val payload = buildJsonObject {
            put("goal", goal)
            put("deadline", deadline)
            daily?.let { put("dailyMinutes", it) }
            put("bvids", kotlinx.serialization.json.JsonArray(materials.bvids.map { JsonPrimitive(it) }))
            put("urls", kotlinx.serialization.json.JsonArray(materials.urls.map { JsonPrimitive(it) }))
            put("text", materials.text)
        }.toString()
        val jobId = wf.create("create_plan", payload, stepOrder)
        return runStep(wf, wf.load(jobId)!!, "research", payload, feedback = null)
    }

    private suspend fun advance(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        if (arguments.bool("confirmed") != true) {
            return ToolResult(
                "这次没有往下走：进入下一步之前要让用户明确同意。先说说这一步的结果，用户说「继续 / 可以」之后再带 confirmed=true。",
                isError = true,
            )
        }
        val job = resolveJob(wf, arguments) ?: return ToolResult("没有进行中的建计划流程；先 action=start。", isError = true)
        if (job.state != "awaiting_user") {
            return ToolResult("这份流程现在不在等确认（状态：" + job.state + "）。用 action=status 看进度，或 revise 重做当前步。", isError = true)
        }
        // job_step 的 state 只认 pending/running/done/failed/skipped（v1 契约）；
        // 「等确认」是 job 级状态，这一步本身记 running：输出已经写好，等用户点头后再推进。
        val current = job.steps.firstOrNull { it.state == "running" }
            ?: return ToolResult("流程状态不完整，用 action=status 看一下。", isError = true)
        val index = stepOrder.indexOf(current.step)
        if (index < 0) return ToolResult("不认识的步骤：" + current.step, isError = true)
        if (index == stepOrder.lastIndex) {
            wf.setJobState(job.id, "done")
            return ToolResult("三步都完成了，这份计划已经建好。")
        }
        val next = stepOrder[index + 1]
        // 用户点了「继续」= 这一步验收通过：把它记成 done，再执行下一步。
        // 不记的话，下一步跑完会有两个 running，advance 永远回到最早那一步。
        wf.setStep(job.id, current.step, "done")
        return runStep(wf, job, next, job.payload, feedback = null)
    }

    private suspend fun revise(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult("没有进行中的建计划流程；先 action=start。", isError = true)
        val current = job.steps.lastOrNull { it.state == "running" || it.state == "failed" }
            ?: return ToolResult("这份流程没有可重做的步骤。", isError = true)
        val feedback = arguments.str("feedback")?.trim()
        return when (current.step) {
            "research" -> runStep(wf, job, "research", job.payload, feedback)
            "plan" -> {
                val markdown = arguments.str("markdown")
                    ?: return ToolResult(
                        "计划已经建好、文档也写了。要改总文档就带 markdown（整份替换）；只想继续就 advance。",
                        isError = true,
                    )
                val planId = job.payload.stringField("planId")
                    ?: return ToolResult("流程里没有计划 id，先重新 start。", isError = true)
                // 用 update_plan 的 write_doc（显式带 planId），不走 write_plan_doc 的「当前活跃计划」。
                val saved = dispatch(
                    "update_plan",
                    buildJsonObject {
                        put("planId", planId)
                        put("confirmed", true)
                        put("ops", kotlinx.serialization.json.buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("op", "write_doc")
                                    put("markdown", markdown)
                                },
                            )
                        })
                    },
                )
                if (saved.isError) return saved
                wf.setStep(job.id, "plan", "running", output = saved.content)
                onChanged()
                ToolResult(saved.content + "\n总文档已按你的修改重写；确认后进入第三步排本周。")
            }
            "week" -> runStep(wf, job, "week", job.payload, feedback, replanWeek = true)
            else -> ToolResult("不认识的步骤：" + current.step, isError = true)
        }
    }

    private fun status(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult("现在没有进行中的建计划流程。", isError = true)
        val text = buildString {
            appendLine("建计划流程 " + job.id + "（状态 " + job.state + "）：")
            job.steps.forEachIndexed { index, step ->
                appendLine("  " + (index + 1) + ". " + stepLabel(step.step) + " — " + when (step.state) {
                    "done" -> "已完成"
                    "failed" -> "失败" + (step.error?.let { "：" + it } ?: "")
                    "running" -> if (job.state == "awaiting_user") "等确认" else "进行中"
                    else -> "还没开始"
                })
            }
            job.payload.stringField("planId")?.let { appendLine("计划：" + it) }
            job.payload.stringField("week")?.let { appendLine("本周：" + it) }
        }
        return ToolResult(text.trimEnd())
    }

    private fun cancel(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult("现在没有进行中的建计划流程。", isError = true)
        wf.setJobState(job.id, "cancelled")
        return ToolResult("已取消这份建计划流程。已经写进计划库的内容（如果有）不会自动删；要删计划去计划页。")
    }

    // ---------------------------------------------------------------- 每一步

    private suspend fun runStep(
        wf: PlanWorkflowStore,
        job: PlanWorkflowStore.Job,
        step: String,
        payload: String,
        feedback: String?,
        replanWeek: Boolean = false,
    ): ToolResult {
        val result = when (step) {
            "research" -> runResearch(job, payload, feedback)
            "plan" -> runPlan(job, payload)
            "week" -> runWeek(payload, replanWeek)
            else -> ToolResult("不认识的步骤：" + step, isError = true)
        }
        if (result.isError) {
            wf.setStep(job.id, step, "failed", error = result.content)
            wf.setJobState(job.id, "running")
            return result
        }
        val isLast = stepOrder.lastOrNull() == step
        wf.setStep(job.id, step, if (isLast) "done" else "running", output = result.content)
        if (isLast) {
            wf.setJobState(job.id, "done")
            return ToolResult(result.content + "\n三步都完成了。")
        }
        wf.setJobState(job.id, "awaiting_user")
        val next = stepOrder[stepOrder.indexOf(step) + 1]
        return ToolResult(
            result.content +
                "\n\n下一步（" + stepLabel(next) + "）还没做。先把上面的结果讲给用户看，问一句要不要继续；" +
                "用户同意后再调 create_plan：action=advance，jobId=" + job.id + "，confirmed=true。",
        )
    }

    private suspend fun runResearch(job: PlanWorkflowStore.Job, payload: String, feedback: String?): ToolResult {
        val rt = research() ?: return ToolResult("调研工具没准备好，这次没开始。", isError = true)
        val goal = payload.stringField("goal").orEmpty()
        val deadline = payload.stringField("deadline").orEmpty()
        val daily = payload.stringField("dailyMinutes")?.toIntOrNull()
        val effectiveGoal = if (feedback.isNullOrBlank()) goal else goal + "\n用户补充要求：" + feedback
        return when (val result = rt.draftShellOnly(effectiveGoal, deadline, daily) {}) {
            is ResearchTools.DraftResult.Fail -> ToolResult(result.message, isError = true)
            is ResearchTools.DraftResult.Ok -> {
                val outcome = result.outcome
                val summary = renderResearch(outcome)
                val wf = workflow()!!
                wf.updatePayload(
                    job.id,
                    payloadWith(
                        payload,
                        "raw" to JsonPrimitive(outcome.raw),
                        "goal" to JsonPrimitive(effectiveGoal),
                        "deadline" to JsonPrimitive(outcome.deadline ?: ""),
                        "summary" to JsonPrimitive(summary),
                    ),
                )
                ToolResult(summary)
            }
        }
    }

    private fun renderResearch(outcome: ResearchTools.DraftOutcome): String = buildString {
        val draft = outcome.resolved
        appendLine("调研完成：计划「" + draft.title + "」，共 " + draft.stages.size + " 个阶段。")
        draft.stages.forEachIndexed { index, stage ->
            appendLine((index + 1).toString() + ". " + stage.name + "（约 " + stage.weeks + " 周）")
            if (stage.focus.isNotBlank()) appendLine("   聚焦：" + stage.focus)
            if (stage.deliverable.isNotBlank()) appendLine("   交付物：" + stage.deliverable)
            if (stage.acceptance.isNotBlank()) appendLine("   过关标准：" + stage.acceptance)
            if (stage.resources.isNotEmpty()) {
                appendLine("   资源：" + stage.resources.joinToString("；") { r ->
                    if (r.url.isBlank()) r.title else "[" + r.title + "](" + r.url + ")"
                })
            }
        }
        outcome.deadline?.let { appendLine("截止：$it") }
        if (outcome.sources != null) {
            appendLine("参考了网上 " + outcome.sources.hits.size + " 条资料（" + outcome.sources.pages.size + " 篇读了正文）。")
        }
        append("这一版还没写进计划库。")
    }

    private suspend fun runPlan(job: PlanWorkflowStore.Job, payload: String): ToolResult {
        val svc = service() ?: return ToolResult(noService(), isError = true)
        val rt = research() ?: return ToolResult("调研工具没准备好。", isError = true)
        val goal = payload.stringField("goal").orEmpty()
        val raw = payload.stringField("raw")
            ?: return ToolResult("流程里没有调研结果（第一步可能没完成），先 revise 重做第一步。", isError = true)
        val deadline = payload.stringField("deadline")?.takeIf { it.isNotBlank() }
        val outcome = rt.restoreDraft(goal, deadline, raw)
            ?: return ToolResult("调研结果读不回来了（可能被截断），先 revise 重做第一步。", isError = true)
        val daily = payload.stringField("dailyMinutes")?.toIntOrNull()
        val applied = rt.applyShell(svc, outcome, daily) {}
            ?: return ToolResult("建计划失败：计划库没有返回计划 id。", isError = true)
        // 新计划走周队列：节点不排到天，完成时记到当天。
        runCatching { svc.setScheduleMode(env(applied.planId, "plan.queue"), applied.planId, "queue") }
        // 有材料就入库：B 站分集/外部链接先成为资源池里的材料，不在这一步生成节点。
        val materials = materialsFromPayload(payload)
        // 资源先写进总文档的「## 资源清单」，不建资源池；周规划读整份文档当上下文。
        val ingested = if (materials.bvids.isNotEmpty() || materials.urls.isNotEmpty() || materials.text.isNotBlank()) {
            runCatching {
                writeResourceList(docStore(), applied.planId, bilibili, materials.bvids, materials.urls, materials.text)
            }.getOrNull()
        } else {
            null
        }
        val week = isoWeekOf(clock.today())
        workflow()!!.updatePayload(
            job.id,
            payloadWith(payload, "planId" to JsonPrimitive(applied.planId), "week" to JsonPrimitive(week)),
        )
        onChanged()
        return ToolResult(
            applied.receipt + (ingested?.let { "\n" + it.summary + "分集/链接只作参考，不是节点。" } ?: ""),
        )
    }

    private suspend fun runWeek(payload: String, replan: Boolean): ToolResult {
        val planId = payload.stringField("planId")
            ?: return ToolResult("流程里没有计划 id，先完成第二步。", isError = true)
        val week = payload.stringField("week") ?: isoWeekOf(clock.today())
        val daily = payload.stringField("dailyMinutes")?.toIntOrNull()
        val result = dispatch(
            "plan_week",
            buildJsonObject {
                put("planId", planId)
                put("week", week)
                daily?.let { put("dailyMinutes", it) }
                if (replan) put("replan", true)
            },
        )
        if (result.isError) return result
        onChanged()
        return ToolResult(result.content)
    }

    private fun runSchedule(payload: String): ToolResult {
        val svc = service() ?: return ToolResult(noService(), isError = true)
        val planId = payload.stringField("planId")
            ?: return ToolResult("流程里没有计划 id，先完成第二步。", isError = true)
        val week = payload.stringField("week") ?: isoWeekOf(clock.today())
        val view = runCatching { svc.week(planId, week) }.getOrNull()
            ?: return ToolResult("读不到 $week 这一周，先确认计划库正常。", isError = true)
        val today = clock.today()
        val nowMinute = nowMinuteOfDay()
        var placed = 0
        val failures = mutableListOf<String>()
        val text = buildString {
            appendLine("按钟点排 $week：")
            view.days.forEach { day ->
                val date = runCatching { LocalDate.parse(day.day) }.getOrNull() ?: return@forEach
                if (date.isBefore(today)) return@forEach
                val items = day.items.filter { it.status == "planned" }
                if (items.isEmpty()) return@forEach
                // 今天从「现在之后的最近一刻钟」开始，未来从 9:00 开始；每件之间留 10 分钟。
                var cursor = if (date == today) maxOf(9 * 60, ((nowMinute / 15) + 1) * 15 + 5) else 9 * 60
                val lines = mutableListOf<String>()
                items.forEach { item ->
                    if (cursor + item.minutes <= 23 * 60 + 59) {
                        val result = runCatching {
                            svc.setOccurrenceStart(env(planId, "plan.schedule"), item.occurrenceId, cursor)
                        }.getOrNull()
                        if (result?.ok == true) {
                            placed++
                            lines += hhmm(cursor) + " " + item.title + "（" + item.minutes + " 分钟）"
                        } else {
                            failures += item.title + "：" + (result?.error?.message ?: "写入失败")
                        }
                        cursor += item.minutes + 10
                    } else {
                        failures += item.title + "：当天排不下了（没有写钟点）"
                    }
                }
                if (lines.isNotEmpty()) {
                    appendLine("  " + day.label + "（" + day.day + "）")
                    lines.forEach { appendLine("    " + it) }
                }
            }
        }
        if (placed == 0) {
            return ToolResult(
                "这一周没有可排的节点（第三步可能没排成），先返回上一步检查。",
                isError = true,
            )
        }
        val tail = if (failures.isEmpty()) "" else "\n没排上的：" + failures.joinToString("；")
        return ToolResult(text.trimEnd() + "\n共定了 " + placed + " 条钟点。" + tail)
    }

    // ---------------------------------------------------------------- 小件

    private fun nowMinuteOfDay(): Int {
        val time = java.time.Instant.ofEpochMilli(clock.nowMs()).atZone(clock.zone).toLocalTime()
        return time.hour * 60 + time.minute
    }

    private fun hhmm(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

    private fun env(planId: String, origin: String) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.AGENT,
        planId = planId,
        origin = origin,
    )

    private fun noService(): String {
        val reason = dbError()?.takeIf { it.isNotBlank() }
        return "计划库打不开，这次没写任何东西。" + (reason?.let { "具体原因：$it。" } ?: "")
    }

    private data class Materials(
        val bvids: List<String> = emptyList(),
        val urls: List<String> = emptyList(),
        val text: String = "",
    )

    private fun stringList(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { v -> v.isNotEmpty() } }
            .orEmpty()

    private fun materialsOf(arguments: JsonObject): Materials {
        val bvids = buildList {
            arguments.str("bvid")?.let { add(it) }
            addAll(stringList(arguments["bvids"]))
        }
        return Materials(
            bvids = bvids.distinct(),
            urls = stringList(arguments["urls"]),
            text = arguments.str("text").orEmpty(),
        )
    }

    private fun materialsFromPayload(payload: String): Materials {
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return Materials()
        return Materials(
            bvids = stringList(root["bvids"]),
            urls = stringList(root["urls"]),
            text = root["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun resolveJob(wf: PlanWorkflowStore, arguments: JsonObject): PlanWorkflowStore.Job? {
        val id = arguments.str("jobId")
        return if (id != null) wf.load(id) else wf.latestOpen("create_plan")
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    private fun String.stringField(key: String): String? {
        val root = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull() ?: return null
        return root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun payloadWith(payload: String, vararg pairs: Pair<String, JsonElement>): String {
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        return JsonObject(root + pairs.toMap()).toString()
    }
}
