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
) {
    fun normalized(): StewardSettings = copy(
        morningTime = normalizeStewardTime(morningTime, DEFAULT_MORNING_TIME),
        eveningTime = normalizeStewardTime(eveningTime, DEFAULT_EVENING_TIME),
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
)

/** 记忆事件类型。落盘用枚举名，将来加类型不影响旧文件。 */
internal enum class StewardMemoryKind { Morning, Evening, UserReply, Adopted, Rejected, Note }

/**
 * 一条情景记忆。
 *
 * 只记「发生过什么」，不把模型的推测写进来：[text] 是当时发出的原话（截断），
 * [quote] 是用户回的原话（如果有），[topics] 是实体名（计划、课程、章节），
 * 供以后的词法检索用。向量检索（P2）是在同一份日志上另建索引，不改变这里。
 */
internal data class StewardMemoryEvent(
    val at: Long,
    val day: String,
    val kind: StewardMemoryKind,
    val text: String,
    val quote: String = "",
    val topics: List<String> = emptyList(),
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
        )
    }.getOrElse { StewardCheckpoints() }

    fun saveCheckpoints(checkpoints: StewardCheckpoints) {
        writeJson(
            File(dir, "checkpoints.json"),
            buildJsonObject {
                checkpoints.lastMorningDay?.let { put("lastMorningDay", it) }
                checkpoints.lastEveningDay?.let { put("lastEveningDay", it) }
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
        if (!memoryFile.isFile) return emptyList()
        val since = runCatching {
            java.time.LocalDate.parse(today).minusDays((days - 1).coerceAtLeast(0).toLong()).toString()
        }.getOrNull() ?: return emptyList()
        return runCatching {
            memoryFile.readLines(Charsets.UTF_8)
                .asSequence()
                .mapNotNull { line -> parseMemory(line) }
                .filter { it.day >= since && it.day <= today }
                .toList()
                .takeLast(limit)
        }.getOrElse { emptyList() }
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
        )
    }.getOrNull()
}
