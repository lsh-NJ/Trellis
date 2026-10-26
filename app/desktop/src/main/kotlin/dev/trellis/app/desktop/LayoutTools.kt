package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.slot.Slots
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 布局的手。
 *
 * 之前 agent 说「插件只能挂在固定位置，我没法调整布局」，这话当时是真的：
 * 布局不在任何工具的手里。现在它是一份数据，agent 和用户都能改。
 */
class LayoutTools(private val runtime: AppRuntime) {

    val guidance: String = listOf(
        "<how_to_arrange_ui>",
        "1. 调整界面的入口是布局：一个挂载点可以按行摆卡片，一行放多张就是横排。",
        "2. 先 list_cards 看每个挂载点上有什么（插件 id + 卡片标题），再 arrange_cards。",
        "3. arrange_cards 的 rows 用 ; 分行、| 分同一行的卡片，填**插件 id**；想按 12 栏网格摆就在 id 后面写 :栏数（如 ai.pushups:8|ai.mood-checkin:4）。没写栏数就等宽。",
        "4. 没写进去的卡片会排到最后，不会被藏。宿主支持横排和 1..12 栏的宽度，还没有拖拽、任意绝对位置；这些别承诺。",
        "</how_to_arrange_ui>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(ListCards(), ArrangeCards())

    private inner class ListCards : AgentTool {
        override val name: String = "list_cards"
        override val description: String =
            "列出每个挂载点上现在有哪些卡片：插件 id 和卡片标题。改布局之前先看这个。"
        override val parameters: String =
            "point：可选，只列某一个挂载点（如 slot.home.section）；不填就列全部。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val raw = arguments["point"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val points = if (raw.isEmpty()) {
                RENDERED_MOUNT_POINTS.toList()
            } else {
                listOf(ExtensionPointId(raw))
            }
            val text = buildString {
                for (point in points) {
                    appendLine(point.value + "：")
                    val cards = runtime.cardsOn(point)
                    if (cards.isEmpty()) {
                        appendLine("  （空）")
                    } else {
                        for ((plugin, title) in cards) appendLine("  - " + plugin.value + "　" + title)
                    }
                }
            }.trim()
            return ToolResult(text.ifEmpty { "现在没有任何卡片。" })
        }
    }

    private inner class ArrangeCards : AgentTool {
        override val name: String = "arrange_cards"
        override val description: String =
            "给一个挂载点排版：按行摆放已装插件的卡片，一行多张就是横排；传空 rows 恢复默认。"
        override val parameters: String =
            "point：挂载点 id（如 slot.home.section）；rows：分行的插件 id，用 ; 分行、用 | 分隔同一行；" +
                "可在 id 后写 :栏数 指定宽度（1..12），不写等宽。" +
                "例：ai.pushups:8|ai.mood-checkin:4;ai.reading-pages"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val point = arguments["point"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return ToolResult("arrange_cards 需要 point", isError = true)
            val rawRows = arguments["rows"]?.jsonPrimitive?.contentOrNull ?: ""
            val text = runtime.arrangeCards(point, LayoutStore.parseRows(rawRows))
            return ToolResult(text, isError = text.startsWith("这个挂载点") || text.startsWith("这些插件"))
        }
    }
}
