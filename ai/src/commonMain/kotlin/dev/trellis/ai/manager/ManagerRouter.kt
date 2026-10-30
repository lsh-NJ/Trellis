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
 *
 * ### 稳定工具集实验（P1，开关 `TRELLIS_MANAGER_STABLE_TOOLS=1`）
 *
 * [stableTools] 打开时，[tools] 对任何意图都返回同一套全量工具（含 Chat），
 * [prompt] 也返回同一份静态前缀。旧路径（按意图单选）原样保留，开关关掉即回滚；
 * 设计背景与迁移阶段见 `docs/agent-harness.md`。
 */
class ManagerRouter(
    private val classifier: IntentClassifier = KeywordIntentClassifier,
    private val toolsFor: (AgentIntent) -> List<AgentTool>,
    private val promptFor: (AgentIntent) -> ManagerPrompt,
    /**
     * P1 实验开关：稳定工具集 + 统一提示词。
     * 默认读环境变量，所以生产装配点（AppRuntime）不用改一行就能 A/B。
     */
    val stableTools: Boolean = stableToolsEnabled(),
) {

    /** 单个拼装器：一份提示词 + 全套工具（测试和老调用点用）。 */
    constructor(
        prompt: ManagerPrompt,
        allTools: List<AgentTool>,
        classifier: IntentClassifier = KeywordIntentClassifier,
        stableTools: Boolean = false,
    ) : this(
        classifier = classifier,
        toolsFor = { intent ->
            if (stableTools) stableToolset(allTools) else toolsForIntent(intent, allTools)
        },
        promptFor = { prompt },
        stableTools = stableTools,
    )

    /** 按意图重建提示词：每条工具的目录只出现在它该出现的那一版里。 */
    constructor(
        catalog: CapabilityCatalog,
        allTools: List<AgentTool>,
        guidance: String = "",
        classifier: IntentClassifier = KeywordIntentClassifier,
        stableTools: Boolean = stableToolsEnabled(),
    ) : this(
        classifier = classifier,
        toolsFor = { intent ->
            if (stableTools) stableToolset(allTools) else toolsForIntent(intent, allTools)
        },
        promptFor = { intent ->
            val tools = if (stableTools) stableToolset(allTools) else toolsForIntent(intent, allTools)
            ManagerPrompt(
                base = ManagerPrompt.buildBase(catalog, tools, guidance, stableTools),
                stableTools = stableTools,
            )
        },
        stableTools = stableTools,
    )

    fun decide(request: String): IntentDecision = classifier.decide(request)

    fun tools(intent: AgentIntent): List<AgentTool> = toolsFor(intent)

    fun prompt(intent: AgentIntent): ManagerPrompt = promptFor(intent)

    /**
     * 工具名 → 拥有它的意图（多归属时按 [FALLBACK_PRIORITY] 取第一个）。
     *
     * 用途只有一条补救路径：意图判成 Chat、手里没有工具，但模型自己点名了一个真实工具
     * （比如用户点选项「每天 60 分钟」后它想调 `create_learning_plan`）。
     * 与其拿空工具箱让它连续报「没有这个工具」，不如换到拥有它的意图再跑。
     */
    fun intentForTool(name: String): AgentIntent? =
        FALLBACK_PRIORITY.firstOrNull { intent -> tools(intent).any { it.name == name } }

    private companion object {
        /** 多归属工具（get_diagnostics、set_plan…）的兜底偏好：先任务类，再管理类。 */
        val FALLBACK_PRIORITY = listOf(
            AgentIntent.FindResource,
            AgentIntent.PlanManage,
            AgentIntent.CreatePlugin,
            AgentIntent.ManagePlugin,
            AgentIntent.ArrangeUi,
        )
    }
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

    // 学习计划维护：读现状、推进周节奏、勾节点、顺延、复盘、改每天投入。
    // 这几只新工具必须**显式登记**：没登记的会落进 DEFAULT_INTENTS，
    // 于是「做个插件」那一轮也拿到 complete_plan_node —— 又变成越界。
    "get_learning_plan" to setOf(AgentIntent.PlanManage),
    "plan_next_week" to setOf(AgentIntent.PlanManage),
    "complete_plan_node" to setOf(AgentIntent.PlanManage),
    "move_plan_node" to setOf(AgentIntent.PlanManage),
    "review_last_week" to setOf(AgentIntent.PlanManage),
    "set_plan_pace" to setOf(AgentIntent.PlanManage),

    // 清单：多步活都要，聊天不要
    "set_plan" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin, AgentIntent.ArrangeUi, AgentIntent.FindResource, AgentIntent.PlanManage),
    "step_done" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin, AgentIntent.ArrangeUi, AgentIntent.FindResource, AgentIntent.PlanManage),

    // 让用户拍板：任何会改数据的意图都可能需要，所以这几套都给它
    "ask_choices" to setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin, AgentIntent.ArrangeUi, AgentIntent.FindResource, AgentIntent.PlanManage),

    // 自己看错：报错（计划库/数据库/缺模块/配置/网络）时先查诊断，再决定怎么修或怎么绕。
    // 每个会动手的意图都得有 —— 出错发生在哪一轮，那一轮就得看得见原因。
    "get_diagnostics" to setOf(
        AgentIntent.CreatePlugin,
        AgentIntent.ManagePlugin,
        AgentIntent.ArrangeUi,
        AgentIntent.FindResource,
        AgentIntent.PlanManage,
    ),
)

private val DEFAULT_INTENTS: Set<AgentIntent> = setOf(AgentIntent.CreatePlugin, AgentIntent.ManagePlugin)

/** 按意图选工具（单选）：不在归属表里的工具按 [DEFAULT_INTENTS] 处理。 */
internal fun toolsForIntent(intent: AgentIntent, all: List<AgentTool>): List<AgentTool> {
    if (intent == AgentIntent.Chat) return emptyList()
    return all.filter { tool ->
        (TOOL_INTENTS[tool.name] ?: DEFAULT_INTENTS).contains(intent)
    }
}

/**
 * 稳定工具集的目录顺序：按工具名排。
 *
 * 为什么排序：前缀缓存要求同一份静态前缀逐字节一致。工具列表是启动时装配的，
 * 顺序其实也稳定；但排序是零成本的保险 —— 将来任何一处装配顺序变了，
 * 也不会悄悄让缓存整段失效。
 */
internal fun stableToolset(all: List<AgentTool>): List<AgentTool> = all.sortedBy { it.name }
