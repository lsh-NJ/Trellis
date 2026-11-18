package dev.trellis.feature.learning

import java.io.File

/**
 * 计划节点（要学的知识点）的体量档：小 / 中 / 大 → 15 / 30 / 45 分钟。
 *
 * 为什么不给每条编一个精确分钟数（2026-11-17 用户定的）：周预算是「每天投入 × 7」，
 * 让模型「按周预算摊开」时它只会做除法——十条一模一样的「约 42 分钟」，看着像测量
 * 结果，其实是预算除以条数，没有信息量。计划粒度的时长本来也估不准（规划谬误）。
 *
 * 所以条目时长只分三档、按内容多少选，具体分钟数由档位决定（不同条目自然不同）；
 * 周级只给一行由宿主从设置算出来的投入参考，不摊到条目上。真实时长靠计时器 /
 * 屏幕使用记录事后测量，那才是能准的数据。
 *
 * 上限 45 分钟是计划库的既有约束（`plan_unit.estimate_minutes` 只收 10–45）：
 * 一块超过 45 分钟就该拆成两条，而不是把档位抬上去。
 */
object PlanUnitBand {
    const val SMALL = 15
    const val MEDIUM = 30
    const val LARGE = 45

    /** 模型没写档位时用的默认档（中）。 */
    const val DEFAULT = MEDIUM

    /** 计划库接受的分钟范围，见 `plan_unit` 的 CHECK 约束。 */
    const val MIN_MINUTES = 10
    const val MAX_MINUTES = 45

    /** 档位标签 → 分钟；认不出返回 null。 */
    fun minutesOf(label: String): Int? = when (label.trim()) {
        "小" -> SMALL
        "中" -> MEDIUM
        "大" -> LARGE
        else -> null
    }

    /** 分钟 → 档位标签（老文档只写了分钟数时用这个回推）。 */
    fun labelOf(minutes: Int): String = when {
        minutes <= 20 -> "小"
        minutes <= 35 -> "中"
        else -> "大"
    }

    /** 界面上一行说明：`中（约 30 分钟）`。 */
    fun describe(minutes: Int): String = labelOf(minutes) + "（约 " + minutes + " 分钟）"

    /** 落库前的钳制：计划库只收 10–45。 */
    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
}

/**
 * 计划文档里的一条条目（周计划 / 待安排）。
 *
 * [minutesHint] 是从条目文字里读出来的时长：新格式读体量档
 * （「（中 · 约 30 分钟）」→ 30，只有档位「（大）」也行），老格式读分钟数
 * （「计算机网络 3 节（约 90 分钟）」→ 90）；取不到就是 null。
 * 它只是提示，真正落库的时长仍由 `appendUnits` 按列约束夹。
 */
data class PlanDocItem(
    val text: String,
    val minutesHint: Int? = null,
    /** 周文档里这个节点属于哪个主题（`### 主题：进程状态` 分组）；老文档没有，就是 null。 */
    val topic: String? = null,
)

/** 阶段下的一个主题：名称 + 体量（天，可空表示文档没写）。 */
data class PlanDocTopic(
    val name: String,
    val days: Int? = null,
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
    /** 阶段的主题清单：`- 主题：进程状态与切换（约 2 天）`。 */
    val topics: List<PlanDocTopic> = emptyList(),
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
    private val topics = mutableListOf<PlanDocTopic>()
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
                "主题" -> addTopic(value)
                "资源" -> {
                    resourceMode = true
                    addLinks(value)
                }
            }
            return
        }
        if (resourceMode) addLinks(bullet)
    }

    /** `进程状态与切换（约 2 天）` → 主题名 + 天数；没写天数就只留名字。 */
    private fun addTopic(text: String) {
        val days = TOPIC_DAYS.find(text)
        val name = if (days != null) {
            text.removeRange(days.range).trim().trimEnd('，', ',', '；', ';', '、')
        } else {
            text.trim()
        }
        if (name.isBlank()) return
        val parsed = days?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..30 }
        if (topics.none { it.name == name }) topics += PlanDocTopic(name = name, days = parsed)
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
        topics = topics.toList(),
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
        private val FIELD_PREFIX = Regex("^(聚焦|交付物|过关标准|主题|资源)\\s*[：:]\\s*(.*)$")

        /** 主题体量：`（约 2 天）`。 */
        private val TOPIC_DAYS = Regex("[（(]\\s*约?\\s*(\\d+)\\s*天\\s*[）)]")
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
 * - 中断与系统调用（中 · 约 30 分钟）
 * - 物理 2 节（约 90 分钟）
 * ## 待安排
 * - 数据结构实验
 * ```
 *
 * 阶段块兼容两种写法：`### 阶段 N · 名称（…）` 小节，或 `## 阶段` 下的
 * `- 阶段 N · 名称（约 N 周）：聚焦` bullet；资源写在 `- 资源：` 后，
 * 每行一条 `[名称](url)`（可带 `— 备注`）。
 *
 * 时长提示认两种：体量档「（中 · 约 30 分钟）」「（大）」（见 [PlanUnitBand]），
 * 以及老格式的分钟数「（约 90 分钟）」「(90分钟)」「约90分钟」；都取不到就是 null。
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
        var weekTopic: String? = null
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
                    weekTopic = null
                    section = Section.WEEK
                }
                section == Section.WEEK && WEEK_TOPIC_HEADING.matches(line) -> {
                    // 周文档里 `### 主题：X` 是分组标题：它下面直到下一个 `###`/`##` 的条目都属于这个主题。
                    // 其它 `###` 仍按「不认识的标题」处理（结束当前小节），避免把随手写的子标题带进来。
                    weekTopic = WEEK_TOPIC_HEADING.matchEntire(line)
                        ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                }
                UNSCHEDULED_HEADING.matches(line) -> {
                    flushStage()
                    section = Section.UNSCHEDULED
                    week = null
                    weekTopic = null
                }
                line.startsWith("#") -> {
                    // 其它标题（含 `### ...`）结束当前小节，其下条目忽略。
                    flushStage()
                    section = Section.NONE
                    week = null
                    weekTopic = null
                }
                else -> {
                    val bullet = BULLET.matchEntire(line)?.groupValues?.get(1)?.trim()
                    if (bullet.isNullOrEmpty()) return@forEach
                    when (section) {
                        Section.TARGETS -> targets += bullet
                        Section.UNSCHEDULED -> itemOf(bullet)?.let(unscheduled::add)
                        Section.WEEK -> {
                            val current = week ?: return@forEach
                            itemOf(bullet)?.let {
                                weeks.getOrPut(current) { mutableListOf() }.add(it.copy(topic = weekTopic))
                            }
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

    /**
     * 某份计划在某一周应该落到的阶段。
     *
     * 老计划按周区间匹配；2026-11-18 起阶段不再绑周，就退回第一个阶段（周文档由「当前阶段主题」
     * 决定，这里只给资源挂载/展示一个兜底，别让没有区间的计划拿不到阶段资源）。
     */
    fun stageForWeek(planId: String, week: String): PlanDocStage? {
        val all = stages(planId)
        return all.firstOrNull { stage ->
            val start = stage.weekStart
            val end = stage.weekEnd
            start != null && end != null && week in start..end
        } ?: all.firstOrNull()
    }

    /**
     * 一条 bullet 文字 → 条目（把「（中 · 约 30 分钟）」这类时长标注剥出来）。
     *
     * 先认体量档（只有档位、或档位 + 分钟都行），再认老格式的裸分钟数；
     * 档位和分钟都没有就只当文字。
     */
    private fun itemOf(raw: String): PlanDocItem? {
        val text = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        BAND_PAREN.find(text)?.let { bandMatch ->
            val explicit = bandMatch.groupValues[2].toIntOrNull()?.takeIf { it in 1..MAX_HINT_MINUTES }
            val minutes = explicit ?: PlanUnitBand.minutesOf(bandMatch.groupValues[1])
            if (minutes != null) return PlanDocItem(cleanItemText(text, bandMatch.range), minutes)
        }
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

        /** 周文档里的主题分组标题：`### 主题：进程状态`。 */
        val WEEK_TOPIC_HEADING = Regex("^###\\s*主题\\s*[：:]\\s*(.+)$")
        val UNSCHEDULED_HEADING = Regex("^##\\s*待安排\\s*$")
        val BULLET = Regex("^(?:[-*+]|\\d+[.)])\\s+(.+)$")

        /** 体量档标注：`（中 · 约 30 分钟）`、`（大）`、`(小·15分钟)`。 */
        val BAND_PAREN = Regex("[（(]\\s*([小中大])\\s*(?:[·,，、]\\s*(?:约\\s*)?(\\d+)\\s*分钟)?\\s*[）)]")

        /** 括起来的分钟提示：`（约 90 分钟）`、`(90分钟)`。 */
        val PAREN_MINUTES = Regex("[（(]\\s*约?\\s*(\\d+)\\s*分钟?\\s*[）)]")

        /** 裸的分钟提示：`约90分钟`、`约 90 分钟`。 */
        val BARE_MINUTES = Regex("约\\s*(\\d+)\\s*分钟?")

        /** 分钟提示的合理上限（一天）；超过当没写，防止把编号当成时长。 */
        const val MAX_HINT_MINUTES = 24 * 60
    }
}
