package dev.trellis.ai

import dev.trellis.kernel.extension.Template
import dev.trellis.kernel.expr.Expression
import dev.trellis.kernel.expr.ExpressionException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
    // 有默认值：拒绝时输出的是 {"cannotDo": "..."}，里面**没有 id**。
    // 不给默认值的话，那条最该被接受的输出反而会反序列化失败。
    val id: String = "",
    val name: String = "",
    val description: String = "",
    /**
     * 怎么用：在哪看、什么时候看。**这句话是给用户看的**，不是给机器。
     *
     * 装上一个插件之后，用户并不能自动知道它长什么样、去哪儿找。
     * 没有这句话，用户只能反复开关插件玩「找不同」。
     */
    val howToUse: String = "",
    /**
     * 这个插件**少做了什么**。没退步就留空。
     *
     * 退一步不是错，**不吭声地退一步**才是错：用户会以为这就是他要的。
     */
    val limitation: String = "",
    /**
     * 自己算出来的字段。键是字段名，值是表达式。
     * 模板里用 {{ self.字段名 }} 引用。
     */
    val fields: Map<String, String> = emptyMap(),
    /** 规则：某个事件发生时依次做几件事。 */
    val rules: List<AuthoredRule> = emptyList(),
    val slots: List<AuthoredSlot> = emptyList(),
    /**
     * 非空表示**模型主动拒绝**：这个需求超出了「只能显示卡片」的范围。
     *
     * 这是整套设计里最重要的一条诚实性约束。没有它，模型会生成一张
     * 写着「已按你的精力曲线排好顺序」的卡片，而它一个字都没排。
     * 那种输出比直接失败更糟：**它看起来是能用的。**
     */
    val cannotDo: String? = null,
) {

    /** 这是一次「老实说做不到」，不是插件。 */
    val isRefusal: Boolean get() = !cannotDo.isNullOrBlank()

    /**
     * 展开成真正的两个文件。
     *
     * 卡片 id 由这里生成（s1、s2……），不让模型操心 —— 它对用户毫无意义。
     */
    fun toFiles(catalog: CapabilityCatalog): Map<String, String> = mapOf(
        "manifest.json" to manifestJson(catalog),
        "main.dsl.json" to entryJson(),
    )

    /**
     * 这个插件用到的动作需要哪些权限。
     *
     * 必须写进 manifest：用户要能在插件详情里看见「它要写数据」，
     * 内核也只放行**声明过**的能力  （少这一步，规则一跑就会被拒，
     * 而且报错会指向「没声明权限」，看起来像插件的错、其实是生成器漏了。）
     */
    fun capabilities(catalog: CapabilityCatalog): Set<String> {
        val used = buildSet {
            rules.forEach { rule -> rule.actions.forEach { add(it.action.trim()) } }
            slots.forEach { slot -> slot.actions.forEach { add(it.action.trim()) } }
        }
        return catalog.actions.mapNotNull { action ->
            action.capability?.takeIf { action.id in used }
        }.toSortedSet()
    }

    fun manifestJson(catalog: CapabilityCatalog): String = PRETTY.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("id", id)
            put("version", VERSION)
            put("schemaVersion", SCHEMA_VERSION)
            put("loader", "dsl")
            if (name.isNotBlank()) put("name", name)
            if (description.isNotBlank()) put("description", description)
            if (howToUse.isNotBlank()) put("usage", howToUse)
            if (limitation.isNotBlank()) put("limitation", limitation)
            val caps = capabilities(catalog)
            if (caps.isNotEmpty()) {
                putJsonArray("capabilities") { caps.forEach { add(JsonPrimitive(it)) } }
            }
        },
    )

    fun entryJson(): String = PRETTY.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            if (fields.isNotEmpty()) {
                putJsonObject("fields") { fields.forEach { (k, v) -> put(k, v) } }
            }
            if (rules.isNotEmpty()) {
                putJsonArray("rules") {
                    rules.forEach { rule ->
                        addJsonObject {
                            put("on", rule.on)
                            rule.condition?.trim()?.takeIf { it.isNotEmpty() }
                                ?.let { put("when", it) }
                            putJsonArray("do") {
                                rule.actions.forEach { call ->
                                    addJsonObject {
                                        put("action", call.action)
                                        if (call.args.isNotEmpty()) {
                                            putJsonObject("args") {
                                                call.args.forEach { (k, v) -> put(k, v) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("slots") {
                slots.forEachIndexed { index, slot ->
                    addJsonObject {
                        put("point", slot.point)
                        put("id", "s" + (index + 1))
                        put("order", slot.order)
                        if (slot.title.isNotBlank()) put("title", slot.title)
                        if (slot.body.isNotBlank()) put("body", slot.body)
                        if (slot.actions.isNotEmpty()) {
                            putJsonArray("actions") {
                                slot.actions.forEach { button ->
                                    addJsonObject {
                                        put("action", button.action)
                                        put("label", button.label)
                                        if (button.args.isNotEmpty()) {
                                            putJsonObject("args") {
                                                button.args.forEach { (k, v) -> put(k, v) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
        // 拒绝是合法结局，不校验插件本身
        if (isRefusal) return emptyList()

        val problems = mutableListOf<String>()

        if (!ID_PATTERN.matches(id)) {
            problems += "id「" + id + "」不合规：必须由小写字母、数字、点、横线组成，" +
                "至少两段，且以 ai. 开头，例如 ai.exam-countdown"
        }
        if (fields.isNotEmpty()) {
            try {
                fields.forEach { (_, source) -> Expression.parse(source) }
            } catch (t: ExpressionException) {
                problems += "fields 里的表达式有问题：" + t.message
            }
        }
        rules.forEachIndexed { index, rule ->
            val n = index + 1
            if (rule.on.isBlank()) {
                problems += "第 " + n + " 条规则没写 on（在什么事件发生时触发）"
            } else if (catalog.eventIds.isEmpty()) {
                problems += "第 " + n + " 条规则挂了事件，「当前没有任何可触发的事件」，不要写 rules"
            } else if (rule.on !in catalog.eventIds) {
                problems += "第 " + n + " 条规则的 on「" + rule.on + "」不是内核知道的事件。只能用：" +
                    catalog.eventIds.sorted().joinToString("、")
            }
            if (rule.actions.isEmpty()) problems += "第 " + n + " 条规则没有任何动作"
            for (call in rule.actions) {
                if (call.action !in catalog.actionIds) {
                    problems += "第 " + n + " 条规则用了动作「" + call.action + "」，没有这个动作。只能用：" +
                        catalog.actionIds.sorted().joinToString("、")
                }
            }
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
        if (howToUse.isBlank()) {
            problems += "howToUse 是空的：写一句「在哪儿看、什么时候看」"
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
            if (slot.actions.size > MAX_BUTTONS) {
                problems += "第 " + n + " 张卡片有 " + slot.actions.size +
                    " 个按钮，最多只能有 " + MAX_BUTTONS + " 个"
            }
            slot.actions.forEachIndexed { buttonIndex, button ->
                val b = buttonIndex + 1
                if (button.action.trim().isEmpty()) {
                    problems += "第 " + n + " 张卡片的第 " + b + " 个按钮没写 action"
                } else if (button.action.trim() !in catalog.actionIds) {
                    problems += "第 " + n + " 张卡片第 " + b + " 个按钮要执行的动作「" +
                        button.action.trim() + "」内核没有。只能用这些：" +
                        catalog.actionIds.sorted().joinToString("、")
                }
                if (button.label.isBlank()) {
                    problems += "第 " + n + " 张卡片的第 " + b + " 个按钮没写 label（按钮上显示的字）"
                } else if (button.label.length > MAX_BUTTON_LABEL) {
                    problems += "第 " + n + " 张卡片第 " + b + " 个按钮写了 " +
                        button.label.length + " 个字，最多 " + MAX_BUTTON_LABEL + " 个"
                }
            }

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
        const val MAX_BUTTONS = 2
        const val MAX_BUTTON_LABEL = 6

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
    /**
     * 卡片下面最多两个按钮。
     *
     * 按钮不是「插件自己写的一段逻辑」，而是**点名**宿主已经定义好的原子动作
     * （catalog.actions 里的 id）。点名可以静态检查，代码不行 ——
     * 所以这是声明式插件唯一被允许动的那一下。
     */
    val actions: List<AuthoredAction> = emptyList(),
)

/** 一条规则：某个事件发生时，依次做这几件事。 */
@Serializable
data class AuthoredRule(
    val on: String,
    /**
     * JSON 里叫 do。规则动作只能用宿主提供的原子动作。
     *
     * 踩过一次：漏了 @SerialName("do")，模型写的是 do、这里期待 actions，
     * 而 Json 配了 ignoreUnknownKeys=true —— 于是 do 被**静默丢掉**，
     * 规则变成「没有任何动作」，报错指向的地方和真正的原因完全不相干。
     */
    @SerialName("do") val actions: List<AuthoredAction> = emptyList(),
    /** JSON 里叫 when。条件不成立就不执行，只能引用 event.xxx。 */
    @SerialName("when") val condition: String? = null,
)

/** 一次动作调用。参数值支持 {{ event.xxx }}。 */
@Serializable
data class AuthoredAction(
    val action: String,
    val args: Map<String, String> = emptyMap(),
    /**
     * 按钮上显示的字。规则里的动作不需要（没人点），留空。
     * 卡片按钮必须写，而且很短 —— 长句子会从卡片里溢出去。
     */
    val label: String = "",
)
