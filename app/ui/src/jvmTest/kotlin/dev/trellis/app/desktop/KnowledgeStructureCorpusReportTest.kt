package dev.trellis.app.desktop

import java.io.File
import kotlin.test.Test

/**
 * 结构化分块的人工验收报告（不参与普通 CI）。
 *
 * 跑法：`TRELLIS_KNOWLEDGE_CORPUS=1 ./gradlew :app:ui:jvmTest --tests dev.trellis.app.desktop.KnowledgeStructureCorpusReportTest`
 * 产出：`app/ui/build/reports/knowledge-structure.md`。
 *
 * 目的：拿仓库里真实的、结构各异的 Markdown 文档看「标题 1 > 标题 2 > 本段正文」到底怎么拆，
 * 而不是只靠单测里的构造数据。
 */
class KnowledgeStructureCorpusReportTest {
    @Test
    fun writeStructureReportForRepositoryDocs() {
        if (System.getenv("TRELLIS_KNOWLEDGE_CORPUS") != "1") return
        val docs = findDocsDir() ?: return
        val samples = listOf(
            "knowledge-graph-design.md" to "单个 H1 + 19 个 H2 + 78 个 H3 + 8 万字（触发段落模式）",
            "kernel-design.md" to "单 H1 + 31 个 H2 + 86 个 H3（宽而深）",
            "ui-review-2026-11-06.md" to "H3 为主、H2 少（评审稿式结构）",
            "agent-harness.md" to "H2 + H3 混合，1.5 万字",
            "activity.md" to "只有 H2，没有 H3（扁平章节）",
            "knowledge-base-draft.md" to "只有一个 H1、14 个 H2，3 万字",
            "time-conventions.md" to "13 个并列 H2，1 万字",
            "README.md" to "短文档（约 7 千字），验证不必分段",
            "markdown-webview-opensource-survey.md" to "代码块/提示词里有大量 #（不应算标题）",
        )
        val report = StringBuilder()
        report.append("# 知识库结构化分块报告\n\n")
        report.append("单块上限 ").append(MAX_EXTRACT_BODY_CHARS)
            .append(" 字，单篇最多 ").append(MAX_EXTRACT_CHUNKS).append(" 块。\n\n")
        for ((name, note) in samples) {
            val file = File(docs, name)
            if (!file.isFile) continue
            appendDocument(report, file, note)
        }
        appendDocument(report, File(docs, "../AGENTS.md").canonicalFile, "长文档 + 大量表格（表格不计入）")
        appendDocument(report, File(docs, "../tools/web-editor/README.md").canonicalFile, "短文档 + 代码块里的 #（不应算标题）")
        appendSynthetic(report)
        val out = File("build/reports/knowledge-structure.md")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println("[knowledge-structure] report → " + out.absolutePath)
    }

    private fun appendDocument(report: StringBuilder, file: File, note: String) {
        val body = file.readText()
        val headings = scanHeadingLines(body)
        val chunks = extractionChunks(body)
        val levels = headings.groupingBy { it.level }.eachCount().toSortedMap()
        report.append("## ").append(file.name).append("\n\n")
        report.append("- 路径：`").append(file.path).append("`\n")
        report.append("- 规模：").append(body.length).append(" 字符，")
            .append(body.count { it == '\n' } + 1).append(" 行\n")
        report.append("- 标题：").append(headings.size).append(" 个，层级分布 ")
            .append(levels.entries.joinToString(" / ") { "H" + it.key + "=" + it.value }).append("\n")
        report.append("- 结构标签：").append(note).append("\n")
        val tableLines = tableLineMask(body.split('\n')).count { it }
        report.append("- 表格行：").append(tableLines).append(" 行（不送模型）\n")
        val mode = if (chunks.any { it.sectionPath.isNotEmpty() }) "段落模式" else "按节"
        report.append("- 分块：").append(chunks.size).append(" 块（").append(mode).append("）")
        if (chunks.any { it.truncated }) report.append("（超出上限，尾部未送模型）")
        report.append("\n\n")
        chunks.take(8).forEachIndexed { index, chunk ->
            report.append("  ").append(index + 1).append(". L").append(chunk.startLine + 1)
                .append("-L").append(chunk.endLine + 1)
                .append("  ").append(chunk.text.length).append(" 字")
                .append("  所属=")
                .append(if (chunk.contextPath.isEmpty()) "（根）" else chunk.contextPath.joinToString(" > "))
            if (chunk.continued) report.append("  续上段")
            report.append("\n")
            report.append("     首行：").append(firstLine(chunk.text)).append("\n")
        }
        if (chunks.size > 8) {
            report.append("  … 其余 ").append(chunks.size - 8).append(" 块略\n")
        }
        report.append("\n")
        if (file.name.startsWith("knowledge-graph")) {
            report.append("  标题树（前 24 行，缩进表示层级）：\n\n")
            outlinePromptLines(parseOutline(body), maxLines = 24).forEach { line ->
                report.append("      ").append(line).append("\n")
            }
            report.append("\n")
        }
        if (file.name == "knowledge-graph-design.md") {
            val sampleIndex = chunks.indexOfFirst { it.sectionPath.size >= 2 }.coerceAtLeast(0)
            val sample = chunks[sampleIndex]
            val messages = buildKnowledgeExtractionMessages(
                title = file.nameWithoutExtension,
                body = sample.text,
                existing = emptyList(),
                slots = emptyList(),
                headings = outlinePromptLines(parseOutline(body)),
                chunkIndex = sampleIndex,
                chunkCount = chunks.size,
                contextPath = sample.contextPath,
                continued = sample.continued,
                truncated = sample.truncated,
                headingPrefix = sample.headingPrefix,
            )
            val user = messages.first { it.role == dev.trellis.ai.LlmRole.User }.content
            report.append("  提示词样例（第 ").append(sampleIndex + 1).append(" 块的 user 消息，前 28 行）：\n\n")
            user.lineSequence().take(28).forEach { line ->
                report.append("      ").append(if (line.length <= 110) line else line.take(110) + "…").append("\n")
            }
            report.append("\n")
        }
    }

    private fun appendSynthetic(report: StringBuilder) {
        report.append("## 合成边界样例\n\n")
        val skipped = buildString {
            append("# 一级标题\n")
            append("### 直接跳到三级\n")
            append("内容".repeat(3500)).append("\n")
            append("```\n# 代码块里的假标题\n```\n")
        }
        val multipleH1 = buildString {
            append("# 第一部分\n").append("甲".repeat(2500)).append("\n")
            append("# 第二部分\n").append("乙".repeat(2500)).append("\n")
            append("# 第三部分\n").append("丙".repeat(2500)).append("\n")
        }
        val noHeadings = "行内容。".repeat(4000)
        val oneLine = "长".repeat(MAX_EXTRACT_BODY_CHARS * 2 + 500)
        listOf(
            "跳级标题 + 代码块假标题" to skipped,
            "多个并列一级标题" to multipleH1,
            "完全没有标题" to noHeadings,
            "一整行超长（只能硬切）" to oneLine,
        ).forEach { (label, body) ->
            val chunks = extractionChunks(body)
            report.append("### ").append(label).append("\n\n")
            report.append("- ").append(body.length).append(" 字符 → ").append(chunks.size).append(" 块\n")
            chunks.forEachIndexed { index, chunk ->
                report.append("  ").append(index + 1).append(". ")
                    .append(chunk.text.length).append(" 字  所属=")
                    .append(if (chunk.contextPath.isEmpty()) "（根）" else chunk.contextPath.joinToString(" > "))
                if (chunk.continued) report.append("  续上段")
                report.append("  首行：").append(firstLine(chunk.text)).append("\n")
            }
            report.append("\n")
        }
    }

    private fun firstLine(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() } ?: "（空）"
        return if (line.length <= 70) line else line.take(70) + "…"
    }

    private fun findDocsDir(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(5) {
            val candidate = File(dir, "docs")
            if (candidate.isDirectory) return candidate
            dir = dir?.parentFile
        }
        return null
    }
}
