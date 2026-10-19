package dev.trellis.ai

import dev.trellis.kernel.extension.Template
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * **给模型书写用的**插件形态。
 *
 * 它比真正的插件格式窄得多：只有 id / name / description / slots 四项。
 * 模型不用知道 `manifest.json` 和 `main.dsl.json` 是两个文件，不用记 `loader`、
 * `schemaVersion`、`entry`、每张卡片的 `id`。
 *
 * ### 为什么要窄
 *
 * 模型每多写对一个字段，就多一次犯错的机会。给它两个文件、十个必填项，
 * 它会在「忘了 `loader`」和「`order` 写成字符串」上翻车，而那些**跟用户的需求毫无关系**。
 *
 * 窄 schema + 本地展开 = 把「格式错误」这一整类问题从重试循环里消掉。
 */
@Serializable
data class AuthoredPlugin(
    val id: String,
    val name: String = "",
    val description: String = "",
    val slots: List<AuthoredSlot> = emptyList(),
) {

    /**
     * 展开成真正的两个文件。
     *
     * 卡片 id 由这里生成（s1、s2……），不让模型操心 —— 它对用户毫无意义。
     */
    fun toFiles(): Map<String, String> = mapOf(
        "manifest.json" to manifestJson(),
        "main.dsl.json" to entryJson(),
    )

    fun manifestJson(): String = PRETTY.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("id", id)
            put("version", VERSION)
            put("schemaVersion", SCHEMA_VERSION)
            put("loader", "dsl")
            if (name.isNotBlank()) put("name", name)
            if (description.isNotBlank()) put("description", description)
        },
    )

    fun entryJson(): String = PRETTY.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            putJsonArray("slots") {
                slots.forEachIndexed { index, slot ->
                    addJsonObject {
                        put("point", slot.point)
                        put("id", "s" + (index + 1))
                        put("order", slot.order)
                        if (slot.title.isNotBlank()) put("title", slot.title)
                        if (slot.body.isNotBlank()) put("body", slot.body)
                    }
                }
            }
        },
    )

    /**
     * 落地之前先自查。
     *
     * 返回的每一条都会被**原样喂回模型**，所以必须是它能照着改的话：
     * 说清楚哪一项不对、正确选项有哪些。
     */
    fun validate(catalog: CapabilityCatalog): List<String> {
        val problems = mutableListOf<String>()

        if (!ID_PATTERN.matches(id)) {
            problems += "id「" + id + "」不合规：必须由小写字母、数字、点、横线组成，" +
                "至少两段，且以 ai. 开头，例如 ai.exam-countdown"
        }
        if (slots.isEmpty()) {
            problems += "slots 是空的：至少要有一张卡片"
        }
        if (slots.size > MAX_SLOTS) {
            problems += "slots 有 " + slots.size + " 张，最多只能有 " + MAX_SLOTS + " 张"
        }
        if (description.isBlank()) {
            problems += "description 是空的：写一句大白话说明这个插件干什么"
        }

        val knownPoints = catalog.mountPointIds
        val knownFields = catalog.fieldPaths

        slots.forEachIndexed { index, slot ->
            val n = index + 1
            if (slot.point !in knownPoints) {
                problems += "第 " + n + " 张卡片的 point「" + slot.point + "」不是内核认识的挂载点。" +
                    "只能用这些：" + knownPoints.sorted().joinToString("、")
            }
            if (slot.title.isBlank() && slot.body.isBlank()) {
                problems += "第 " + n + " 张卡片的标题和正文都是空的"
            }
            if (slot.title.length > MAX_TITLE) {
                problems += "第 " + n + " 张卡片标题 " + slot.title.length + " 个字，最多 " + MAX_TITLE + " 个"
            }
            if (slot.body.length > MAX_BODY) {
                problems += "第 " + n + " 张卡片正文 " + slot.body.length + " 个字，最多 " + MAX_BODY + " 个"
            }

            // 模板里引用的每个字段都必须在数据源清单里。
            // 这一条挡的是「模型很自信地编了一个不存在的字段」—— 那种错误
            // 装上去之后只会显示成 --，用户根本不知道为什么。
            val placeholders = Template.parse(slot.title + " " + slot.body)
            for (p in placeholders) {
                val full = p.namespace + "." + p.path
                if (full !in knownFields) {
                    problems += "第 " + n + " 张卡片引用了 {{ " + full + " }}，但数据源里没有这个字段。" +
                        "只能用这些：" + knownFields.sorted().joinToString("、")
                }
            }
        }
        return problems
    }

    companion object {
        const val VERSION = "1.0.0"
        const val SCHEMA_VERSION = 1
        const val MAX_SLOTS = 3
        const val MAX_TITLE = 20
        const val MAX_BODY = 80

        /**
         * `ai.` 前缀是**故意的**：所有模型生成的东西一眼可辨。
         * 用户想清掉 AI 造的东西时，按前缀筛就行。
         */
        private val ID_PATTERN = Regex("""^ai\.[a-z0-9]+(\.[a-z0-9-]+)*$""")

        private val PRETTY = Json {
            prettyPrint = true
            encodeDefaults = false
        }
    }
}

/** 模型能写的一张卡片。`id` 不在这里 —— 由 [AuthoredPlugin.toFiles] 生成。 */
@Serializable
data class AuthoredSlot(
    val point: String,
    val title: String = "",
    val body: String = "",
    val order: Int = 100,
)
