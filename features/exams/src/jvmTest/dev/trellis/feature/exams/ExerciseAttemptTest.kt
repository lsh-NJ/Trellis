package dev.trellis.feature.exams

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 作答记录与最小掌握度的回归测试。
 *
 * 这一层只做「可核对的事实」：谁在什么时候做了哪道题、对不对、是不是自评。
 * 排序规则（没做过 > 上次做错 > 上次做对）是推荐器的基础，改动时必须先改这里。
 */
class ExerciseAttemptTest {

    private val now = 1_790_000_000_000L

    private fun attempt(
        id: String = "ds-1",
        subject: String = "数据结构",
        correct: Boolean = true,
        at: Long = now,
        selfReported: Boolean = false,
    ) = ExerciseAttempt(
        at = at,
        exerciseId = id,
        subject = subject,
        course = "数据结构（混合式）",
        kind = ExerciseKind.RecallCard,
        correct = correct,
        selfReported = selfReported,
    )

    private fun exercise(
        id: String,
        kind: ExerciseKind = ExerciseKind.SingleChoice,
        tags: List<String> = listOf("线性表"),
    ) = Exercise(
        id = id,
        subject = "数据结构",
        course = "数据结构（混合式）",
        kind = kind,
        stem = "题干 $id",
        options = if (kind == ExerciseKind.SingleChoice) {
            listOf(ExerciseOption("A", "选项 A"), ExerciseOption("B", "选项 B"))
        } else {
            emptyList()
        },
        answer = "A",
        tags = tags,
        sourcePath = "数据结构_2023.md",
    )

    @Test
    fun attemptsAppendAndReload() {
        val dir = Files.createTempDirectory("attempts")
        val store = ExerciseAttemptStore(dir.resolve("attempts.jsonl"))

        store.append(attempt(id = "ds-1", correct = true))
        store.append(attempt(id = "ds-2", correct = false, selfReported = true, at = now + 1))

        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals("ds-2", loaded[1].exerciseId)
        assertTrue(loaded[1].selfReported)
        assertEquals(ExerciseKind.RecallCard, loaded[1].kind)
    }

    @Test
    fun badLinesAreSkippedNotFatal() {
        val dir = Files.createTempDirectory("attempts-bad")
        val file = dir.resolve("attempts.jsonl")
        Files.writeString(
            file,
            "not json\n" + """{"at":1,"exerciseId":"ds-1","subject":"数据结构","course":"x","kind":"recall_card","correct":true,"selfReported":false}""" + "\n",
        )

        val loaded = ExerciseAttemptStore(file).load()
        assertEquals(1, loaded.size)
        assertEquals("ds-1", loaded.first().exerciseId)
    }

    @Test
    fun masterySummarisesBySubjectAndIgnoresCase() {
        val attempts = listOf(
            attempt(id = "ds-1", correct = true),
            attempt(id = "ds-2", correct = false, at = now + 1),
            attempt(id = "sport-1", subject = "体育", correct = true),
        )

        val mastery = masteryOf(attempts, " 数据结构 ".trim())
        assertNotNull(mastery)
        assertEquals(2, mastery.attempts)
        assertEquals(1, mastery.correct)
        assertEquals(50, mastery.accuracyPercent)
        assertTrue(mastery.label().contains("近 2 题答对 1"))

        val all = summarizeMastery(attempts)
        assertEquals(listOf("数据结构", "体育"), all.map { it.subject })

        assertNull(masteryOf(attempts, "毛概"))
    }

    @Test
    fun recommendPrefersWrongThenUntriedThenCorrect() {
        val bank = ExerciseBank(
            listOf(
                exercise("ds-1"),
                exercise("ds-2"),
                exercise("ds-3"),
            ),
        )
        val attempts = listOf(
            attempt(id = "ds-1", correct = true),
            attempt(id = "ds-2", correct = false),
        )

        // 上次做错的最优先。
        assertEquals(listOf("ds-2"), bank.recommendFor("数据结构 线性表", attempts = attempts).map { it.id })
        // 排除错题后，没做过的优先于做对过的。
        assertEquals(
            listOf("ds-3"),
            bank.recommendFor("数据结构 线性表", attempts = attempts, excludeIds = setOf("ds-2")).map { it.id },
        )
        // 全做过：轮到最后做对的。
        assertEquals(
            listOf("ds-1"),
            bank.recommendFor(
                "数据结构 线性表",
                attempts = attempts,
                excludeIds = setOf("ds-2", "ds-3"),
            ).map { it.id },
        )
    }

    @Test
    fun recommendFallsBackToTopicHits() {
        val bank = ExerciseBank(
            listOf(
                exercise("ds-1", tags = listOf("线性表", "数组")),
                exercise("ds-2", tags = listOf("线性表")),
                exercise("ds-3", tags = listOf("树")),
            ),
        )

        // 命中数多的排前面；标签不匹配的不出现。
        assertEquals(listOf("ds-1", "ds-2"), bank.recommendFor("线性表", limit = 5).map { it.id })
    }
}
