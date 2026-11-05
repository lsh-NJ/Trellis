package dev.trellis.app.desktop

import dev.trellis.kernel.extension.KernelPoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 内置主题包也得过宿主那三道闸（词表 / 色值 / 对比度）。
 *
 * 它现在随代码发布，改坏一次就是**所有人**的界面一起坏，所以比外部主题更需要
 * 这道测试：外部主题校验不过只会被拒收，内置的校验不过会变成发版事故。
 */
class BentoThemePluginTest {

    private val plugin = BentoThemePlugin()

    @Test
    fun theManifestDeclaresTheThemeContribution() {
        assertEquals("dev.bento.shift", plugin.manifest.id.value)
        assertTrue(plugin.manifest.builtin, "默认主题必须内置，不能依赖用户目录里的脚本")
        val declared = plugin.manifest.contributes[KernelPoints.Theme.id].orEmpty().map { it.id }
        assertEquals(listOf("bento"), declared)
    }

    @Test
    fun theThemePassesEveryHostCheck() {
        val theme = plugin.theme
        assertEquals("bento", theme.id)
        val problems = themeProblems(theme.light, theme.dark) +
            chromeProblems(theme) +
            extraProblems(theme.extra, theme.light)
        assertTrue(problems.isEmpty(), problems.toString())
    }

    @Test
    fun theThemeResolvesToAScheme() {
        val scheme = themeSchemeOf(plugin.theme)
        assertNotNull(scheme)
        assertEquals("#141414", hexOf(scheme.light.primary).uppercase())
        assertEquals("#D6F24B", hexOf(scheme.dark.primary).uppercase())
    }
}
