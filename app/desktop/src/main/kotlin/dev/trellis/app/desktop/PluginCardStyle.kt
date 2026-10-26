package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 单个插件的卡片样式覆盖。
 *
 * 和 [UiPrefs] 的分工：UiPrefs 是「整页一个值」，这里是「这个插件单独一套」。
 * 两个字段都可为空，空 = 跟随全局；没被点名的插件不会被改动。
 *
 * 只开 card（bordered / flat / filled）和 radius（0-24）两样：这是「让某张卡片显眼一点」
 * 够用的粒度。插件**里面**的排版仍然归插件自己（UiNode 词汇表）。
 */
data class PluginCardStyle(
    val card: String = "bordered",
    val radius: Int? = null,
) {
    fun normalized(): PluginCardStyle = copy(
        card = card.takeIf { it in PLUGIN_CARDS } ?: "bordered",
        radius = radius?.coerceIn(0, 24),
    )
}

internal val PLUGIN_CARDS = listOf("bordered", "flat", "filled", "bento")

/** 落盘在宿主目录的 `plugin-style.json`：`{ "ai.pushups": { "card": "filled", "radius": 6 } }`。 */
internal class PluginStyleStore(home: File) {
    private val file = File(home, "plugin-style.json")

    fun load(): Map<PluginId, PluginCardStyle> {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?.removePrefix("\uFEFF") ?: return emptyMap()
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return root.entries.mapNotNull { (id, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val card = obj["card"]?.jsonPrimitive?.contentOrNull ?: "bordered"
            PluginId(id) to PluginCardStyle(
                card = card,
                radius = obj["radius"]?.jsonPrimitive?.intOrNull,
            ).normalized()
        }.toMap()
    }

    fun save(styles: Map<PluginId, PluginCardStyle>) {
        val root = buildJsonObject {
            styles.forEach { (id, style) ->
                val clean = style.normalized()
                put(
                    id.value,
                    buildJsonObject {
                        put("card", clean.card)
                        clean.radius?.let { put("radius", it) }
                    },
                )
            }
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }
}
