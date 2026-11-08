package dev.trellis.app.desktop

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 管家主动开口时用的手：只读 + 只提议，回调注入，装配处不碰真实数据。
 *
 * 这里盯三件事：工具名/参数说明和契约一致、参数按默认值/上下界传进回调、
 * 任何失败（缺参数、回调抛异常）都变成一句能指导模型的错误，而不是把整轮打断。
 */
class AutonomyToolsTest {

    private fun tools(
        learningOverview: () -> String = { "学习总览" },
        knowledgeOverview: (keyword: String) -> String = { "知识总览：$it" },
        searchWeb: suspend (query: String, limit: Int) -> String = { query, limit -> "网页：$query/$limit" },
        searchExercises: (keyword: String, subject: String, limit: Int) -> String = { k, s, l -> "题库：$k/$s/$l" },
        recommendExercises: (text: String, limit: Int) -> String = { text, limit -> "推荐：$text/$limit" },
        proposePractice: (exerciseId: String, title: String, detail: String) -> String = { id, title, detail ->
            "练习卡：$id/$title/$detail"
        },
        rest: () -> Unit = {},
    ): AutonomyTools = AutonomyTools(
        learningOverview = learningOverview,
        knowledgeOverview = knowledgeOverview,
        searchWeb = searchWeb,
        searchExercises = searchExercises,
        recommendExercises = recommendExercises,
        proposePractice = proposePractice,
        rest = rest,
    )

    private fun AutonomyTools.tool(name: String) = all.single { it.name == name }

    @Test
    fun allSevenToolsAreExposedWithTheAgreedNames() {
        val names = tools().all.map { it.name }

        assertEquals(
            listOf(
                "read_learning_overview",
                "read_knowledge_overview",
                "search_web",
                "search_exercises",
                "recommend_exercises",
                "propose_practice",
                "rest",
            ),
            names,
        )
    }

    @Test
    fun theLearningOverviewIsJustTheRuntimeText() = runTest {
        var calls = 0
        val tool = tools(learningOverview = { calls++; "计划：第 2 阶段，本周 3 条待做" })
            .tool("read_learning_overview")

        val result = tool.call(buildJsonObject { })

        assertFalse(result.isError, result.content)
        assertEquals("计划：第 2 阶段，本周 3 条待做", result.content)
        assertEquals(1, calls)
    }

    @Test
    fun theKnowledgeOverviewPassesTheKeywordAndDefaultsToAFullOverview() = runTest {
        val keywords = mutableListOf<String>()
        val tool = tools(knowledgeOverview = { keywords += it; "知识：$it" })
            .tool("read_knowledge_overview")

        val full = tool.call(buildJsonObject { })
        val filtered = tool.call(buildJsonObject { put("keyword", "极限") })

        assertEquals("知识：", full.content)
        assertEquals("知识：极限", filtered.content)
        assertEquals(listOf("", "极限"), keywords)
    }

    @Test
    fun searchWebPassesTheQueryAndClampsTheLimit() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val tool = tools(searchWeb = { query, limit -> calls += query to limit; "搜索结果" })
            .tool("search_web")

        val defaults = tool.call(buildJsonObject { put("query", "Kotlin 协程") })
        val explicit = tool.call(buildJsonObject { put("query", "a"); put("limit", 2) })
        val overLimit = tool.call(buildJsonObject { put("query", "b"); put("limit", 99) })

        assertEquals("搜索结果", defaults.content)
        assertEquals("搜索结果", explicit.content)
        assertEquals("搜索结果", overLimit.content)
        assertEquals(listOf("Kotlin 协程" to 5, "a" to 2, "b" to 8), calls)
    }

    @Test
    fun searchWebWithoutQueryIsAnActionableErrorAndDoesNotCallTheProvider() = runTest {
        var calls = 0
        val tool = tools(searchWeb = { _, _ -> calls++; "不该被调用" }).tool("search_web")

        val result = tool.call(buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(result.content.contains("query"), result.content)
        assertTrue(result.content.contains("必填"), result.content)
        assertEquals(0, calls)
    }

    @Test
    fun searchExercisesPassesKeywordSubjectAndLimitWithDefaults() = runTest {
        val calls = mutableListOf<Triple<String, String, Int>>()
        val tool = tools(searchExercises = { k, s, l -> calls += Triple(k, s, l); "题目列表" })
            .tool("search_exercises")

        val defaults = tool.call(buildJsonObject { })
        val explicit = tool.call(
            buildJsonObject {
                put("keyword", "极限")
                put("subject", "数学")
                put("limit", 12)
            },
        )

        assertEquals("题目列表", defaults.content)
        assertEquals("题目列表", explicit.content)
        assertEquals(listOf(Triple("", "", 5), Triple("极限", "数学", 12)), calls)
    }

    @Test
    fun recommendExercisesNeedsTextAndDefaultsToThree() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val tool = tools(recommendExercises = { text, limit -> calls += text to limit; "三道题" })
            .tool("recommend_exercises")

        val missing = tool.call(buildJsonObject { })
        val ok = tool.call(buildJsonObject { put("text", "第一章 极限") })

        assertTrue(missing.isError)
        assertTrue(missing.content.contains("text"), missing.content)
        assertEquals("三道题", ok.content)
        assertEquals(listOf("第一章 极限" to 3), calls)
    }

    @Test
    fun proposePracticeNeedsAnExerciseIdAndPassesTheCardTextThrough() = runTest {
        val calls = mutableListOf<Triple<String, String, String>>()
        val tool = tools(proposePractice = { id, title, detail -> calls += Triple(id, title, detail); "已推送：$id" })
            .tool("propose_practice")

        val missing = tool.call(buildJsonObject { })
        val full = tool.call(
            buildJsonObject {
                put("exercise_id", "ex_1")
                put("title", "极限练习")
                put("detail", "上次错在这题")
            },
        )
        val minimal = tool.call(buildJsonObject { put("exercise_id", "ex_2") })

        assertTrue(missing.isError)
        assertTrue(missing.content.contains("exercise_id"), missing.content)
        assertEquals("已推送：ex_1", full.content)
        assertEquals("已推送：ex_2", minimal.content)
        assertEquals(
            listOf(
                Triple("ex_1", "极限练习", "上次错在这题"),
                Triple("ex_2", "", ""),
            ),
            calls,
        )
    }

    /** 契约：propose_practice 成功/失败都按文本回给模型，失败时也不抛异常。 */
    @Test
    fun proposePracticeReturnsTheProviderTextEvenWhenItReportsAFailure() = runTest {
        val tool = tools(proposePractice = { _, _, _ -> "题库里没有这道题，先 recommend_exercises 挑一道" })
            .tool("propose_practice")

        val result = tool.call(buildJsonObject { put("exercise_id", "ex_missing") })

        assertFalse(result.isError, result.content)
        assertEquals("题库里没有这道题，先 recommend_exercises 挑一道", result.content)
    }

    @Test
    fun restStopsTheRoundSilently() = runTest {
        var calls = 0
        val tool = tools(rest = { calls++ }).tool("rest")

        val result = tool.call(buildJsonObject { })

        assertEquals(1, calls)
        assertFalse(result.isError)
        assertTrue(result.done)
        assertEquals("（本轮无话可说，已静默收尾）", result.content)
    }

    @Test
    fun aBrokenProviderBecomesAnErrorWithTheReasonInsteadOfThrowing() = runTest {
        val syncFailure = tools(learningOverview = { error("计划库打不开") })
            .tool("read_learning_overview")
            .call(buildJsonObject { })
        val suspendFailure = tools(searchWeb = { _, _ -> error("网络不通") })
            .tool("search_web")
            .call(buildJsonObject { put("query", "x") })

        assertTrue(syncFailure.isError)
        assertTrue(syncFailure.content.contains("计划库打不开"), syncFailure.content)
        assertTrue(suspendFailure.isError)
        assertTrue(suspendFailure.content.contains("网络不通"), suspendFailure.content)
    }

    @Test
    fun aBlankRequiredArgumentCountsAsMissing() = runTest {
        val results = listOf(
            tools().tool("search_web").call(buildJsonObject { put("query", "   ") }),
            tools().tool("recommend_exercises").call(buildJsonObject { put("text", "") }),
            tools().tool("propose_practice").call(buildJsonObject { put("exercise_id", "  ") }),
        )

        assertTrue(results.all { it.isError }, results.map { it.content }.toString())
    }

    @Test
    fun parameterDocsSayWhatIsRequiredAndWhatTheLimitsAre() {
        val byName = tools().all.associateBy { it.name }

        assertTrue(byName.getValue("search_web").parameters.contains("必填"))
        assertTrue(byName.getValue("search_web").parameters.contains("1–8"))
        assertTrue(byName.getValue("search_exercises").parameters.contains("1–20"))
        assertTrue(byName.getValue("recommend_exercises").parameters.contains("text"))
        assertTrue(byName.getValue("recommend_exercises").parameters.contains("1–10"))
        assertTrue(byName.getValue("propose_practice").parameters.contains("exercise_id"))
        assertTrue(byName.getValue("read_knowledge_overview").parameters.contains("keyword"))
    }

    @Test
    fun theGuidanceSetsTheBoundariesWithoutHardcodingADecisionFlow() {
        val guidance = tools().guidance

        assertTrue(guidance.contains("<how_to_autonomy_tools>"))
        assertTrue(guidance.contains("</how_to_autonomy_tools>"))
        // 知识库 = 能力证据，不要建议重复学习
        assertTrue(guidance.contains("能力证据"), guidance)
        assertTrue(guidance.contains("知识库"), guidance)
        // propose_practice 只推卡、不改计划
        assertTrue(guidance.contains("propose_practice"), guidance)
        assertTrue(guidance.contains("练习卡"), guidance)
        // rest = 别打扰
        assertTrue(guidance.contains("rest"), guidance)
        assertTrue(guidance.contains("安静收尾"), guidance)
        // 决策由模型自己判断
        assertTrue(guidance.contains("由你判断"), guidance)
    }
}
