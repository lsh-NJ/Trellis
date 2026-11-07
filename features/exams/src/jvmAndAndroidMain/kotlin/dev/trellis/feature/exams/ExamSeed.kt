package dev.trellis.feature.exams

/**
 * 随应用打包的种子题库。
 *
 * 数据文件在 `features/exams/src/jvmMain/resources/exam-seed/exercises.jsonl`，由
 * `tools/exams/` 的管线生成（见 `tools/exams/README.md`）。运行时导入用户自己追加的题库
 * 走 [ExerciseFileStore]（`<TRELLIS_HOME>/exams/` 目录里的 jsonl），两者互不覆盖。
 *
 * 这里做的是「懒加载 + 缓存」：种子文件几十 KB，第一次访问解析一次，之后共享同一个
 * 不可变 [ExerciseBank]；解析失败不会留下半个 bank（下一次访问会重试）。
 */
object ExamSeed {

    const val RESOURCE_PATH: String = "/exam-seed/exercises.jsonl"

    @Volatile
    private var cached: Result<ExerciseBank>? = null

    /** 种子文件的原始 JSONL 文本；资源缺失时报错并说明怎么生成。 */
    fun text(): String {
        val stream = ExamSeed::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: throw IllegalStateException(
                "找不到种子题库资源 $RESOURCE_PATH。" +
                    "请在仓库根目录运行：python3 tools/exams/fetch.py && " +
                    "python3 tools/exams/build_seed.py（详见 tools/exams/README.md）",
            )
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 解析种子文件并返回校验结果；坏行不会抛错，交给调用方决定（测试会断言 issues 为空）。 */
    fun loadResult(): ExerciseStore.ParseResult = ExerciseStore.parse(text())

    /**
     * 加载并校验种子题库：任何一行不合法都抛 [JsonlExerciseException]。
     * 这是给「资源必须完整」的代码路径用的（启动自检、测试）。
     */
    fun load(): ExerciseBank {
        cached?.let { result -> return result.getOrThrow() }
        val result = runCatching { loadResult().requireNoIssues() }
        cached = result
        return result.getOrThrow()
    }

    /** 测试用：清掉缓存，模拟首次访问 / 资源被替换。 */
    internal fun resetCacheForTest() {
        cached = null
    }
}
