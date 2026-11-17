package dev.trellis.feature.learning

import java.io.File

/**
 * 计划文档里的一条条目（周计划 / 待安排）。
 *
 * [minutesHint] 是从条目文字里读出来的分钟提示（「计算机网络 3 节（约 90 分钟）」→ 90），
 * 取不到就是 null。它只是提示，真正落库的时长仍由 `appendUnits` 按列约束夹。
 */
data class PlanDocItem(
    val text: String,
    val minutesHint: Int? = null,
)

/** 总文档阶段里的一条资源：`- [名称](url) — 备注`。 */
data class DocResource(
    val title: String,
    val url: String,
    val note: String = "",
)

/**
 * 总文档里的一个阶段块，和计划库的 `plan_stage` 同源映照。
 *
 * 调研产生的阶段先写在这里，再由上层同步成 `plan_stage`；阶段下的资源随阶段走，
 * 排周计划时按节点标题就近挂到单元上（`unit_resource`）。
 */
data class PlanDocStage(
    val name: String,
    val weekStart: String? = null,
    val weekEnd: String? = null,
    val weeks: Int? = null,
    val focus: String = "",
    val deliverable: String = "",
    val acceptance: String = "",
    val resources: List<DocResource> = emptyList(),
)

/** 一份计划文档解析后的结构：`# 目标与验收` / `## 阶段` / `## 2026-W45` / `## 待安排` 四块。 */
data class PlanDoc(
    val targets: List<String> = emptyList(),
    val stages: List<PlanDocStage> = emptyList(),
    val weeks: Map<String, List<PlanDocItem>> = emptyMap(),
    val unscheduled: List<PlanDocItem> = emptyList(),
)

/** 把 `## 阶段` 下的一段收成 [PlanDocStage]；两种标题写法都认。 */
private class StageBuilder(
    var name: String,
    private val weekStart: String? = null,
    private val weekEnd: String? = null,
    private val weeks: Int? = null,
    var focus: String = "",
) {
    private var deliverable: String = ""
    private var acceptance: String = ""
    private val resources = mutableListOf<DocResource>()
    private var resourceMode = false

    fun addBullet(bullet: String) {
        val field = FIELD_PREFIX.matchEntire(bullet)
        if (field != null) {
            resourceMode = false
            val value = field.groupValues[2].trim()
            when (field.groupValues[1]) {
                "聚焦" -> focus = value
                "交付物" -> deliverable = value
                "过关标准" -> acceptance = value
                "资源" -> {
                    resourceMode = true
                    addLinks(value)
                }
            }
            return
        }
        if (resourceMode) addLinks(bullet)
    }

    private fun addLinks(text: String) {
        if (text.isBlank()) return
        val links = LINK.findAll(text).toList()
        if (links.isEmpty()) {
            val title = text.trim().trim('—', '-', '：', ':', ' ', '，', ',')
            if (title.isNotEmpty()) resources += DocResource(title = title, url = "")
            return
        }
        links.forEachIndexed { index, match ->
            val title = match.groupValues[1].trim()
            val url = match.groupValues[2].trim()
            if (title.isEmpty() || url.isEmpty()) return@forEachIndexed
            val noteEnd = links.getOrNull(index + 1)?.range?.first ?: text.length
            val note = text.substring(match.range.last + 1, noteEnd)
                .trim()
                .trim('—', '-', '：', ':', ' ', '，', ',')
            if (resources.none { it.url == url && it.title == title }) {
                resources += DocResource(title = title, url = url, note = note)
            }
        }
    }

    fun build(): PlanDocStage = PlanDocStage(
        name = name,
        weekStart = weekStart,
        weekEnd = weekEnd,
        weeks = weeks,
        focus = focus,
        deliverable = deliverable,
        acceptance = acceptance,
        resources = resources.toList(),
    )

    companion object {
        fun fromHeading(raw: String): StageBuilder {
            var text = raw.trim().removePrefix("###").trim()
            if (text.startsWith("阶段")) text = text.removePrefix("阶段").trim()
            text = text.replaceFirst(Regex("^\\d+\\s*[·.、:：-]?\\s*"), "")

            val range = WEEK_RANGE.find(text)
            val count = WEEKS_COUNT.find(text)
            var focus = ""
            val colon = text.indexOfFirst { it == '：' || it == ':' }
            if (colon >= 0) {
                focus = text.substring(colon + 1).trim()
                text = text.substring(0, colon)
            }
            val name = text.replace(WEEK_PAREN, "").trim().trimEnd('·', '-', '—', ':', '：', ' ')
            return StageBuilder(
                name = name.ifBlank { "阶段" },
                weekStart = range?.groupValues?.get(1),
                weekEnd = range?.groupValues?.get(2),
                weeks = count?.groupValues?.get(1)?.toIntOrNull(),
                focus = focus,
            )
        }

        private val LINK = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")
        private val FIELD_PREFIX = Regex("^(聚焦|交付物|过关标准|资源)\\s*[：:]\\s*(.*)$")
        private val WEEK_RANGE = Regex("(\\d{4}-W\\d{2})\\s*[–—~-]\\s*(\\d{4}-W\\d{2})")
        private val WEEKS_COUNT = Regex("约\\s*(\\d+)\\s*周")
        private val WEEK_PAREN = Regex("\\s*[（(][^）)]*[）)]\\s*$")
    }
}

/** 旧 `plan_doc` 表里的一行：迁移时先整表读出来，再逐行补成文件。 */
private data class LegacyDocRow(
    val planId: String,
    val kind: String,
    val week: String,
    val markdown: String,
)

/**
 * 计划文档（Markdown）的存取与解析：文件是真相，放在 `<home>/knowledge/plan-docs/` 下。
 *
 * - 总文档：`<home>/knowledge/plan-docs/<planId>/总览.md`
 * - 周文档：`<home>/knowledge/plan-docs/<planId>/<YYYY-Www>.md`（如 `2026-W47.md`）
 * - 草稿：`<home>/knowledge/plan-docs/_drafts/<draftId>.md`
 *
 * 2026-11-17 之前文档存在 `<home>/plans/plan.db` 的 `plan_doc` 表里，再往前是 `plans/docs`
 * 下的 Markdown 文件。这两种旧存储现在只做一次性导入来源：打开 store 时把库里/旧目录里
 * 还没有对应文件的文档补写成文件（只补缺失，不覆盖已有文件），之后写入只写文件。
 * 搬进 `<home>/knowledge/` 是为了让计划文档和笔记一样是普通 Markdown 资产——能备份、
 * 能导出，知识库也能直接把「计划文档」当一个分类列出来。
 *
 * 草稿给「计划工作流」第一步用：计划本体还没建、或用户只想先把总计划写出来时，先写
 * `_drafts/<draftId>.md`；计划建好后再 [adoptDraft] 落成该计划的总文档。草稿不是计划，
 * `save` 的「计划必须存在」校验不管它。
 *
 * 文档是「想法」层：用户 / AI 先把条目写进文档，再由上层把条目变成计划的单元
 * （`appendUnits`）。它不是计划库的副本 —— 计划库里没有的条目，这里也可能先有。
 *
 * 格式（只有这四类标题有意义，其它标题下的内容一律忽略）：
 * ```
 * # 目标与验收
 * - 目标A：把 TypeScript 学扎实
 * - 验收：能独立写一个类型安全的模块
 * ## 阶段
 * ### 阶段 1 · 基础语法（2026-W45 – 2026-W46，约 2 周）
 * - 聚焦：类型系统与函数式写法
 * - 交付物：一个类型安全的 CLI 小工具
 * - 过关标准：能讲清每个类型别名的取舍
 * - 资源：
 *   - [TypeScript 官方手册](https://www.typescriptlang.org/docs/) — 前 4 章
 * ## 2026-W45
 * - 计算机网络 3 节（约 90 分钟）
 * - 物理 2 节
 * ## 待安排
 * - 数据结构实验
 * ```
 *
 * 阶段块兼容两种写法：`### 阶段 N · 名称（…）` 小节，或 `## 阶段` 下的
 * `- 阶段 N · 名称（约 N 周）：聚焦` bullet；资源写在 `- 资源：` 后，
 * 每行一条 `[名称](url)`（可带 `— 备注`）。
 *
 * 分钟提示认「（约 90 分钟）」「(90分钟)」「约90分钟」几种写法，取不到就是 null。
 * 解析是容错的：非 bullet 行、未知标题、坏行都跳过，不抛异常；
 * 读文件也容错：没有文档 / id 不合法 / 文件读坏都当「没有文档」。
 */
class PlanDocStore(home: File) : AutoCloseable {

    /**
     * 计划库连接：只用于 `save` 的「计划是否存在」校验和读旧 `plan_doc` 表做一次性迁移。
     * 文档本身不再写进库。与计划库共用一个文件、各自一条连接，SQLite 的写锁负责串行化。
     */
    private val database = PlanDatabase.open(File(File(home, "plans"), "plan.db").toPath())
    private val connection get() = database.connection

    /** 计划文档根：`<home>/knowledge/plan-docs/`。 */
    private val docsDir = File(File(home, "knowledge"), "plan-docs")

    /** 草稿目录：`<home>/knowledge/plan-docs/_drafts/`；`_` 开头，不会和计划 id 撞。 */
    private val draftsDir = File(docsDir, DRAFT_DIR)

    init {
        docsDir.mkdirs()
        draftsDir.mkdirs()
        // 先读库（2026-11-15 之后它才是真相），再读更老的 plans/docs 文件。两边都只补缺失的
        // 文件，所以顺序不影响结果，也不会覆盖已经存在（用户 / AI 改过）的文件。
        runCatching { importDatabaseDocs() }
        runCatching { importLegacyDocs(home) }
    }

    /** 已经有文档的计划 id（按 id 排序）。 */
    fun planIds(): List<String> = try {
        docsDir.listFiles().orEmpty()
            // `_drafts` 也满足 id 形状，但它放的是草稿，不是计划。
            .filter { it.isDirectory && it.name != DRAFT_DIR && PLAN_ID_PATTERN.matches(it.name) }
            .filter { dir -> dir.listFiles().orEmpty().any { it.isFile && isDocFileName(it.name) } }
            .map { it.name }
            .sorted()
    } catch (_: RuntimeException) {
        emptyList()
    }

    /** 某个计划已经写过的周文档（新的在前）。 */
    fun weeks(planId: String): List<String> {
        if (!PLAN_ID_PATTERN.matches(planId)) return emptyList()
        val dir = planDir(planId)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .mapNotNull { file -> file.takeIf { it.isFile }?.name?.removeSuffix(MARKDOWN_SUFFIX) }
            .filter { WEEK_PATTERN.matches(it) }
            .sortedDescending()
    }

    /** 读总文档；没有或 id 不合法都返回 null，不抛。 */
    fun read(planId: String): String? = readDoc(planId, OVERVIEW_FILE)

    /** 读某一周的计划文档；没有就返回 null。 */
    fun readWeek(planId: String, week: String): String? {
        if (!WEEK_PATTERN.matches(week)) return null
        return readDoc(planId, week + MARKDOWN_SUFFIX)
    }

    private fun readDoc(planId: String, fileName: String): String? {
        if (!PLAN_ID_PATTERN.matches(planId)) return null
        return readFile(File(planDir(planId), fileName))
    }

    /** 写某一周的计划文档（每周排计划时写）。计划不存在时抛 IllegalArgumentException。 */
    fun saveWeek(planId: String, week: String, markdown: String) {
        require(WEEK_PATTERN.matches(week)) { "周格式不合法：$week" }
        saveDoc(planId, week + MARKDOWN_SUFFIX, markdown)
    }

    /** 写总文档。计划不存在时抛 IllegalArgumentException（写不进孤儿文档）。 */
    fun save(planId: String, markdown: String) = saveDoc(planId, OVERVIEW_FILE, markdown)

    private fun saveDoc(planId: String, fileName: String, markdown: String) {
        require(PLAN_ID_PATTERN.matches(planId)) { "计划 id 不合法：$planId" }
        if (!planExists(planId)) throw IllegalArgumentException("找不到这个计划：$planId")
        writeFile(File(planDir(planId), fileName), markdown)
    }

    /** 写一份「总计划草稿」：计划本体还没建时也能先写文档，计划建好后再 [adoptDraft]。 */
    fun saveDraft(draftId: String, markdown: String) {
        require(DRAFT_ID_PATTERN.matches(draftId)) { "草稿 id 不合法：$draftId" }
        writeFile(draftFile(draftId), markdown)
    }

    /** 读草稿；没有或 id 不合法都返回 null，不抛。 */
    fun readDraft(draftId: String): String? =
        if (DRAFT_ID_PATTERN.matches(draftId)) readFile(draftFile(draftId)) else null

    /**
     * 把草稿内容落成该计划的总文档（覆盖已有总文档），然后删掉草稿。
     *
     * 先写文档、后删草稿：写失败（例如计划不存在）时草稿还在，用户不用重写一遍。
     */
    fun adoptDraft(draftId: String, planId: String) {
        require(DRAFT_ID_PATTERN.matches(draftId)) { "草稿 id 不合法：$draftId" }
        val markdown = readDraft(draftId) ?: throw IllegalArgumentException("找不到这份草稿：$draftId")
        save(planId, markdown)
        deleteDraft(draftId)
    }

    /** 删草稿；不存在就当删过了，不抛。 */
    fun deleteDraft(draftId: String) {
        if (!DRAFT_ID_PATTERN.matches(draftId)) return
        runCatching { draftFile(draftId).delete() }
    }

    /** 现存草稿 id（按 id 排序）。 */
    fun draftIds(): List<String> = try {
        draftsDir.listFiles().orEmpty()
            .filter { it.isFile }
            .map { it.name.removeSuffix(MARKDOWN_SUFFIX) }
            .filter { DRAFT_ID_PATTERN.matches(it) }
            .sorted()
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun planDir(planId: String): File = File(docsDir, planId)

    private fun draftFile(draftId: String): File = File(draftsDir, draftId + MARKDOWN_SUFFIX)

    private fun isDocFileName(name: String): Boolean =
        name == OVERVIEW_FILE ||
            (name.endsWith(MARKDOWN_SUFFIX) && WEEK_PATTERN.matches(name.removeSuffix(MARKDOWN_SUFFIX)))

    private fun planExists(planId: String): Boolean = try {
        connection.prepareStatement("SELECT 1 FROM plan WHERE id = ? LIMIT 1").use { ps ->
            ps.setString(1, planId)
            ps.executeQuery().use { it.next() }
        }
    } catch (_: RuntimeException) {
        false
    }

    /**
     * 一次性迁移：把旧 `plan_doc` 表里的行补写成文件（只补缺失，不覆盖文件）。
     *
     * 先整表读完再写文件：别边查边写，SQLite 的 ResultSet 和文件 IO 搅在一起不好排错。
     * 不校验「计划还在」：旧行是 2026-11-15 之后的真相，哪怕计划后来被软删，也先把内容落到
     * 文件里，至少不丢；`read` 本来就不依赖计划表。
     */
    private fun importDatabaseDocs() {
        val rows = mutableListOf<LegacyDocRow>()
        connection.prepareStatement("SELECT plan_id, kind, week, markdown FROM plan_doc").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    rows += LegacyDocRow(
                        planId = rs.getString(1).orEmpty(),
                        kind = rs.getString(2).orEmpty(),
                        week = rs.getString(3).orEmpty(),
                        markdown = rs.getString(4).orEmpty(),
                    )
                }
            }
        }
        for (row in rows) {
            if (!PLAN_ID_PATTERN.matches(row.planId)) continue
            val fileName = when (row.kind) {
                "overview" -> OVERVIEW_FILE
                "week" -> if (WEEK_PATTERN.matches(row.week)) row.week + MARKDOWN_SUFFIX else continue
                else -> continue
            }
            val target = File(planDir(row.planId), fileName)
            if (target.isFile) continue
            runCatching { writeFile(target, row.markdown) }
        }
    }

    /**
     * 更老的一次性迁移：`plans/docs/<planId>.md` 与 `plans/docs/<planId>/<week>.md` 补写成文件。
     *
     * 只给「计划还在」的 id 补：老版本就是这么导入的，保持原语义；已有文件不覆盖，
     * 读坏（非 UTF-8）的文件跳过，不中断整次迁移。
     */
    private fun importLegacyDocs(home: File) {
        val dir = File(File(home, "plans"), "docs")
        if (!dir.isDirectory) return
        for (entry in dir.listFiles().orEmpty()) {
            if (entry.isFile && entry.name.endsWith(MARKDOWN_SUFFIX)) {
                val planId = entry.name.removeSuffix(MARKDOWN_SUFFIX)
                if (!PLAN_ID_PATTERN.matches(planId) || !planExists(planId)) continue
                val target = File(planDir(planId), OVERVIEW_FILE)
                if (target.isFile) continue
                readLegacyFile(entry)?.let { markdown -> runCatching { writeFile(target, markdown) } }
                continue
            }
            if (!entry.isDirectory || !PLAN_ID_PATTERN.matches(entry.name)) continue
            val planId = entry.name
            if (!planExists(planId)) continue
            for (weekFile in entry.listFiles().orEmpty()) {
                if (!weekFile.isFile || !weekFile.name.endsWith(MARKDOWN_SUFFIX)) continue
                val week = weekFile.name.removeSuffix(MARKDOWN_SUFFIX)
                if (!WEEK_PATTERN.matches(week)) continue
                val target = File(planDir(planId), week + MARKDOWN_SUFFIX)
                if (target.isFile) continue
                readLegacyFile(weekFile)?.let { markdown -> runCatching { writeFile(target, markdown) } }
            }
        }
    }

    /** 旧文件可能带 BOM 或坏字节：读坏（非 UTF-8）返回 null，跳过这一份，不中断整次迁移。 */
    private fun readLegacyFile(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 读一个文档文件：文件不在 / 读不到 / 坏字节（非 UTF-8）都返回 null，去掉文件头的 BOM。 */
    private fun readFile(file: File): String? {
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        }.getOrNull()
    }

    /**
     * 写文档：先写 `.tmp` 再改名（Windows 上改名到已存在的文件会失败，退化成直接写）。
     * 写失败往上抛，让调用方知道文档没写进去；读永远不抛。
     */
    private fun writeFile(file: File, markdown: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(markdown, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.delete()
            file.writeText(markdown, Charsets.UTF_8)
        }
    }

    override fun close() = database.close()

    /** 读取并解析；没有文档或文档读坏时返回 null。 */
    fun load(planId: String): PlanDoc? = read(planId)?.let(::parse)

    /** 容错解析，格式见类头注释。 */
    fun parse(markdown: String): PlanDoc {
        val targets = mutableListOf<String>()
        val stages = mutableListOf<PlanDocStage>()
        val weeks = linkedMapOf<String, MutableList<PlanDocItem>>()
        val unscheduled = mutableListOf<PlanDocItem>()
        var section = Section.NONE
        var week: String? = null
        var stage: StageBuilder? = null

        fun flushStage() {
            val current = stage ?: return
            if (current.name.isNotBlank()) stages += current.build()
            stage = null
        }

        markdown.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val weekHeading = WEEK_HEADING.matchEntire(line)
            val stageHeading = STAGE_HEADING.matchEntire(line)
            when {
                TARGETS_HEADING.matches(line) -> {
                    flushStage()
                    section = Section.TARGETS
                    week = null
                }
                STAGES_HEADING.matches(line) -> {
                    flushStage()
                    section = Section.STAGES
                    week = null
                }
                stageHeading != null -> {
                    flushStage()
                    section = Section.STAGES
                    week = null
                    stage = StageBuilder.fromHeading(stageHeading.groupValues[1])
                }
                weekHeading != null -> {
                    flushStage()
                    week = weekHeading.groupValues[1]
                    section = Section.WEEK
                }
                UNSCHEDULED_HEADING.matches(line) -> {
                    flushStage()
                    section = Section.UNSCHEDULED
                    week = null
                }
                line.startsWith("#") -> {
                    // 其它标题（含 `### ...`）结束当前小节，其下条目忽略。
                    flushStage()
                    section = Section.NONE
                    week = null
                }
                else -> {
                    val bullet = BULLET.matchEntire(line)?.groupValues?.get(1)?.trim()
                    if (bullet.isNullOrEmpty()) return@forEach
                    when (section) {
                        Section.TARGETS -> targets += bullet
                        Section.UNSCHEDULED -> itemOf(bullet)?.let(unscheduled::add)
                        Section.WEEK -> {
                            val current = week ?: return@forEach
                            itemOf(bullet)?.let { weeks.getOrPut(current) { mutableListOf() }.add(it) }
                        }
                        Section.STAGES -> {
                            if (bullet.startsWith("阶段 ")) {
                                // 兼容旧格式：`- 阶段 1 · 名称（约 2 周）：聚焦`
                                flushStage()
                                stage = StageBuilder.fromHeading(bullet)
                            } else {
                                stage?.addBullet(bullet)
                            }
                        }
                        Section.NONE -> Unit
                    }
                }
            }
        }
        flushStage()
        return PlanDoc(targets = targets, stages = stages, weeks = weeks, unscheduled = unscheduled)
    }

    /** 某份计划总文档里的阶段（无文档返回空）。 */
    fun stages(planId: String): List<PlanDocStage> = load(planId)?.stages.orEmpty()

    /** 某份计划在某一周应该落到的阶段（按阶段周区间判断；拿不到区间返回 null）。 */
    fun stageForWeek(planId: String, week: String): PlanDocStage? =
        stages(planId).firstOrNull { stage ->
            val start = stage.weekStart
            val end = stage.weekEnd
            start != null && end != null && week in start..end
        }

    /** 一条 bullet 文字 → 条目（把「（约 90 分钟）」之类的提示剥出来）。 */
    private fun itemOf(raw: String): PlanDocItem? {
        val text = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        val match = PAREN_MINUTES.find(text) ?: BARE_MINUTES.find(text) ?: return PlanDocItem(text)
        val minutes = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_HINT_MINUTES }
            ?: return PlanDocItem(text) // 数字坏 / 太离谱：当没写提示
        return PlanDocItem(cleanItemText(text, match.range), minutes)
    }

    private fun cleanItemText(raw: String, hintRange: IntRange): String =
        raw.removeRange(hintRange)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .trimEnd('，', ',', '；', ';', '、')
            .trim()

    private enum class Section { NONE, TARGETS, STAGES, WEEK, UNSCHEDULED }

    private companion object {
        /** 总文档的文件名：固定叫「总览.md」，打开目录一眼认得出。 */
        const val OVERVIEW_FILE = "总览.md"

        /** 文档后缀；周文档文件名就是 `<week>.md`。 */
        const val MARKDOWN_SUFFIX = ".md"

        /** 草稿目录名：`_` 开头，和计划 id（字母数字下划线连字符）不会撞。 */
        const val DRAFT_DIR = "_drafts"

        val PLAN_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** 草稿 id 和计划 id 同一套形状：只允许字母数字下划线连字符，防目录穿越。 */
        val DRAFT_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
        val WEEK_PATTERN = Regex("^\\d{4}-W\\d{2}$")
        val TARGETS_HEADING = Regex("^#\\s*目标与验收\\s*$")
        val STAGES_HEADING = Regex("^##\\s*阶段\\s*$")
        val STAGE_HEADING = Regex("^###\\s+(阶段\\b.*)$")
        val WEEK_HEADING = Regex("^##\\s*(\\d{4}-W\\d{2})\\s*$")
        val UNSCHEDULED_HEADING = Regex("^##\\s*待安排\\s*$")
        val BULLET = Regex("^(?:[-*+]|\\d+[.)])\\s+(.+)$")

        /** 括起来的分钟提示：`（约 90 分钟）`、`(90分钟)`。 */
        val PAREN_MINUTES = Regex("[（(]\\s*约?\\s*(\\d+)\\s*分钟?\\s*[）)]")

        /** 裸的分钟提示：`约90分钟`、`约 90 分钟`。 */
        val BARE_MINUTES = Regex("约\\s*(\\d+)\\s*分钟?")

        /** 分钟提示的合理上限（一天）；超过当没写，防止把编号当成时长。 */
        const val MAX_HINT_MINUTES = 24 * 60
    }
}
