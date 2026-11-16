package dev.trellis.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 打包运行时的前提，写成测试是因为**开发机（完整 JDK）永远踩不到**：
 *
 * jpackage/jlink 按 jdeps 推断模块，JavaFX 的反射/JNI 依赖推断不出来，段落式编辑器在
 * 打出来的 .app 里依次表现为：白板 + 整页点不动（缺 jdk.unsupported.desktop / jdk.unsupported）、
 * executeScript 直接 SIGSEGV（缺 jdk.jsobject）。四个模块都必须手写进 packaging 配置。
 * 另外启动前的探测要能识别缺失，否则界面会停在一个半死的 JavaFX 上。
 */
class MarkdownWebRuntimeModuleTest {

    @Test
    fun `开发机 JDK 带 jdk swing interop`() {
        assertFalse(MarkdownWebRuntime.javafxSwingInteropMissing())
    }

    @Test
    fun `缺模块时探测得到失败`() {
        assertTrue(
            MarkdownWebRuntime.javafxSwingInteropMissing {
                throw NoClassDefFoundError("jdk/swing/interop/SwingInterOpUtils")
            },
        )
    }

    @Test
    fun `打包配置声明了 WebView 需要的 jdk 模块`() {
        val repo = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
        val root = repo ?: error("找不到仓库根目录（settings.gradle.kts）")
        val build = File(root, "app/desktop/build.gradle.kts").readText()
        val required = listOf(
            "jdk.unsupported.desktop" to "JFXPanel 的鼠标/键盘事件桥（jdk.swing.interop）",
            "jdk.jsobject" to "WebKit 的 netscape.javascript.JSObject（executeScript 缺了直接 SIGSEGV）",
            "jdk.xml.dom" to "javafx.web 声明的 DOM 依赖",
            "jdk.unsupported" to "WebView 软件绘制的 sun.misc.Unsafe（缺了页面是白板）",
        )
        val missing = required.filter { (module, _) -> "\"$module\"" !in build }
        assertTrue(
            missing.isEmpty(),
            "app/desktop/build.gradle.kts 的 modules(...) 少了：" +
                missing.joinToString("、") { (module, why) -> "$module（$why）" },
        )
    }
}
