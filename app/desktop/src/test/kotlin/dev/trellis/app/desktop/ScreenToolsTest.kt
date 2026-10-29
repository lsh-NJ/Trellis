package dev.trellis.app.desktop

import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 眼睛和手的接线测试。
 *
 * 钉三件事：描述认得当前页、没截图不许点、没屏幕时好好说话而不是崩。
 * 真去点按钮、真去截图属于手工验收 —— 测试能覆盖的只到「接口接线」。
 */
class ScreenToolsTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-screen-", "").let {
            it.delete(); it.mkdirs(); tempDirs += it; it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private suspend fun tool(runtime: AppRuntime, name: String) =
        ScreenTools(runtime).all.first { it.name == name }

    @Test
    fun theDescriptionSaysWhichPageTheUserIsOn() = runTest {
        val runtime = AppRuntime.create(tempHome())
        runtime.currentTab = TAB_PLUGINS

        val text = tool(runtime, "describe_screen").call(JsonObject(emptyMap())).content

        assertTrue(text.contains("当前页：插件"), "描述里得先说清在哪一页：\n" + text)
        assertTrue(text.contains("设置页分组"), "插件页上真正渲染的挂载点也要报出来：\n" + text)
    }

    @Test
    fun clickingBeforeAScreenshotIsRefused() = runTest {
        val runtime = AppRuntime.create(tempHome())

        val result = tool(runtime, "click").call(
            buildJsonObject {
                put("x", 10)
                put("y", 10)
            },
        )

        if (GraphicsEnvironment.isHeadless()) {
            // 无屏幕时连鼠标都不该碰：headless 分支先被拦住，这也是「不乱点」的一种。
            assertTrue(
                result.content.contains("headless"),
                "无屏幕时要先说是 headless，而不是让它以为只是缺截图：" + result.content,
            )
        } else {
            assertTrue(result.content.contains("先 take_screenshot"), "没截图不该让鼠标乱跑：" + result.content)
        }
    }

    @Test
    fun screenshotsExplainThemselvesInsteadOfCrashing() = runTest {
        val runtime = AppRuntime.create(tempHome())

        val text = tool(runtime, "take_screenshot").call(JsonObject(emptyMap())).content

        assertTrue(text.isNotBlank(), "截图工具必须给一句话，哪怕是拒绝")
        if (GraphicsEnvironment.isHeadless()) {
            assertTrue(text.contains("headless"), "无屏幕时要说是 headless：" + text)
        } else {
            assertTrue(
                text.startsWith("截图已保存") || text.startsWith("截图失败"),
                "有屏幕时要么真存了一张、要么说清为什么失败：" + text,
            )
        }
    }

    @Test
    fun openTabSwitchesThePageInsteadOfGuessingCoordinates() = runTest {
        val runtime = AppRuntime.create(tempHome())

        val moved = tool(runtime, "open_tab").call(buildJsonObject { put("tab", "插件") })
        assertTrue(moved.content.contains("插件"), "切页要说清切到哪了：" + moved.content)
        assertEquals(TAB_PLUGINS, runtime.currentTab)

        val unknown = tool(runtime, "open_tab").call(buildJsonObject { put("tab", "火星") })
        assertTrue(unknown.isError, "不存在的页要当场拒绝")
    }
}
