package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * 给 AI 的「自己查错」的手。
 *
 * 为什么需要它：计划库打不开这类错误（典型是打包版缺 `java.sql` 模块导致的
 * `NoClassDefFoundError: java/sql/DriverManager`）用户根本修不好——让用户去「看一眼错误提示」
 * 等于把问题丢回去。AI 应该先自己看：到底哪个库打不开、缺哪个 JDK 模块、最近 app.log 里有什么，
 * 再决定是重试、绕开，还是给用户一个真能执行的修复动作（比如「这个版本打包漏了模块，需要重新打包」）。
 *
 * 只读：不修文件、不重启、不改配置。能力面越小越好。
 */
class DiagnosticsTools(private val report: () -> String) {

    val guidance: String = listOf(
        "<how_to_diagnose>",
        "1. 工具返回错误时，先分清是「用户填的内容不对」还是「环境问题」：",
        "   计划库打不开、数据库坏、缺 JDK 模块、LLM 没配、网络不通 —— 这些都是环境问题。",
        "2. 环境问题先调 get_diagnostics 拿具体原因（它会报计划库错误、JDK 模块自检、app.log 最近 20 行、",
        "   LLM 是否配置），不要让别人自己看错误提示。",
        "3. 看完再决定：能重试就重试；能换个做法就绕开；只有用户真能执行的修复动作才让用户做，",
        "   并且说清「做什么、在哪做、做完怎么验证」。",
        "4. 不确定原因时，把诊断里的关键一行原样引用给用户，别用「系统错误」这种话糊过去。",
        "</how_to_diagnose>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(GetDiagnostics())

    private inner class GetDiagnostics : AgentTool {
        override val name: String = "get_diagnostics"
        override val description: String =
            "读应用诊断信息：计划库为什么打不开、JDK 运行时缺哪些模块、app.log 最近日志、LLM 是否配置、活动库状态。" +
                "任何工具报环境类错误（数据库/模块/配置/网络）时先调它，再决定怎么修或怎么绕。"

        override val parameters: String = "不需要参数。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val outcome = runCatching { report() }
            return outcome.fold(
                onSuccess = { ToolResult(it) },
                onFailure = { error ->
                    ToolResult(
                        "读诊断信息失败：" + (error.message ?: error::class.simpleName ?: "未知错误"),
                        isError = true,
                    )
                },
            )
        }
    }
}
