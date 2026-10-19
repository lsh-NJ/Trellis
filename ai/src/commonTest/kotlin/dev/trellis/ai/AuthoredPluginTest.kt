package dev.trellis.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthoredPluginTest {

    private val plugin = AuthoredPlugin(
        id = "ai.today",
        name = "今日待办",
        description = "一眼看到今天还剩多少事",
        slots = listOf(
            AuthoredSlot("slot.home.section", "今天", "还有 {{ summary.dueToday }} 项"),
            AuthoredSlot("slot.home.header", "小提示", "纯文字", order = 20),
        ),
    )

    @Test
    fun expandsIntoTheTwoRealFiles() {
        val files = plugin.toFiles()
        assertEquals(setOf("manifest.json", "main.dsl.json"), files.keys)
    }

    @Test
    fun manifestCarriesTheLoaderAndVersion() {
        val manifest = Json.parseToJsonElement(plugin.manifestJson()) as JsonObject
        assertEquals("ai.today", manifest["id"]!!.jsonPrimitive.content)
        assertEquals("dsl", manifest["loader"]!!.jsonPrimitive.content)
        assertEquals("1.0.0", manifest["version"]!!.jsonPrimitive.content)
    }

    /** 卡片 id 由展开时生成，不让模型操心。 */
    @Test
    fun slotIdsAreGeneratedInOrder() {
        val entry = Json.parseToJsonElement(plugin.entryJson()) as JsonObject
        val slots = entry["slots"]!!.jsonArray
        assertEquals("s1", slots[0].let { (it as JsonObject)["id"]!!.jsonPrimitive.content })
        assertEquals("s2", slots[1].let { (it as JsonObject)["id"]!!.jsonPrimitive.content })
        assertEquals(20, slots[1].let { (it as JsonObject)["order"]!!.jsonPrimitive.content.toInt() })
    }

    @Test
    fun aGoodPluginPassesSelfCheck() {
        assertEquals(emptyList(), plugin.validate(TEST_CATALOG))
    }

    /** 挂载点写错 —— 报错里必须列出所有能用的，否则模型改不对。 */
    @Test
    fun anUnknownMountPointListsTheValidOnes() {
        val bad = plugin.copy(slots = listOf(AuthoredSlot("slot.homeSection", "标题", "正文")))
        val problems = bad.validate(TEST_CATALOG)

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("slot.homeSection"))
        assertTrue(problems.single().contains("slot.home.header"), "必须把正确的列出来")
        assertTrue(problems.single().contains("slot.home.section"), "必须把正确的列出来")
    }

    /** 编造数据字段 —— 这种错装上去只会显示 --，用户不知道为什么。必须提前挡。 */
    @Test
    fun anInventedDataFieldIsRejected() {
        val bad = plugin.copy(slots = listOf(AuthoredSlot("slot.home.section", "今天", "{{ summary.magic }} 项")))
        val problems = bad.validate(TEST_CATALOG)

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("summary.magic"))
        assertTrue(problems.single().contains("summary.dueToday"), "必须把正确的列出来")
    }

    @Test
    fun theAiPrefixIsEnforced() {
        assertTrue(plugin.copy(id = "exam-countdown").validate(TEST_CATALOG).any { it.contains("ai. 开头") })
        assertTrue(plugin.copy(id = "ai.Bad-Caps").validate(TEST_CATALOG).any { it.contains("不合规") })
    }

    @Test
    fun tooManySlotsAreRejected() {
        val many = plugin.copy(slots = List(4) { AuthoredSlot("slot.home.section", "t", "b") })
        assertTrue(many.validate(TEST_CATALOG).any { it.contains("最多") })
    }

    @Test
    fun anEmptyPluginIsRejected() {
        assertTrue(plugin.copy(slots = emptyList()).validate(TEST_CATALOG).any { it.contains("slots 是空的") })
        assertTrue(plugin.copy(description = "").validate(TEST_CATALOG).any { it.contains("description 是空的") })
    }
}
