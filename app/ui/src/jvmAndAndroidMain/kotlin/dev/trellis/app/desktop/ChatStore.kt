package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 * 写盘必须原子：先写同目录的 `.tmp` 再 `ATOMIC_MOVE` 覆盖，避免进程崩溃/磁盘满时
 * 留下半截 JSON、让整段会话从列表里无声消失。读失败的文件改名成 `.bad-<时间戳>`
 * 留档（不删），下次 `list()` 不再看见它。
 *
 * 读失败一律当「没有这段会话」：聊天记录坏一个文件，不能把程序带崩。
 */
internal class ChatStore(home: File, private val log: (String) -> Unit = {}) {
    private val dir = File(home, "chat")

    init {
        dir.mkdirs()
    }

    fun list(): List<ChatSession> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file -> read(file.nameWithoutExtension) }
            .sortedByDescending { it.updatedAt }
    }

    fun read(id: String): ChatSession? {
        val file = File(dir, "$id.json")
        if (!file.isFile) return null
        val raw = runCatching { file.readText(Charsets.UTF_8).removePrefix("\uFEFF") }.getOrElse { error ->
            log("会话文件读不出来（$id）：${error.message ?: error::class.simpleName}")
            quarantine(file, id)
            return null
        }
        val session = runCatching { parse(raw) }.getOrElse { error ->
            log("会话文件解析失败（$id）：${error.message ?: error::class.simpleName}")
            quarantine(file, id)
            return null
        }
        if (session == null) {
            log("会话文件格式不对，已留档（$id）")
            quarantine(file, id)
        }
        return session
    }

    fun save(session: ChatSession) {
        dir.mkdirs()
        val target = File(dir, "${session.id}.json")
        val tmp = File(dir, "${session.id}.json.tmp")
        runCatching {
            tmp.writeText(encode(session).toString(), Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // 极少数文件系统不支持原子替换：退化成普通替换，至少不会留下半截文件。
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { error ->
            runCatching { tmp.delete() }
            log("会话没能写进磁盘（${session.id}）：${error.message ?: error::class.simpleName}")
        }
    }

    /** 坏文件改名留档，避免下一次 `list()` 继续把它当会话读。 */
    private fun quarantine(file: File, id: String) {
        runCatching {
            val stamp = System.currentTimeMillis()
            val backup = File(dir, "$id.json.bad-$stamp")
            if (file.renameTo(backup)) {
                log("坏掉的会话文件已留档：${backup.name}")
            }
        }
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
                proposal = line["proposal"]?.jsonObject?.let { parseProposal(it) },
                proposalState = ProposalState.entries
                    .firstOrNull { it.name == line["proposalState"]?.jsonPrimitive?.content }
                    ?: ProposalState.Open,
                practiceProposal = line["practice"]?.jsonObject?.let { parsePractice(it) },
                practiceState = PracticeProposalState.entries
                    .firstOrNull { it.name == line["practiceState"]?.jsonPrimitive?.content }
                    ?: PracticeProposalState.Open,
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
            // 旧文件没有 kind：默认是普通的管理员会话。
            kind = SessionKind.entries
                .firstOrNull { it.name == root["kind"]?.jsonPrimitive?.content }
                ?: SessionKind.Manager,
        )
    }.getOrNull()

    private fun parseProposal(root: JsonObject): StewardProposal? {
        val changes = root["changes"]?.jsonArray?.mapNotNull { element ->
            val change = element.jsonObject
            val title = change["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val day = change["day"]?.jsonPrimitive?.content ?: return@mapNotNull null
            when (change["kind"]?.jsonPrimitive?.content) {
                "move" -> change["occurrenceId"]?.jsonPrimitive?.content?.let { StewardChange.Move(title, it, day) }
                "place" -> change["unitId"]?.jsonPrimitive?.content?.let { StewardChange.Place(title, it, day) }
                else -> null
            }
        } ?: return null
        if (changes.isEmpty()) return null
        return StewardProposal(
            id = root["id"]?.jsonPrimitive?.content ?: return null,
            title = root["title"]?.jsonPrimitive?.content.orEmpty(),
            detail = root["detail"]?.jsonPrimitive?.content.orEmpty(),
            changes = changes,
        )
    }

    private fun parsePractice(root: JsonObject): PracticeProposal? {
        val planId = root["planId"]?.jsonPrimitive?.content ?: return null
        val exerciseId = root["exerciseId"]?.jsonPrimitive?.content ?: return null
        return PracticeProposal(
            id = root["id"]?.jsonPrimitive?.content ?: return null,
            planId = planId,
            planTitle = root["planTitle"]?.jsonPrimitive?.content.orEmpty(),
            subject = root["subject"]?.jsonPrimitive?.content.orEmpty(),
            exerciseId = exerciseId,
            title = root["title"]?.jsonPrimitive?.content.orEmpty(),
            detail = root["detail"]?.jsonPrimitive?.content.orEmpty(),
        )
    }

    private fun encode(session: ChatSession): JsonObject = buildJsonObject {
        put("id", session.id)
        put("title", session.title)
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        // 只写 true：已读是默认值，不往每份会话里塞一个 false。
        if (session.unread) put("unread", true)
        // 只写 steward：普通管理员会话是默认值，不写进每份文件。
        if (session.kind != SessionKind.Manager) put("kind", session.kind.name)
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
                        line.proposal?.let { proposal ->
                            put(
                                "proposal",
                                buildJsonObject {
                                    put("id", proposal.id)
                                    put("title", proposal.title)
                                    put("detail", proposal.detail)
                                    putJsonArray("changes") {
                                        proposal.changes.forEach { change ->
                                            add(
                                                buildJsonObject {
                                                    when (change) {
                                                        is StewardChange.Move -> {
                                                            put("kind", "move")
                                                            put("occurrenceId", change.occurrenceId)
                                                        }
                                                        is StewardChange.Place -> {
                                                            put("kind", "place")
                                                            put("unitId", change.unitId)
                                                        }
                                                    }
                                                    put("title", change.title)
                                                    put("day", change.day)
                                                },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        if (line.proposalState != ProposalState.Open) {
                            put("proposalState", line.proposalState.name)
                        }
                        line.practiceProposal?.let { practice ->
                            put(
                                "practice",
                                buildJsonObject {
                                    put("id", practice.id)
                                    put("planId", practice.planId)
                                    put("planTitle", practice.planTitle)
                                    put("subject", practice.subject)
                                    put("exerciseId", practice.exerciseId)
                                    put("title", practice.title)
                                    put("detail", practice.detail)
                                },
                            )
                        }
                        if (line.practiceState != PracticeProposalState.Open) {
                            put("practiceState", line.practiceState.name)
                        }
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
