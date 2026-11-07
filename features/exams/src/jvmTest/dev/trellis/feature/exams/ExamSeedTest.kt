package dev.trellis.feature.exams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** 种子题库必须完整可用：解析零坏行、学科/题型覆盖、随机抽题可复现。 */
class ExamSeedTest {

    @Test
    fun seedParsesWithoutAnyIssue() {
        val result = ExamSeed.loadResult()
        assertEquals(0, result.rejectedCount, "种子 JSONL 有坏行：" + result.issues.joinToString("\n") { it.describe() })
        assertTrue(result.bank.size >= 80, "种子题量应 ≥80，实际 ${result.bank.size}")
    }

    @Test
    fun seedCoversSeveralSubjectsAndWellFormedKinds() {
        val bank = ExamSeed.load()
        assertTrue(bank.subjects.size >= 5, "种子应覆盖 ≥5 个学科，实际：" + bank.subjects.joinToString("、"))
        bank.exercises.forEach { exercise ->
            assertTrue(exercise.id.isNotBlank(), "题目 id 不能为空")
            assertTrue(exercise.sourcePath.isNotBlank(), "题目必须记来源：" + exercise.id)
            if (exercise.kind.isObjective) {
                assertTrue(exercise.options.size >= 2, "客观题至少两个选项：" + exercise.id)
                assertTrue(
                    exercise.normalizedAnswerLabels().isNotEmpty(),
                    "客观题答案必须是选项标签：" + exercise.id + " -> " + exercise.answer,
                )
            }
        }
    }

    @Test
    fun seedCanBeSampledDeterministicallyBySubject() {
        val bank = ExamSeed.load()
        val subject = bank.subjects.first()
        val first = bank.randomBySubject(subject, 3, seed = 7L)
        val second = bank.randomBySubject(subject, 3, seed = 7L)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(minOf(3, bank.bySubject(subject).size), first.size)
    }

    @Test
    fun requiringNonEmptyRejectsAnEmptyBank() {
        assertFailsWith<IllegalArgumentException> { ExerciseBank.requireNonEmpty(ExerciseBank.EMPTY) }
    }
}
