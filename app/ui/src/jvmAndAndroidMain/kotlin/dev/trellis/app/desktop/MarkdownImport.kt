package dev.trellis.app.desktop

import java.io.File

/**
 * 「导入 Markdown」的文件侧：选文件、认后缀、给导入的笔记起名。
 *
 * 和导出（[MarkdownExport]）对称：系统面板走 [HostPlatform]，纯逻辑留在这里能单测；
 * 真正解析 + 写盘交给 [KnowledgeStore.importNotes]。
 */

/** 认哪些后缀：Markdown 的两种写法，外加 `.txt`（不少人把笔记存成 txt）。 */
internal fun isMarkdownFileName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")
}

/** 弹系统面板选要导入的文件；用户取消返回空列表。 */
internal fun chooseImportMarkdownFiles(): List<File> = HostPlatform.chooseImportFiles()

/**
 * 给导入的笔记起一个不冲突的标题。
 *
 * 空标题用占位名；重名追加「 (2)」「 (3)」——同名的两篇笔记在侧栏里本来就分不清，
 * 但不能让它们看起来一模一样。
 */
internal fun uniqueNoteTitle(base: String, takenTitles: Set<String>): String {
    val cleaned = base.trim().ifBlank { "无标题笔记" }
    if (cleaned !in takenTitles) return cleaned
    var index = 2
    while ("$cleaned ($index)" in takenTitles) index++
    return "$cleaned ($index)"
}
