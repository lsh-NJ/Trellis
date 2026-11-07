package dev.trellis.feature.exams

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 一次练习作答记录（追加写，不删不改）。
 *
 * 为什么只记结果不记题目内容：题目本体在题库里（<home>/exams 目录下的 jsonl 或内置种子），
 * 这里只留做题事实，题库换版本也不会把历史记录带偏。
 *
 * [correct] 对知识点卡片（[ExerciseKind.RecallCard]）是用户自评「记住了/没记住」；
 * [selfReported] 标记这种情况，统计时可以和客观题判分区分开（演示/答辩时能解释）。
 */
data class ExerciseAttempt(
    val at: Long,
    val exerciseId: String,
    val subject: String,
    val course: String,
    val kind: ExerciseKind,
    val correct: Boolean,
    val selfReported: Boolean = false,
)

/**
 * 一个学科的作答汇总（掌握度最小版）。
 *
 * 不做「掌握度百分比」这种容易过拟合的指标：只给「做了几题、答对几题、最近一次何时」，
 * 推荐器与管家消息都用这些**可核对**的数字。
 */
data class SubjectMastery(
    val subject: String,
    val attempts: Int,
    val correct: Int,
    val lastAt: Long,
) {
    val accuracyPercent: Int
        get() = if (attempts <= 0) 0 else (correct * 100) / attempts

    /** 给界面/管家文案用的一句话，例如「近 5 题答对 4（80%）」。 */
    fun label(): String = "近 $attempts 题答对 $correct（$accuracyPercent%）"
}

/** 按学科聚合：作答次数降序，其次学科名，保证展示稳定。 */
fun summarizeMastery(attempts: List<ExerciseAttempt>, limit: Int = 6): List<SubjectMastery> =
    attempts.groupBy { it.subject.trim() }
        .mapNotNull { (subject, list) ->
            if (subject.isEmpty()) {
                null
            } else {
                SubjectMastery(
                    subject = subject,
                    attempts = list.size,
                    correct = list.count { it.correct },
                    lastAt = list.maxOfOrNull { it.at } ?: 0L,
                )
            }
        }
        .sortedWith(compareByDescending<SubjectMastery> { it.attempts }.thenBy { it.subject })
        .take(limit.coerceAtLeast(1))

/** 取某个学科的汇总；学科名忽略首尾空白、大小写不敏感。 */
fun masteryOf(attempts: List<ExerciseAttempt>, subject: String): SubjectMastery? {
    val needle = subject.trim()
    if (needle.isEmpty()) return null
    val list = attempts.filter { it.subject.trim().equals(needle, ignoreCase = true) }
    if (list.isEmpty()) return null
    return SubjectMastery(
        subject = needle,
        attempts = list.size,
        correct = list.count { it.correct },
        lastAt = list.maxOfOrNull { it.at } ?: 0L,
    )
}

/**
 * `<home>/exams/attempts.jsonl` 读写。
 *
 * 坏行像题库导入一样跳过（不让一条坏数据毁掉全部历史）；文件不存在返回空。
 */
class ExerciseAttemptStore(private val file: Path) {

    fun load(): List<ExerciseAttempt> {
        if (!file.exists()) return emptyList()
        return runCatching {
            Files.readAllLines(file).mapNotNull { parse(it) }
        }.getOrDefault(emptyList())
    }

    fun append(attempt: ExerciseAttempt) {
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            file,
            encode(attempt).toString() + "\n",
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun encode(attempt: ExerciseAttempt): String = buildJsonObject {
        put("at", attempt.at)
        put("exerciseId", attempt.exerciseId)
        put("subject", attempt.subject)
        put("course", attempt.course)
        put("kind", attempt.kind.wireName)
        put("correct", attempt.correct)
        put("selfReported", attempt.selfReported)
    }.toString()

    private fun parse(line: String): ExerciseAttempt? {
        if (line.isBlank()) return null
        return runCatching {
            val root = Json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject ?: return null
            val id = root["exerciseId"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
            if (id.isBlank()) return null
            val kind = ExerciseKind.fromWire(root["kind"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()) ?: return null
            ExerciseAttempt(
                at = root["at"]?.jsonPrimitive?.longOrNull ?: 0L,
                exerciseId = id,
                subject = root["subject"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                course = root["course"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                kind = kind,
                correct = root["correct"]?.jsonPrimitive?.booleanOrNull ?: false,
                selfReported = root["selfReported"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }.getOrNull()
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()
