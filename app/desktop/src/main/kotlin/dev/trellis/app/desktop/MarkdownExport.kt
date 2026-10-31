package dev.trellis.app.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 「导出 Markdown」的落盘侧：让用户选保存位置，把当前笔记写成独立 `.md` 文件。
 *
 * 笔记平时也存成 `<home>/knowledge/notes/<id>.md`，但那是内部格式（带 frontmatter）。
 * 导出的是给人和别的工具看的干净文件：frontmatter 去掉、AI 标记还原成普通文字
 * （纯文本逻辑在 [exportNoteMarkdown]）。
 *
 * 选择面板用 AWT [FileDialog]：macOS / Windows 上都是系统原生保存面板，自带覆盖确认；
 * 不自己画对话框，少一套需要维护的 UI。
 */

/** 导出文件名：标题里不能进文件名的字符换成空格；空标题用占位名。 */
internal fun markdownExportFileName(title: String): String {
    val cleaned = displayTitle(title)
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.')
        .take(80)
        .trim()
    return cleaned.ifBlank { "无标题笔记" } + noteFileExtension
}

/**
 * 弹系统保存面板，返回要写的文件；用户取消返回 null。
 *
 * 默认文件名由标题清理而来；用户没写扩展名时补 `.md`。
 * 必须在 AWT 事件线程调用（Compose 的点击回调就在这个线程上）。
 */
internal fun chooseMarkdownExportFile(defaultName: String): File? {
    val dialog = FileDialog(null as Frame?, "导出 Markdown", FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val directory = dialog.directory ?: return null
    val file = File(directory, name)
    val extension = noteFileExtension.removePrefix(".")
    return if (file.extension.equals(extension, ignoreCase = true)) {
        file
    } else {
        File(file.parentFile, file.name + noteFileExtension)
    }
}

/** 写 UTF-8、不加 BOM；异常交给调用方转成界面提示。 */
internal fun writeMarkdownExport(note: KnowledgeNote, target: File) {
    target.writeText(exportNoteMarkdown(note), StandardCharsets.UTF_8)
}
