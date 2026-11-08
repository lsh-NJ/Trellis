package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import java.time.LocalDate
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.ResourceDraft
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

/** B 站的一个分集（目录里的一集）。任务说明里也叫它 BilibiliPart。 */
internal typealias BilibiliPart = BilibiliClient.Part

/**
 * 分集标题里出现这些词，就不是课程内容：片尾、预告、花絮、彩蛋、广告、联系方式…
 *
 * B 站目录里混着这些「非正片」条目是常态。不过滤的话，用户会在每周的待办里
 * 看到「片尾」「下集预告」这种节点，还得自己去删。
 */
private val JUNK_PART_WORDS_CN = listOf(
    "片尾", "片头", "预告", "花絮", "彩蛋", "广告", "联系方式", "关注", "下一集", "合集",
    "简介", "开箱", "福利", "抽奖", "直播回放", "宣传片", "三连", "点赞", "投币", "打卡",
)

/**
 * 英文黑名单按**词边界**匹配（大小写不敏感）。
 *
 * 不用裸 "ad" / "intro" 这类短词：电子课里的「AD 转换」、编程课里的「Introduction」
 * 都是正片内容，误杀比漏杀更糟。
 */
private val JUNK_PART_WORDS_EN = listOf(
    "trailer", "preview", "blooper", "bloopers", "outtake", "outtakes",
    "behind the scenes", "sponsor", "sponsored", "advertisement", "contact us",
    "follow me", "subscribe", "next episode", "playlist", "outro", "ending",
)

private fun isJunkPartTitle(raw: String): Boolean {
    val lower = raw.lowercase()
    if (JUNK_PART_WORDS_CN.any { lower.contains(it) }) return true
    return JUNK_PART_WORDS_EN.any { word ->
        Regex("(?<![a-z])" + Regex.escape(word) + "(?![a-z])").containsMatchIn(lower)
    }
}

/** 「第 3 集」「第2话」「第4讲」这类分集序号标签（「第X章」不在此列，章节要保留）。 */
private val PART_NUMBER_LABEL = Regex("""第\s*[0-9一二三四五六七八九十百零〇两]+\s*[集话期讲回]""")

/** 「P12」「EP03」「E03」这类集号标签；前后不能粘字母数字，避免误伤内容词。 */
private val PART_EPISODE_LABEL = Regex(
    """(?<![A-Za-z0-9])(?:p|ep|e)\s?\d{1,4}(?![A-Za-z0-9])""",
    RegexOption.IGNORE_CASE,
)

/** 【…】/[…]/(…)/（…）这类补充标签，整段去掉。 */
private val PART_BRACKET_LABEL = Regex("""【[^】]*】|\[[^\]]*\]|（[^）]*）|\([^)]*\)""")

private val PART_WHITESPACE = Regex("""\s+""")

/** 清洗后留在两端的常见分隔符。 */
private val PART_EDGE_SEPARATORS = charArrayOf(
    '·', '-', '—', '–', ':', '：', '、', '|', '/', ',', '，', '.', '．',
)

/**
 * 清洗一条分集标题，返回能当节点名字的那部分；返回 null = 这一条该被过滤掉。
 *
 * 依次做：黑名单整条拦截 → 去「第 N 集 / P12 / EP03」标签 → 去括号补充 →
 * 折叠空白、去掉两端分隔符。清洗后什么都不剩（比如标题只有「第 1 集」）也算过滤。
 */
internal fun cleanPartTitle(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (isJunkPartTitle(text)) return null
    val cleaned = text
        .replace(PART_NUMBER_LABEL, " ")
        .replace(PART_EPISODE_LABEL, " ")
        .replace(PART_BRACKET_LABEL, " ")
        .replace(PART_WHITESPACE, " ")
        .trim()
        .trim(*PART_EDGE_SEPARATORS)
        .trim()
        .replace(PART_WHITESPACE, " ")
    return cleaned.ifEmpty { null }
}

/**
 * 目录清洗：过滤掉片尾/预告/花絮这类非课程分集，返回（留下的分集, 被过滤的条数）。
 *
 * 过滤后不足 3 集就**退回原始分集**（只有一两集的短视频课程不能被清洗清空），
 * 但被过滤条数照样带回去当警告。调用方判断是否退回：`filtered > 0 && 返回条数 == 原始条数`。
 */
internal fun pickCourseParts(parts: List<BilibiliPart>): Pair<List<BilibiliPart>, Int> {
    // 空标题的分集保留：它不是「片尾」这类垃圾，只是标题没填，单元里会用 P{page} 兜底。
    val kept = parts.filter { part -> part.title.isBlank() || cleanPartTitle(part.title) != null }
    val filtered = parts.size - kept.size
    return if (kept.size >= 3) kept to filtered else parts to filtered
}

/**
 * 单元标题不再逐字复制分集标题：
 * - 有章节（「第2章」/「3.1」→「第3章」）就拼成「第N章 · 这一讲的名字」；
 * - 没有章节就用清洗后的名字（「P2 第2集 进程管理」→「进程管理」）；
 * - 连名字都没有就用 P{page}。
 */
private fun unitTitleOf(chapter: String, cleaned: String): String {
    if (chapter == "未分章") return cleaned
    val rest = CHAPTER_IN_TITLE.replace(cleaned, " ")
        .replace(PART_WHITESPACE, " ")
        .trim()
        .trim(*PART_EDGE_SEPARATORS)
        .trim()
    return if (rest.isEmpty()) chapter else "$chapter · $rest"
}

/** 一个确定性单元 + 它是从哪个原始分集来的（资源落库要用原始标题与页码）。 */
internal data class CourseUnit(val draft: UnitDraft, val part: BilibiliPart)

/**
 * 分集 → 确定性单元：章节/标题/分钟都由宿主算，长分集拆「上/下」。
 * 整门课全量导入，不靠模型逐条转录。
 */
internal fun courseUnitsFromParts(parts: List<BilibiliPart>): List<CourseUnit> = buildList {
    parts.forEach { part ->
        val chapter = chapterOf(part.title)
        val cleaned = cleanPartTitle(part.title) ?: ("P" + part.page)
        val title = unitTitleOf(chapter, cleaned)
        val minutes = unitMinutesOf(part.durationMinutes)
        if (minutes.size == 1) {
            add(CourseUnit(UnitDraft(chapter = chapter, title = title, estimateMinutes = minutes[0]), part))
        } else {
            add(CourseUnit(UnitDraft(chapter = chapter, title = title + "（上）", estimateMinutes = minutes[0]), part))
            add(CourseUnit(UnitDraft(chapter = chapter, title = title + "（下）", estimateMinutes = minutes[1]), part))
        }
    }
}

/**
 * 从 [start] 开始按 [budgetMinutes] 分钟参考值顺序取一周的单元。
 *
 * 参考值不是上限（2026-11-08 用户定的：时间只对 AGENT 起参考作用）：跨过参考值的那一条也带上，
 * 不因为差几分钟把单元推到下周；第一个单元一定取（哪怕它本身超参考），否则参考值小的用户会永远排不出东西。
 */
private fun takeWeekBatch(units: List<UnitDraft>, start: Int, budgetMinutes: Int): List<Int> {
    val picked = mutableListOf<Int>()
    var used = 0
    var index = start
    while (index < units.size) {
        val minutes = units[index].estimateMinutes
        // 已经攒够参考值就收手；还没到就继续，跨过参考值的那一条算在这一周。
        if (picked.isNotEmpty() && used >= budgetMinutes) break
        picked += index
        used += minutes
        index++
    }
    return picked
}

/**
 * source 模式（bvid 全量导入）的排周：**全局周表**——从本周起一周接一周地排，
 * 每周按 dailyMinutes×7 当参考值（跨过参考值的那一条也归本周），直到所有单元都排完。
 *
 * 时间只是参考（2026-11-08 用户定的）：参考值小不会让单元漏排，多出来的周会整体往后延；
 * 有截止日期时只排到截止周，剩下的才留在「待安排」池。
 *
 * 之前这里只排本周+下周、其余留在池里，结果是「计划永远只活在前两周」，
 * 看不到整体路线。现在把整条路线一次排完：未来周只是「已承诺、还没铺到天」，
 * 等那一周到了再由节奏引擎把该周铺到每天（materializeWeek）。
 *
 * 周号由调用方从注入时钟算好传进来：工具不能自己取系统时间，
 * 否则开发者模式拨日期后，AI 建出来的计划仍按真实日期排。
 */
private fun autoCommitments(
    units: List<UnitDraft>,
    dailyMinutes: Int,
    thisWeek: String,
    lastWeek: String? = null,
): List<CommitmentDraft> {
    val firstMonday = isoWeekStart(thisWeek) ?: return emptyList()
    val budgetMinutes = dailyMinutes * 7
    val weeks = mutableListOf<CommitmentDraft>()
    var start = 0
    var index = 0
    while (start < units.size) {
        val week = isoWeekOf(firstMonday.plusWeeks(index.toLong()))
        // 有截止日期：只排到那周为止，剩下的一律留在「待安排」池。
        if (lastWeek != null && week > lastWeek) break
        val batch = takeWeekBatch(units, start, budgetMinutes)
        if (batch.isEmpty()) break
        weeks += CommitmentDraft(week = week, unitIndexes = batch)
        start = batch.last() + 1
        index++
    }
    return weeks
}

/** deadline 参数校验结果：要么给出「最后一周」，要么给出错误文案。 */
private class DeadlineCheck(val lastWeek: String?, val error: String?)

/**
 * 解析可选的 deadline（YYYY-MM-DD）：格式错、已过去都直接报错。
 *
 * 截止日期只影响**排到哪一周**：宿主不会把它换算成「先排两周」，
 * 也不会因为截止日期近就把用户的每日投入偷偷调高——排不下就留在待安排池。
 */
private fun checkDeadline(arguments: JsonObject, today: LocalDate): DeadlineCheck {
    val raw = arguments.textArg("deadline") ?: return DeadlineCheck(null, null)
    val date = runCatching { LocalDate.parse(raw) }.getOrNull()
        ?: return DeadlineCheck(null, "deadline 要写成 YYYY-MM-DD，现在是「$raw」。")
    if (date < today) {
        return DeadlineCheck(null, "deadline（$raw）已经过去了；不要给过去的截止日期。")
    }
    return DeadlineCheck(isoWeekOf(date), null)
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
    /** 计划落库后回调新建的 planId：调用方拿它把计划页切到新计划。 */
    private val onChanged: suspend (String) -> Unit,
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

    val guidance: String = listOf(
        "<how_to_create_learning_plan>",
        "1. `set_plan` 只写这一轮的动作；**计划页要的是 create_learning_plan**。",
        "   长期、要按周推进的事调 create_learning_plan；一次性、几条能勾完的用 add_tasks。不用问用户「待办还是计划」，自己判断。",
        "2. 用户只有目标、没有指定课程/BV/链接时，**先 research_plan 调研出路径**（见 how_to_research_plan），不要一上来搜 B 站。",
        "   用户明确指定了课程/视频/BV 号，或要求「用某门课速通」时，才把 bvid 直接交给 create_learning_plan(bvid=...)，",
        "   可选 fromPart/toPart 只导入第几集到第几集。宿主会自己拉分集、按分集标题生成全部单元，",
        "   并把**从本周起的全局周表**一次排好（每周按每天分钟数**参考**估算，整门课不会只排前两周；排不完就往后延周，时间只做参考）。**总路线要全**：不要替用户挑「前面几集」，也不要逐条抄分集标题。",
        "   走 add_tasks 那条路才从目录里挑 6-12 集；排计划不要挑。",
        "3. 用了 bvid 就不要再写 units / weeks：单元和全局周表都由宿主自动做。手写会和分集对不上，工具会直接报错让你删掉。",
        "4. dailyMinutes 只是**建议**：用户主动说了就用用户的值；没说就用设置页「每天预计投入」（默认 60）。",
        "   不要为这个值单独问用户、更不要弹选项；排完在回复里说一句「按每天 X 分钟排的，想改随时说（也可以直接让我调节奏）」。",
        "5. 计划先做**整体**、再做**每周局部**：先把整条路线按周排全（覆盖到目标/截止日期），之后每周由节奏引擎把该周铺到天；不要让计划只活在前两周。",
        "6. 来源不是 B 站（自己的书 / 网课章节 / 其它资料）才手写 units + weeks：单元时间按真实时长估（10–45 分钟）；",
        "   weeks 从本周起、周与周连续、每周 1–14 个；把能覆盖到目标的周表一次给全（**不设周数上限**）。",
        "7. 用户给了考试/截止日期（「还有两周考试」「月底前学完」）：先按今天换算成具体日期，再把 deadline 传给 create_learning_plan / research_plan，",
        "   宿主会按截止周倒排全局周表；最后 1–2 周留复习/模考。排不下就整体延期、其余留在待安排，不要为此擅自抬高每天投入。",
        "8. 手写 units + weeks 时第一周必须是**本周**（今天所在的那一周）：计划从今天开始排，不要从下周开始。",
        "9. 写完说清「全局排了多少周、本周 X 个、待安排还剩几个」；不要去改用户别的数据。",
        "</how_to_create_learning_plan>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(CreateLearningPlan())

    private inner class CreateLearningPlan : AgentTool {
        override val name: String = "create_learning_plan"
        override val description: String =
            "在「计划」页建一份学习计划。排 B 站课程时优先传 bvid：宿主会直接拉分集，" +
                "把整门课**全量**变成单元（总路线要全），并按**从本周起的全局周表**把整门课排到未来各周（每周按每天分钟数**参考**估算；时间只做参考，排不完就往后延周）；" +
                "传了 bvid 就不要手写 units/weeks。非 B 站来源才手写 units + weeks（第一周必须是本周、周与周连续、每周 1–14 个，" +
                "周数按整个计划周期给全，不设上限）。dailyMinutes 是建议值（15–240；用户没主动说就别单独问）；传 deadline 时宿主按截止周倒排、排不下留待安排。" +
                "会写用户数据；长期学习计划用这个，一次性任务用 add_tasks。"
        override val parameters: String =
            "bvid：B 站视频 BV 号（首选，例：BV1YE411D7nH）；传了就不要再传 units/weeks，单元由宿主按分集生成、全局周表也由宿主自动排。" +
                "fromPart / toPart：可选，只导入第几集到第几集（1-based，省略=全部集）。" +
                "title：计划标题（如「操作系统速通」）；" +
                "dailyMinutes：建议值，每天投入分钟数（15–240；没说就按设置里的每天预计投入/60）；" +
                "deadline：可选截止日期（YYYY-MM-DD）。用户说「还有两周考试」「月底前学完」时，按今天算出日期再传给 create_learning_plan，" +
                "宿主会把全局周表排到那个周为止，排不下的留在待安排；" +
                "units：数组，每项 {title, minutes?, chapter?}（title 必填，minutes 10–45，默认 25），只在手写路径用；" +
                "weeks：数组，每项 {week: \"2026-W44\", units: [单元 title...]}（从本周起、周与周连续、每周 1–14 个；周数按整个计划周期给全，不设上限），只在手写 units 时用。" +
                "goalId：可选，目标 id（list_goals 里的值）。传了就顺手把这份计划挂到那个目标上。"

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

            val thisWeek = currentIsoWeek()
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
                if (names.size !in 1..14) {
                    return@call ToolResult("$week 这一周要有 1–14 个单元，现在有 ${names.size} 个", isError = true)
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
            // 全局周表：从本周起一周接一周，中间不跳周（周数本身不限）。
            val firstMonday = isoWeekStart(thisWeek) ?: return ToolResult("本周格式不对：$thisWeek", isError = true)
            commitments.forEachIndexed { position, draft ->
                val expected = isoWeekOf(firstMonday.plusWeeks(position.toLong()))
                if (draft.week != expected) {
                    return ToolResult(
                        "weeks 要从本周 $thisWeek 起一周接一周（周数不限），" +
                            "第 ${position + 1} 周应该是 $expected，现在是 ${draft.week}。",
                        isError = true,
                    )
                }
            }

            val written = writePlan(plan, title, dailyMinutes, units, commitments, arguments.textArg("goalId"))
            written.error?.let { return it }

            val weekLines = commitments.joinToString("；") { draft ->
                val count = draft.unitIndexes.size
                "${draft.week} $count 个单元"
            }
            val placed = commitments.sumOf { it.unitIndexes.size }
            return ToolResult(
                "计划「$title」建好了：${units.size} 个单元，全局周表共 ${commitments.size} 周" +
                    "（${commitments.first().week} 起：$weekLines）；其余 ${units.size - placed} 个留在「待安排」池。" +
                    "每天按 $dailyMinutes 分钟排（只是建议，想改随时说）；去「计划」页能看到全局周卡片和每天安排。",
            )
        }
    }

    /**
     * bvid 模式：只选源，不手写内容。
     *
     * 分集 → 单元是确定性的：目录先过 [pickCourseParts] 洗掉片尾/预告等非正片，
     * 再由 [courseUnitsFromParts] 算标题/时长/章节；排周也是确定性的（[autoCommitments]：
     * 全局周表），模型只负责选视频和起止集。
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

        val deadlineText = arguments.textArg("deadline")
        val deadline = checkDeadline(arguments, clock.today())
        deadline.error?.let { return ToolResult(it, isError = true) }

        // 目录清洗：片尾/预告/花絮这类条目不是课程内容，不能变成用户要勾的节点。
        // 清洗后不足 3 集就退回原始目录（课程只有一两集时不能清空），但过滤计数留着当警告。
        val (courseParts, filteredOut) = pickCourseParts(selected)
        val fellBack = filteredOut > 0 && courseParts.size == selected.size

        val allCourseUnits = courseUnitsFromParts(courseParts)
        val truncated = allCourseUnits.size > UNIT_LIMIT
        val courseUnits = allCourseUnits.take(UNIT_LIMIT)
        val units = courseUnits.map { it.draft }
        // 单元下标 → 原始分集：资源落库（setUnitResources）按它写原分集标题/页码，见 phase 2。
        val partByUnit: Map<Int, BilibiliPart> = courseUnits.mapIndexed { index, unit -> index to unit.part }.toMap()
        val sourcePartCount = partByUnit.values.map { it.page }.distinct().size

        val commitments = autoCommitments(units, dailyMinutes, currentIsoWeek(), deadline.lastWeek)
        if (commitments.isEmpty()) {
            return ToolResult("（$bvid）没生成出可排的单元，换一个视频或检查分集时长。", isError = true)
        }

        val written = writePlan(plan, title, dailyMinutes, units, commitments, arguments.textArg("goalId")) { planId ->
            writeBilibiliResources(plan, planId, bvid, courseUnits, commitments.map { it.week })
        }
        written.error?.let { return it }

        val placed = commitments.sumOf { it.unitIndexes.size }
        val weekLines = commitments.joinToString("；") { draft ->
            "${draft.week} ${draft.unitIndexes.size} 个单元"
        }
        return ToolResult(
            buildString {
                append("计划「$title」建好了：按 B 站 $bvid 的分集全量导入 ${units.size} 个单元（来自 $sourcePartCount 集）")
                if (truncated) append("，超过 $UNIT_LIMIT 个已截断：只取前 $UNIT_LIMIT 个")
                if (filteredOut > 0) {
                    if (fellBack) {
                        append("；目录里 $filteredOut 条像片尾/预告的条目没清掉（清洗后不足 3 集，已退回使用原始分集）")
                    } else {
                        append("；已按课程大纲清洗 $sourcePartCount 集，过滤 $filteredOut 条（片尾/预告等）")
                    }
                }
                append("。全局周表共 ${commitments.size} 周（$weekLines）")
                deadline.lastWeek?.let { append("，按截止日 $deadlineText 排到 $it") }
                if (placed < units.size) append("；按每天 $dailyMinutes 分钟（参考值）排，截止日前没排进任何一周的 ${units.size - placed} 个留在「待安排」池（之后再说）")
                written.notes.forEach { append("；").append(it) }
                append("。总路线一次排完，之后每周到点会自动铺到天；每天按 $dailyMinutes 分钟（参考值）排，内容多会往后延周（想改随时说）。")
                append("去「计划」页能看到全局周卡片和每天安排。")
            },
        )
    }

    /**
     * 一次落库的结果：成功时 [planId] 非空、[notes] 是附加说明（资源是否写好等，不影响计划本身）；
     * 失败时 [error] 就是要直接回给模型的 [ToolResult]。
     */
    private class PlanWriteResult(
        val planId: String? = null,
        val error: ToolResult? = null,
        val notes: List<String> = emptyList(),
    )

    /**
     * 三条命令真正落库；失败返回错误 [ToolResult]，成功返回 [PlanWriteResult]（由调用方拼成功文案）。
     * 两条路径（手写 / bvid）共用，避免写库流程各写一遍。
     *
     * [afterAccept] 在 acceptDraft 成功、onChanged 之前调用（现在只有 bvid 路径用它写分集资源）；
     * 它返回的说明行会带进回执，抛异常也不会把「计划已经建好」判失败。
     */
    private suspend fun writePlan(
        plan: LearningPlanService,
        title: String,
        dailyMinutes: Int,
        units: List<UnitDraft>,
        commitments: List<CommitmentDraft>,
        goalId: String? = null,
        afterAccept: (suspend (planId: String) -> List<String>)? = null,
    ): PlanWriteResult {
        val created = plan.createProcess(
            env(null, "plan.create"),
            title = title,
            goal = title,
            // 后端按「周容量」存；用户说的是每天多少分钟，×7 折成周。这个数只做参考，
            // 不是排程硬上限（2026-11-08 用户定的：时间只对 AGENT 起参考作用）。
            weeklyCapacityMinutes = dailyMinutes * 7,
        )
        val createdValue = created.value
            ?: return PlanWriteResult(error = ToolResult("建计划失败：" + (created.error?.message ?: "未知原因"), isError = true))

        val saved = plan.saveDraft(
            env(createdValue.planId, "plan.save"),
            planId = createdValue.planId,
            versionId = null,
            units = units,
            commitments = commitments,
        )
        val savedValue = saved.value
            ?: return PlanWriteResult(error = ToolResult("存草稿失败：" + (saved.error?.message ?: "未知原因"), isError = true))

        val accepted = plan.acceptDraft(
            env(createdValue.planId, "plan.accept"),
            versionId = savedValue.versionId,
            firstWeek = commitments.first().week,
        )
        if (accepted.value == null) {
            return PlanWriteResult(error = ToolResult("生效失败：" + (accepted.error?.message ?: "未知原因"), isError = true))
        }
        if (goalId != null) {
            val linked = plan.linkPlanToGoal(env(createdValue.planId, "plan.link_goal"), createdValue.planId, goalId)
            if (linked.value == null) {
                // 计划已经建成了，关联失败不该把整件事判失败；把原因带回去让模型补一次。
                return PlanWriteResult(
                    planId = createdValue.planId,
                    error = ToolResult(
                        "计划建好了，但挂到目标上没成功：" + (linked.error?.message ?: "未知原因") +
                            "\n可以用 link_plan_to_goal 补一次。",
                    ),
                )
            }
        }
        val notes = if (afterAccept != null) {
            runCatching { afterAccept(createdValue.planId) }
                .getOrElse { listOf("建计划的后续步骤没做完：" + (it.message ?: "未知原因")) }
        } else {
            emptyList()
        }
        onChanged(createdValue.planId)
        return PlanWriteResult(planId = createdValue.planId, notes = notes)
    }

    /** 落库后按标题找回单元 id（承诺里的单元 + 待安排池），用于挂分集资源。 */
    private fun unitIdsByTitle(plan: LearningPlanService, planId: String, weeks: List<String>): Map<String, String> {
        val ids = mutableMapOf<String, String>()
        weeks.forEach { week ->
            runCatching { plan.week(planId, week) }.getOrNull()
                ?.commitment?.units?.forEach { unit -> if (unit.id.isNotBlank()) ids[unit.title] = unit.id }
        }
        runCatching { plan.pool(planId) }.getOrNull().orEmpty().forEach { unit -> ids[unit.title] = unit.id }
        return ids
    }

    /**
     * 给 bvid 导入的每个单元挂一条分集资源：kind=bilibili，url 带 `?p=<page>`，locator 写「第 N 集」。
     *
     * 资源写失败不影响计划本身（计划已经建成），只把说明带进回执；失败原因聚合一条，避免刷屏。
     */
    private suspend fun writeBilibiliResources(
        plan: LearningPlanService,
        planId: String,
        bvid: String,
        courseUnits: List<CourseUnit>,
        weeks: List<String>,
    ): List<String> {
        if (courseUnits.isEmpty()) return emptyList()
        val idByTitle = unitIdsByTitle(plan, planId, weeks)
        var written = 0
        var missing = 0
        val failures = mutableListOf<String>()
        courseUnits.forEach { unit ->
            val unitId = idByTitle[unit.draft.title]
            if (unitId == null) {
                missing++
                return@forEach
            }
            val part = unit.part
            val resource = ResourceDraft(
                kind = "bilibili",
                // 资源标题用原分集标题：单元标题是清洗后的学习点，这里是「这一集叫什么」，两边都要能对上。
                title = part.title.trim().ifEmpty { "P" + part.page },
                url = "https://www.bilibili.com/video/$bvid?p=" + part.page,
                locator = buildString {
                    append("第 ").append(part.page).append(" 集")
                    if (part.durationText.isNotBlank()) append("（分集 ").append(part.durationText).append("）")
                },
                source = bvid,
            )
            val result = runCatching {
                plan.setUnitResources(env(planId, "plan.resources"), planId, unitId, listOf(resource))
            }.getOrNull()
            if (result?.ok == true) written++ else failures += (result?.error?.message ?: "写入异常")
        }
        val notes = mutableListOf<String>()
        if (written > 0) notes += "已给 $written 个单元挂上 B 站分集资源（点开能直接回到对应分集）"
        if (missing > 0) notes += "$missing 个单元没找到落库后的 id，分集资源没写进去（不影响学习计划）"
        if (failures.isNotEmpty()) {
            notes += "${failures.size} 个单元的分集资源没写进去（" + failures.first().take(60) + "），不影响学习计划"
        }
        return notes
    }

    private fun env(planId: String?, origin: String) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.AGENT,
        planId = planId,
        origin = origin,
    )
}
