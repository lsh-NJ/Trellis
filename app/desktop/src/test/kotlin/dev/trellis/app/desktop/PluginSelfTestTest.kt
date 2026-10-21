package dev.trellis.app.desktop

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MANIFEST = """{"id":"ai.bad","version":"1.0.0","schemaVersion":1,"name":"坏插件","description":"用来证明自检真的会红","loader":"dsl","entry":"main.dsl.json"}"""

class PluginSelfTestTest {

    private val selfTest = PluginSelfTest()

    @Test
    fun aMountPointThatDoesNotExistIsCaughtBeforeInstall() = runTest {
        val files = mapOf(
            "manifest.json" to MANIFEST,
            "main.dsl.json" to """{"slots":[{"id":"s1","point":"slot.nowhere","title":"标题","body":"正文"}]}""",
        )

        val issues = selfTest.test(files)

        assertTrue(issues.isNotEmpty(), "挂了个不存在的挂载点，自检必须报出来")
        assertTrue(issues.any { it.message.contains("slot.nowhere") }, "报错要点名那个错的挂载点：" + issues)
    }

    @Test
    fun aPluginThatParsesReportsNothing() = runTest {
        val files = mapOf(
            "manifest.json" to MANIFEST,
            "main.dsl.json" to """{"slots":[{"id":"s1","point":"slot.home.section","title":"标题","body":"正文"}]}""",
        )

        assertEquals(emptyList(), selfTest.test(files))
    }
}
