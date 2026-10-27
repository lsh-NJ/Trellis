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
        intent: AgentIntent = intent(request),
    ): List<LlmMessage> {
        val out = mutableListOf<LlmMessage>()
        out += LlmMessage(LlmRole.System, base)
        if (state.isNotBlank()) out += LlmMessage(LlmRole.System, stateBlock(state))
        out += history
        taskModule(intent)?.let { out += LlmMessage(LlmRole.System, it) }
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
            AgentIntent.FindResource -> """
                <task:find_resource>
                用户这次想学点东西、或者要外部学习资源。资源在 B 站，用手里的工具去查，不要凭记忆报课程名：
                0. 如果用户说的是目标（「我要速通操作系统」「两个月学会 XX」），先用一句话把目标翻译成可执行的范围
                   （几周完成、每天几集/几页），然后按下面的路走 —— 不要先说「做不到」，也不要只回一段解释；
                1. search_bilibili(keyword=...)：默认按播放量（热度）排序，返回 BV 号、up 主、播放量、时长、分集数；
                2. 挑一个可信的（up 主正经、分集多、播放量高），用 list_bilibili_parts(bvid=...) 拿分集清单；
                   每集都带时长（分钟）：**做计划节点时就用这个时长当预估时间**，不要凭感觉写 25；
                   超过 45 分钟的拆成两个单元，或按 45 记（后端只收 10–45 分钟）；
                3. 分集名就是天然大纲，但**先别动用户的数据**：只搜、只挑、只说明；
                   需要用户决定下一步时，调 ask_choices（question 一句话，options 2–5 个短语），
                   界面上会把选项竖着摆到输入框上方；调完就停下等用户点，别自己替用户挑：
                   只看建议 ｜ 落成待办 ｜ 排成学习计划；
                   用户没选之前不要 add_tasks、不要建计划，也不要把「已经加好了」说得像做过了；
                4. 用户选了之后：
                   - 落成待办 → add_tasks(confirmed=true)；
                   - **排成学习计划 → create_learning_plan(title, units, weeks)**：这是唯一能把东西
                     写进「计划」页的工具。`set_plan` 只是这一轮的动作清单，只调它的话计划页还是空的，
                     用户会以为你在骗他；
                   - 两种活都可以用 2–4 步的 set_plan 把动作标出来，做完一步 step_done 一步；
                5. 回答里说清「选了哪个视频、为什么、留了哪几集、总共大概多久」。
                这个工具只读元数据：不能播放、不能下载、不能判断讲得好不好。用户要「这两个视频哪个讲得好」就直说看不到内容。
                这一轮不要再造插件（除非用户明确要「装个卡片」并接受步骤被排版）。
                没做过的步骤不要写成做过了：说过「装好了/排好了」，就必须真有 deploy / arrange_cards 的过程行；
                中途停下也要说明停在哪、剩下的留给下一步。
                </task:find_resource>
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

            <when_to_create_a_plugin>
            只有用户明确要「新做一张卡片 / 新插件 / 新的打卡表」时才创建插件。
            整理待办、把资料排成计划、改布局、改样式、查资源、回答现状 —— 这些全是**对已有
            东西的管理**，一律不新造插件。为了让某个信息「显示出来」而造插件是越界：
            用户要的是几条能勾的待办，不是插件列表里多一行。
            而且非「造/改插件」的轮次里，你手里根本没有装插件的那两只手。
            </when_to_create_a_plugin>

            <goal_requests>
            用户在说「我想 / 我要 / 帮我达成」某个目标时（尤其带「速通、学会、掌握、考过、背完、刷完」），
            第一反应必须是**把它变成能执行的东西**：计划或待办。不要先解释「这做不到」「这不是一张卡片能解决的」——
            那等于什么都没给。正确的第一步是：
            1. 要问范围（要多久做完、每天大概能花多久）就用 `ask_choices` 把范围摆成选项问，
               不要只在文字里问一句然后停下 —— 那样界面上下一步还是空的；
            2. 能自己定就用默认拆法直接动手：先查资源（search_bilibili），再 add_tasks 或 set_plan；
            3. 要用户拍板就用 `ask_choices`（例如：落成待办 / 排成计划 / 只看建议），然后停下等点；
            4. 只有用户明确说「先别建」，才停在建议上。
            `cannotDo` 只用于**真的做不到**的事（比如要解锁手机、要读别的应用的数据）；
            「我还需要用户给一个范围」不是做不到，是 `ask_choices`。
            目标型请求的合格回答里，至少要有一样「用户接下来可以勾/可以照着做」的东西。
            </goal_requests>

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
