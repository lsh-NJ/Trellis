package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 管家「主动开口」时用的手。
 *
 * 和对话页里那套工具的区别：这里**没有写操作**——只有三种能力，
 * 1) 读学习现状（计划/阶段/本周/待安排池）；
 * 2) 读知识库现状（笔记/概念），联网或题库里查资料；
 * 3) 往会话里推一张练习卡（用户可接受/忽略，不改计划）。
 *
 * 剩下的事（要不要开口、说什么、推哪道题）由模型自己判断，工具只给边界。
 * 回调全是注入的：装配在 [AppRuntime]，测试里换成假实现，不碰真实数据。
 */
internal class AutonomyTools(
    private val learningOverview: () -> String,
    private val knowledgeOverview: (keyword: String) -> String,
    private val searchWeb: suspend (query: String, limit: Int) -> String,
    private val searchExercises: (keyword: String, subject: String, limit: Int) -> String,
    private val recommendExercises: (text: String, limit: Int) -> String,
    private val proposePractice: (exerciseId: String, title: String, detail: String) -> String,
    private val rest: () -> Unit,
) {

    val guidance: String = listOf(
        "<how_to_autonomy_tools>",
        "1. 这组工具只读或只提议：read_learning_overview / read_knowledge_overview 读现状，",
        "   search_web / search_exercises 查资料，recommend_exercises 从题库里挑题；只有 propose_practice 会动界面，",
        "   而它也只往会话里推一张卡，不写计划、不记完成。",
        "2. 知识库里的笔记和概念默认表示用户已经看过、基本掌握：把它们当**能力证据**用",
        "   （据此挑练习、决定聊什么），不要建议用户把已经记下来的内容再学一遍。",
        "3. propose_practice 推出来的练习卡用户可接受或忽略：一次只推一张，说明为什么推它；",
        "   它不修改计划、不代替用户完成节点。",
        "4. rest 表示这一轮没有值得打扰用户的事：调了它就安静收尾，不要再补一段总结把用户叫回来。",
        "5. 什么时候读、什么时候查、要不要开口由你判断；这里只写边界，不规定顺序。",
        "   工具报错时先看错误文本里说的参数或环境问题，能换做法就换，别把错误原样丢给用户。",
        "</how_to_autonomy_tools>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(
        ReadLearningOverview(),
        ReadKnowledgeOverview(),
        SearchWeb(),
        SearchExercises(),
        RecommendExercises(),
        ProposePractice(),
        Rest(),
    )

    /** 读可选字符串参数：没传 / null / 非字符串都当空串。 */
    private fun textOf(arguments: JsonObject, key: String): String =
        arguments[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    /** 读可选整数参数并夹到允许区间：没传就用默认值，超界夹回去（不报错，模型不用猜边界）。 */
    private fun limitOf(arguments: JsonObject, key: String, fallback: Int, range: IntRange): Int =
        (arguments[key]?.jsonPrimitive?.intOrNull ?: fallback).coerceIn(range.first, range.last)

    /**
     * 把所有异常变成一句能指导模型的错误：工具循环靠 `isError` 决定要不要换个做法，
     * 抛出异常则会把整轮打断。协程取消要原样抛回去，否则取消信号会被吞掉。
     */
    private suspend fun guarded(label: String, block: suspend () -> ToolResult): ToolResult =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult(
                "$label 失败：" + (error.message ?: error::class.simpleName ?: "未知错误") +
                    "。先判断是参数问题还是环境问题，能换个做法就换，别把这条错误原样丢给用户。",
                isError = true,
            )
        }

    private inner class ReadLearningOverview : AgentTool {
        override val name: String = "read_learning_overview"
        override val description: String =
            "只读：读用户学习计划的现状（进行中的计划、阶段进度、本周安排、待安排池）。" +
                "想知道「用户现在学到哪了」时先调它。"

        override val parameters: String = "无参数。"

        override suspend fun call(arguments: JsonObject): ToolResult = guarded("读学习总览") {
            ToolResult(learningOverview())
        }
    }

    private inner class ReadKnowledgeOverview : AgentTool {
        override val name: String = "read_knowledge_overview"
        override val description: String =
            "只读：读知识库里的笔记与概念（用户已经记下来、基本掌握的内容）。" +
                "传 keyword 只看匹配的部分，空串或不传看全览。"

        override val parameters: String =
            "keyword：可选，字符串；空串或不传 = 全览，非空 = 只返回笔记/概念名里包含它的部分。"

        override suspend fun call(arguments: JsonObject): ToolResult = guarded("读知识库总览") {
            ToolResult(knowledgeOverview(textOf(arguments, "keyword")))
        }
    }

    private inner class SearchWeb : AgentTool {
        override val name: String = "search_web"
        override val description: String =
            "只读：联网搜资料（DeepSeek 官方搜索），返回摘要与链接。想给用户补充资料时用，不要凭空编。"

        override val parameters: String =
            "query：必填，字符串，要搜什么（越具体越好）；" +
                "limit：可选，整数 1–8，默认 5。"

        override suspend fun call(arguments: JsonObject): ToolResult = guarded("联网搜索") {
            val query = textOf(arguments, "query")
            if (query.isEmpty()) {
                return@guarded ToolResult(
                    "缺少必填参数 query：search_web 的参数形如 {\"query\":\"要搜什么\",\"limit\":5}；" +
                        "query 必须是非空字符串。",
                    isError = true,
                )
            }
            val limit = limitOf(arguments, "limit", fallback = 5, range = 1..8)
            ToolResult(searchWeb(query, limit))
        }
    }

    private inner class SearchExercises : AgentTool {
        override val name: String = "search_exercises"
        override val description: String =
            "只读：在题库里按关键词/学科搜题，返回题目 id、题干与选项。想找特定范围（某章、某知识点）的题时用。"

        override val parameters: String =
            "keyword：可选，字符串，课程/章节/知识点关键词；" +
                "subject：可选，字符串，学科（如数学、物理）；" +
                "limit：可选，整数 1–20，默认 5。"

        override suspend fun call(arguments: JsonObject): ToolResult = guarded("搜题") {
            val keyword = textOf(arguments, "keyword")
            val subject = textOf(arguments, "subject")
            val limit = limitOf(arguments, "limit", fallback = 5, range = 1..20)
            ToolResult(searchExercises(keyword, subject, limit))
        }
    }

    private inner class RecommendExercises : AgentTool {
        override val name: String = "recommend_exercises"
        override val description: String =
            "只读：根据计划名/章节/知识点从题库里推荐几道题（带作答历史：错过的、没做过的优先）。" +
                "想推练习前先调它看有什么题。"

        override val parameters: String =
            "text：必填，字符串，计划名/章节名/知识点（用来匹配题库）；" +
                "limit：可选，整数 1–10，默认 3。"

        override suspend fun call(arguments: JsonObject): ToolResult = guarded("推荐练习") {
            val text = textOf(arguments, "text")
            if (text.isEmpty()) {
                return@guarded ToolResult(
                    "缺少必填参数 text：recommend_exercises 的参数形如 {\"text\":\"计划名或章节\",\"limit\":3}；" +
                        "text 必须是非空字符串。",
                    isError = true,
                )
            }
            val limit = limitOf(arguments, "limit", fallback = 3, range = 1..10)
            ToolResult(recommendExercises(text, limit))
        }
    }

    private inner class ProposePractice : AgentTool {
        override val name: String = "propose_practice"
        override val description: String =
            "往用户会话里推一张练习卡（用户可接受或忽略）。只推一道题，不改计划、不记完成。" +
                "exercise_id 只能是 search_exercises / recommend_exercises 返回过的题。"

        override val parameters: String =
            "exercise_id：必填，字符串，题目 id（来自 search_exercises / recommend_exercises）；" +
                "title：可选，字符串，卡片标题；" +
                "detail：可选，字符串，为什么推这道题（一句话）。"

        override suspend fun call(arguments: JsonObject): ToolResult = guarded("推送练习卡") {
            val exerciseId = textOf(arguments, "exercise_id")
            if (exerciseId.isEmpty()) {
                return@guarded ToolResult(
                    "缺少必填参数 exercise_id：propose_practice 的参数形如 " +
                        "{\"exercise_id\":\"ex_...\",\"title\":\"...\",\"detail\":\"...\"}；" +
                        "exercise_id 必须是 search_exercises / recommend_exercises 返回过的题目 id。",
                    isError = true,
                )
            }
            val title = textOf(arguments, "title")
            val detail = textOf(arguments, "detail")
            ToolResult(proposePractice(exerciseId, title, detail))
        }
    }

    private inner class Rest : AgentTool {
        override val name: String = "rest"
        override val description: String =
            "这一轮没有值得打扰用户的事时静默收尾：不生成任何内容，也不改任何数据。"

        override val parameters: String = "无参数。"

        override suspend fun call(arguments: JsonObject): ToolResult = guarded("静默收尾") {
            rest()
            ToolResult("（本轮无话可说，已静默收尾）", done = true)
        }
    }
}
