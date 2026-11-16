package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * H1 回归 harness 的入口。
 *
 * 默认 **不执行**：只有 TRELLIS_WEB_HARNESS=1 时才启动 JavaFX WebView、跑夹具、写基线。
 * 这样日常 `./gradlew test` / CI 在无显示、无 JavaFX natives 的机器上不会因为一条
 * 环境用例挂掉（要求是 SKIP 而不是 FAIL）。
 *
 * 需要真跑时（模块目录下）：
 *
 *   export TRELLIS_WEB_HARNESS=1
 *   ./gradlew :app:ui:jvmTest --tests 'dev.trellis.app.desktop.WebEditorHarnessTest' --console=plain
 *
 * 产出：
 *   - app/ui/build/web-editor/baseline-current.json
 *   - app/ui/build/web-editor/baseline-current.png
 */
class WebEditorHarnessTest {

    @Test
    fun recordsBaselineWithTheCurrentBundle() {
        if (System.getenv("TRELLIS_WEB_HARNESS") != "1") {
            println("[web-editor-harness] 跳过（未设置 TRELLIS_WEB_HARNESS=1）：默认 jvmTest 不启动 JavaFX。")
            return
        }

        val ready = try {
            MarkdownWebRuntime.ensureReadyBlocking()
        } catch (t: Throwable) {
            println("[web-editor-harness] JavaFX 启动抛异常，按环境不可用跳过：${t.javaClass.name}: ${t.message}")
            false
        }
        if (!ready) {
            println("[web-editor-harness] SKIP：JavaFX 不可用（${MarkdownWebRuntime.failureMessage() ?: "原因未知"}）")
            return
        }

        val report = WebEditorHarness.run() ?: run {
            println("[web-editor-harness] SKIP：环境创建不出 WebView 窗口，baseline 未产出。")
            return
        }

        assertTrue(report.fixtureCount >= 12, "夹具太少：${report.fixtureCount}")
        assertTrue(report.jsonFile.isFile, "没有产出 ${report.jsonFile}")
        assertTrue(report.pngFile.isFile, "没有产出 ${report.pngFile}")
        println(
            "[web-editor-harness] 完成：${report.fixtureCount} 个夹具 → " +
                "${report.jsonFile.absolutePath} / ${report.pngFile.absolutePath}",
        )
    }
}
