package dev.trellis.feature.exams

import kotlin.random.Random

/**
 * 题库查询层：一道题只安安静静地是 [Exercise]，所有「怎么找题」的逻辑都收在这里。
 *
 * 约定：
 * - 所有查询都返回**新的不可变列表**，不改内部状态；同一个 bank 可以被多处并发读取；
 * - [bySubject] / [byTag] / [byKind] 等精确匹配忽略首尾空白、大小写不敏感（中文无影响，
 *   但英文科目/标签比如 `DB`、`os` 输入时不该因大小写查不到）；
 * - 随机抽题必须可复现：[randomBySubject] 接受显式 [Random]，测试里传 `Random(seed)`，
 *   产品里传默认随机源。
 */
class ExerciseBank(exercises: List<Exercise>) {

    /** 全部题目，保持传入顺序（= 种子文件里的顺序，便于 diff 与定位）。 */
    val exercises: List<Exercise> = exercises.toList()

    private val byId: Map<String, Exercise> = this.exercises.associateBy { it.id }

    init {
        require(byId.size == this.exercises.size) {
            "题目 id 重复：${this.exercises.groupBy { it.id }.filterValues { it.size > 1 }.keys}"
        }
    }

    val size: Int get() = exercises.size
    val isEmpty: Boolean get() = exercises.isEmpty()

    fun get(id: String): Exercise? = byId[id.trim()]

    fun bySubject(subject: String): List<Exercise> = matches(exercises, subject) { it.subject }

    fun byCourse(course: String): List<Exercise> = matches(exercises, course) { it.course }

    fun byKind(kind: ExerciseKind): List<Exercise> = exercises.filter { it.kind == kind }

    fun byTag(tag: String): List<Exercise> = exercises.filter { exercise ->
        exercise.tags.any { it.equals(tag.trim(), ignoreCase = true) }
    }

    fun byYear(year: String): List<Exercise> = matches(exercises, year) { it.year.orEmpty() }

    /** 题干/答案/选项/标签里出现关键字的题（大小写不敏感）。 */
    fun search(keyword: String): List<Exercise> {
        val needle = keyword.trim()
        if (needle.isEmpty()) return exercises
        return exercises.filter { exercise ->
            exercise.stem.contains(needle, ignoreCase = true) ||
                exercise.answer.contains(needle, ignoreCase = true) ||
                exercise.explanation.contains(needle, ignoreCase = true) ||
                exercise.options.any { it.text.contains(needle, ignoreCase = true) } ||
                exercise.tags.any { it.contains(needle, ignoreCase = true) }
        }
    }

    /**
     * 学完一个单元后，从题库里找一道和这一段学习内容匹配的练习。
     *
     * 匹配只做**学科 / 课程 / 标签**的关键词包含，不看题干：计划名、章节、节点标题
     * 里出现的词才说明「在学什么」；拿题干去匹配会把「以下哪个选项正确」这类
     * 通用词也算命中，提议就变成随机了。
     *
     * 返回按命中词数降序；同分时客观题优先，再按 id 排序（保证可复现）。
     * 一道都没命中就返回空列表 —— 调用方据此决定「不提议」。
     */
    fun suggestFor(text: String, limit: Int = 1, excludeIds: Set<String> = emptySet()): List<Exercise> {
        val tokens = suggestionTokens(text)
        if (tokens.isEmpty()) return emptyList()
        return exercises.asSequence()
            .filter { it.id !in excludeIds }
            .mapNotNull { exercise ->
                val hits = tokens.count { token -> exercise.matchesTopic(token) }
                if (hits == 0) null else exercise to hits
            }
            .sortedWith(
                compareByDescending<Pair<Exercise, Int>> { it.second }
                    .thenBy { if (it.first.kind.isQuestion) 0 else 1 }
                    .thenBy { it.first.id },
            )
            .take(limit.coerceIn(1, 20))
            .map { it.first }
            .toList()
    }

    /** 分词用的分隔符：中英文标点、空白与常见符号（正则原样传给 Regex）。 */
    private val suggestionSeparators = Regex(
        """[\s，。、；：！？,.;:!?()（）\[\]【】「」『』/\\|·\-—_~]+""",
    )

    /** 把「计划名 + 章节 + 节点标题」切成 2 字以上的词；中文没空格，靠标点与连续段切。 */
    private fun suggestionTokens(text: String): List<String> =
        text.lowercase()
            .split(suggestionSeparators)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()

    /** 一个词是否命中这道题的主题（学科 / 课程 / 标签）。 */
    private fun Exercise.matchesTopic(token: String): Boolean {
        fun hit(value: String): Boolean {
            val v = value.trim().lowercase()
            return v.isNotEmpty() && (v.contains(token) || token.contains(v))
        }
        return hit(subject) || hit(course) || tags.any { hit(it) }
    }

    /** 组合筛选：参数为 null 表示不限制；keyword 为空串表示不限制。 */
    fun filter(
        subject: String? = null,
        course: String? = null,
        kind: ExerciseKind? = null,
        tag: String? = null,
        year: String? = null,
        keyword: String? = null,
    ): List<Exercise> {
        var result: List<Exercise> = exercises
        if (!subject.isNullOrBlank()) result = result.filter { it.subject.equals(subject.trim(), ignoreCase = true) }
        if (!course.isNullOrBlank()) result = result.filter { it.course.equals(course.trim(), ignoreCase = true) }
        if (kind != null) result = result.filter { it.kind == kind }
        if (!tag.isNullOrBlank()) result = result.filter { e -> e.tags.any { it.equals(tag.trim(), ignoreCase = true) } }
        if (!year.isNullOrBlank()) result = result.filter { it.year.equals(year.trim(), ignoreCase = true) }
        if (!keyword.isNullOrBlank()) {
            result = result.filter { exercise ->
                exercise.stem.contains(keyword.trim(), ignoreCase = true) ||
                    exercise.answer.contains(keyword.trim(), ignoreCase = true) ||
                    exercise.tags.any { it.contains(keyword.trim(), ignoreCase = true) }
            }
        }
        return result
    }

    /**
     * 从某个学科里随机抽 [count] 道题（抽不满就返回全部，不补别的学科）。
     *
     * 用 partial shuffle（Fischer-Yates 只走前 count 步），题库大了也不会整份打乱复制。
     */
    fun randomBySubject(
        subject: String,
        count: Int,
        random: Random = Random.Default,
    ): List<Exercise> {
        require(count >= 0) { "count 不能为负：$count" }
        val pool = bySubject(subject)
        if (count == 0) return emptyList()
        if (pool.size <= count) return pool.shuffled(random)
        val indices = pool.indices.toMutableList()
        for (i in 0 until count) {
            val j = i + random.nextInt(indices.size - i)
            val tmp = indices[i]
            indices[i] = indices[j]
            indices[j] = tmp
        }
        return indices.take(count).map { pool[it] }
    }

    /** 带种子的重载：调用方只要复现随机结果，不用自己 new Random。 */
    fun randomBySubject(subject: String, count: Int, seed: Long): List<Exercise> =
        randomBySubject(subject, count, Random(seed))

    /** 全部学科，按题量降序、同量按名字排序（UI 列表直接用）。 */
    val subjects: List<String>
        get() = exercises.groupingBy { it.subject }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    val kinds: List<ExerciseKind>
        get() = ExerciseKind.entries.filter { kind -> exercises.any { it.kind == kind } }

    /** 来源文档聚合：同一 `sourcePath` 的题算一份资料。 */
    fun sources(): List<ExamSource> = exercises
        .groupBy { it.sourcePath }
        .values
        .filter { it.isNotEmpty() }
        .map { group ->
            val first = group.first()
            ExamSource(
                path = first.sourcePath,
                subject = first.subject,
                course = first.course,
                title = first.tags.firstOrNull { it.startsWith("source:") }?.removePrefix("source:")
                    ?: first.sourcePath.substringAfterLast('/'),
                year = first.year,
                format = first.tags.firstOrNull { it.startsWith("format:") }?.removePrefix("format:").orEmpty(),
                exerciseCount = group.size,
                pages = group.mapNotNull { it.sourcePage }.distinct().sorted(),
            )
        }
        .sortedWith(compareBy({ it.subject }, { it.path }))

    fun stats(): ExerciseBankStats = ExerciseBankStats(
        total = exercises.size,
        questionCount = exercises.count { it.kind.isQuestion },
        bySubject = exercises.groupingBy { it.subject }.eachCount().toSortedMap(),
        byKind = exercises.groupingBy { it.kind }.eachCount()
            .toSortedMap(compareBy { ExerciseKind.entries.indexOf(it) }),
    )

    /** 题量摘要，写日志/报错时用。 */
    fun summary(): String =
        "题库 ${size} 条（${kinds.joinToString { "${it.displayName}${byKind(it).size}" }}），" +
            "学科 ${subjects.size} 个：" +
            stats().bySubject.entries.joinToString("、") { "${it.key}${it.value}" }

    private inline fun matches(all: List<Exercise>, value: String, field: (Exercise) -> String): List<Exercise> {
        val needle = value.trim()
        if (needle.isEmpty()) return all
        return all.filter { field(it).equals(needle, ignoreCase = true) }
    }

    companion object {
        val EMPTY = ExerciseBank(emptyList())

        /** 题量为 0 时抛错，用于「种子文件必须可用」的启动检查。 */
        fun requireNonEmpty(bank: ExerciseBank): ExerciseBank {
            require(!bank.isEmpty) { "题库为空：检查种子文件或导入目录" }
            return bank
        }
    }
}

/** 题量统计快照（按学科/题型）。 */
data class ExerciseBankStats(
    val total: Int,
    val questionCount: Int,
    val bySubject: Map<String, Int>,
    val byKind: Map<ExerciseKind, Int>,
)
