package dev.trellis.feature.learning

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

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

/**
 * 计划文档（Markdown）的落盘与解析：`<trellis home>/plans/docs/<planId>.md`。
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
 * 读文件也容错：不存在 / id 不合法 / 坏字节一律当「没有文档」。
 */
class PlanDocStore(private val home: File) {

    /** `<home>/plans/docs`。 */
    val directory: File = File(File(home, "plans"), "docs")

    /**
     * 某个计划的文档文件。
     *
     * 计划 id 只允许字母 / 数字 / 下划线 / 中划线 —— 文档路径由 id 拼出来，
     * 不校验就能用 `../` 写出目录。
     */
    fun fileFor(planId: String): File {
        require(PLAN_ID_PATTERN.matches(planId)) { "计划 id 不合法：$planId" }
        return File(directory, "$planId.md")
    }

    /** 某个计划的周文档文件：`<home>/plans/docs/<planId>/<week>.md`。 */
    fun weekFile(planId: String, week: String): File {
        require(PLAN_ID_PATTERN.matches(planId)) { "计划 id 不合法：$planId" }
        require(WEEK_PATTERN.matches(week)) { "周格式不合法：$week" }
        return File(weekDirectory(planId), "$week.md")
    }

    private fun weekDirectory(planId: String): File {
        require(PLAN_ID_PATTERN.matches(planId)) { "计划 id 不合法：$planId" }
        return File(directory, planId)
    }

    /** 已经有总文档的计划 id（按 id 排序）。 */
    fun planIds(): List<String> = try {
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") }
            ?.map { it.name.removeSuffix(".md") }
            ?.filter { PLAN_ID_PATTERN.matches(it) }
            ?.sorted()
            .orEmpty()
    } catch (_: RuntimeException) {
        emptyList()
    }

    /** 某个计划已经写过的周文档（新的在前）。 */
    fun weeks(planId: String): List<String> = try {
        weekDirectory(planId).listFiles()
            ?.filter { it.isFile && WEEK_PATTERN.matches(it.name.removeSuffix(".md")) }
            ?.map { it.name.removeSuffix(".md") }
            ?.sortedDescending()
            .orEmpty()
    } catch (_: RuntimeException) {
        emptyList()
    }

    /** 读 Markdown；文件不存在、id 不合法、读坏（非 UTF-8 等）都返回 null，不抛。 */
    fun read(planId: String): String? {
        val file = try {
            fileFor(planId)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return readFile(file)
    }

    /** 读某一周的计划文档；没有就返回 null。 */
    fun readWeek(planId: String, week: String): String? {
        val file = try {
            weekFile(planId, week)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return readFile(file)
    }

    private fun readFile(file: File): String? {
        if (!file.isFile) return null
        return try {
            decodeUtf8(file.readBytes())
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 写某一周的计划文档（每周排计划时写），返回落盘的文件。 */
    fun saveWeek(planId: String, week: String, markdown: String): File = writeFile(weekFile(planId, week), markdown)

    /** 写 Markdown（UTF-8，目录不存在就建），返回落盘的文件。 */
    fun save(planId: String, markdown: String): File = writeFile(fileFor(planId), markdown)

    private fun writeFile(file: File, markdown: String): File {
        val parent = file.parentFile
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("建不了计划文档目录：$parent")
        }
        // 先写临时文件再换名：写到一半崩掉不会留下半份文档。
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(markdown, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // 有的文件系统不让 rename 覆盖已存在的文件：删掉目标再换一次。
            if (!file.delete() || !tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("计划文档写不进去：$file")
            }
        }
        return file
    }

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

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }

    private enum class Section { NONE, TARGETS, STAGES, WEEK, UNSCHEDULED }

    private companion object {
        val PLAN_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
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
