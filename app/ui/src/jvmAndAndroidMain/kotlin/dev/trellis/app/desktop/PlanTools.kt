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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 一次最多能建多少个单元；bvid 全量导入和手写 units 共用这个上限。 */
private const val UNIT_LIMIT = 200

/**
 * 一集拆出来的单元分钟数：0 → 25；<10 → 10；10..45 原样；>45 拆两段，各取一半并夹在 10..45。
 */
private fun unitMinutesOf(durationMinutes: Int): List<Int> = when {
    durationMinutes <= 0 -> listOf(25)
    durationMinutes < 10 -> listOf(10)
    durationMinutes <= 45 -> listOf(durationMinutes)
    else -> {
        val half = durationMinutes / 2
        listOf(half.coerceIn(10, 45), (durationMinutes - half).coerceIn(10, 45))
    }
}

/** 标题里的「第X章」（X 是数字或中文数字）。 */
private val CHAPTER_IN_TITLE = Regex("""第\s*([0-9]+|[一二三四五六七八九十百零〇两]+)\s*章""")

/** 标题开头的 "1.2" / "2" 这类章节号；取第一段数字当章。 */
private val LEADING_NUMBER = Regex("""^(\d+)(?:[.．]\d+)*""")

/** 章节：优先标题里的「第X章」；否则标题开头的数字取首段 → 「第 N 章」；都没有 → 未分章。 */
private fun chapterOf(title: String): String {
    CHAPTER_IN_TITLE.find(title)?.let { match -> return "第" + match.groupValues[1] + "章" }
    LEADING_NUMBER.find(title)?.let { match -> return "第" + match.groupValues[1] + "章" }
    return "未分章"
}

/**
 * 分集 → 确定性单元：标题取分集名（空就用 P{page}），长分集拆「上/下」，
 * 章节从标题里找。整门课全量导入，不靠模型逐条转录。
 */
private fun unitsFromParts(parts: List<BilibiliClient.Part>): List<UnitDraft> = buildList {
    parts.forEach { part ->
        val title = part.title.trim().ifEmpty { "P" + part.page }
        val chapter = chapterOf(title)
        val minutes = unitMinutesOf(part.durationMinutes)
        if (minutes.size == 1) {
            add(UnitDraft(chapter = chapter, title = title, estimateMinutes = minutes[0]))
        } else {
            add(UnitDraft(chapter = chapter, title = title + "（上）", estimateMinutes = minutes[0]))
            add(UnitDraft(chapter = chapter, title = title + "（下）", estimateMinutes = minutes[1]))
        }
    }
}

/**
 * 从 [start] 开始按 [budgetMinutes] 分钟预算顺序取一周的单元（最多 7 个）。
 *
 * 第一个单元一定取（哪怕它本身超预算），否则预算小的用户会永远排不出东西。
 */
private fun takeWeekBatch(units: List<UnitDraft>, start: Int, budgetMinutes: Int): List<Int> {
    val picked = mutableListOf<Int>()
    var used = 0
    var index = start
    while (index < units.size && picked.size < 7) {
        val minutes = units[index].estimateMinutes
        if (picked.isNotEmpty() && used + minutes > budgetMinutes) break
        picked += index
        used += minutes
        index++
    }
    return picked
}

/**
 * source 模式（bvid 全量导入）的排周：本周按 dailyMinutes×7×0.8 的预算取一批，
 * 下一周再取一批；取不到就不建承诺，剩下的都留在「待安排」池里。
 *
 * 周号由调用方从注入时钟算好传进来：工具不能自己取系统时间，
 * 否则开发者模式拨日期后，AI 建出来的计划仍按真实日期排。
 */
private fun autoCommitments(
    units: List<UnitDraft>,
    dailyMinutes: Int,
    thisWeek: String,
    nextWeek: String,
): List<CommitmentDraft> {
    val budgetMinutes = (dailyMinutes * 7 * 0.8).toInt()
    val firstBatch = takeWeekBatch(units, 0, budgetMinutes)
    val secondBatch = takeWeekBatch(units, firstBatch.size, budgetMinutes)
    return buildList {
        if (firstBatch.isNotEmpty()) add(CommitmentDraft(week = thisWeek, unitIndexes = firstBatch))
        if (secondBatch.isNotEmpty()) add(CommitmentDraft(week = nextWeek, unitIndexes = secondBatch))
    }
}

/** 参数里的字符串（去空白）；没有或是 null 就是 null。 */
private fun JsonObject.textArg(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** 参数里的整数；模型偶尔把数字写成字符串，两种都收。 */
private fun JsonObject.intArg(name: String): Int? =
    (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

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
 *
 * 排 B 站课程时模型只选源（`bvid` + 可选起止集），单元由宿主按分集**确定性**生成：
 * 模型逐条转录分集标题一定会漏、会改错，不如根本不给它这个机会。
 */
class PlanTools(
    private val service: () -> LearningPlanService?,
    private val onChanged: () -> Unit,
    /** 设置页里的「每天预计投入」×7；没设置就是 null，计划就只记标题和目标。 */
    private val weeklyCapacity: () -> Int? = { null },
    /** 只读的 B 站客户端；`bvid` 模式直接拉分集生成全部单元。测试可注入假 fetch。 */
    private val bilibili: BilibiliClient = BilibiliClient(),
    /** 计划库打不开时的具体原因（AppRuntime 存好的）；给模型看，别让它对用户说「打不开」。 */
    private val planDbError: () -> String? = { null },
    /** 时间源：本周/下周从这里算（开发者模式拨日期后跟着变）。 */
    private val clock: TrellisClock = SystemTrellisClock(),
) {

    /** 今天所在的 ISO 周，比如 "2026-W44"。 */
    private fun currentIsoWeek(): String = clock.isoWeek()

    /** 下一 ISO 周。 */
    private fun nextIsoWeek(): String = isoWeekOf(clock.today().plusWeeks(1))

    val guidance: String = listOf(
        "<how_to_create_learning_plan>",
        "1. `set_plan` 只写这一轮的动作；**计划页要的是 create_learning_plan**。",
        "   用户点「排成学习计划」、或者说「排个计划 / 安排每周」时，调 create_learning_plan。",
        "2. 来源是 B 站课程时，**优先 bvid 全量导入**：把 bvid 直接交给 create_learning_plan(bvid=...)，",
        "   可选 fromPart/toPart 只导入第几集到第几集。宿主会自己拉分集、按分集标题生成全部单元，",
        "   并把本周+下周排好，其余留在「待安排」。**总路线要全**：不要替用户挑「前面几集」，也不要逐条抄分集标题。",
        "   只有「落成待办」那条路（add_tasks）才从目录里挑 6-12 集；排计划不要挑。",
        "3. 用了 bvid 就不要再写 units / weeks：单元和排周都由宿主自动做。手写会和分集对不上，工具会直接报错让你删掉。",
        "4. 排计划前先问一句「你每天大概能投入多少分钟」（15–240），拿到的值填 dailyMinutes。",
        "   不知道就先按 60 分钟建，并在回复里说明「按每天 60 分钟排的，要改跟我说」。",
        "5. 只有来源不是 B 站（自己的书 / 网课章节）才手写 units + weeks：",
        "   单元时间按真实时长估（10–45 分钟）；第一次只给**本周和下一周**建承诺（最多 2 周、每周 1–7 个），其余单元留在池里。",
        "6. 手写 units + weeks 时第一周必须是**本周**（今天所在的那一周）：计划从今天开始排，不要从下周开始。",
        "7. 写完说清「计划页第 X 周有多少单元、待安排还剩几个」；不要去改用户别的数据。",
        "</how_to_create_learning_plan>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(CreateLearningPlan())

    private inner class CreateLearningPlan : AgentTool {
        override val name: String = "create_learning_plan"
        override val description: String =
            "在「计划」页建一份学习计划。排 B 站课程时优先传 bvid：宿主会直接拉分集，" +
                "把整门课**全量**变成单元（总路线要全），并自动把本周+下周排好，其余进「待安排」；" +
                "传了 bvid 就不要手写 units/weeks。非 B 站来源才手写 units + weeks（第一周必须是本周，最多 2 周、每周 1–7 个）。" +
                "dailyMinutes 是用户每天能投入的分钟数（15–240，拿不准就按 60）。会写用户数据，只在用户要「排成计划」时调用。"
        override val parameters: String =
            "bvid：B 站视频 BV 号（首选，例：BV1YE411D7nH）；传了就不要再传 units/weeks，单元由宿主按分集生成、排周也由宿主自动做。" +
                "fromPart / toPart：可选，只导入第几集到第几集（1-based，省略=全部集）。" +
                "title：计划标题（如「操作系统速通」）；" +
                "dailyMinutes：每天投入分钟数（15–240，默认 60）；" +
                "units：数组，每项 {title, minutes?, chapter?}（title 必填，minutes 10–45，默认 25），只在手写路径用；" +
                "weeks：数组，每项 {week: \"2026-W44\", units: [单元 title...]}（最多 2 周，每周 1–7 个），只在手写 units 时用。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val plan = service() ?: return ToolResult(
                buildString {
                    append("计划库打不开，没写成。")
                    planDbError()?.takeIf { it.isNotBlank() }?.let { append("具体原因：").append(it).append("。") }
                    append("先调 get_diagnostics 看清楚环境（是缺运行时模块、文件坏了还是权限问题），")
                    append("再决定重试、绕开还是让用户做一个真能执行的修复动作；不要把「打不开」原样丢回去让用户自己修。")
                },
                isError = true,
            )
            val title = arguments.textArg("title") ?: "学习计划"
            val dailyMinutes = arguments.intArg("dailyMinutes")?.coerceIn(15, 240)
                ?: (weeklyCapacity()?.div(7)?.takeIf { it >= 15 } ?: 60)

            val bvid = arguments.textArg("bvid")
            if (bvid != null) return createFromSource(plan, arguments, title, dailyMinutes, bvid)

            // ---------------- 手写 units + weeks 路径（行为不变，只把上限 60 提到 200） ----------------
            val rawUnits = arguments["units"] as? JsonArray
                ?: return ToolResult("units 要写成数组：[{title, minutes}, …]", isError = true)
            if (rawUnits.size > UNIT_LIMIT) {
                return ToolResult("一次最多 $UNIT_LIMIT 个单元，现在有 ${rawUnits.size} 个", isError = true)
            }

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
            if (rawWeeks.size > 2) {
                return ToolResult(
                    "第一次只排本周和下一周（最多 2 周），剩下的单元留在「待安排」池里；现在写了 ${rawWeeks.size} 周。",
                    isError = true,
                )
            }

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

            writePlan(plan, title, dailyMinutes, units, commitments)?.let { return it }

            val weekLines = commitments.joinToString("；") { draft ->
                val count = draft.unitIndexes.size
                "${draft.week} $count 个单元"
            }
            return ToolResult(
                "计划「$title」建好了：${units.size} 个单元，其中 ${commitments.sumOf { it.unitIndexes.size }} 个排在 " +
                    "${commitments.size} 周里（$weekLines），其余 ${units.size - commitments.sumOf { it.unitIndexes.size }} 个" +
                    "留在「待安排」池。每天按 $dailyMinutes 分钟排；去「计划」页能看到周卡片、待安排集合和每天安排。",
            )
        }
    }

    /**
     * bvid 模式：只选源，不手写内容。
     *
     * 分集 → 单元是确定性的（标题/时长/章节都由 [unitsFromParts] 算），
     * 排周也是确定性的（[autoCommitments]：本周 + 下周，其余进池），模型只负责选视频和起止集。
     */
    private suspend fun createFromSource(
        plan: LearningPlanService,
        arguments: JsonObject,
        title: String,
        dailyMinutes: Int,
        bvid: String,
    ): ToolResult {
        if (arguments["weeks"] != null) {
            return ToolResult(
                "用了 bvid 就不用手写 weeks，宿主会自动排本周+下周；把 weeks 删掉再调一次。",
                isError = true,
            )
        }
        val rawUnits = arguments["units"]
        if (rawUnits is JsonArray && rawUnits.isNotEmpty()) {
            return ToolResult(
                "用了 bvid 就不用手写 units：宿主会按分集自动生成全部单元；把 units 删掉再调一次。",
                isError = true,
            )
        }
        val fromPart = arguments.intArg("fromPart")
        val toPart = arguments.intArg("toPart")
        if (fromPart != null && fromPart < 1) {
            return ToolResult("fromPart 是 1-based（第 1 集写 1），现在是 $fromPart", isError = true)
        }
        if (toPart != null && toPart < 1) {
            return ToolResult("toPart 是 1-based（第 1 集写 1），现在是 $toPart", isError = true)
        }
        if (fromPart != null && toPart != null && fromPart > toPart) {
            return ToolResult("fromPart（$fromPart）不能大于 toPart（$toPart）", isError = true)
        }

        val parts = try {
            bilibili.parts(bvid)
        } catch (e: BilibiliException) {
            return ToolResult(e.message ?: "B 站这次没答上来。", isError = true)
        }
        val from = fromPart ?: 0
        val to = toPart ?: Int.MAX_VALUE
        val selected = parts.filter { it.page in from..to }
        if (selected.isEmpty()) {
            val rangeText = when {
                fromPart != null && toPart != null -> "第 $fromPart–$toPart 集"
                fromPart != null -> "第 $fromPart 集之后"
                else -> "第 $toPart 集之前"
            }
            return ToolResult(
                "（$bvid）共 ${parts.size} 集，$rangeText 没有分集；检查一下 fromPart/toPart。",
                isError = true,
            )
        }

        val allUnits = unitsFromParts(selected)
        val truncated = allUnits.size > UNIT_LIMIT
        val units = allUnits.take(UNIT_LIMIT)
        val commitments = autoCommitments(units, dailyMinutes, currentIsoWeek(), nextIsoWeek())
        if (commitments.isEmpty()) {
            return ToolResult("（$bvid）没生成出可排的单元，换一个视频或检查分集时长。", isError = true)
        }

        writePlan(plan, title, dailyMinutes, units, commitments)?.let { return it }

        val placed = commitments.sumOf { it.unitIndexes.size }
        val weekLines = commitments.joinToString("；") { draft ->
            "${draft.week} ${draft.unitIndexes.size} 个单元"
        }
        return ToolResult(
            buildString {
                append("计划「$title」建好了：按 B 站 $bvid 的分集全量导入 ${units.size} 个单元")
                if (truncated) append("（超过 $UNIT_LIMIT 个，已截断：只取前 $UNIT_LIMIT 个）")
                append("，其中 $placed 个排在 ${commitments.size} 周里（$weekLines），其余 ${units.size - placed} 个留在「待安排」池。")
                append("总路线是全的，之后每周会从池子里自动补；每天按 $dailyMinutes 分钟排。")
                append("去「计划」页能看到周卡片、待安排集合和每天安排。")
            },
        )
    }

    /**
     * 三条命令真正落库；失败返回错误 [ToolResult]，成功返回 null（由调用方拼成功文案）。
     * 两条路径（手写 / bvid）共用，避免写库流程各写一遍。
     */
    private fun writePlan(
        plan: LearningPlanService,
        title: String,
        dailyMinutes: Int,
        units: List<UnitDraft>,
        commitments: List<CommitmentDraft>,
    ): ToolResult? {
        val created = plan.createProcess(
            env(null, "plan.create"),
            title = title,
            goal = title,
            // 后端按「周容量」存；用户说的是每天多少分钟，×7 折成周。
            weeklyCapacityMinutes = dailyMinutes * 7,
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
        return null
    }

    private fun env(planId: String?, origin: String) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.AGENT,
        planId = planId,
        origin = origin,
    )
}
