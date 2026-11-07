package dev.trellis.feature.exams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「学完一个单元，题库里有没有匹配的练习」。
 *
 * 匹配只看学科/课程/标签，不看题干：计划名和章节名里出现的词才代表「在学什么」。
 * 这条规则决定提议会不会变成随机弹题，所以逐条钉住。
 */
class ExerciseSuggestionTest {

    private fun exercise(
        id: String,
        subject: String,
        kind: ExerciseKind = ExerciseKind.SingleChoice,
        course: String = "",
        tags: List<String> = emptyList(),
        stem: String = "$id 的题干",
    ) = Exercise(
        id = id,
        subject = subject,
        course = course,
        kind = kind,
        stem = stem,
        options = if (kind.isObjective) listOf(ExerciseOption("A", "甲"), ExerciseOption("B", "乙")) else emptyList(),
        answer = if (kind.isObjective) "A" else "答案 $id",
        tags = tags,
        sourcePath = "src/$id.md",
    )

    private val bank = ExerciseBank(
        listOf(
            exercise("ds-1", "数据结构", course = "数据结构与算法", tags = listOf("线性表")),
            exercise("ds-2", "数据结构", kind = ExerciseKind.RecallCard),
            exercise("mao-1", "毛概", course = "毛泽东思想概论"),
            exercise("pe-1", "体育", tags = listOf("理论考试")),
        ),
    )

    @Test
    fun aPlanTitleThatMatchesASubjectFindsAQuestion() {
        val hit = bank.suggestFor("我要速通数据结构")
        assertTrue(hit.isNotEmpty(), "计划名里出现学科时应该匹配到题")
        assertTrue(hit.all { it.subject == "数据结构" })
    }

    @Test
    fun chapterWordsAndTagsAlsoCount() {
        assertEquals(listOf("ds-1"), bank.suggestFor("线性表").map { it.id }, "标签命中也要算")
        assertEquals(listOf("mao-1"), bank.suggestFor("第 3 章 毛泽东思想概论").map { it.id })
    }

    @Test
    fun noTopicMatchMeansNoSuggestion() {
        assertEquals(emptyList(), bank.suggestFor("计算机网络 第一章 概览"))
        assertEquals(emptyList(), bank.suggestFor("Linux 编程 文件系统"))
    }

    @Test
    fun blankTextGivesNothing() {
        assertEquals(emptyList(), bank.suggestFor(""))
        assertEquals(emptyList(), bank.suggestFor(" ，。、 "))
    }

    @Test
    fun objectiveQuestionsWinTiesAndTheResultIsStable() {
        // ds-1（选择）与 ds-2（知识点卡）都命中数据结构；同分时客观题优先。
        assertEquals(listOf("ds-1"), bank.suggestFor("数据结构", limit = 1).map { it.id })
        assertEquals(listOf("ds-1"), bank.suggestFor("数据结构", limit = 1).map { it.id })
        // limit 上限生效，且多命中时按 id 兜底排序。
        assertEquals(listOf("ds-1", "ds-2"), bank.suggestFor("数据结构", limit = 5).map { it.id })
    }

    @Test
    fun alreadyProposedExercisesCanBeExcluded() {
        // 同一批计划连续学完几个单元时，别每次都推同一道题。
        assertEquals(listOf("ds-1"), bank.suggestFor("数据结构", limit = 1).map { it.id })
        assertEquals(
            listOf("ds-2"),
            bank.suggestFor("数据结构", limit = 1, excludeIds = setOf("ds-1")).map { it.id },
        )
    }
}
