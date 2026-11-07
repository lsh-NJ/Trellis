package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 管家的设置：`<home>/steward/settings.json`。
 *
 * 时间是「应用内时间」（`TrellisClock`）的本地钟点，不是系统时区里的绝对时间：
 * 调试时把应用内日期往后拨，仪式跟着往后走，不会因为系统时间没动而卡住。
 * 默认 08:30 / 22:30；关掉某个仪式就完全不再主动说话。
 */
internal data class StewardSettings(
    val morningTime: String = DEFAULT_MORNING_TIME,
    val eveningTime: String = DEFAULT_EVENING_TIME,
    val morningEnabled: Boolean = true,
    val eveningEnabled: Boolean = true,
    /** 要不要弹系统通知。应用在前台时只用未读红点，不打扰。 */
    val notifyDesktop: Boolean = true,
    /**
     * 自动采纳（G-C1 最小版）：应用不在前台时，自动采纳「只挪动逾期」一类建议。
     *
     * 默认关：自动改用户计划是重手，得由用户自己打开。补排欠账（Place）永远不自动做。
     */
    val autoAdopt: Boolean = true,
    /** 每天最多自动采纳几条；1–10，默认 3。 */
    val autoAdoptDailyLimit: Int = 0,
) {
    fun normalized(): StewardSettings = copy(
        morningTime = normalizeStewardTime(morningTime, DEFAULT_MORNING_TIME),
        eveningTime = normalizeStewardTime(eveningTime, DEFAULT_EVENING_TIME),
        // 0 = 不限制；其余夹在 1..10。
        autoAdoptDailyLimit = autoAdoptDailyLimit.coerceIn(0, 10),
    )

    companion object {
        const val DEFAULT_MORNING_TIME = "08:30"
        const val DEFAULT_EVENING_TIME = "22:30"
    }
}

/** `HH:mm`，写坏了就退回默认值（设置文件是用户手改的也可能）。 */
internal fun normalizeStewardTime(raw: String, fallback: String): String {
    val match = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(raw.trim()) ?: return fallback
    val hour = match.groupValues[1].toIntOrNull() ?: return fallback
    val minute = match.groupValues[2].toIntOrNull() ?: return fallback
    if (hour !in 0..23 || minute !in 0..59) return fallback
    return "%02d:%02d".format(hour, minute)
}

/**
 * 仪式发到哪一天了：`<home>/steward/checkpoints.json`。
 *
 * 只记应用内日期字符串（`2026-11-04`）。同一天最多一次靠它；
 * 删掉文件等于允许重发一次，没有别的副作用。
 */
internal data class StewardCheckpoints(
    val lastMorningDay: String? = null,
    val lastEveningDay: String? = null,
    /** 自动采纳的日内计数：`autoAdoptDay` 这天已经自动采纳了几条（换天归零）。 */
    val autoAdoptDay: String? = null,
    val autoAdoptCount: Int = 0,
)

/**
 * 记忆事件类型。落盘用枚举名，将来加类型不影响旧文件。
 *
 * [Goal]、[Preference] 是 v1 的跨会话记忆：用户说出的长期目标 / 长期偏好（不是这一轮的指令）。
 * 它们和仪式记忆共用同一份 append-only 日志，读取侧靠 [StewardStore.goals] /
 * [StewardStore.preferences] 过滤 —— 加一个类型不需要动旧文件，旧版本读到不认识的
 * kind 会按 Note 兜底。
 */
internal enum class StewardMemoryKind { Morning, Evening, UserReply, Adopted, Rejected, Note, Goal, Preference }

/**
 * 记忆类型的人话名字。管家仪式行、跨会话记忆块、`recall_memory` 的输出共用这一份，
 * 三处说法不一致会让模型把同一件事认成两类。
 */
internal fun stewardMemoryKindLabel(kind: StewardMemoryKind): String = when (kind) {
    StewardMemoryKind.Morning -> "晨间"
    StewardMemoryKind.Evening -> "晚间"
    StewardMemoryKind.UserReply -> "用户说"
    StewardMemoryKind.Adopted -> "采纳"
    StewardMemoryKind.Rejected -> "没采纳"
    StewardMemoryKind.Note -> "记"
    StewardMemoryKind.Goal -> "目标"
    StewardMemoryKind.Preference -> "偏好"
}

/**
 * 一条情景记忆。
 *
 * 只记「发生过什么」，不把模型的推测写进来：[text] 是当时发出的原话（截断），
 * [quote] 是用户回的原话（如果有），[topics] 是实体名（计划、课程、章节），
 * [entities] 是更细的专名（书/课/工具/地点），供跨会话词法检索用。
 * 向量检索（P2）是在同一份日志上另建索引，不改变这里。
 *
 * [entities] 是 v1 新加的字段：只在非空时落盘，旧 JSONL 没有它，解析时按空列表兜底。
 */
internal data class StewardMemoryEvent(
    val at: Long,
    val day: String,
    val kind: StewardMemoryKind,
    val text: String,
    val quote: String = "",
    val topics: List<String> = emptyList(),
    val entities: List<String> = emptyList(),
)

/**
 * 管家全部落盘：设置、checkpoints、情景记忆（append-only JSONL）、每日摘要。
 *
 * 为什么是 JSONL 而不是 SQLite：写入只有「追加一行」，读取只有「最近 N 天」，
 * 一年也就几千行；文本可直接打开看、可直接删。等检索真的要升级（FTS5/向量），
 * 再把这批行灌进索引即可，日志仍是唯一事实来源。
 */
internal class StewardStore(private val home: File) {
    private val dir = File(home, "steward")
    private val digestDir = File(dir, "digests")
    private val memoryFile = File(dir, "memory.jsonl")
    private val json = Json { ignoreUnknownKeys = true }

    fun settings(): StewardSettings = runCatching {
        val file = File(dir, "settings.json")
        if (!file.isFile) return@runCatching StewardSettings()
        val root = parseObject(file.readText(Charsets.UTF_8))
        StewardSettings(
            morningTime = root["morningTime"]?.jsonPrimitive?.content
                ?: StewardSettings.DEFAULT_MORNING_TIME,
            eveningTime = root["eveningTime"]?.jsonPrimitive?.content
                ?: StewardSettings.DEFAULT_EVENING_TIME,
            morningEnabled = root["morningEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: true,
            eveningEnabled = root["eveningEnabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: true,
            notifyDesktop = root["notifyDesktop"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: true,
            autoAdopt = root["autoAdopt"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: false,
            autoAdoptDailyLimit = root["autoAdoptDailyLimit"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: 3,
        ).normalized()
    }.getOrElse { StewardSettings() }

    fun saveSettings(settings: StewardSettings) {
        val normalized = settings.normalized()
        writeJson(
            File(dir, "settings.json"),
            buildJsonObject {
                put("morningTime", normalized.morningTime)
                put("eveningTime", normalized.eveningTime)
                put("morningEnabled", normalized.morningEnabled)
                put("eveningEnabled", normalized.eveningEnabled)
                put("notifyDesktop", normalized.notifyDesktop)
                put("autoAdopt", normalized.autoAdopt)
                put("autoAdoptDailyLimit", normalized.autoAdoptDailyLimit)
            },
        )
    }

    fun checkpoints(): StewardCheckpoints = runCatching {
        val file = File(dir, "checkpoints.json")
        if (!file.isFile) return@runCatching StewardCheckpoints()
        val root = parseObject(file.readText(Charsets.UTF_8))
        StewardCheckpoints(
            lastMorningDay = root["lastMorningDay"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            lastEveningDay = root["lastEveningDay"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            autoAdoptDay = root["autoAdoptDay"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            autoAdoptCount = root["autoAdoptCount"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        )
    }.getOrElse { StewardCheckpoints() }

    fun saveCheckpoints(checkpoints: StewardCheckpoints) {
        writeJson(
            File(dir, "checkpoints.json"),
            buildJsonObject {
                checkpoints.lastMorningDay?.let { put("lastMorningDay", it) }
                checkpoints.lastEveningDay?.let { put("lastEveningDay", it) }
                checkpoints.autoAdoptDay?.let { put("autoAdoptDay", it) }
                if (checkpoints.autoAdoptCount > 0) put("autoAdoptCount", checkpoints.autoAdoptCount)
            },
        )
    }

    fun appendMemory(event: StewardMemoryEvent) {
        runCatching {
            dir.mkdirs()
            memoryFile.appendText(encodeMemory(event) + "\n", Charsets.UTF_8)
        }
    }

    /**
     * 最近 [days] 天（含 [today]）的记忆事件，按时间正序、最多 [limit] 条。
     *
     * 先把整个文件读成行再按「day 字符串比较」过滤：日期是 ISO 格式，字典序就是时间序；
     * limit 从尾部取（新的优先），再翻回正序 —— 注入提示词时「越近的越靠后」更符合直觉。
     */
    fun recentMemory(today: String, days: Int, limit: Int = 40): List<StewardMemoryEvent> {
        val since = runCatching {
            java.time.LocalDate.parse(today).minusDays((days - 1).coerceAtLeast(0).toLong()).toString()
        }.getOrNull() ?: return emptyList()
        return readMemory()
            .filter { it.day >= since && it.day <= today }
            .takeLast(limit.coerceAtLeast(0))
    }

    /**
     * 用户说过的长期目标，按记录时间**降序**（最新在前）；没有就返回空列表。
     *
     * limit 默认 5：注入提示词的只有最前面几条，多出来反而稀释重点。
     * 目标不按天数过滤 —— 三个月前说的「想考软考」今天仍然有效。
     */
    fun goals(limit: Int = 5): List<StewardMemoryEvent> =
        memoryOfKinds(setOf(StewardMemoryKind.Goal), limit)

    /** 用户说过的长期偏好，按记录时间降序（最新在前）。语义同 [goals]。 */
    fun preferences(limit: Int = 5): List<StewardMemoryEvent> =
        memoryOfKinds(setOf(StewardMemoryKind.Preference), limit)

    /**
     * 在记忆里做词法检索：大小写不敏感的子串匹配，覆盖 text / quote / topics / entities，
     * 按 [StewardMemoryEvent.at] 降序返回最多 [limit] 条。
     *
     * [days] 是「最近多少天」窗口。窗口终点用 [today]（应用内日期字符串）；
     * 不传时退化为「记忆里最新一条的 day」—— 这样只传三个参数的调用也仍然有窗口，
     * 不会退化成全量扫描。传了非法日期则返回空列表（和 [recentMemory] 一致：不猜）。
     */
    fun searchMemory(
        query: String,
        days: Int = 90,
        limit: Int = 20,
        today: String? = null,
    ): List<StewardMemoryEvent> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val events = readMemory()
        if (events.isEmpty()) return emptyList()
        val end = today?.takeIf { it.isNotBlank() } ?: events.maxOf { it.day }
        val since = runCatching {
            java.time.LocalDate.parse(end).minusDays((days.coerceAtLeast(1) - 1).toLong()).toString()
        }.getOrNull() ?: return emptyList()
        return events
            .filter { it.day >= since && it.day <= end && it.matches(needle) }
            .sortedByDescending { it.at }
            .take(limit.coerceIn(1, MAX_MEMORY_QUERY_LIMIT))
    }

    /**
     * 记忆里是否已经有一条「关于这些对象的备注」。
     *
     * 用途一是去重：同一次练习提议只记一条，重启后不再反复弹；用途二是让 AI 的
     * `remember_memory` 能先查后写。只认 [StewardMemoryKind.Note] —— 目标/偏好
     * 是另一类事实，不参与这里的「这件事已经记过没有」判断。
     */
    fun hasNote(topic: String, entities: List<String>): Boolean {
        val wanted = entities.filter { it.isNotBlank() }
        return readMemory().any { event ->
            event.kind == StewardMemoryKind.Note &&
                topic in event.topics &&
                wanted.all { entity -> entity in event.entities }
        }
    }

    /** 每天仪式结束时把当天的摘要写一份；没有就返回 null。 */
    fun digest(day: String): String? = runCatching {
        val file = File(digestDir, "$day.md")
        if (!file.isFile) null else file.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 最近 [days] 天的摘要，按日期正序（旧 → 新）。 */
    fun recentDigests(today: String, days: Int): List<Pair<String, String>> {
        val todayDate = runCatching { java.time.LocalDate.parse(today) }.getOrNull() ?: return emptyList()
        return (0 until days.coerceIn(1, 31)).mapNotNull { offset ->
            val day = todayDate.minusDays(offset.toLong()).toString()
            digest(day)?.let { day to it }
        }.reversed()
    }

    fun saveDigest(day: String, markdown: String) {
        runCatching {
            digestDir.mkdirs()
            File(digestDir, "$day.md").writeText(markdown.trim() + "\n", Charsets.UTF_8)
        }
    }

    /**
     * 升级迁移：删掉旧的「计划检查」残留。
     *
     * 用户明确说过旧会话可以直接删（m03352）。按天标记的 `plan-check.json` 和
     * 那段全局会话一起清掉；管家用自己的一套 checkpoint，不需要旧文件。
     */
    fun migrateLegacyPlanCheck(): Boolean = runCatching {
        val legacyStore = File(home, "plan-check.json")
        val legacyChat = File(File(home, "chat"), "plan-check.json")
        val removed = (if (legacyStore.isFile) legacyStore.delete() else true) and
            (if (legacyChat.isFile) legacyChat.delete() else true)
        removed
    }.getOrElse { false }

    private fun parseObject(text: String): JsonObject =
        json.parseToJsonElement(text.removePrefix("\uFEFF")).jsonObject

    private fun writeJson(file: File, value: JsonObject) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(value.toString(), Charsets.UTF_8)
        }
    }

    private fun encodeMemory(event: StewardMemoryEvent): String = buildJsonObject {
        put("at", event.at)
        put("day", event.day)
        put("kind", event.kind.name)
        put("text", event.text)
        if (event.quote.isNotBlank()) put("quote", event.quote)
        if (event.topics.isNotEmpty()) {
            putJsonArray("topics") { event.topics.forEach { add(it) } }
        }
        // entities 是 v1 新字段：只在非空时写，旧文件里没有它也不会多出一行空数组。
        if (event.entities.isNotEmpty()) {
            putJsonArray("entities") { event.entities.forEach { add(it) } }
        }
    }.toString()

    private fun parseMemory(line: String): StewardMemoryEvent? = runCatching<StewardMemoryEvent?> {
        if (line.isBlank()) return@runCatching null
        val root = json.parseToJsonElement(line.removePrefix("\uFEFF")).jsonObject
        val day = root["day"]?.jsonPrimitive?.content ?: return@runCatching null
        val kind = StewardMemoryKind.entries
            .firstOrNull { it.name == root["kind"]?.jsonPrimitive?.content }
            ?: StewardMemoryKind.Note
        StewardMemoryEvent(
            at = root["at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            day = day,
            kind = kind,
            text = root["text"]?.jsonPrimitive?.content.orEmpty(),
            quote = root["quote"]?.jsonPrimitive?.content.orEmpty(),
            topics = root["topics"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content.takeIf { topic -> topic.isNotBlank() } }
                ?: emptyList(),
            // 旧 memory.jsonl 没有 entities 字段：缺失按空列表，不报错、不丢行。
            entities = root["entities"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content.takeIf { entity -> entity.isNotBlank() } }
                ?: emptyList(),
        )
    }.getOrNull()

    /** 把整个日志读成事件列表（文件顺序）；坏行逐行丢弃，不影响其它行。 */
    private fun readMemory(): List<StewardMemoryEvent> {
        if (!memoryFile.isFile) return emptyList()
        return runCatching {
            memoryFile.readLines(Charsets.UTF_8).mapNotNull { line -> parseMemory(line) }
        }.getOrElse { emptyList() }
    }

    /** 指定 kind 的事件，按记录时间降序、最多 [limit] 条。 */
    private fun memoryOfKinds(
        kinds: Set<StewardMemoryKind>,
        limit: Int,
    ): List<StewardMemoryEvent> = readMemory()
        .filter { it.kind in kinds }
        .sortedByDescending { it.at }
        .take(limit.coerceIn(1, MAX_MEMORY_QUERY_LIMIT))

    /** 检索用的匹配：text / quote / topics / entities 任一包含 [needle]（大小写不敏感）。 */
    private fun StewardMemoryEvent.matches(needle: String): Boolean =
        text.contains(needle, ignoreCase = true) ||
            quote.contains(needle, ignoreCase = true) ||
            topics.any { it.contains(needle, ignoreCase = true) } ||
            entities.any { it.contains(needle, ignoreCase = true) }

    companion object {
        /** 单次检索/列目标的硬上限：日志再长也不给模型灌一整屏。 */
        const val MAX_MEMORY_QUERY_LIMIT: Int = 200
    }
}
