package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 一条记忆文本的落盘上限：记忆是「一句人话」，不是整段对话。 */
private const val MEMORY_TEXT_LIMIT = 400

/** 单条记忆最多带几个 topics / entities。 */
private const val MEMORY_TAG_LIMIT = 8

/** 单个 topic / entity 的字符上限。 */
private const val MEMORY_TAG_CHARS = 40

/** recall_memory 一次最多回几条：再多模型也读不完，还会挤掉本轮真正的上下文。 */
private const val MEMORY_RECALL_MAX = 20

/**
 * 跨会话记忆的手。
 *
 * 管家记忆 v0.5 只记「仪式发生过什么」，模型看不到、也写不进去；而用户真正要的是
 * **目标/偏好这类跨会话的东西**：这周说过「想考软考」、上次说过「晚上别排新任务」，
 * 下次开新会话时模型应当知道。所以这里给模型两只手：
 *
 * - [RememberMemory]：把用户说出的长期目标/偏好写进 `steward/memory.jsonl`（append-only，
 *   和仪式记忆同一份日志）。目标做文本去重 —— 同一句话反复说，记忆里只留一条。
 * - [RecallMemory]：按关键词/天数把过去写下的记忆捞回来，让模型基于「事实」而不是猜测回答。
 *
 * 写进来的东西会被 [AppRuntime.memoryBlock] 在每次对话开始时带进提示词（不依赖这两只工具），
 * 所以「记住」和「用上」是两条独立的路：工具负责落盘，运行时负责注入。
 *
 * 时间必须走注入的 [clock]（`docs/time-conventions.md`）：开发者模式拨日期后，
 * 记忆的 day 要跟着应用内日期走，不能用系统时间。
 */
internal class MemoryTools(
    private val store: StewardStore,
    private val clock: TrellisClock = SystemTrellisClock(),
) {

    val guidance: String = listOf(
        "<how_to_remember>",
        "1. 用户说出**长期目标**（想考什么、想学成什么样、几个月后要达成什么）时，调 remember_memory kind=goal；",
        "   说出**长期偏好**（作息、讲解方式、不要哪种安排）时，kind=preference。`text` 只写一句人话，别抄整段对话。",
        "   这两类会跨会话写进记忆，下次开新会话也会带上；同一句话不重复记。",
        "2. 需要回忆过去的约定/目标/用户说过的偏好时，调 recall_memory（可给 query；不给就回最近几天的记忆）。",
        "   查到什么就说什么；没查到就说「记忆里没有」，**不要凭空编造**过去的承诺或目标。",
        "3. 寒暄、一次性的抱怨不值得写记忆；note 只用来记「下次还值得提起」的一句话。",
        "</how_to_remember>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(RememberMemory(), RecallMemory())

    private inner class RememberMemory : AgentTool {
        override val name: String = "remember_memory"
        override val description: String =
            "把用户说出的长期目标/长期偏好写进跨会话记忆（append-only 日志）。" +
                "goal 会做文本去重：同一句话已经在记忆里就不再写。只写「用户明确说出来的」，不要替用户猜。"

        override val parameters: String =
            "kind：必填，只支持 goal / preference / note（goal=长期目标，preference=长期偏好，note=值得下次提起的一句话）；" +
                "text：必填，一句人话（最多 400 字）；" +
                "topics：可选，字符串数组，主题名（如 [\"软考\",\"操作系统\"]）；" +
                "entities：可选，字符串数组，专名（书/课/工具/地点，如 [\"CSAPP\"]）。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val rawKind = arguments.memTextArg("kind")
                ?: return ToolResult("remember_memory 需要 kind，可选值：goal / preference / note", isError = true)
            val kind = when (rawKind.lowercase()) {
                "goal" -> StewardMemoryKind.Goal
                "preference" -> StewardMemoryKind.Preference
                "note" -> StewardMemoryKind.Note
                else -> return ToolResult(
                    "kind 只支持 goal / preference / note，现在是「$rawKind」。goal=长期目标，preference=长期偏好，note=一句话备忘。",
                    isError = true,
                )
            }
            val rawText = arguments.memTextArg("text")
                ?: return ToolResult("remember_memory 需要 text：把要记的那句话写成一句人话", isError = true)
            val text = rawText.take(MEMORY_TEXT_LIMIT)
            val topics = arguments.tagListArg("topics")
            val entities = arguments.tagListArg("entities")
            val today = clock.today().toString()

            // 目标去重：同一句话（忽略空白/大小写）已经在记忆里就不重复写。
            // 偏好不去重：'晚上别排新任务' / '周末也别排' 是两条独立偏好，文本不同。
            if (kind == StewardMemoryKind.Goal) {
                val existing = store.goals(limit = StewardStore.MAX_MEMORY_QUERY_LIMIT)
                    .firstOrNull { normalizeMemoryText(it.text) == normalizeMemoryText(text) }
                if (existing != null) {
                    return ToolResult(
                        "这个目标已经在记忆里（${existing.day} 记的）：「${existing.text}」。没有重复写；" +
                            "以后每次对话都会带上它，不用再让用户重复。",
                    )
                }
            }

            store.appendMemory(
                StewardMemoryEvent(
                    at = clock.nowMs(),
                    day = today,
                    kind = kind,
                    text = text,
                    topics = topics,
                    entities = entities,
                ),
            )
            return ToolResult(
                "已记住${stewardMemoryKindLabel(kind)}：「$text」（$today）。" +
                    "这条会跨会话保留，下次对话开始时自动带上。",
            )
        }
    }

    private inner class RecallMemory : AgentTool {
        override val name: String = "recall_memory"
        override val description: String =
            "检索跨会话记忆（历史目标/偏好/约定/决定），按时间降序返回最多 20 条。" +
                "没查到就返回空，此时直接告诉用户「记忆里没有」，不要编造。"

        override val parameters: String =
            "query：可选，关键词（大小写不敏感，匹配记忆正文/主题/专名；不传则回最近几天的记忆）；" +
                "days：可选，只找最近多少天，默认 90（1–3650）；" +
                "limit：可选，最多几条，默认 10（1–20）。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val query = arguments.memTextArg("query")
            val days = arguments.memIntArg("days")?.coerceIn(1, 3650) ?: 90
            val limit = arguments.memIntArg("limit")?.coerceIn(1, MEMORY_RECALL_MAX) ?: 10
            val today = clock.today().toString()
            val hits = if (query == null) {
                store.recentMemory(today, days, limit).reversed()
            } else {
                store.searchMemory(query, days, limit, today)
            }
            if (hits.isEmpty()) {
                val scope = if (query == null) "最近 $days 天" else "最近 $days 天里和「$query」相关的"
                return ToolResult(
                    "记忆里没有${scope}记录。可以直接告诉用户「记忆里没有这条」，不要编造过去的约定或目标；" +
                        "如果用户刚说过，先用 remember_memory 记下来。",
                )
            }
            val body = hits.joinToString("\n") { event ->
                buildString {
                    append("- ")
                    append(event.day)
                    append(' ')
                    append(stewardMemoryKindLabel(event.kind))
                    append("：")
                    append(event.text.take(120))
                    val tags = event.topics + event.entities
                    if (tags.isNotEmpty()) {
                        append("（")
                        append(tags.distinct().take(6).joinToString("、"))
                        append("）")
                    }
                }
            }
            return ToolResult("命中 ${hits.size} 条记忆（最近的在前，窗口 $days 天）：\n$body")
        }
    }
}

/**
 * 跨会话记忆块的固定外壳。两条对话路径（普通会话 / 管家会话）注入同一份文本，
 * 测试也钉这两个字面量；改格式只改这里。
 */
internal const val CROSS_SESSION_MEMORY_OPEN = "<cross_session_memory>"
internal const val CROSS_SESSION_MEMORY_CLOSE = "</cross_session_memory>"

/** 记忆块里每类最多写几条：提示词只该有重点，多出来反而稀释。 */
private const val MEMORY_BLOCK_GOAL_LIMIT = 3
private const val MEMORY_BLOCK_PREFERENCE_LIMIT = 3
private const val MEMORY_BLOCK_RECENT_LIMIT = 3

/** 「近期」的窗口：太久以前的事件对当下对话没有参考价值。 */
private const val MEMORY_BLOCK_RECENT_DAYS = 14

/** 单条记忆在提示词里最多占多少字；记忆正文本身可以有 400 字，提示词里只留结论。 */
private const val MEMORY_BLOCK_ITEM_CHARS = 80

/**
 * 进入记忆块第三节的 kind：决定和值得下次提起的事件。
 *
 * 晨/夜仪式是管家自己的独白，Goal/Preference 已经各有一节 —— 重复出现只会让模型
 * 以为用户说了两遍。
 */
private val MEMORY_BLOCK_RECENT_KINDS = setOf(
    StewardMemoryKind.Adopted,
    StewardMemoryKind.Rejected,
    StewardMemoryKind.Note,
)

/**
 * 把 [store] 里的跨会话记忆渲染成一段提示词块；没有可写的记忆时返回空串。
 *
 * 纯函数（不读环境、不取当前时间），方便单测钉文案。顺序固定
 * **目标 → 偏好 → 近期决定/事件**，和用户最初的诉求一致：
 * 先说「你要去哪」，再说「你喜欢怎么走」，最后才是「上次决定了什么」。
 *
 * [maxChars] 是整块（含外壳标签）的硬上限：超出时按上面的优先级截断，
 * 保证目标永远优先于近期琐事。
 *
 * 调用链：`AppRuntime.sendChat` → `AppRuntime.memoryBlock()` → 这里。
 */
internal fun renderCrossSessionMemory(
    store: StewardStore,
    today: String,
    maxChars: Int = 600,
): String {
    if (maxChars <= 0) return ""
    val goals = store.goals(limit = MEMORY_BLOCK_GOAL_LIMIT)
    val preferences = store.preferences(limit = MEMORY_BLOCK_PREFERENCE_LIMIT)
    val recent = store.recentMemory(today, days = MEMORY_BLOCK_RECENT_DAYS, limit = 20)
        .filter { it.kind in MEMORY_BLOCK_RECENT_KINDS }
        .takeLast(MEMORY_BLOCK_RECENT_LIMIT)
    if (goals.isEmpty() && preferences.isEmpty() && recent.isEmpty()) return ""

    val sections = listOf(
        "用户长期目标（跨会话记忆）" to goals.map { "${it.day}：${briefMemoryText(it.text)}" },
        "用户长期偏好（跨会话记忆）" to preferences.map { "${it.day}：${briefMemoryText(it.text)}" },
        "最近的约定/决定" to recent.map {
            "${it.day} ${stewardMemoryKindLabel(it.kind)}：${briefMemoryText(it.text)}"
        },
    )
    // 记忆块总长 = 外壳 + 换行 + body；先把外壳的固定开销扣掉，剩下的才是正文预算。
    val overhead = CROSS_SESSION_MEMORY_OPEN.length + CROSS_SESSION_MEMORY_CLOSE.length + 2
    val budget = maxChars - overhead
    if (budget <= 0) return ""
    val body = StringBuilder()
    for ((title, items) in sections) {
        if (items.isEmpty()) continue
        val lead = if (body.isEmpty()) title else "\n$title"
        // 标题 + 至少一小段数据放不下就整节跳过：不留只有标题的空节。
        if (body.length + lead.length + "\n- ".length + MIN_MEMORY_LINE_CHARS > budget) continue
        body.append(lead)
        for (item in items) {
            val prefix = "\n- "
            val room = budget - body.length - prefix.length
            if (room < MIN_MEMORY_LINE_CHARS) break
            // 预算不够时截断这一条，而不是整节丢掉：目标永远优先于近期琐事。
            val shown = if (item.length <= room) item else item.take(room - 1) + "…"
            body.append(prefix).append(shown)
        }
    }
    if (body.isEmpty()) return ""
    return CROSS_SESSION_MEMORY_OPEN + "\n" + body + "\n" + CROSS_SESSION_MEMORY_CLOSE
}

/** 一条记忆行至少要能显示这么多字符才值得占位（放不下就整节跳过）。 */
private const val MIN_MEMORY_LINE_CHARS = 8

/** 提示词里的记忆只留一句：压平换行、超长补省略号。 */
private fun briefMemoryText(text: String): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    return if (flat.length <= MEMORY_BLOCK_ITEM_CHARS) flat else flat.take(MEMORY_BLOCK_ITEM_CHARS - 1) + "…"
}

/** 目标去重用的归一化：去空白、忽略大小写。 */
private fun normalizeMemoryText(text: String): String =
    text.trim().replace(Regex("\\s+"), " ").lowercase()

/** 参数里的字符串（去空白）；没有或是 null 就是 null。 */
private fun JsonObject.memTextArg(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** 参数里的整数；模型偶尔把数字写成字符串，两种都收。 */
private fun JsonObject.memIntArg(name: String): Int? =
    (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

/**
 * 参数里的字符串列表：优先数组；模型只给一个字符串时按「,，、」拆开。
 * 去空、去重、每条最多 [MEMORY_TAG_CHARS] 字、最多 [MEMORY_TAG_LIMIT] 条。
 */
private fun JsonObject.tagListArg(name: String): List<String> {
    val raw = this[name] ?: return emptyList()
    val parts = when (raw) {
        is JsonArray -> raw.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
        }

        is JsonPrimitive -> raw.contentOrNull?.split(',', '，', '、', ';', '；')
        else -> null
    } ?: return emptyList()
    return parts
        .mapNotNull { it.trim().takeIf { part -> part.isNotEmpty() } }
        .map { it.take(MEMORY_TAG_CHARS) }
        .distinct()
        .take(MEMORY_TAG_LIMIT)
}
