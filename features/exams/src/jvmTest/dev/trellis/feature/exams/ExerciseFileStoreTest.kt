package dev.trellis.feature.exams

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 运行时导入：目录不存在返回空、坏行逐条报、跨文件 id 重复只留第一条。 */
class ExerciseFileStoreTest {

    private fun jsonl(vararg ids: String): String = ids.joinToString("\n") { id ->
        """{"id":"$id","subject":"测试科目","kind":"short_answer","stem":"$id 的题","answer":"答案","sourcePath":"src/$id.md"}"""
    }

    private fun tempDir(): Path = Files.createTempDirectory("trellis-exams").also { it.toFile().deleteOnExit() }

    @Test
    fun missingDirectoryIsAnEmptyBankNotAnError() {
        val result = ExerciseFileStore.loadDirectory(tempDir().resolve("nope"))
        assertEquals(0, result.bank.size)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun multipleFilesAreMergedInNameOrder() {
        val dir = tempDir()
        dir.resolve("b-second.jsonl").writeText(jsonl("b1", "b2"))
        dir.resolve("a-first.jsonl").writeText(jsonl("a1"))

        val result = ExerciseFileStore.loadDirectory(dir)
        assertEquals(listOf("a1", "b1", "b2"), result.bank.exercises.map { it.id })
        assertEquals(2, result.files.size)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun badLinesAreReportedButGoodOnesStillLoad() {
        val dir = tempDir()
        dir.resolve("mixed.jsonl").writeText(
            jsonl("ok1") + "\n" + "{not json" + "\n" + jsonl("ok2"),
        )
        val result = ExerciseFileStore.loadDirectory(dir)
        assertEquals(listOf("ok1", "ok2"), result.bank.exercises.map { it.id })
        assertEquals(1, result.issues.size)
        assertTrue(result.issues.first().describe().contains("mixed.jsonl"))
    }

    @Test
    fun duplicateIdsAcrossFilesOnlyKeepTheFirst() {
        val dir = tempDir()
        dir.resolve("a.jsonl").writeText(jsonl("same"))
        dir.resolve("b.jsonl").writeText(jsonl("same", "other"))

        val result = ExerciseFileStore.loadDirectory(dir)
        assertEquals(listOf("same", "other"), result.bank.exercises.map { it.id })
        assertEquals(1, result.issues.size)
        assertTrue(result.issues.first().errors.first().contains("重复"))
    }

    @Test
    fun loadFileOnMissingPathIsEmpty() {
        val result = ExerciseFileStore.loadFile(tempDir().resolve("absent.jsonl"))
        assertEquals(0, result.bank.size)
        assertTrue(result.issues.isEmpty())
    }
}
