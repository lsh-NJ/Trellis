package dev.trellis.app.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 「AI 按笔记自动完成」的账本：`<home>/plans/auto-completions.json`。
 *
 * 两个用途：
 * 1. **可追溯**：每条记录留着是哪个节点、哪篇笔记、正文里哪段证据、置信度多少；
 *    计划页「AI 自动完成」列表就是读它。
 * 2. **防重复**：撤销过的节点进 `blocked`，同一篇（或别的）笔记再整理也不会又把它勾上。
 *
 * 写入策略沿用知识库那几个 store：去 BOM 读、写前 `.bak`、临时文件 + rename；
 * 文件读坏时改名 `.bad-<ms>` 留档、按空账本继续（自动完成丢记录不影响计划本身）。
 * 不认识的顶层字段原样保留。
 */
internal enum class AutoCompletionStatus(val wire: String) {
    /** 已经由 AI 完成。 */
    Auto("auto"),

    /** 用户撤销了（节点应该已经改回没做）。 */
    Undone("undone"),
    ;

    companion object {
        fun fromWire(raw: String): AutoCompletionStatus =
            entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) } ?: Auto
    }
}

/** 这笔自动完成是谁触发的：笔记整理判定，还是计时器跑满预估时长。 */
internal enum class AutoCompletionKind(val wire: String) {
    /** 笔记 AI 整理后判定「这篇笔记已经讲完这个节点」。 */
    Note("note"),

    /** 计时器/计划 session 的实际计时达到节点预估时长。 */
    Time("time"),
    ;

    companion object {
        fun fromWire(raw: String): AutoCompletionKind =
            entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) } ?: Note
    }
}

/** 一笔自动完成。 */
internal data class AutoCompletionEntry(
    val id: String,
    val planId: String,
    val occurrenceId: String,
    val title: String,
    val chapter: String,
    val day: String,
    val noteId: String,
    val noteTitle: String,
    val evidence: String,
    val confidence: Double,
    val at: Long,
    val kind: AutoCompletionKind = AutoCompletionKind.Note,
    val actualMinutes: Int = 0,
    val plannedMinutes: Int = 0,
    /** 时间来源：`timer` = 计时器 session；`activity` = 屏幕使用归因；空 = 旧记录（只有计时）。 */
    val source: String = "",
    /**
     * 屏幕使用来源的「学的是什么」：归因片段里停留最久的窗口/标签标题（例如
     * `CPU 是如何执行程序的？ - 小林coding`）。只用于计划页展示，不参与是否完成的判定。
     */
    val sourceTitle: String = "",
    val status: AutoCompletionStatus = AutoCompletionStatus.Auto,
    val undoneAt: Long? = null,
)

/** 整个账本：记录 + 屏蔽名单（撤销过的节点）。 */
internal data class AutoCompletionLedger(
    val version: Int = 1,
    val entries: List<AutoCompletionEntry> = emptyList(),
    val blocked: Set<String> = emptySet(),
) {
    companion object {
        val Empty = AutoCompletionLedger()
    }
}

/** 账本上的纯操作（不碰文件，方便单测）。 */

internal fun AutoCompletionLedger.withCompletion(entry: AutoCompletionEntry): AutoCompletionLedger =
    copy(entries = entries + entry)

internal fun AutoCompletionLedger.withUndone(entryId: String, at: Long): AutoCompletionLedger =
    copy(
        entries = entries.map { entry ->
            if (entry.id == entryId && entry.status == AutoCompletionStatus.Auto) {
                entry.copy(status = AutoCompletionStatus.Undone, undoneAt = at)
            } else {
                entry
            }
        },
    )

internal fun AutoCompletionLedger.withBlocked(occurrenceId: String): AutoCompletionLedger =
    if (occurrenceId.isBlank() || occurrenceId in blocked) this
    else copy(blocked = blocked + occurrenceId)

internal fun AutoCompletionLedger.withUnblocked(occurrenceId: String): AutoCompletionLedger =
    if (occurrenceId !in blocked) this else copy(blocked = blocked - occurrenceId)

internal fun AutoCompletionLedger.isBlocked(occurrenceId: String): Boolean =
    occurrenceId in blocked

/** 这个节点当前有效的自动完成记录（撤销过的不算）。 */
internal fun AutoCompletionLedger.activeCompletionFor(occurrenceId: String): AutoCompletionEntry? =
    entries.lastOrNull { it.occurrenceId == occurrenceId && it.status == AutoCompletionStatus.Auto }

/** 计划页列表要的：按时间倒序的最近记录。 */
internal fun AutoCompletionLedger.recentEntries(limit: Int): List<AutoCompletionEntry> =
    entries.sortedByDescending { it.at }.take(limit.coerceAtLeast(0))

/** 读 / 写 `<home>/plans/auto-completions.json`。 */
internal class AutoCompletionStore(
    private val home: File,
    private val clock: TrellisClock,
) {
    private val json = Json { prettyPrint = true }

    val file: File = File(File(home, "plans"), "auto-completions.json")

    /** 上次 [load] 的失败说明（没失败 = null）；UI 可以拿去显示诊断。 */
    var loadError: String? = null
        private set

    /** 上次读到的未知顶层字段；保存时补回。 */
    private var extras: JsonObject = JsonObject(emptyMap())

    private var seq = 0

    fun load(): AutoCompletionLedger {
        loadError = null
        extras = JsonObject(emptyMap())
        val text = readTextOrNull(file) ?: return AutoCompletionLedger.Empty
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            extras = JsonObject(root.filterKeys { it !in TOP_LEVEL_KEYS })
            AutoCompletionLedger(
                version = root.intValue("version") ?: 1,
                entries = root["entries"]?.let(::parseEntries).orEmpty(),
                blocked = root["blocked"]?.let(::parseBlocked).orEmpty(),
            )
        } catch (error: Exception) {
            loadError = quarantine(file, error)
            AutoCompletionLedger.Empty
        }
    }

    fun save(ledger: AutoCompletionLedger): Boolean {
        val encoded = buildJsonObject {
            put("version", ledger.version)
            put("entries", buildJsonArray { ledger.entries.forEach { add(encodeEntry(it)) } })
            put("blocked", buildJsonArray { ledger.blocked.sorted().forEach { add(it) } })
        }
        return writeAtomic(file, encodeToJson(mergeExtras(encoded, extras)))
    }

    /**
     * 生成一条记录 id（`ac_` + 8 位十六进制）。
     *
     * 只保证「形状」：调用方（`AppRuntime`）用账本里已有的 id 查重，撞了就再取一个。
     * 用时时钟 + 自增，测试里固定时钟也能得到稳定值。
     */
    fun newEntryId(): String {
        seq++
        val seed = clock.nowMs() * 31L + seq
        val hex = graphHashText(fnv1a64("auto-completion:$seed:$seq")).removePrefix(GRAPH_HASH_PREFIX)
        return "ac_" + hex.take(8)
    }

    // ---- 解析 ----

    private fun parseEntries(element: JsonElement): List<AutoCompletionEntry> {
        val array = runCatching { element.jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val occurrenceId = obj.stringValue("occurrenceId").orEmpty()
            if (occurrenceId.isBlank()) return@mapNotNull null
            AutoCompletionEntry(
                id = obj.stringValue("id").orEmpty(),
                planId = obj.stringValue("planId").orEmpty(),
                occurrenceId = occurrenceId,
                title = obj.stringValue("title").orEmpty(),
                chapter = obj.stringValue("chapter").orEmpty(),
                day = obj.stringValue("day").orEmpty(),
                noteId = obj.stringValue("noteId").orEmpty(),
                noteTitle = obj.stringValue("noteTitle").orEmpty(),
                evidence = obj.stringValue("evidence").orEmpty(),
                confidence = obj.doubleValue("confidence") ?: 0.0,
                at = obj.longValue("at") ?: 0L,
                kind = AutoCompletionKind.fromWire(obj.stringValue("kind").orEmpty()),
                actualMinutes = obj.intValue("actualMinutes") ?: 0,
                plannedMinutes = obj.intValue("plannedMinutes") ?: 0,
                source = obj.stringValue("source").orEmpty(),
                sourceTitle = obj.stringValue("sourceTitle").orEmpty(),
                status = AutoCompletionStatus.fromWire(obj.stringValue("status").orEmpty()),
                undoneAt = obj.longValue("undoneAt"),
            )
        }
    }

    private fun parseBlocked(element: JsonElement): Set<String> {
        val array = runCatching { element.jsonArray }.getOrNull() ?: return emptySet()
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    // ---- 编码 ----

    private fun encodeEntry(entry: AutoCompletionEntry): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("planId", entry.planId)
        put("occurrenceId", entry.occurrenceId)
        put("title", entry.title)
        if (entry.chapter.isNotBlank()) put("chapter", entry.chapter)
        put("day", entry.day)
        put("noteId", entry.noteId)
        put("noteTitle", entry.noteTitle)
        put("evidence", entry.evidence)
        put("confidence", entry.confidence)
        put("at", entry.at)
        put("kind", entry.kind.wire)
        if (entry.actualMinutes > 0) put("actualMinutes", entry.actualMinutes)
        if (entry.plannedMinutes > 0) put("plannedMinutes", entry.plannedMinutes)
        if (entry.source.isNotBlank()) put("source", entry.source)
        if (entry.sourceTitle.isNotBlank()) put("sourceTitle", entry.sourceTitle)
        put("status", entry.status.wire)
        if (entry.undoneAt != null) put("undoneAt", entry.undoneAt)
    }

    private fun encodeToJson(element: JsonObject): String =
        json.encodeToString(JsonElement.serializer(), element)

    private fun mergeExtras(encoded: JsonObject, extras: JsonObject): JsonObject {
        if (extras.isEmpty()) return encoded
        val merged = LinkedHashMap<String, JsonElement>(encoded)
        extras.forEach { (key, value) -> if (!merged.containsKey(key)) merged[key] = value }
        return JsonObject(merged)
    }

    // ---- 文件 ----

    private fun readTextOrNull(file: File): String? {
        if (!file.isFile) return null
        return try {
            file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAtomic(file: File, text: String): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        return try {
            if (file.isFile && file.length() > 0L) {
                try {
                    file.copyTo(File(parent, file.name + ".bak"), overwrite = true)
                } catch (_: Exception) {
                    // 备份失败不阻塞主写入。
                }
            }
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (file.exists() && !file.delete()) return false
            if (tmp.renameTo(file)) return true
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun quarantine(file: File, error: Exception): String {
        val target = File(file.parentFile, file.name + ".bad-" + clock.nowMs())
        val moved = try {
            file.renameTo(target)
        } catch (_: Exception) {
            false
        }
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty()
        return if (moved) {
            "「${file.name}」解析失败（$reason），已留档为「${target.name}」，当前按空账本处理"
        } else {
            "「${file.name}」解析失败（$reason），当前按空账本处理"
        }
    }

    private companion object {
        val TOP_LEVEL_KEYS = setOf("version", "entries", "blocked")
    }
}

// ---------------------------------------------------------------------------
// JSON 小工具
// ---------------------------------------------------------------------------

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intValue(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

private fun JsonObject.longValue(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

private fun JsonObject.doubleValue(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
