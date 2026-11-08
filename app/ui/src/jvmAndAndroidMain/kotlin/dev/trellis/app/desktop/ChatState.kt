package dev.trellis.app.desktop

/**
 * 对话流里的一行。
 *
 * [Step] 是 agent 干活时的过程（第几步、调了哪个工具、返回什么），
 * 和用户/助手的正式发言分开：过程可以很长，而且用户不一定要读，
 * 所以界面上用更小的字号、更灰的颜色。
 *
 * [Plan] 是 agent 开工前排的工作清单（见 [PlanStep]）。它和 [Step] 的区别是
 * **先有清单、再逐条打勾**：步骤是它自己定的、用户能提前看见的，
 * 而不是事后的一串调用日志。清单显示在右侧常驻栏（收起时贴在输入框上方），
 * 让人一眼知道它打算干什么、干到哪了。
 *
 * [Status] 是**一轮的界标**：完成 / 已停止 / 出错。流式回答的麻烦是它没有
 * 「话说完了」这个动作 —— 字停下来可能只是模型在喘气，也可能真的结束了。
 * 每一轮末尾压一条界标，用户不用猜。
 *
 * [atMs] 只用于显示时间（聊天记录要能看出「哪天说的」）；不参与模型上下文。
 */
enum class ChatRole { User, Assistant, Step, Status, Plan }

/**
 * 清单上一条的状态。
 *
 * [Skipped] 和「没做成」分开记：失败是「试了、砸了」，跳过是「前一步砸了，
 * 这一步根本没跑」—— 后者不该被算进失败数里。
 */
enum class PlanStepState { Pending, Running, Done, Failed, Skipped }

/**
 * 清单里的一步。
 *
 * [tool] 由宿主在工具开跑时填（模型不用报）；[impact] 是「这一步会改什么」，
 * 用户对「会不会动我的东西」的焦虑比「成功没有」更强，所以单列一行。
 * [dependsOn] 存的是**步序号**（0 开始）：前一步失败时，依赖它的步骤自动变 [PlanStepState.Skipped]。
 */
data class PlanStep(
    val text: String,
    val state: PlanStepState = PlanStepState.Pending,
    val tool: String = "",
    val note: String = "",
    val impact: String = "",
    val undoable: Boolean = false,
    val ms: Long = 0L,
    val startedMs: Long = 0L,
    val dependsOn: List<Int> = emptyList(),
)

/**
 * 一次工具调用的详情（过程行里那条「· 查了学习计划」点开之后看到的东西）。
 *
 * 为什么单独存：参数和结果经常是几十行 JSON，塞进过程行文本会把折叠区撑爆；
 * 文本行只留人话摘要，细节点开看。
 */
data class ChatToolTrace(
    val tool: String,
    val description: String,
    val arguments: String,
    val result: String,
    val isError: Boolean,
)

data class ChatLine(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val atMs: Long = 0L,
    val plan: List<PlanStep> = emptyList(),
    /** 清单出来/全部结束的时间，用于「已完成 · 5.6s」和完成 1.5s 后自动收起。 */
    val planStartedMs: Long = 0L,
    val planFinishedMs: Long = 0L,
    /**
     * 管家消息附带的模板化建议（渲染成卡片，点「一键采纳」直接执行）。
     *
     * 普通对话没有这个字段；管家会话里也可能没有（没有可改的事就不建议）。
     */
    val proposal: StewardProposal? = null,
    /** 建议被采纳/被「先这样」之后记下来，重启后卡片不再显示按钮。 */
    val proposalState: ProposalState = ProposalState.Open,
    /**
     * 学习单元完成后，AI 提议练一道题（模板生成，见 [PracticeProposal]）。
     *
     * 普通对话没有这个字段；管家会话里只在「刚学完一个单元、题库里有匹配的题」
     * 且这一单元还没提议过时出现。
     */
    val practiceProposal: PracticeProposal? = null,
    /** 接受/先不练之后记下来，重启后卡片不再显示按钮。 */
    val practiceState: PracticeProposalState = PracticeProposalState.Open,
    /**
     * 工具调用详情（有就渲染成一条可展开的工具行）。
     *
     * 旧会话没有这个字段：过程行还是纯文本，展开后按老样子平铺。
     */
    val tool: ChatToolTrace? = null,
)

/**
 * 输入框上方的一个选项（竖排列表里的一行）。
 *
 * [label] 是那行短名（「看建议」），[text] 是点了之后真正发给 agent 的
 * 原话 —— 让用户挑，而不是替用户决定要不要动数据。
 */
data class ChatChoice(val label: String, val text: String)

/**
 * 这段会话是谁。
 *
 * 默认是 [Manager]：和插件管理员对话，意图分类器按这句话选工具、拼提示词。
 * [Steward] 是「管家」那一段常驻会话：人格更克制，主动说话（晨/夜仪式），
 * 手里是**全量工具集**、同一套审批闸门 —— 界面和普通会话完全一样，
 * 区别只在提示词、主动消息入口和会话身份。
 */
enum class SessionKind { Manager, Steward }

/**
 * 一段会话。对话记录按会话存盘，关掉程序再打开还能接着聊。
 *
 * [title] 取第一句用户发言（截断）；[updatedAt] 用来排序和显示「最近说过话」。
 *
 * [unread] 只表示「这段里有用户还没看过的主动消息」——目前的来源是
 * 管家会话的晨/夜仪式（`AppRuntime.stewardRuntime`）。
 * 用户自己发起的一轮不算未读：那时他人就在对话页。打开会话即清掉；
 * 旧会话文件没有这个字段，按已读处理（不要一升级就满屏红点）。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lines: List<ChatLine>,
    val unread: Boolean = false,
    /** 旧会话文件没有这个字段，默认 [SessionKind.Manager]。 */
    val kind: SessionKind = SessionKind.Manager,
)
