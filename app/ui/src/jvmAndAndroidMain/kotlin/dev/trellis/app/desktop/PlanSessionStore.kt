package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 计划 ↔ 会话的绑定：`<home>/plans/sessions.json`。
 *
 * 每周的 `plan_week` 要在「这条计划自己的会话」里跑，这样那周的讨论、决定、凭证都留在
 * 同一个上下文里。建计划时如果用户在某个会话里，就把这个会话记下来；没有绑定或者
 * 会话被删了，由管家代跑（见 `AppRuntime.runWeeklyPlanRound`）。
 *
 * 结构：`{"bindings": {"pln_xxx": "chat_xxx"}}`。文件坏了就当没有绑定 —— 周计划还能由管家代跑。
 */
internal class PlanSessionStore(home: File) {
    private val file = File(File(home, "plans"), "sessions.json")

    fun sessionFor(planId: String): String? = bindings()[planId]

    fun bindings(): Map<String, String> = runCatching {
        if (!file.isFile) return@runCatching emptyMap()
        val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8).removePrefix("\uFEFF")).jsonObject
        val bindings = root["bindings"]?.jsonObject ?: return@runCatching emptyMap()
        bindings.mapNotNull { (planId, value) ->
            val sessionId = value.jsonPrimitive.content.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            planId to sessionId
        }.toMap()
    }.getOrDefault(emptyMap())

    /** 记下「这条计划的周计划在哪个会话里跑」；重复绑定以最新的为准。 */
    fun bind(planId: String, sessionId: String) {
        if (planId.isBlank() || sessionId.isBlank()) return
        val updated = bindings().toMutableMap().apply { put(planId, sessionId) }
        runCatching {
            file.parentFile?.mkdirs()
            val root: JsonObject = buildJsonObject {
                put("bindings", buildJsonObject {
                    updated.toSortedMap().forEach { (plan, session) -> put(plan, session) }
                })
            }
            file.writeText(Json.encodeToString(JsonObject.serializer(), root), Charsets.UTF_8)
        }
    }
}
