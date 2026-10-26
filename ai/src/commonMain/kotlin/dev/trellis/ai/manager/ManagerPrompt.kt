package dev.trellis.ai.manager

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import dev.trellis.ai.PromptBuilder
import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolAgent

/**
 * 管理员的上下文怎么拼。
 *
 * ### 为什么要分层，而不是每轮重算一整段提示词
 *
 * 大模型服务的上下文缓存按**前缀**命中：只有从头开始完全一致的部分才算缓存。
 * 所以这里的消息顺序刻意做成「越靠前越稳定」：
 *
 * ```
 * [system] 静态前缀：设计规则 + 工作守则 + 工具清单 + 管理员守则   ← 整场会话一字不变
 * [system] 动态状态：当前装了哪些插件、内核状态                    ← 每轮都可能变
 * ...历史对话...
 * [system] 任务模块：这次是「造插件 / 管插件 / 调布局」            ← 只在相关意图时插入
 * [user]   这一句请求
 * ```
 *
 * 动态状态放在静态前缀**之后**，所以它怎么变都不会让前缀缓存失效；
 * 任务模块放在动态状态之后，只在切换意图时变化。这就是「一次性注入不变的
 * 系统提示词，保证前缀缓存命中」的落点 —— 不是靠少说话，是靠**顺序**。
 *
 * 注意：历史对话必须排在任务模块之前。任务模块是给「这一轮」的指令，
 * 历史是「已经发生的事」；夹在历史中间会让模型以为指令是上一轮说的。
 */
class ManagerPrompt(
    /**
     * 静态前缀。由 [buildBase] 生成，内容只取决于能力清单和工具清单，
     * 不依赖「当前装了什么」，所以整场会话保持逐字节一致。
     */
    val base: String,
    private val classifier: IntentClassifier = KeywordIntentClassifier,
) {

    fun intent(request: String): AgentIntent = classifier.classify(request)

    fun messages(
        state: String,
        request: String,
        history: List<LlmMessage> = emptyList(),
    ): List<LlmMessage> {
        val out = mutableListOf<LlmMessage>()
        out += LlmMessage(LlmRole.System, base)
        if (state.isNotBlank()) out += LlmMessage(LlmRole.System, stateBlock(state))
        out += history
        taskModule(intent(request))?.let { out += LlmMessage(LlmRole.System, it) }
        out += LlmMessage(LlmRole.User, userLine(request))
        return out
    }

    companion object {

        fun userLine(request: String): String = "我想要：" + request.trim()

        fun stateBlock(state: String): String =
            "<current_state>\n" + state.trim() + "\n</current_state>"

        /**
         * 静态前缀 = 设计规则（[PromptBuilder.system]，已装插件传空 —— 那是动态状态）
         * + 工具工作守则 + 工具清单 + 管理员守则。
         */
        fun buildBase(
            catalog: CapabilityCatalog,
            tools: List<AgentTool>,
            guidance: String = "",
        ): String = buildString {
            append(PromptBuilder.system(catalog, existing = emptyList()))
            if (guidance.isNotBlank()) {
                append("\n\n")
                append(guidance)
            }
            append("\n\n")
            append(ToolAgent.catalogueOf(tools))
            append("\n\n")
            append(MANAGER_ROLE)
        }

        /** 意图相关的任务模块。纯聊天不插入任何模块。 */
        fun taskModule(intent: AgentIntent): String? = when (intent) {
            AgentIntent.Chat -> null
            AgentIntent.CreatePlugin -> """
                <task:create_plugin>
                用户这次要造一个插件（或改一个已装的）。按 <how_to_work> 走：先看清楚环境，再写；
                先 try_plugin，跑通再 deploy；要改已有的就用同一个 id 改，不要另起一个新 id。
                装完只报「装上了什么、怎么用、退了哪一步」，不要复述过程。
                </task:create_plugin>
            """.trimIndent()
            AgentIntent.ManagePlugin -> """
                <task:manage_plugin>
                用户这次要管已装插件（停用 / 启用 / 删除 / 读源码 / 看健康）。先用 list_plugins 看清 id 和现状，
                再动手；删除会进回收站、七天内可还原，要说清楚。不存在的插件不要假设它存在。
                </task:manage_plugin>
            """.trimIndent()
            AgentIntent.ArrangeUi -> """
                <task:arrange_ui>
                用户这次想调整界面：摆放插件卡片，或者换配色/密度/卡片外观/字号。
                摆放：先 list_cards 看每个挂载点上有什么，再用 arrange_cards 按行摆；
                一行放多张就是横排；要按 12 栏排就写成 `插件id:栏数`（如 ai.pushups:8|ai.mood-checkin:4）。
                没写进去的卡片会排到最后（不会被藏起来）。
                样式：先 list_ui_options 看现在什么样，再用 set_ui 改一两样，改完 take_screenshot 核对；
                样式包括配色、密度、卡片外观、圆角、字号、导航位置（top 顶栏 / left 左侧栏）、导航底色（default/dark/accent）。
                逐个插件调整：只用 set_plugin_card 改被点名的那几个插件（bordered/flat/filled，可带圆角），
                没点名的插件保持全局样式，不要顺手改别的。
                整套换装：list_themes 看有没有插件贡献主题包，set_theme 切换（builtin 回配色方案）；
                没有现成主题又想要「一整套自己的配色」，就走 set_ui 的 palette=custom + hue/chroma。
                宿主支持横排、上面这些样式、逐插件卡片外观和主题包；还没有网格、拖拽、任意绝对位置，卡片内部也改不了，这些别承诺。
                做完说清楚「哪个挂载点、哪几行」或「改了哪几样样式」，不要只说「已经好了」。
                </task:arrange_ui>
            """.trimIndent()
        }

        private val MANAGER_ROLE = """
            <your_role>
            你是这个应用的管理员，不是「一次生成一个插件」的工具。
            你负责内核之外的一切：解释现状、判断该不该动手、创建 / 修改 / 停用 / 删除插件、
            检查它们是否健康、调整它们的摆放。

            你看到的上下文分三层，自上而下：
            1. 这一层：不变的规则和工具（整场会话不变）；
            2. <current_state>：当前装着什么、内核什么状态（每轮更新）；
            3. <task:...>：这一次要干什么（没出现就是纯聊天）。
            不要把这三层的存在念给用户听，也不要复述规则，直接回答或动手。

            用户可能只是在问你问题，也可能一口气要你管一堆插件。先判断这次要不要动手：
            不要一上来就写插件，也不要明明是聊天却去调工具。

            <what_the_host_draws>
            每张插件卡片的外框（圆角、边框、底色、卡片间距）是**宿主**画的：插件只能决定
            卡片里的文字、零件、按钮，改不了外框。用户要「每张卡片有个圆角边界」时，
            告诉他宿主已经给了（这是事实，不是拒绝）；想进一步区分内容，用标题、分割线、
            徽章来表达，不要答应去改外框样式。
            </what_the_host_draws>
            </your_role>
        """.trimIndent()
    }
}
