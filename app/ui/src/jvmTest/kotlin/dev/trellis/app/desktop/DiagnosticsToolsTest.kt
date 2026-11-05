package dev.trellis.app.desktop

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「AI 自己查错」的手：工具报环境类错误时，模型要先能看到具体原因，而不是让用户自己修。
 *
 * 真实事故：打包版漏了 `java.sql`，计划库打不开，模型只能对用户说「你去看看错误提示」——
 * 用户看到的是一行 `java/sql/DriverManager`，完全无从下手。
 */
class DiagnosticsToolsTest {

    @Test
    fun theToolReturnsWhateverTheRuntimeReported() = runTest {
        val report = "【Trellis 诊断】\nJDK 模块自检：\n  - java.sql（SQLite 驱动）：缺失 ← 就是它"
        val tool = DiagnosticsTools { report }.all.single()

        assertEquals("get_diagnostics", tool.name)
        val result = tool.call(buildJsonObject { })

        assertTrue(!result.isError, result.content)
        assertEquals(report, result.content)
    }

    @Test
    fun aBrokenReportIsAnErrorWithAHumanMessage() = runTest {
        val tool = DiagnosticsTools { error("读不了 home 目录") }.all.single()

        val result = tool.call(buildJsonObject { })

        assertTrue(result.isError)
        assertTrue(result.content.contains("读诊断信息失败"), result.content)
        assertTrue(result.content.contains("读不了 home 目录"), result.content)
    }

    /** 提示词里必须写清「先自查再看用户能不能修」，否则模型还是会把错误原样丢回去。 */
    @Test
    fun theGuidanceTellsTheModelToLookFirstAndNotDumpErrorsOnTheUser() {
        val guidance = DiagnosticsTools { "" }.guidance

        assertTrue(guidance.contains("get_diagnostics"))
        assertTrue(guidance.contains("环境问题"))
        assertTrue(guidance.contains("用户"))
        assertTrue(guidance.contains("自己看"), guidance)
    }
}
