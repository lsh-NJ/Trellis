package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * B 站这只手：查学习资源。
 *
 * 为什么要有它：用户说「我想学操作系统」，agent 能装卡片、能排布局、能写计划，
 * 但它手里没有任何**外部资源** —— 只能凭记忆编课程名。这个工具让「找资源」变成
 * 一次可核对的检索：按播放量排序，标题、up 主、时长、分集数都是 B 站返回的。
 *
 * 第二个工具是目录：多 P 视频的分集名（「1.1.1 操作系统的概念」「第二章 进程管理」）
 * 本身就是一份大纲，正好可以塞进 set_plan 当步骤。这条链路是「查资源 → 定计划」
 * 的关键一环，别让模型自己去猜章节。
 *
 * 边界：只读元数据。不下载、不播放、不登录、不点赞收藏。
 */
class BilibiliTools(private val client: BilibiliClient = BilibiliClient()) {

    val guidance: String = listOf(
        "<how_to_find_learning_resources>",
        "1. 用户说想学某个主题、或问「有没有好的教程/课程/视频」时，先 search_bilibili 搜一下，别凭记忆报课程名。",
        "2. 默认按热度（播放量）排序；用户要「最新的」就用 order=最新。搜到多条时挑 up 主可信、分集多的那个，把 bvid 记下来。",
        "3. 分集目录的用处分两岔，别混：**落成待办**时挑 **6-12 个**相邻分集当 add_tasks 的条目（标题用分集名，可截短到 20 字内）——那是待办清单，80 集不要一次抄完；",
        "   **排成学习计划**时不要逐条抄标题，直接把 bvid（必要时带 fromPart/toPart）交给 create_learning_plan 全量导入——总路线要全，宿主会拉全部分集生成单元并自动排本周+下周。",
        "4. 告诉用户你选的是哪个视频、为什么（播放量/分集数/up 主），以及每步大概多少分钟（分集时长就在结果里）。",
        "5. 这个工具只读：拿不到视频内容、不能播放、不能下载、也不能判断讲得好不好。用户问「这视频讲得怎么样」就说你只看到了元数据。",
        "6. 搜不到很常见：换个更通用的词（「操作系统 考研」比「操作系统 第一章 概述」好使），或者直接告诉用户 B 站没搜到合适的。",
        "</how_to_find_learning_resources>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(Search(), Parts())

    private inner class Search : AgentTool {
        override val name: String = "search_bilibili"
        override val description: String =
            "在 B 站按关键词搜视频，默认按播放量（热度）排序。返回标题、BV 号、up 主、播放量、时长、分集数。" +
                "找学习资源/教程/课程时用；拿到 BV 号后可以用 list_bilibili_parts 看分集。"

        override val parameters: String =
            "keyword：搜什么，必填，越短越准（例：操作系统 考研）。" +
                "order：可选，热度（默认，按播放量）/ 最新 / 弹幕。limit：可选，返回几条，默认 5，最多 10。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val keyword = arguments["keyword"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (keyword.isEmpty()) return ToolResult("search_bilibili 需要 keyword：想搜什么？", isError = true)
            val order = when (arguments["order"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()) {
                "最新", "pubdate" -> "pubdate"
                "弹幕", "dm" -> "dm"
                else -> "click"
            }
            val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 10)
            return try {
                val videos = client.search(keyword, order, limit)
                if (videos.isEmpty()) {
                    ToolResult("按「" + keyword + "」没搜到视频。换个更通用的词再试。")
                } else {
                    val text = buildString {
                        val orderText = when (order) {
                            "click" -> "热度"
                            "pubdate" -> "最新发布"
                            else -> "弹幕"
                        }
                        appendLine("按" + orderText + "搜「" + keyword + "」，前 " + videos.size + " 个：")
                        videos.forEachIndexed { index, video ->
                            appendLine(
                                (index + 1).toString() + ". " + video.title + "（" + video.bvid + "）" +
                                    " · up：" + video.author +
                                    " · 播放 " + video.playText +
                                    " · 时长 " + video.duration +
                                    (if (video.parts > 0) " · " + video.parts + " 集" else ""),
                            )
                        }
                        appendLine()
                        appendLine("要看分集目录（可当计划步骤）就用 list_bilibili_parts，bvid 填上面括号里的。")
                    }
                    ToolResult(text.trim())
                }
            } catch (e: BilibiliException) {
                ToolResult(e.message ?: "B 站这次没答上来。", isError = true)
            }
        }
    }

    private inner class Parts : AgentTool {
        override val name: String = "list_bilibili_parts"
        override val description: String =
            "拿一个 B 站视频的分集清单（多 P 视频的每一集）。分集名就是天然的学习大纲，" +
                "做学习计划时挑相邻的几集当步骤。"

        override val parameters: String =
            "bvid：视频的 BV 号，必填（例：BV1YE411D7nH）。limit：可选，最多列多少集，默认 40，最多 100。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val bvid = arguments["bvid"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (bvid.isEmpty()) return ToolResult("list_bilibili_parts 需要 bvid：视频的 BV 号。", isError = true)
            val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 40).coerceIn(1, 100)
            return try {
                val parts = client.parts(bvid)
                if (parts.size <= 1) {
                    ToolResult("（" + bvid + "）是单集视频，没有分集目录 —— 可以把它当成「看完这一集」一个步骤。")
                } else {
                    val shown = parts.take(limit)
                    val text = buildString {
                        appendLine("（" + bvid + "）共 " + parts.size + " 集，这里是前 " + shown.size + " 集：")
                        shown.forEach { part ->
                            val minutes = if (part.durationMinutes > 0) "，约 ${part.durationMinutes} 分钟" else ""
                            appendLine("P${part.page} ${part.title}（${part.durationText}$minutes）")
                        }
                        appendLine()
                        appendLine("做计划时挑 6-12 个相邻分集当步骤，标题用分集名（可截短）；不用把全部集数写进计划。")
                        appendLine("预估时间直接用分集时长（分钟）；超过 45 分钟的，拆成两个单元或按 45 记。")
                    }
                    ToolResult(text.trim())
                }
            } catch (e: BilibiliException) {
                ToolResult(e.message ?: "B 站这次没答上来。", isError = true)
            }
        }
    }
}
