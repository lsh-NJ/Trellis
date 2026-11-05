package dev.trellis.app.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import androidx.compose.ui.graphics.toComposeImageBitmap
import javax.imageio.ImageIO

/**
 * 桌面实现：AWT + Robot。
 *
 * 这段代码原来长在 `AppRuntime` 里，搬出来只是为了让手机上能编过 ——
 * 行为一个字没改（按键延迟、坐标校验、1280 缩放都一样）。
 */
actual object HostPlatform {

    /**
     * 桌面为 true。`TRELLIS_FORCE_MOBILE=1` 可以把它伪装成手机 ——
     * 预览器是跑在 JVM 上的，没有这个开关就看不到「手机专属文案」（比如配置指引）。
     */
    actual val isDesktop: Boolean = System.getenv("TRELLIS_FORCE_MOBILE") != "1"

    actual fun copyToClipboard(text: String) {
        runCatching {
            val selection = java.awt.datatransfer.StringSelection(text)
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }
    }

    actual fun pressKey(key: String): String {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return "当前环境没有屏幕（headless），按不了键。"
        }
        val code = when (key.trim().lowercase()) {
            "enter", "回车" -> java.awt.event.KeyEvent.VK_ENTER
            "tab" -> java.awt.event.KeyEvent.VK_TAB
            "escape", "esc" -> java.awt.event.KeyEvent.VK_ESCAPE
            "backspace", "退格" -> java.awt.event.KeyEvent.VK_BACK_SPACE
            "space", "空格" -> java.awt.event.KeyEvent.VK_SPACE
            else -> return "只认 enter / tab / escape / backspace / space，不认「" + key + "」。"
        }
        return try {
            val robot = java.awt.Robot()
            robot.keyPress(code)
            robot.keyRelease(code)
            "按了 " + key
        } catch (t: Throwable) {
            "按不动：" + (t.message ?: (t::class.simpleName ?: "未知错误"))
        }
    }

    actual fun typeText(text: String): String {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return "当前环境没有屏幕（headless），打不了字。"
        }
        return try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val old = runCatching {
                clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            }.getOrNull()
            clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
            val robot = java.awt.Robot()
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
            robot.keyPress(java.awt.event.KeyEvent.VK_V)
            robot.keyRelease(java.awt.event.KeyEvent.VK_V)
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
            robot.delay(80)
            if (old != null) {
                clipboard.setContents(java.awt.datatransfer.StringSelection(old), null)
            }
            "输入了 " + text.length + " 个字"
        } catch (t: Throwable) {
            "打不了字：" + (t.message ?: (t::class.simpleName ?: "未知错误"))
        }
    }

    actual fun decodeImage(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? =
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    actual fun openFolder(dir: File): Boolean =
        runCatching { java.awt.Desktop.getDesktop().open(dir) }.isSuccess

    actual fun captureScreen(home: File): HostCapture {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return HostCapture("当前环境没有屏幕（headless），截不了图。", null, null)
        }
        return try {
            // 截「应用自己的窗口」而不是整块屏幕：用户桌面上有别的东西，
            // 模型看到别人的聊天窗口既没用又越界。找不到窗口才退回全屏。
            val bounds = appWindowBounds()
            val rect = CaptureRect(bounds.x, bounds.y, bounds.width, bounds.height)
            val image = java.awt.Robot().createScreenCapture(bounds)
            val dir = File(home, "diag/screenshots").apply { mkdirs() }
            val file = File(dir, "shot-" + System.currentTimeMillis() + ".png")
            ImageIO.write(image, "png", file)
            HostCapture(
                "截图已保存：" + file.absolutePath +
                    "（" + rect.width + "×" + rect.height + " 像素，click 的坐标从这张图左上角算）",
                downscaledDataUrl(image),
                rect,
            )
        } catch (t: Throwable) {
            HostCapture("截图失败：" + (t.message ?: (t::class.simpleName ?: "未知错误")), null, null)
        }
    }

    actual fun clickScreen(rect: CaptureRect, x: Int, y: Int): String {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return "当前环境没有屏幕（headless），点不了。"
        }
        if (x < 0 || y < 0 || x >= rect.width || y >= rect.height) {
            return "坐标 (" + x + ", " + y + ") 不在最近那张截图里（" +
                rect.width + "×" + rect.height + "）。重新截图再算坐标。"
        }
        return try {
            val robot = java.awt.Robot()
            robot.mouseMove(rect.x + x, rect.y + y)
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            robot.delay(60)
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            "点了截图坐标 (" + x + ", " + y + ")"
        } catch (t: Throwable) {
            "点不动：" + (t.message ?: (t::class.simpleName ?: "未知错误"))
        }
    }

    actual fun chooseExportFile(defaultName: String): File? {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "导出 Markdown", java.awt.FileDialog.SAVE)
        dialog.file = defaultName
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return File(dir, name)
    }

    private fun appWindowBounds(): java.awt.Rectangle {
        val window = java.awt.Window.getWindows()
            .firstOrNull { it.isVisible && it.width > 300 && it.height > 200 }
        if (window != null) return window.bounds
        val size = java.awt.Toolkit.getDefaultToolkit().screenSize
        return java.awt.Rectangle(0, 0, size.width, size.height)
    }

    actual fun resizePointerIcon(): androidx.compose.ui.input.pointer.PointerIcon =
        androidx.compose.ui.input.pointer.PointerIcon(
            java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR),
        )

    /** 缩到最长边 1280 再转 data URL（OpenAI 兼容的 image_url 格式）。 */
    private fun downscaledDataUrl(image: java.awt.image.BufferedImage): String? = try {
        val maxSide = 1_280
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(image.width, image.height))
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        val scaled = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        val bytes = ByteArrayOutputStream()
        ImageIO.write(scaled, "png", bytes)
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())
    } catch (t: Throwable) {
        null
    }
}
