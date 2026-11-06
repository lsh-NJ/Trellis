package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 离屏渲染整个界面，并存成 PNG。
 *
 * 两个作用：
 *  1. 证明界面**能画出来而不抛异常**  这件事在纯逻辑测试里验证不到
 *  2. 产出一张可以直接看的图（`app/desktop/build/ui-shot*.png`），
 *     在没有显示器的环境里也能知道它长什么样
 *
 * 它不需要显示器：`ImageComposeScene` 走的是软件光栅化。
 *
 * 2026-10-28 起，截图**走真实的 [AppTheme]**（浅色 + 深色两张）。
 * 之前这里套的是裸 `MaterialTheme {}`，用的是 Material3 的默认紫，
 * 和用户实际看到的配色不是一回事 —— 拿它评审界面会被带偏。
 */
class UiRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        runtime: AppRuntime,
        dark: Boolean,
        width: Int = 1180,
        height: Int = 820,
    ): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            AppTheme(
                prefs = runtime.uiPrefs,
                themeScheme = runtime.themeScheme(dark),
                dark = dark,
                chrome = runtime.chromeStyle(),
                extra = runtime.extraColors(dark),
            ) {
                AppScreen(runtime)
            }
        }
        val image = scene.render()
        scene.close()
        return image.encodeToData()?.bytes ?: error("PNG 编码失败")
    }

    private fun write(name: String, bytes: ByteArray) {
        val out = File("build/$name")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("  截图已写出: " + out.absolutePath + "  (" + bytes.size + " 字节)")
    }

    @Test
    fun rendersTheWholeScreenOffscreen() {
        val home = Files.createTempDirectory("trellis-ui").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }

        runBlocking {
            runtime.addTask("第 1 章 计算机组成")
            runtime.addTask("第 2 章 操作系统")
            runtime.addTask("整理错题")
            runtime.toggle(runtime.tasks.last())
            runtime.logTime(runtime.tasks.last(), 45)
        }

        // 任务页：浅色 + 深色（这两张是评审时最常看的主界面）。
        runtime.currentTab = TAB_TASKS
        val light = render(runtime, dark = false)
        val dark = render(runtime, dark = true)
        write("ui-shot.png", light)
        write("ui-shot-dark.png", dark)
        assertTrue(light.size > 5_000, "PNG 太小了，八成是空白画面：" + light.size)
        assertTrue(dark.size > 5_000, "深色截图太小了，八成是空白画面：" + dark.size)

        // 计划页 / 对话页也各留一张：外壳组件（按钮、输入框、卡片）是三个页签共用的，
        // 只拍任务页的话，改外壳时另外两页什么时候坏掉没人知道。
        for ((tab, name) in listOf(TAB_PLAN to "ui-shot-plan.png", TAB_CHAT to "ui-shot-chat.png")) {
            runtime.currentTab = tab
            val shot = render(runtime, dark = false)
            write(name, shot)
            assertTrue(shot.size > 5_000, name + " 太小了，八成是空白画面：" + shot.size)
        }
        // 设置页也留一张：2026-11-03 清理过版式（单列扁平段 + 发丝线，不再套 Material 卡片），
        // 这是用户最常说的「和其他页不像同一套 UI」的地方，改完要能肉眼核对。
        for ((dark, name) in listOf(false to "ui-shot-settings.png", true to "ui-shot-settings-dark.png")) {
            runtime.currentTab = TAB_SETTINGS
            val shot = render(runtime, dark = dark)
            write(name, shot)
            assertTrue(shot.size > 5_000, name + " 太小了，八成是空白画面：" + shot.size)
        }

        runtime.currentTab = TAB_TASKS

        runBlocking { runtime.shutdown() }
    }

    /**
     * 2026-11 批 1 的验收材料：设置页「外观」和两套内置主题的对比。
     *
     * 拍高一点（1500）——外观现在有五组选项（主题/深浅色/字号/密度/卡片/圆角/导航），
     * 820 只够看到主题那一组，评审时看不出「五组是不是一样的手感」。
     * 两套主题都拍，因为默认主题还没定：Bento 是旧气质，书桌是新样板，
     * 交给用户并排看（决策 1）。
     */
    @Test
    fun rendersTheNewAppearanceOptionsInBothThemesAndDensities() {
        val home = Files.createTempDirectory("trellis-ui-appearance").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }

        // 深浅色跟着系统走，这里强制浅色/深色各拍一张
        runtime.updateUiPrefs(runtime.uiPrefs.copy(appearance = "light"))
        runtime.currentTab = TAB_SETTINGS

        // 默认已经换成书桌了，但两套都要拍：Bento 还是可选主题，得证明它仍然能用。
        for ((key, name) in listOf("dev.bento.shift/bento" to "bento", DESK_THEME_KEY to "desk")) {
            val result = runtime.applyTheme(key)
            assertTrue(result.startsWith("界面偏好已更新"), result)
            assertEquals(key, UiPrefsStore(home).load().theme, "主题没有落盘")

            for (dark in listOf(false, true)) {
                val shot = render(runtime, dark = dark, height = 1500)
                val file = "ui-shot-appearance-" + name + (if (dark) "-dark" else "") + ".png"
                write(file, shot)
                assertTrue(shot.size > 5_000, file + " 太小了，八成是空白画面：" + shot.size)
            }
        }

        // 密度：外观行高应该真的跟着 32/40/48 变，不是只改一个枚举值
        runtime.applyTheme(DEFAULT_THEME)
        for (density in DENSITIES) {
            runtime.updateUiPrefs(runtime.uiPrefs.copy(density = density))
            assertEquals(density, UiPrefsStore(home).load().density, "密度没有落盘")
            val shot = render(runtime, dark = false, height = 1500)
            write("ui-shot-density-" + density + ".png", shot)
            assertTrue(shot.size > 5_000, "密度 " + density + " 的截图太小了：" + shot.size)
        }

        runtime.updateUiPrefs(runtime.uiPrefs.copy(density = "normal"))
        runtime.currentTab = TAB_TASKS
        runBlocking { runtime.shutdown() }
    }

    /**
     * 组件套件的样张：批次 2 新增/统一的按钮、选择片、列表行、输入框和页头一次性画出来。
     *
     * 这张图是给评审看的「组件长什么样」，也是新组件的冒烟测试：只要有一个组件在
     * 离屏渲染里抛异常，测试就红。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderSheet(runtime: AppRuntime, dark: Boolean): ByteArray {
        val scene = ImageComposeScene(width = 760, height = 720, density = Density(1f)) {
            AppTheme(
                prefs = runtime.uiPrefs,
                themeScheme = runtime.themeScheme(dark),
                dark = dark,
                chrome = runtime.chromeStyle(),
                extra = runtime.extraColors(dark),
            ) {
                ComponentSheet()
            }
        }
        val image = scene.render()
        scene.close()
        return image.encodeToData()?.bytes ?: error("PNG 编码失败")
    }

    @Test
    fun rendersTheComponentSheetInBothThemes() {
        val home = Files.createTempDirectory("trellis-ui").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        runtime.updateUiPrefs(runtime.uiPrefs.copy(radius = FOLLOW_THEME_RADIUS, appearance = "light"))

        for ((theme, name) in listOf(DESK_THEME_KEY to "desk", "dev.bento.shift/bento" to "bento")) {
            runtime.applyTheme(theme)
            assertEquals(theme, runtime.uiPrefs.theme, "主题没切过去")
            val shot = renderSheet(runtime, dark = false)
            write("ui-shot-components-" + name + ".png", shot)
            assertTrue(shot.size > 5_000, "组件样张太小了，八成是空白画面：" + shot.size)
        }

        runBlocking { runtime.shutdown() }
    }
}

/** 批次 2 的组件样张内容。 */
@androidx.compose.runtime.Composable
private fun ComponentSheet() {
    val tokens = LocalTrellisTokens.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(tokens.space.xl),
        verticalArrangement = Arrangement.spacedBy(tokens.space.s),
    ) {
        ChromePageHeader(title = "组件套件", subtitle = "批 2 · 按钮 / 选择片 / 列表行 / 输入 / 页头")

        ChromeSectionHeader("按钮")
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.space.s)) {
            ChromeButton("主要动作", onClick = {})
            ChromeButton("次要动作", onClick = {}, filled = false)
            ChromeButton("危险动作", onClick = {}, destructive = true)
            ChromeTextLink("文字链接", onClick = {})
        }

        ChromeSectionHeader("选择片")
        Row(horizontalArrangement = Arrangement.spacedBy(tokens.space.s)) {
            ChromeChip("全部", selected = true, onClick = {})
            ChromeChip("今天", onClick = {})
            ChromeChip("本周", onClick = {})
            ChromeChip("禁用", enabled = false, onClick = {})
        }

        ChromeSectionHeader("列表行")
        ChromeListRow(title = "标准行", subtitle = "副标题说明这件事的上下文")
        ChromeListRow(title = "选中行", selected = true, subtitle = "行首竖条 + 紧跟标签的 ✓")
        ChromeListRow(title = "禁用行", enabled = false)
        ChromeListRow(
            title = "带尾部件",
            trailing = { ChromeTextLink("详情", onClick = {}) },
        )

        ChromeSectionHeader("输入")
        ChromeTextField(value = "", onValueChange = {}, placeholder = "比如：周三前把方案发出去")

        Spacer(Modifier.height(tokens.space.s))
        ChromeButton("保存", onClick = {})
    }
}
