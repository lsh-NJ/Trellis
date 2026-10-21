package dev.trellis.ai.flow

import dev.trellis.ai.AgentEvent
import dev.trellis.ai.AgentOutcome
import dev.trellis.ai.AuthoredPlugin
import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.ExistingPlugin
import dev.trellis.ai.JsonExtraction
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import dev.trellis.ai.PluginInstaller
import dev.trellis.ai.PromptBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * 「一句话 -> 能装上的插件」的工作流。
 *
 * 三个阶段，写在这里就是三行节点、四条边，没有嵌套回调：
 *   draft  要模型写一版
 *   check  自己试跑一遍，拿到具体哪儿不对
 *   ship   装上去
 * 试跑不过就带着毛病回到 draft，最多 maxRounds 轮。
 *
 * 为什么值得单独抽一层：以前是「模型写完 -> 校验 -> 装」，
 * 装了才发现画错，而那时候人已经坐在电脑前了。试跑把发现错误的时间
 * 从「用户看到的时候」提前到「模型还在的时候」。
 */
class AgentFlow(
    private val llm: LlmClient,
    private val catalog: CapabilityCatalog,
    private val installer: PluginInstaller,
    private val existing: List<ExistingPlugin> = emptyList(),
    private val selfTest: SelfTestTool = SelfTestTool { emptyList() },
    private val maxRounds: Int = 4,
    private val onEvent: suspend (AgentEvent) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val system = PromptBuilder.system(catalog, existing)

    fun graph(): Flow = Flow(
        nodes = mapOf(
            DRAFT to FlowNode { draft(it) },
            CHECK to FlowNode { check(it) },
            SHIP to FlowNode { ship(it) },
        ),
        edges = listOf(
            FlowEdge(DRAFT, CHECK) { it.refusal == null && it.files.isNotEmpty() },
            FlowEdge(CHECK, DRAFT) { it.issues.isNotEmpty() && it.round < maxRounds },
            FlowEdge(CHECK, SHIP) { it.issues.isEmpty() },
            FlowEdge(SHIP, DRAFT) { it.installedId == null && it.round < maxRounds },
        ),
        entry = DRAFT,
    )

    suspend fun run(
        request: String,
        checkpoints: CheckpointSink = NoCheckpoints,
    ): AgentOutcome {
        val start = AgentState(
            messages = listOf(LlmMessage(LlmRole.User, PromptBuilder.user(request))),
        )
        return graph().run(start, checkpoints).outcome()
    }

    private suspend fun draft(state: AgentState): AgentState {
        val round = state.round + 1
        val messages = buildList {
            addAll(state.messages)
            if (state.issues.isNotEmpty()) add(LlmMessage(LlmRole.User, feedback(state.issues)))
        }
        onEvent(AgentEvent.RequestingModel(round, maxRounds))
        val raw = try {
            llm.complete(listOf(LlmMessage(LlmRole.System, system)) + messages)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val issue = Issue("LLM_CALL", "模型", t.message ?: "调用失败")
            return state.copy(round = round, files = emptyMap(), issues = listOf(issue))
                .say("第 " + round + " 轮：模型没理我")
        }
        onEvent(AgentEvent.ModelReplied(raw))

        val jsonText = JsonExtraction.extractFirstObject(raw)
            ?: return state.copy(
                round = round,
                messages = messages + LlmMessage(LlmRole.Assistant, raw),
                files = emptyMap(),
                issues = listOf(Issue("NO_JSON", "输出", "没有找到 JSON 对象", "只输出一个 JSON 对象，不要解释")),
            ).say("第 " + round + " 轮：输出里没有 JSON")

        val authored = try {
            json.decodeFromString<AuthoredPlugin>(jsonText)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return state.copy(
                round = round,
                messages = messages + LlmMessage(LlmRole.Assistant, raw),
                files = emptyMap(),
                issues = listOf(Issue("BAD_JSON", "输出", t.message ?: "解析失败", "字段名和类型要符合说明")),
            ).say("第 " + round + " 轮：JSON 结构不对")
        }

        if (authored.isRefusal) {
            return state.copy(round = round, refusal = authored.cannotDo.orEmpty(), files = emptyMap())
                .say("第 " + round + " 轮：它说做不到")
        }

        val problems = authored.validate(catalog)
        val files = authored.toFiles(catalog)
        val issues = problems.map { Issue("SELF_CHECK", "自检", it) }
        if (issues.isNotEmpty()) onEvent(AgentEvent.SelfCheckFailed(problems))
        return state.copy(
            round = round,
            messages = messages + LlmMessage(LlmRole.Assistant, raw),
            files = files,
            pluginId = authored.id,
            capabilities = authored.capabilities(catalog),
            issues = issues,
        ).say(
            if (issues.isEmpty()) "第 " + round + " 轮：写出来了 " + authored.id
            else "第 " + round + " 轮：自检没过（" + issues.size + " 条）",
        )
    }

    private suspend fun check(state: AgentState): AgentState {
        if (state.refusal != null || state.issues.isNotEmpty()) return state
        val issues = try {
            selfTest.test(state.files)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            listOf(Issue("SELFTEST_CRASH", "试跑", t.message ?: "试跑器自己崩了"))
        }
        if (issues.isNotEmpty()) onEvent(AgentEvent.SelfCheckFailed(issues.map { it.render() }))
        val better = issues.size < state.bestIssues
        return state.copy(
            issues = issues,
            bestIssues = if (better) issues.size else state.bestIssues,
            bestRound = if (better) state.round else state.bestRound,
        ).say(
            if (issues.isEmpty()) "试跑通过"
            else "试跑发现 " + issues.size + " 个问题：" + issues.first().render(),
        )
    }

    private suspend fun ship(state: AgentState): AgentState {
        if (state.pluginId.isEmpty()) return state.say("没有插件 id，装不了")
        val grant = installer.ensureGrants(state.pluginId, state.capabilities)
        if (grant != null) {
            onEvent(AgentEvent.InstallFailed(grant))
            return state.copy(files = emptyMap(), issues = listOf(Issue("GRANT", "权限", grant)))
                .say("权限没批：" + grant)
        }
        val error = installer.install(state.files)
        if (error != null) {
            onEvent(AgentEvent.InstallFailed(error))
            return state.copy(files = emptyMap(), issues = listOf(Issue("INSTALL", "安装", error)))
                .say("装不上：" + error)
        }
        onEvent(AgentEvent.Installed(state.pluginId))
        return state.copy(installedId = state.pluginId).say("装好了：" + state.pluginId)
    }

    private fun feedback(issues: List<Issue>): String = buildString {
        appendLine("上一次的插件不能直接用，试跑/自检报了这些问题：")
        for (issue in issues) appendLine("- " + issue.render())
        appendLine("请重新输出**完整**的插件 JSON（不是补丁），把上面每一条都改掉。")
    }

    private fun AgentState.outcome(): AgentOutcome = when {
        installedId != null -> AgentOutcome.Installed(installedId, round)
        refusal != null -> AgentOutcome.Refused(refusal, round)
        else -> AgentOutcome.GaveUp(
            attempts = maxOf(1, round),
            problems = issues.map { it.render() }.ifEmpty { listOf("模型没有给出可用的插件") },
        )
    }

    private companion object {
        const val DRAFT = "draft"
        const val CHECK = "check"
        const val SHIP = "ship"
    }
}
