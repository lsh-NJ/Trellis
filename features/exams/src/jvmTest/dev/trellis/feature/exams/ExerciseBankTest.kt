package dev.trellis.feature.exams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 查询层：精确匹配忽略大小写、组合筛选、搜索、随机抽题与 id 唯一性。 */
class ExerciseBankTest {

    private fun choice(id: String, subject: String, answer: String = "A") = Exercise(
        id = id,
        subject = subject,
        course = subject + " 课程",
        kind = ExerciseKind.SingleChoice,
        stem = "$id 的题干：网络分层",
        options = listOf(ExerciseOption("A", "选项甲"), ExerciseOption("B", "选项乙")),
        answer = answer,
        explanation = "解析 $id",
        tags = listOf("DB", "chapter:1"),
        sourcePath = "src/$id.md",
        year = "23-24-1",
    )

    private fun short(id: String, subject: String) = Exercise(
        id = id,
        subject = subject,
        kind = ExerciseKind.ShortAnswer,
        stem = "$id 的简答题干",
        answer = "答案 $id",
        tags = listOf("os"),
        sourcePath = "src/$id.md",
    )

    private val bank = ExerciseBank(
        listOf(
            choice("s1", "数据库"),
            choice("s2", "数据库"),
            choice("s3", "操作系统"),
            short("s4", "操作系统"),
        ),
    )

    @Test
    fun exactQueriesIgnoreCaseAndWhitespace() {
        assertEquals(listOf("s1", "s2"), bank.bySubject(" 数据库 ").map { it.id })
        assertEquals(emptyList(), bank.byCourse("数据库").map { it.id })
        assertEquals(listOf("s1", "s2"), bank.byCourse("数据库 课程").map { it.id })
        assertEquals(3, bank.byKind(ExerciseKind.SingleChoice).size)
        assertEquals(3, bank.byTag("db").size)
        assertEquals(3, bank.byYear("23-24-1").size)
        assertNull(bank.get("missing"))
        assertEquals("s3", bank.get(" s3 ")?.id)
    }

    @Test
    fun searchLooksAtStemAnswerExplanationOptionsAndTags() {
        assertEquals(listOf("s1", "s2", "s3", "s4"), bank.search("").map { it.id })
        assertEquals(listOf("s1", "s2", "s3"), bank.search("网络分层").map { it.id })
        assertEquals(listOf("s1", "s2", "s3"), bank.search("选项甲").map { it.id })
        assertEquals(listOf("s4"), bank.search("答案 s4").map { it.id })
        assertEquals(listOf("s4"), bank.search("OS").map { it.id })
    }

    @Test
    fun filterCombinesConditions() {
        assertEquals(listOf("s1", "s2"), bank.filter(subject = "数据库", keyword = "网络").map { it.id })
        assertEquals(listOf("s4"), bank.filter(kind = ExerciseKind.ShortAnswer).map { it.id })
        assertEquals(emptyList(), bank.filter(subject = "数据库", kind = ExerciseKind.ShortAnswer).map { it.id })
        assertEquals(listOf("s3"), bank.filter(course = "操作系统 课程", keyword = "s3").map { it.id })
    }

    @Test
    fun randomBySubjectIsReproducibleAndStaysInSubject() {
        val a = bank.randomBySubject("数据库", 1, seed = 11L)
        val b = bank.randomBySubject("数据库", 1, seed = 11L)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertTrue(a.all { it.subject == "数据库" })

        // 抽不满就返回全部，不跨学科补齐。
        assertEquals(2, bank.randomBySubject("数据库", 99).size)
        assertTrue(bank.randomBySubject("数据库", 0).isEmpty())
    }

    @Test
    fun duplicateIdsAreRejectedAtConstruction() {
        val error = assertFailsWith<IllegalArgumentException> {
            ExerciseBank(listOf(choice("dup", "数据库"), choice("dup", "操作系统")))
        }
        assertTrue(error.message!!.contains("dup"))
    }

    @Test
    fun statsAndSubjectsAggregateProperly() {
        assertEquals(2, bank.subjects.size)
        assertEquals(4, bank.stats().total)
        assertEquals(4, bank.stats().questionCount, "知识点卡不算题，本题库没有知识点卡")
        assertEquals(mapOf("数据库" to 2, "操作系统" to 2), bank.stats().bySubject)
        assertTrue(bank.sources().all { it.path.startsWith("src/") })
    }
}
