package dev.trellis.app.desktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * 安卓实现。
 *
 * 剪贴板有等价物；截屏、点击、注入按键**没有** —— 手机沙箱不允许一个应用
 * 去操作别的应用。所以这三个能力返回一句明确的话，而不是假装成功：
 * AI 工具循环会把这句话当成「工具没办成」的输入，用户看到的是
 * 「手机上没法截屏给模型看」，而不是一个转圈或者闪退。
 */
actual object HostPlatform {

    /**
     * 需要 Context 才能拿剪贴板。由 `MainActivity` 在 `onCreate` 里注入。
     *
     * 用可变单例而不是构造注入：`HostPlatform` 是 `actual object`，
     * 界面代码到处都是静态调用；为了一个 `getSystemService` 把 Context 传遍
     * 70 个文件不划算。注入点只有一个，漏注入的表现也很直接（复制没反应）。
     */
    var context: Context? = null

    actual val isDesktop: Boolean = false

    actual fun copyToClipboard(text: String) {
        val ctx = context ?: return
        runCatching {
            val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("Trellis", text))
        }
    }

    actual fun pressKey(key: String): String =
        "手机端不支持向其它应用注入按键（系统沙箱限制）。可以在对话里直接说明要做什么。"

    actual fun typeText(text: String): String =
        "手机端不支持往其它应用输入文字（系统沙箱限制）。"

    actual fun decodeImage(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? =
        runCatching {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()

    actual fun openFolder(dir: File): Boolean = false

    actual fun captureScreen(home: File): HostCapture =
        HostCapture(
            "手机端不支持截屏给模型看（系统沙箱限制）。可以让用户把界面截图发到对话里。",
            null,
            null,
        )

    actual fun clickScreen(rect: CaptureRect, x: Int, y: Int): String =
        "手机端不支持模拟点击（系统沙箱限制）。"

    actual fun resizePointerIcon(): androidx.compose.ui.input.pointer.PointerIcon =
        androidx.compose.ui.input.pointer.PointerIcon.Default

    actual fun chooseExportFile(defaultName: String): File? {
        val ctx = context ?: return null
        // 外部私有目录：用户能用文件管理器找到，卸载应用时一起清掉，不需要存储权限。
        val dir = (ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "exports"))
            .let { if (it.name == "exports") it else File(it, "exports") }
            .apply { mkdirs() }
        val target = File(dir, defaultName)
        // 同名就加序号，不覆盖用户上一份导出。
        if (!target.exists()) return target
        val base = defaultName.substringBeforeLast('.', defaultName)
        val ext = defaultName.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = File(dir, if (ext.isEmpty()) "$base-$index" else "$base-$index.$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    /**
     * 手机上没有等价的同步文件面板：SAF 的 `OpenMultipleDocuments` 要
     * `ActivityResult` 和 Activity 上下文，和这个同步 object 对不上。
     * 先返回空列表，界面会说明「手机上暂时不能导入」。
     */
    actual fun chooseImportFiles(): List<File> = emptyList()
}
