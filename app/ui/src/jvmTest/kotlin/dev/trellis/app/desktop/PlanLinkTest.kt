package dev.trellis.app.desktop

import dev.trellis.domain.plan.CommitmentView
import dev.trellis.domain.plan.EntryView
import dev.trellis.domain.plan.WeekDay
import dev.trellis.domain.plan.WeekDayItem
import dev.trellis.domain.plan.WeekView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 笔记 → 计划节点联动判定的纯逻辑测试：候选筛选、提示词、解析、逐字校验、上限。
 *
 * 真正调模型和写计划库不在这里（`AppRuntime` 的 `autoCompletePlanNodes` 集成，
 * 验证走 mock 端到端）；账本读写见 `AutoCompletionStoreTest`。
 */
class PlanLinkTest {

    private val body = "开头这一段介绍 AlphaCore：它是用来测试分块抽取的开头概念，" +
        "后面还有很长的填充内容，到了很靠后的位置才出现 ZetaRecall 这个词。" +
        "这一节把 HNSW 的建图过程讲了一遍：每个节点最多认识 M 个邻居，查询从入口点开始逐层下降，" +
        "建图时新节点随机选一层，从上层入口点开始贪心搜索最近的邻居，再把这些邻居连起来。" +
        "填充内容只是把正文拉长，讲的是普通的界面布局和按钮样式，没有任何值得长期回查的概念。" +
        "填充内容只是把正文拉长，讲的是普通的界面布局和按钮样式，没有任何值得长期回查的概念。" +
        "填充内容只是把正文拉长，讲的是普通的界面布局和按钮样式，没有任何值得长期回查的概念。" +
        "填充内容只是把正文拉长，讲的是普通的界面布局和按钮样式，没有任何值得长期回查的概念。" +
        "再补一段：这一节其实只是占位文字，用来让正文超过四百字，好让判定链路有机会跑起来；" +
        "里面没有真正值得长期回查的概念，也没有讲完任何一个计划节点。"

    private fun week(vararg days: WeekDay): WeekView = WeekView(
        week = "2026-W45",
        rangeLabel = "11-02 ~ 11-08",
        commitment = CommitmentView(label = "RAG", units = emptyList(), summary = ""),
        days = days.toList(),
        reviewEntry = EntryView(available = true, label = "复盘"),
        changeCommitmentEntry = EntryView(available = false, label = "调整承诺"),
    )

    private fun day(day: String, vararg items: WeekDayItem): WeekDay =
        WeekDay(day = day, label = day, today = false, items = items.toList())

    private fun item(
        id: String,
        title: String,
        status: String = "planned",
        minutes: Int = 25,
    ): WeekDayItem = WeekDayItem(
        occurrenceId = id,
        title = title,
        status = status,
        minutes = minutes,
    )

    private fun candidate(id: String, title: String, day: String = "2026-11-05"): PlanLinkCandidate =
        PlanLinkCandidate(occurrenceId = id, title = title, day = day, minutes = 25)

    @Test
    fun candidatesComeOnlyFromPlannedItemsAndAreDeduped() {
        val view = week(
            day("2026-11-02", item("occ_1", "HNSW 建图"), item("occ_2", "已完成的", status = "done")),
            day("2026-11-03", item("occ_3", "BM25 检索"), item("occ_1", "HNSW 建图")),
            day("2026-11-04", item("occ_4", "跳过", status = "skipped")),
        )
        val candidates = planLinkCandidates(view, chapters = mapOf("occ_1" to "向量数据库"))
        assertEquals(listOf("occ_1", "occ_3"), candidates.map { it.occurrenceId })
        assertEquals("向量数据库", candidates.first().chapter)
        assertEquals("2026-11-02", candidates.first().day)
    }

    @Test
    fun shortNotesOrEmptyCandidatesSkipTheCall() {
        assertFalse(shouldAttemptPlanLink("太短了", listOf(candidate("occ_1", "HNSW"))))
        assertFalse(shouldAttemptPlanLink(body, emptyList()))
        assertTrue(shouldAttemptPlanLink(body, listOf(candidate("occ_1", "HNSW"))))
    }

    @Test
    fun promptListsTheCandidatesAndAsksForVerbatimEvidence() {
        val messages = buildPlanLinkMessages(
            noteTitle = "HNSW 笔记",
            body = body,
            candidates = listOf(candidate("occ_1", "HNSW 建图"), candidate("occ_2", "BM25 检索")),
        )
        val system = messages.first().content
        val user = messages.last().content
        assertTrue(system.contains("逐字"), "system 要强调 evidence 逐字摘录")
        assertTrue(system.contains("coverage=full"))
        assertTrue(user.contains("occ_1"))
        assertTrue(user.contains("HNSW 建图"))
        assertTrue(user.contains("completions"))
    }

    @Test
    fun parseToleratesFencesAndReadsFields() {
        val raw = "好的，结果如下：\n```json\n" +
            "{\"completions\":[" +
            "{\"occurrenceId\":\"occ_1\",\"coverage\":\"full\",\"confidence\":0.92," +
            "\"evidence\":\"每个节点最多认识 M 个邻居\",\"reason\":\"讲清了建图\"}," +
            "{\"occurrenceId\":\"occ_2\",\"coverage\":\"PARTIAL\",\"confidence\":\"0.6\"}" +
            "]}\n```"
        val judgments = parsePlanLinkJudgments(raw)
        assertEquals(2, judgments.size)
        assertEquals("occ_1", judgments[0].occurrenceId)
        assertEquals(PlanLinkCoverage.Full, judgments[0].coverage)
        assertEquals(0.92, judgments[0].confidence)
        assertTrue(judgments[0].evidence.contains("M 个邻居"))
        assertEquals(PlanLinkCoverage.Partial, judgments[1].coverage)
        assertEquals(0.6, judgments[1].confidence)
    }

    @Test
    fun parseReturnsEmptyForGarbage() {
        assertTrue(parsePlanLinkJudgments("模型拒绝回答").isEmpty())
        assertTrue(parsePlanLinkJudgments("{\"completions\":\"not-an-array\"}").isEmpty())
    }

    @Test
    fun onlyFullHighConfidenceVerbatimEvidenceIsAutoCompleted() {
        val candidates = listOf(
            candidate("occ_1", "HNSW 建图"),
            candidate("occ_2", "BM25 检索"),
            candidate("occ_3", "段落切分"),
            candidate("occ_4", "Embedding 选型"),
            candidate("occ_5", "被屏蔽的节点"),
        )
        val evidence = "每个节点最多认识 M 个邻居"
        val judgments = listOf(
            // 通过。
            PlanLinkJudgment("occ_1", PlanLinkCoverage.Full, 0.9, evidence),
            // 置信度太低。
            PlanLinkJudgment("occ_2", PlanLinkCoverage.Full, 0.5, evidence),
            // 只覆盖一部分。
            PlanLinkJudgment("occ_3", PlanLinkCoverage.Partial, 0.95, evidence),
            // 证据不在正文里（模型改写/编造）。
            PlanLinkJudgment("occ_4", PlanLinkCoverage.Full, 0.9, "每个节点认识 64 个邻居"),
            // 用户屏蔽过。
            PlanLinkJudgment("occ_5", PlanLinkCoverage.Full, 0.9, evidence),
            // 候选里没有这个节点。
            PlanLinkJudgment("occ_999", PlanLinkCoverage.Full, 0.99, evidence),
        )
        val completions = validatePlanLinkJudgments(
            body = body,
            candidates = candidates,
            judgments = judgments,
            blocked = setOf("occ_5"),
        )
        assertEquals(listOf("occ_1"), completions.map { it.occurrenceId })
        assertEquals(evidence, completions.single().evidence)
    }

    @Test
    fun completionsAreCappedAndOrderedByConfidence() {
        val candidates = listOf(
            candidate("occ_1", "一"),
            candidate("occ_2", "二"),
            candidate("occ_3", "三"),
            candidate("occ_4", "四"),
        )
        val evidence = "每个节点最多认识 M 个邻居"
        val judgments = listOf(
            PlanLinkJudgment("occ_1", PlanLinkCoverage.Full, 0.82, evidence),
            PlanLinkJudgment("occ_2", PlanLinkCoverage.Full, 0.97, evidence),
            PlanLinkJudgment("occ_3", PlanLinkCoverage.Full, 0.88, evidence),
            PlanLinkJudgment("occ_4", PlanLinkCoverage.Full, 0.91, evidence),
        )
        val completions = validatePlanLinkJudgments(body, candidates, judgments)
        assertEquals(PLAN_LINK_MAX_AUTO_COMPLETIONS, completions.size)
        assertEquals(listOf("occ_2", "occ_4", "occ_3"), completions.map { it.occurrenceId })
    }

    @Test
    fun relevancePicksTheMostRelatedCandidatesWhenThereAreTooMany() {
        val candidates = listOf(
            candidate("occ_1", "界面布局与按钮样式"),
            candidate("occ_2", "HNSW 建图"),
            candidate("occ_3", "ZetaRecall 召回"),
        )
        val selected = selectPlanLinkCandidates(body, candidates, limit = 1)
        // 正文里「界面布局与按钮样式」出现多次，相关度最高。
        assertEquals(listOf("occ_1"), selected.map { it.occurrenceId })
    }
}
