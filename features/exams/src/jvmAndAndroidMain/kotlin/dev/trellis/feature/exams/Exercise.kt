package dev.trellis.feature.exams

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 题型。
 *
 * JSONL 里用稳定的 snake_case 字符串（`single_choice` 等），改名会动到已落库的数据，
 * 所以这个映射是数据契约的一部分，别只当展示文案用。
 */
@Serializable
enum class ExerciseKind {
    @SerialName("single_choice")
    SingleChoice,

    @SerialName("multiple_choice")
    MultipleChoice,

    @SerialName("true_false")
    TrueFalse,

    @SerialName("short_answer")
    ShortAnswer,

    @SerialName("essay")
    Essay,

    /** 知识点卡片：正面是问题/提示，背面是答案/要点；对应「纯复习知识点」的 md。 */
    @SerialName("recall_card")
    RecallCard,
    ;

    /** 有选项、答案能用选项标签核对的题型。 */
    val isObjective: Boolean
        get() = this == SingleChoice || this == MultipleChoice || this == TrueFalse

    /** 适合按「题」计分的题型（知识点卡片不算题）。 */
    val isQuestion: Boolean
        get() = this != RecallCard

    val displayName: String
        get() = when (this) {
            SingleChoice -> "单选题"
            MultipleChoice -> "多选题"
            TrueFalse -> "判断题"
            ShortAnswer -> "简答题"
            Essay -> "论述题"
            RecallCard -> "知识点"
        }

    companion object {
        /** 解析 JSONL / 命令行里写的字符串；认不出返回 null，调用方负责报错。 */
        fun fromWire(value: String): ExerciseKind? {
            val normalized = value.trim().lowercase().replace('-', '_')
            return entries.firstOrNull {
                it.name.lowercase().replace("_", "") == normalized.replace("_", "") ||
                    it.serialName.lowercase() == normalized
            }
        }

        private val ExerciseKind.serialName: String
            get() = when (this) {
                SingleChoice -> "single_choice"
                MultipleChoice -> "multiple_choice"
                TrueFalse -> "true_false"
                ShortAnswer -> "short_answer"
                Essay -> "essay"
                RecallCard -> "recall_card"
            }
    }
}

/**
 * 一个选项。
 *
 * [label] 是 A/B/C/D（判断题是 A/B），[text] 是选项正文。分开存是为了：
 * - 答案可以用标签记（`answer = "C"`），显示时再经 [Exercise.answerText] 还原正文；
 * - 未来做选项乱序时标签不变、正文可换。
 */
@Serializable
data class ExerciseOption(
    val label: String,
    val text: String,
)

/**
 * 题库里的一道题 / 一张知识点卡。
 *
 * 字段设计约定：
 * - [id] 全局唯一、稳定（`<subject>-<source 短名>-<序号>`），是复习记录/错题本将来引用的主键；
 * - [subject] 学科（如「数据结构」），[course] 课程（如教材/课程全名），UI 按 subject 分组、course 细分；
 * - [kind] 决定交互：选择题有 [options]，简答/论述/知识点只有题干与答案；
 * - [answer] 选择题存选项标签（`"C"` / `"AC"`），其它题型存答案文本；
 * - [tags] 用于检索（题型/章节/来源标记），不参与分组；
 * - [sourcePath] 必填：来自上游仓库的哪个文件；[sourcePage] 只有 PDF 来源才有；
 * - [year] 试卷学年（如 `23-24-1`），方便按年份筛。
 */
@Serializable
data class Exercise(
    val id: String,
    val subject: String,
    val course: String = "",
    val kind: ExerciseKind,
    val stem: String,
    val options: List<ExerciseOption> = emptyList(),
    val answer: String,
    val explanation: String = "",
    val tags: List<String> = emptyList(),
    val sourcePath: String,
    val sourcePage: Int? = null,
    val year: String? = null,
) {
    /** 选择题把 `answer`（标签）还原成选项正文；非选择题原样返回答案文本。 */
    fun answerText(): String {
        if (options.isEmpty() || !kind.isObjective) return answer
        val labels = normalizedAnswerLabels()
        if (labels.isEmpty()) return answer
        val texts = labels.mapNotNull { label -> options.firstOrNull { it.label.equals(label, ignoreCase = true) }?.text }
        if (texts.isEmpty()) return answer
        return texts.joinToString("；")
    }

    /**
     * 选择题答案里的选项标签列表。
     *
     * 同时支持 `"C"`、`"AC"`、`"A,C"`、`"A、C"`、`"C. 体育"` 等写法——上游资料是人工整理的，
     * 格式不统一；解析放宽，写入口（[ExerciseStore]）仍在保存前把答案规范化。
     */
    fun normalizedAnswerLabels(): List<String> {
        val compact = answer.filter { it.isLetter() || it.isDigit() }.uppercase()
        if (compact.isEmpty()) return emptyList()
        return compact.map { it.toString() }.distinct().filter { label ->
            options.any { it.label.equals(label, ignoreCase = true) }
        }
    }

    /** `subject / course` 的展示串；course 为空时只显示 subject。 */
    val subjectLabel: String
        get() = if (course.isBlank() || course == subject) subject else "$subject / $course"
}

/**
 * 同一份来源文档的聚合描述（由 [ExerciseBank.sources] 现算）。
 *
 * 单题的出处字段已经挂在 [Exercise] 上（sourcePath/sourcePage/year）；这里是把它们按文档
 * 收拢成「数据来源」列表，给 UI 显示「本题库来自哪些资料」以及按来源筛选。
 */
@Serializable
data class ExamSource(
    val path: String,
    val subject: String,
    val course: String = "",
    val title: String = "",
    val year: String? = null,
    /** `markdown` / `pdf_text` / `pdf_ocr`；记录来源的可信度（扫描件 OCR 会有更多错字）。 */
    val format: String = "",
    val exerciseCount: Int = 0,
    val pages: List<Int> = emptyList(),
)

/**
 * 校验规则（写入/读取共用一处，避免「能写进去但读出来报错」）。
 *
 * 报错信息要能直接告诉人怎么改（AGENTS.md §7）：说明是哪条规则、当前值是什么。
 */
object ExerciseValidator {

    fun validate(exercise: Exercise): List<String> = buildList {
        if (exercise.id.isBlank()) add("id 为空：每道题必须有稳定唯一 id")
        if (exercise.subject.isBlank()) add("subject 为空：必须归属一个学科")
        if (exercise.stem.isBlank()) add("stem 为空：题干不能是空白")
        if (exercise.sourcePath.isBlank()) add("sourcePath 为空：必须记录来自哪个源文件")
        if (exercise.answer.isBlank()) {
            add(
                "answer 为空：${exercise.kind.displayName}必须有答案" +
                    if (exercise.kind.isObjective) "（选择题填选项标签，如 \"C\" 或 \"AC\"）" else "（简答/知识点填答案文本）",
            )
        }

        when (exercise.kind) {
            ExerciseKind.SingleChoice, ExerciseKind.MultipleChoice -> {
                if (exercise.options.size < 2) {
                    add("${exercise.kind.displayName}的 options 少于 2 个（当前 ${exercise.options.size} 个）")
                }
                if (exercise.options.any { it.text.isBlank() }) add("options 里有正文为空的选项")
                if (exercise.options.map { it.label.uppercase() }.distinct().size != exercise.options.size) {
                    add("options 标签重复：${exercise.options.map { it.label }}")
                }
                if (exercise.options.isNotEmpty() && exercise.answer.isNotBlank()) {
                    val labels = exercise.normalizedAnswerLabels()
                    if (labels.isEmpty()) {
                        add(
                            "answer「${exercise.answer}」不在选项标签 " +
                                exercise.options.map { it.label }.toString() + " 中",
                        )
                    }
                    if (exercise.kind == ExerciseKind.SingleChoice && labels.size > 1) {
                        add("单选题 answer「${exercise.answer}」只能有一个选项标签")
                    }
                }
            }

            ExerciseKind.TrueFalse -> {
                if (exercise.options.isNotEmpty() && exercise.options.size != 2) {
                    add("判断题 options 要么留空，要么正好 2 个（当前 ${exercise.options.size} 个）")
                }
                if (exercise.options.isNotEmpty() && exercise.normalizedAnswerLabels().isEmpty()) {
                    add(
                        "判断题 answer「${exercise.answer}」不在选项标签 " +
                            exercise.options.map { it.label }.toString() + " 中",
                    )
                }
            }

            ExerciseKind.ShortAnswer, ExerciseKind.Essay, ExerciseKind.RecallCard -> Unit
        }
    }

    /** 题目能否被当作「客观题」直接判分（选择题/判断题）。 */
    fun isAutoGradable(exercise: Exercise): Boolean = exercise.kind.isObjective
}
