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

/** 一份计划文档解析后的结构：`# 目标与验收` / `## 2026-W45` / `## 待安排` 三块。 */
data class PlanDoc(
    val targets: List<String> = emptyList(),
    val weeks: Map<String, List<PlanDocItem>> = emptyMap(),
    val unscheduled: List<PlanDocItem> = emptyList(),
)

/**
 * 计划文档（Markdown）的落盘与解析：`<trellis home>/plans/docs/<planId>.md`。
 *
 * 文档是「想法」层：用户 / AI 先把条目写进文档，再由上层把条目变成计划的单元
 * （`appendUnits`）。它不是计划库的副本 —— 计划库里没有的条目，这里也可能先有。
 *
 * 格式（只有这三类标题有意义，其它标题下的内容一律忽略）：
 * ```
 * # 目标与验收
 * - 目标A：把 TypeScript 学扎实
 * - 验收：能独立写一个类型安全的模块
 * ## 2026-W45
 * - 计算机网络 3 节（约 90 分钟）
 * - 物理 2 节
 * ## 待安排
 * - 数据结构实验
 * ```
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

    /** 读 Markdown；文件不存在、id 不合法、读坏（非 UTF-8 等）都返回 null，不抛。 */
    fun read(planId: String): String? {
        val file = try {
            fileFor(planId)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!file.isFile) return null
        return try {
            decodeUtf8(file.readBytes())
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 写 Markdown（UTF-8，目录不存在就建），返回落盘的文件。 */
    fun save(planId: String, markdown: String): File {
        val file = fileFor(planId)
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
        val weeks = linkedMapOf<String, MutableList<PlanDocItem>>()
        val unscheduled = mutableListOf<PlanDocItem>()
        var section = Section.NONE
        var week: String? = null
        markdown.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val weekHeading = WEEK_HEADING.matchEntire(line)
            when {
                TARGETS_HEADING.matches(line) -> {
                    section = Section.TARGETS
                    week = null
                }
                weekHeading != null -> {
                    week = weekHeading.groupValues[1]
                    section = Section.WEEK
                }
                UNSCHEDULED_HEADING.matches(line) -> {
                    section = Section.UNSCHEDULED
                    week = null
                }
                line.startsWith("#") -> {
                    // 其它标题（含 `### ...`）结束当前小节，其下条目忽略。
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
                        Section.NONE -> Unit
                    }
                }
            }
        }
        return PlanDoc(targets = targets, weeks = weeks, unscheduled = unscheduled)
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

    private enum class Section { NONE, TARGETS, WEEK, UNSCHEDULED }

    private companion object {
        val PLAN_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
        val TARGETS_HEADING = Regex("^#\\s*目标与验收\\s*$")
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
