package dev.trellis.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 生成出来的 manifest 必须**声明**它要用的权限。
 *
 * 这条以前是空的：AI 写的插件一个能力都不声明，而内核只按动作自己的 capability
 * 走审批 —— 于是它做的是 manifest 里从没提过的事。用户看不见，也拒不掉。
 * 现在反过来了：写了规则就要声明 write_data，用户才能在插件详情里看到。
 */
class ManifestCapabilityTest {

    private fun rulePlugin() = AuthoredPlugin(
        id = "ai.review",
        name = "完成即复习",
        description = "完成一项就自动建一条复习",
        howToUse = "在首页看",
        slots = listOf(AuthoredSlot("slot.home.section", "复习助手", "已完成 {{ summary.done }} 项")),
        rules = listOf(
            AuthoredRule(
                on = "task.completed",
                actions = listOf(AuthoredAction("addTask", mapOf("title" to "复习：{{ event.title }}"))),
            ),
        ),
    )

    @Test
    fun aRuleThatWritesDeclaresWriteData() {
        val manifest = Json.parseToJsonElement(rulePlugin().manifestJson(TEST_CATALOG)).jsonObject
        val caps = manifest["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertEquals(listOf("write_data"), caps)
    }

    @Test
    fun aPluginThatOnlyShowsThingsDeclaresNothing() {
        val card = AuthoredPlugin(
            id = "ai.card",
            name = "卡片",
            description = "只是显示",
            howToUse = "在首页看",
            slots = listOf(AuthoredSlot("slot.home.section", "标题", "正文")),
        )
        val manifest = Json.parseToJsonElement(card.manifestJson(TEST_CATALOG)).jsonObject
        assertTrue(manifest["capabilities"] == null, "只显示东西的插件不该要权限：" + manifest)
    }
}
