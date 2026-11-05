package dev.trellis.app.desktop

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import dev.trellis.ui.SlotCell
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 布局即数据。
 *
 * 宿主原来只会把卡片一张一张往下叠，所以「把三个插件横着排」这种事，
 * 插件再强也做不到：布局不在它手里。现在布局是一份用户（或 agent）
 * 可以改的数据：每个挂载点一组行，行里按插件 id 排卡片，每格还能写占几栏。
 *
 * 为什么放在宿主、不放进内核：v1 只解决「同一个挂载点里怎么排」，
 * 不值得为它动内核的 Profile/快照协议。等跨端布局也要同步时，再上移。
 *
 * 为什么手写 JSON 而不是 @Serializable：app:desktop 没有接序列化编译器插件
 * （它只依赖运行时库），为了一个布局文件去动构建脚本不值得。
 */
internal class LayoutStore(private val home: File) {

    private val file = File(home, "layout.json")
    private val json = Json { prettyPrint = true }

    fun load(): Map<ExtensionPointId, List<List<SlotCell>>> =
        read().mapNotNull { (raw, rows) ->
            val cleaned = rows.map { row -> row.map { parseCell(it) } }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) null else ExtensionPointId(raw) to cleaned
        }.toMap()

    fun save(layout: Map<ExtensionPointId, List<List<SlotCell>>>) {
        val root = buildJsonObject {
            put(
                "points",
                buildJsonObject {
                    for ((point, rows) in layout) {
                        put(
                            point.value,
                            JsonArray(
                                rows.map { row ->
                                    JsonArray(row.map { cell -> JsonPrimitive(writeCell(cell)) })
                                },
                            ),
                        )
                    }
                },
            )
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        }
    }

    /** 文件坏了就当没排过版：布局坏掉不该让应用起不来。 */
    private fun read(): Map<String, List<List<String>>> = runCatching {
        if (!file.isFile) {
            emptyMap()
        } else {
            val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
            val points = json.parseToJsonElement(text).jsonObject["points"]?.jsonObject
                ?: JsonObject(emptyMap())
            points.mapValues { (_, value) ->
                value.jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
            }
        }
    }.getOrElse { emptyMap() }

    companion object {
        /** 一格最多占 12 栏；和 UiNode.Grid 的上限一致。 */
        const val MAX_SPAN = 12

        /**
         * `rows` 的文本写法：`;` 分行，`|` 分隔同一行里的卡片，卡片可以写 `插件id:栏数`。
         *
         * 例：`ai.pushups:8|ai.mood-checkin:4;ai.reading-pages` =
         * 第一行 8 栏 + 4 栏，第二行独占一行。
         * 为什么不写 span 就等宽：多数时候「一行两张」就是想要的，写数字反而啰嗦。
         */
        fun parseRows(raw: String): List<List<SlotCell>> =
            raw.split(';')
                .map { row ->
                    row.split('|').map { it.trim() }.filter { it.isNotEmpty() }.map { parseCell(it) }
                }
                .filter { it.isNotEmpty() }

        /** 单格解析：`id` 或 `id:span`；span 越界收回 1..12，非法时按 1 处理。 */
        fun parseCell(text: String): SlotCell {
            val trimmed = text.trim()
            val colon = trimmed.lastIndexOf(':')
            if (colon <= 0) return SlotCell(PluginId(trimmed), 1)
            val span = trimmed.substring(colon + 1).trim().toIntOrNull() ?: return SlotCell(PluginId(trimmed), 1)
            val id = trimmed.substring(0, colon).trim()
            if (id.isEmpty()) return SlotCell(PluginId(trimmed), 1)
            return SlotCell(PluginId(id), span.coerceIn(1, MAX_SPAN))
        }

        private fun writeCell(cell: SlotCell): String =
            if (cell.span <= 1) cell.pluginId.value else cell.pluginId.value + ":" + cell.span
    }
}
