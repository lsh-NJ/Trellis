package dev.trellis.ai.manager

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.tool.AgentTool

/**
 * 一句话进来，先定三件事：**是什么意图（单选）→ 用哪套工具（单选）→ 拼哪套提示词**。
 *
 * 顺序是刻意的，也是被真实事故逼出来的：
 *
 * 1. 意图没定下来就拼提示词，等于把所有工具目录都塞给模型 —— 它会挑一个
 *    「看起来像在干活」的，于是「我要速通操作系统」变成了装插件；
 * 2. 工具不按意图裁，提示词里的「别造插件」只是劝，劝不住就还是造；
 * 3. 意图有歧义时不先问，模型拿一半的把握去动用户的数据，事后只能回滚。
 *
 * 所以这里把「选工具」和「拼提示词」都做成按意图的单选：每套工具只属于
 * 一个意图（少数通用工具属于几套），提示词也只列这套工具。
 */
class ManagerRouter(
    private val classifier: IntentClassifier = KeywordIntentClassifier,
    private val toolsFor: (AgentIntent) -> List<AgentTool>,
    private val promptFor: (AgentIntent) -> ManagerPrompt,
) {

    /** 单个拼装器：一份提示词 + 全套工具（测试和老调用点用）。 */
    constructor(prompt: ManagerPrompt, allTools: List<AgentTool>, classifier: IntentClassifier = KeywordIntentClassifier) : this(
        classifier = classifier,
        toolsFor = { intent -> toolsForIntent(intent, allTools) },
        promptFor = { prompt },
    )

    /** 按意图重建提示词：每条工具的目录只出现在它该出现的那一版里。 */
    constructor(
        catalog: CapabilityCatalog,
        allTools: List<AgentTool>,
        guidance: String = "",
        classifier: IntentClassifier = KeywordIntentClassifier,
    ) : this(
        classifier = classifier,
        toolsFor = { intent -> toolsForIntent(intent, allTools) },
        promptFor = { intent ->
            ManagerPrompt(ManagerPrompt.buildBase(catalog, toolsForIntent(intent, allTools), guidance))
        },
    )

    fun decide(request: String): IntentDecision = classifier.decide(request)

    fun tools(intent: AgentIntent): List<AgentTool> = toolsFor(intent)

    fun prompt(intent: AgentIntent): ManagerPrompt = promptFor(intent)
}

/**
 * 工具归属表：一个工具属于哪些意图。
 *
 * 没列到的工具默认只给「造/改插件」和「管插件」—— 宁可少给一只手，
 * 也不要让「整理待办」这种活儿长出 `deploy`。
 */
private val TOOL_INTENTS: Map<String, Set<AgentIntent>> = mapOf(
    // 插件生命周期
    "list_plugins" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin),
    "list_events" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin),
    "list_actions" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin),
    "read_plugin" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin),
    "disable_plugin" to setOf(AgentIntent.ManagePlugin),
    "remove_plugin" to setOf(AgentIntent.ManagePlugin),
    "try_plugin" to setOf(AgentIntent.CreatePlugin),
    "deploy" to setOf(AgentIntent.CreatePlugin),

    // 眼睛和手（调试插件、看界面）
    "describe_screen" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin, AgentIntent.ArrangeUi),
    "take_screenshot" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin, AgentIntent.ArrangeUi),
    "open_tab" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin),
    "click" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin),
    "type_text" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin),
    "press_key" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin),

    // 布局与样式
    "list_cards" to setOf(AgentIntent.ArrangeUi),
    "arrange_cards" to setOf(AgentIntent.ArrangeUi),
    "list_ui_options" to setOf(AgentIntent.ArrangeUi),
    "set_ui" to setOf(AgentIntent.ArrangeUi),
    "set_plugin_card" to setOf(AgentIntent.ArrangeUi),
    "list_themes" to setOf(AgentIntent.ArrangeUi),
    "set_theme" to setOf(AgentIntent.ArrangeUi),

    // 外部资源与待办
    "search_bilibili" to setOf(AgentIntent.FindResource),
    "list_bilibili_parts" to setOf(AgentIntent.FindResource),
    "add_tasks" to setOf(AgentIntent.FindResource),
    // 计划页：用户点「排成学习计划」时要真的写进计划库，不是只写这一轮的动作清单
    "create_learning_plan" to setOf(AgentIntent.FindResource),
    // 长期目标：只在「找资源 / 排学习」这一类里出现。聊天意图按设计不给任何工具，
    // 所以「我想考研」这种话必须被判成 FindResource 才走得到这里（见 Intent.kt 的 resourceWords）。
    "goal_plan" to setOf(AgentIntent.FindResource),

    // 清单：多步活都要，聊天不要
    "set_plan" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin, AgentIntent.ArrangeUi, AgentIntent.FindResource),
    "step_done" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin, AgentIntent.ArrangeUi, AgentIntent.FindResource),

    // 让用户拍板：任何会改数据的意图都可能需要，所以四套都给它
    "ask_choices" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin, AgentIntent.ArrangeUi, AgentIntent.FindResource),
)

private val DEFAULT_INTENTS: Set<AgentIntent> = setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin)

/** 按意图选工具（单选）：不在归属表里的工具按 [DEFAULT_INTENTS] 处理。 */
internal fun toolsForIntent(intent: AgentIntent, all: List<AgentTool>): List<AgentTool> {
    if (intent == AgentIntent.Chat) return emptyList()
    return all.filter { tool ->
        (TOOL_INTENTS[tool.name] ?: DEFAULT_INTENTS).contains(intent)
    }
}
