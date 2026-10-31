package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 会话记录的落盘目录：`<home>/chat/<id>.json`。
 *
 * 为什么自己拼 JSON 而不用序列化插件：app:desktop 没有接 kotlinx-serialization
 * 的编译器插件（`@Serializable` 生成不了 serializer），而会话结构很小、字段稳定，
 * 手写 30 行比为一个文件引入插件+版本迁移划算。
 *
 * 读失败一律当「没有这段会话」：聊天记录坏一个文件，不能把程序带崩。
 */
internal class ChatStore(home: File) {
    private val dir = File(home, "chat")

    init {
        dir.mkdirs()
    }

    fun list(): List<ChatSession> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file -> read(file.nameWithoutExtension) }
            .sortedByDescending { it.updatedAt }
    }

    fun read(id: String): ChatSession? = runCatching {
        val file = File(dir, "$id.json")
        if (!file.isFile) return@runCatching null
        parse(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
    }.getOrNull()

    fun save(session: ChatSession) {
        dir.mkdirs()
        File(dir, "${session.id}.json").writeText(encode(session).toString(), Charsets.UTF_8)
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    private fun parse(raw: String): ChatSession? = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        val lines = root["lines"]?.jsonArray?.mapNotNull { element ->
            val line = element.jsonObject
            val role = ChatRole.entries.firstOrNull { it.name == line["role"]?.jsonPrimitive?.content }
                ?: return@mapNotNull null
            ChatLine(
                id = line["id"]?.jsonPrimitive?.long ?: 0L,
                role = role,
                text = line["text"]?.jsonPrimitive?.content ?: "",
                atMs = line["atMs"]?.jsonPrimitive?.long ?: 0L,
                plan = line["plan"]?.jsonArray?.mapNotNull { element ->
                    val step = element.jsonObject
                    val state = PlanStepState.entries
                        .firstOrNull { it.name == step["state"]?.jsonPrimitive?.content }
                        ?: PlanStepState.Pending
                    PlanStep(
                        text = step["text"]?.jsonPrimitive?.content ?: "",
                        state = state,
                        tool = step["tool"]?.jsonPrimitive?.content ?: "",
                        note = step["note"]?.jsonPrimitive?.content ?: "",
                        impact = step["impact"]?.jsonPrimitive?.content ?: "",
                        undoable = step["undoable"]?.jsonPrimitive?.booleanOrNull == true,
                        ms = step["ms"]?.jsonPrimitive?.long ?: 0L,
                        startedMs = step["startedMs"]?.jsonPrimitive?.long ?: 0L,
                        dependsOn = step["dependsOn"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() } ?: emptyList(),
                    )
                } ?: emptyList(),
                planStartedMs = line["planStartedMs"]?.jsonPrimitive?.long ?: 0L,
                planFinishedMs = line["planFinishedMs"]?.jsonPrimitive?.long ?: 0L,
            )
        } ?: emptyList()
        ChatSession(
            id = root["id"]?.jsonPrimitive?.content ?: return@runCatching null,
            title = root["title"]?.jsonPrimitive?.content ?: "新对话",
            createdAt = root["createdAt"]?.jsonPrimitive?.long ?: 0L,
            updatedAt = root["updatedAt"]?.jsonPrimitive?.long ?: 0L,
            lines = lines,
            // 旧文件没有这个字段：默认已读。升级后不该凭空冒出一排红点。
            unread = root["unread"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }.getOrNull()

    private fun encode(session: ChatSession): JsonObject = buildJsonObject {
        put("id", session.id)
        put("title", session.title)
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        // 只写 true：已读是默认值，不往每份会话里塞一个 false。
        if (session.unread) put("unread", true)
        putJsonArray("lines") {
            session.lines.forEach { line ->
                add(
                    buildJsonObject {
                        put("id", line.id)
                        put("role", line.role.name)
                        put("text", line.text)
                        put("atMs", line.atMs)
                        if (line.planStartedMs > 0L) put("planStartedMs", line.planStartedMs)
                        if (line.planFinishedMs > 0L) put("planFinishedMs", line.planFinishedMs)
                        if (line.plan.isNotEmpty()) {
                            putJsonArray("plan") {
                                line.plan.forEach { step ->
                                    add(
                                        buildJsonObject {
                                            put("text", step.text)
                                            put("state", step.state.name)
                                            if (step.tool.isNotEmpty()) put("tool", step.tool)
                                            if (step.note.isNotEmpty()) put("note", step.note)
                                            if (step.impact.isNotEmpty()) put("impact", step.impact)
                                            if (step.undoable) put("undoable", true)
                                            if (step.startedMs > 0L) put("startedMs", step.startedMs)
                                            if (step.ms > 0L) put("ms", step.ms)
                                            if (step.dependsOn.isNotEmpty()) {
                                                putJsonArray("dependsOn") {
                                                    step.dependsOn.forEach { add(it.toString()) }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** 给会话列表显示「今天 14:03 / 昨天 / 10-22」。 */
internal fun chatTimeLabel(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return ""
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        time.toLocalDate() == today -> time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        time.toLocalDate() == today.minusDays(1) -> "昨天"
        time.year == today.year -> time.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"))
        else -> time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }
}
