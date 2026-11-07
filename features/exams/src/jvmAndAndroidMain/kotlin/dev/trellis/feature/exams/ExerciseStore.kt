package dev.trellis.feature.exams

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSONL 题库的读写与校验。
 *
 * 为什么是 JSONL 而不是一个大 JSON 数组：种子是脚本生成的、要能 `git diff` 一行看一道题；
 * 坏一行只该丢一行，不该让整个题库读不出来。所以 [parse] 的契约是：
 * **能读的收下、坏的逐行报出来**，再由调用方决定是容忍（[ParseResult.issues] 非空但 bank 可用）
 * 还是严格失败（[parseOrThrow]）。
 */
object ExerciseStore {

    /** 对外统一的 JSON 配置：上游字段以后加了/删了不该让旧数据读不出来。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** 一行读坏/校验不过的明细。[line] 从 1 开始，[raw] 是原文（过长会截断）。 */
    data class Issue(
        val line: Int,
        val raw: String,
        val errors: List<String>,
    ) {
        fun describe(): String = "第 $line 行：${errors.joinToString("；")}｜原文：$raw"
    }

    /** [parse] 的结果：[bank] 是能用的题，[issues] 是丢弃的行。 */
    data class ParseResult(
        val bank: ExerciseBank,
        val issues: List<Issue>,
    ) {
        val accepted: List<Exercise> get() = bank.exercises
        val rejectedCount: Int get() = issues.size

        /** 有任何一行被丢弃就抛错，错误信息带全部明细（供测试/构建脚本使用）。 */
        fun requireNoIssues(): ExerciseBank {
            if (issues.isNotEmpty()) {
                throw JsonlExerciseException(
                    "题库 JSONL 有 ${issues.size} 行不合法：\n" +
                        issues.joinToString("\n") { it.describe() },
                )
            }
            return bank
        }
    }

    /**
     * 解析 JSONL 文本。
     *
     * 空行、以 `#` 或 `//` 开头的行按注释跳过（种子文件里写分段注释用）；其余每行必须是一个
     * 合法的 [Exercise] 对象并通过 [ExerciseValidator] 校验，且 id 不能与前面的行重复。
     */
    fun parse(jsonl: String): ParseResult {
        val issues = mutableListOf<Issue>()
        val accepted = mutableListOf<Exercise>()
        val firstLineById = mutableMapOf<String, Int>()

        val lines = jsonl.removePrefix("\uFEFF").split('\n')
        lines.forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trimEnd('\r').trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed

            val exercise = try {
                json.decodeFromString<Exercise>(line)
            } catch (e: SerializationException) {
                issues += Issue(
                    line = lineNumber,
                    raw = excerpt(line),
                    errors = listOf("不是合法的题目 JSON：${e.message ?: e::class.simpleName}"),
                )
                return@forEachIndexed
            } catch (e: IllegalArgumentException) {
                issues += Issue(
                    line = lineNumber,
                    raw = excerpt(line),
                    errors = listOf("不是合法的题目 JSON：${e.message ?: e::class.simpleName}"),
                )
                return@forEachIndexed
            }

            val errors = ExerciseValidator.validate(exercise).toMutableList()
            val previous = firstLineById[exercise.id]
            if (previous != null) {
                errors += "id「${exercise.id}」重复（第 $previous 行已经出现过）"
            }
            if (errors.isNotEmpty()) {
                issues += Issue(line = lineNumber, raw = excerpt(line), errors = errors)
                return@forEachIndexed
            }
            firstLineById[exercise.id] = lineNumber
            accepted += exercise
        }

        return ParseResult(bank = ExerciseBank(accepted), issues = issues)
    }

    /** 严格解析：任何一行坏掉都抛 [JsonlExerciseException]。 */
    fun parseOrThrow(jsonl: String): ExerciseBank = parse(jsonl).requireNoIssues()

    /** 把一道题编码成一行 JSON（字段顺序固定，方便 diff）。 */
    fun encode(exercise: Exercise): String = json.encodeToString(exercise)

    /** 把题列表编码成 JSONL 文本；末尾带一个换行（POSIX 文本文件约定）。 */
    fun toJsonl(exercises: List<Exercise>): String =
        exercises.joinToString(separator = "\n", postfix = "\n") { encode(it) }

    private fun excerpt(line: String, limit: Int = 240): String =
        if (line.length <= limit) line else line.take(limit) + "…"
}

/** JSONL 题库读取失败（一行或多行不合法）。消息里带行号与原因，能直接照着改。 */
class JsonlExerciseException(message: String, cause: Throwable? = null) : Exception(message, cause)
