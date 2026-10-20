package dev.trellis.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * 落地一个生成的插件。返回 null 表示成功，返回字符串表示失败原因。
 *
 * 抽成接口是为了**能测循环**：测试里塞一个「第一次失败第二次成功」的假安装器，
 * 就能验证重试逻辑，完全不需要网络、不需要文件系统。
 */
interface PluginInstaller {
    suspend fun install(files: Map<String, String>): String?
}

/** 循环过程中发生的事，给界面显示进度用。 */
sealed interface AgentEvent {
    data class RequestingModel(val attempt: Int, val maxAttempts: Int) : AgentEvent
    data class ModelReplied(val raw: String) : AgentEvent
    data class SelfCheckFailed(val problems: List<String>) : AgentEvent
    data class InstallFailed(val reason: String) : AgentEvent
    data class Installed(val pluginId: String) : AgentEvent
    data class Refused(val reason: String) : AgentEvent
}

/** 一次生成的最终结果。 */
sealed interface AgentOutcome {
    data class Installed(val pluginId: String, val attempts: Int) : AgentOutcome

    /**
     * 模型**主动拒绝**：这个需求超出了声明式插件能做的范围。
     *
     * 这是好结局，不是失败：它比生成一张假装做到事情的卡片强得多。
     */
    data class Refused(val reason: String, val attempts: Int) : AgentOutcome

    /** 放弃。带着最后一轮的毛病，方便界面告诉用户「它卡在哪」。 */
    data class GaveUp(val attempts: Int, val problems: List<String>) : AgentOutcome
}

/** 单轮的结局。成功带 id，失败带病灶。 */
private sealed interface Attempt {
    data class Ok(val pluginId: String) : Attempt
    data class Refused(val reason: String) : Attempt
    data class Bad(val problems: List<String>) : Attempt
}

/**
 * 「一句话 -> 能装上的插件」的最小闭环。
 *
 * ```
 * 模型 -> 抠 JSON -> 本地自查 -> 落地安装
 *   ^                                  |
 *   +----------- 把毛病喂回去 ---------+
 * ```
 *
 * ### 为什么有两道闸
 *
 * **本地自查**（[AuthoredPlugin.validate]）在写任何文件之前就把格式、挂载点、
 * 数据字段挡掉 —— 这类错误占绝大多数，而且不用付一次磁盘 I/O 和一次装载。
 *
 * **落地安装**是第二道：只有本地查不出来的问题才会走到这儿
 * （比如插件 id 和已有的撞了、内核那边的依赖解析失败）。
 *
 * 两道闸的报错都会**原样喂回模型**。所以报错必须是模型能照着改的话：
 * 说清楚哪一项不对、正确选项有哪些。
 */
class PluginAgent(
    private val llm: LlmClient,
    private val catalog: CapabilityCatalog,
    /** 已经装着的插件。不传给模型，它就不知道哪些点子已经被占了。 */
    private val existing: List<ExistingPlugin> = emptyList(),
    private val installer: PluginInstaller,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val json: Json = DEFAULT_JSON,
) {

    suspend fun run(
        request: String,
        onEvent: suspend (AgentEvent) -> Unit = {},
    ): AgentOutcome {
        val messages = mutableListOf(
            LlmMessage(LlmRole.System, PromptBuilder.system(catalog, existing)),
            LlmMessage(LlmRole.User, PromptBuilder.user(request)),
        )

        var lastProblems: List<String> = emptyList()

        for (attempt in 1..maxAttempts) {
            onEvent(AgentEvent.RequestingModel(attempt, maxAttempts))

            val raw = llm.complete(messages)
            onEvent(AgentEvent.ModelReplied(raw))
            messages += LlmMessage(LlmRole.Assistant, raw)

            when (val result = attemptOnce(raw)) {
                is Attempt.Ok -> {
                    onEvent(AgentEvent.Installed(result.pluginId))
                    return AgentOutcome.Installed(result.pluginId, attempt)
                }

                is Attempt.Refused -> {
                    onEvent(AgentEvent.Refused(result.reason))
                    return AgentOutcome.Refused(result.reason, attempt)
                }

                is Attempt.Bad -> {
                    lastProblems = result.problems
                    val installFailure = result.problems
                        .firstOrNull { it.startsWith(INSTALL_PREFIX) }
                        ?.removePrefix(INSTALL_PREFIX)

                    if (installFailure != null) {
                        onEvent(AgentEvent.InstallFailed(installFailure))
                    } else {
                        onEvent(AgentEvent.SelfCheckFailed(result.problems))
                    }
                    messages += LlmMessage(LlmRole.User, feedback(result.problems))
                }
            }
        }

        return AgentOutcome.GaveUp(maxAttempts, lastProblems)
    }

    private suspend fun attemptOnce(raw: String): Attempt {
        val jsonText = JsonExtraction.extractFirstObject(raw)
            ?: return Attempt.Bad(
                listOf(
                    "你的输出里没有找到 JSON 对象。请只输出一个 JSON 对象，" +
                        "不要解释，不要用 ``` 围栏。",
                ),
            )

        val authored = try {
            json.decodeFromString<AuthoredPlugin>(jsonText)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return Attempt.Bad(
                listOf(
                    "JSON 结构不对：" + (t.message ?: "解析失败") +
                        "。请对照格式说明检查字段名和类型（order 必须是数字）。",
                ),
            )
        }

        if (authored.isRefusal) return Attempt.Refused(authored.cannotDo.orEmpty())

        val problems = authored.validate(catalog) + duplicatesOf(authored)
        if (problems.isNotEmpty()) return Attempt.Bad(problems)

        val installError = installer.install(authored.toFiles())
        if (installError != null) return Attempt.Bad(listOf(INSTALL_PREFIX + installError))

        return Attempt.Ok(authored.id)
    }

    /**
     * 名字撞了就问回去。
     *
     * 查的是**名字**，不是功能 —— 功能是否重复只有人和模型看得出来，内核没有那个判断力。
     * 名字重复是个便宜的代理信号：模型想重做一个东西时，几乎总会沿用同一个名字。
     * 反过来不成立（换个名字做同一件事），那种情况只能靠提示词里那份清单挡。
     */
    private fun duplicatesOf(authored: AuthoredPlugin): List<String> =
        existing.filter { it.name == authored.name }.map {
            "已经有一个叫「" + it.name + "」的插件（" + it.id + "），做的是「" + it.description + "」。" +
                "如果新的和它重复，请改用 {\"cannotDo\": \"...\"} 说明；" +
                "如果确实不一样，换一个能区分开的名字。"
        }

    private fun feedback(problems: List<String>): String = buildString {
        appendLine("上一次的输出有问题，不能直接使用：")
        for (p in problems) appendLine("- " + p.removePrefix(INSTALL_PREFIX))
        appendLine()
        append("请修正后重新输出**完整的** JSON 对象，不要解释，不要用 ``` 围栏。")
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3

        /** 给「落地失败」打一个前缀，好和「自查失败」分开报给界面。 */
        private const val INSTALL_PREFIX = "[安装] "

        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
