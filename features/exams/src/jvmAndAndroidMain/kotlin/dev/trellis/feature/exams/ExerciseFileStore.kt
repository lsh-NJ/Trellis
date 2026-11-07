package dev.trellis.feature.exams

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * 运行时题库导入：把用户放在 `<TRELLIS_HOME>/exams/` 下的 `.jsonl` 文件读进同一个 bank。
 *
 * 为什么在 jvmMain：这是文件系统能力，安卓端将来要换成 SAF/`ContentResolver` 的实现，
 * 不该把 `java.nio` 放进共用源集。UI 接线建议见 `tools/exams/README.md`。
 *
 * 语义：
 * - 目录不存在 / 为空 → 返回空结果而不是报错（刚装的用户没有这个目录很正常）；
 * - 坏文件坏行不阻断其它文件（[FileIssue] 逐条报出来）；
 * - 跨文件 id 重复只保留第一次出现的，重复项进 [DirectoryResult.issues]。
 */
object ExerciseFileStore {

    data class FileIssue(
        val file: Path,
        val line: Int,
        val raw: String,
        val errors: List<String>,
    ) {
        fun describe(): String = "$file 第 $line 行：${errors.joinToString("；")}｜原文：$raw"
    }

    data class DirectoryResult(
        val bank: ExerciseBank,
        val issues: List<FileIssue>,
        val files: List<Path>,
    )

    /** 读单个 `.jsonl` 文件；文件不存在返回空 bank（导入目录场景下不是错误）。 */
    fun loadFile(path: Path): ExerciseStore.ParseResult {
        if (!path.exists()) return ExerciseStore.ParseResult(ExerciseBank.EMPTY, emptyList())
        val text = Files.readString(path)
        return ExerciseStore.parse(text)
    }

    /** 读目录下（不递归）所有 `.jsonl`，按文件名排序后合并。 */
    fun loadDirectory(directory: Path): DirectoryResult {
        if (!directory.exists() || !directory.isDirectory()) {
            return DirectoryResult(ExerciseBank.EMPTY, emptyList(), emptyList())
        }
        val files = directory.listDirectoryEntries()
            .filter { it.isRegularFile() && it.extension.equals("jsonl", ignoreCase = true) }
            .sortedBy { it.name }

        val accepted = mutableListOf<Exercise>()
        val issues = mutableListOf<FileIssue>()
        val firstSeen = mutableMapOf<String, String>() // id -> 文件路径

        files.forEach { file ->
            val result = loadFile(file)
            result.issues.forEach { issue ->
                issues += FileIssue(file, issue.line, issue.raw, issue.errors)
            }
            result.accepted.forEach { exercise ->
                val previousFile = firstSeen[exercise.id]
                if (previousFile != null) {
                    issues += FileIssue(
                        file = file,
                        line = 0,
                        raw = exercise.id,
                        errors = listOf("id「${exercise.id}」与 $previousFile 中的题重复，已忽略本条"),
                    )
                } else {
                    firstSeen[exercise.id] = file.toString()
                    accepted += exercise
                }
            }
        }

        return DirectoryResult(
            bank = ExerciseBank(accepted),
            issues = issues,
            files = files,
        )
    }
}
