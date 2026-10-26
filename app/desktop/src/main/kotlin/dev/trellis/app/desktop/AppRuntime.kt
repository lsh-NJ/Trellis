package dev.trellis.app.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import dev.trellis.ai.AgentEvent
import dev.trellis.ai.AgentOutcome
import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.HttpLlmClient
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmConfigLoader
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import dev.trellis.ai.manager.AgentIntent
import dev.trellis.ai.manager.ManagerAgent
import dev.trellis.ai.manager.ManagerPrompt
import dev.trellis.ai.flow.AgentFlow
import dev.trellis.ai.PluginAgent
import dev.trellis.ai.tool.ToolAgent
import dev.trellis.ai.PromptBuilder
import dev.trellis.ai.ExistingPlugin
import dev.trellis.domain.Plan
import dev.trellis.domain.PlanService
import dev.trellis.domain.PlanSummary
import dev.trellis.domain.Task
import dev.trellis.domain.TaskStatus
import dev.trellis.dsl.DslLoader
import dev.trellis.script.ScriptLoader
import dev.trellis.dsl.JvmPluginFileSystem
import dev.trellis.feature.plan.PlanCorePlugin
import dev.trellis.kernel.api.ActionOutcome
import dev.trellis.kernel.api.Kernel
import dev.trellis.kernel.slot.SlotAction
import dev.trellis.kernel.api.KernelConfig
import dev.trellis.kernel.api.PluginInfo
import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.TemplateSlot
import dev.trellis.kernel.slot.TextSlot
import dev.trellis.ui.currentDataContext
import dev.trellis.ui.DynamicCardStyle
import dev.trellis.ui.ExtraColors
import dev.trellis.ui.SlotCell
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.approval.ActionRequest
import dev.trellis.kernel.approval.DefaultApprovalGate
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.diag.KernelError
import dev.trellis.kernel.extension.Contribution
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.theme.ThemeContribution
import dev.trellis.kernel.extension.ServiceBinding
import dev.trellis.kernel.spi.BuiltinLoader
import dev.trellis.kernel.spi.Platform
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 界面上**真的**渲染了的挂载点。
 *
 * 必须和 AppScreen / PluginsScreen 里那几处 SlotArea 调用保持同步。多写一个，
 * 模型就会往空气里挂卡片：装成功了，用户却在界面上找不到，
 * 只能反复开关插件玩「找不同」。
 *
 * 首页三处 + 全局浮层 + 报表 + 设置页分组。
 * 详情页还没做，所以 `slot.detail.aside` / `slot.detail.tab` 仍然不在里面。
 * `slot.row.trailing` 也不在里面：行尾卡片需要「这一行是哪条任务」的上下文，
 * 而现在的模板求值只有全局数据源，接上去的结果是每条任务下面挂一张一模一样的卡片。
 * 等 DataContext 能带行上下文再接，不拿假能力充数。
 */
/** 页签序号。AppScreen 用它们切页，agent 用它们回答「现在在哪一页」。 */
internal const val TAB_TASKS = 0
internal const val TAB_CHAT = 1
internal const val TAB_PLUGINS = 2
internal const val TAB_DIAG = 3

internal val RENDERED_MOUNT_POINTS: Set<ExtensionPointId> = setOf(
    Slots.HomeHeader.id,
    Slots.HomeSection.id,
    Slots.EmptyState.id,
    Slots.AppOverlay.id,
    Slots.ReportChart.id,
    Slots.SettingsSection.id,
)

/** 一张卡片的预览：模板已经求值过，直接能显示。 */
data class PreviewCard(
    val mountPointId: String,
    val mountPointLabel: String,
    /** 这个位置眼下界面上有没有渲染。false 表示装上去了也看不见。 */
    val visible: Boolean,
    val title: String,
    val body: String,
)

/** 「刚才到底生成了什么」：它叫什么、怎么用、长出哪几张卡片、分别挂在哪。 */
data class GeneratedPreview(
    val pluginId: String,
    val name: String,
    val description: String,
    val usage: String,
    /** 模型自己承认「少做了什么」。空表示没退步。 */
    val limitation: String,
    val cards: List<PreviewCard>,
)

/** 一次截图：[text] 是给人看的话（存到哪、多大），[dataUrl] 是给模型看的图片。 */
data class ScreenCapture(
    val text: String,
    val dataUrl: String?,
)

/**
 * 把脚本卡片摊平成预览卡能显示的两段文字。
 *
 * 脚本卡片是一棵节点树，预览卡只有标题和正文两个位置：第一段文字当标题，
 * 其余拼成正文。摊不开（整棵树没有文字）也要给一句占位，不能让预览误报
 * 「没有往界面上放任何卡片」。
 */
internal fun UiNode.toPreview(): Pair<String, String> {
    val texts = mutableListOf<String>()
    fun walk(node: UiNode) {
        when (node) {
            is UiNode.Column -> node.children.forEach { walk(it) }
            is UiNode.Row -> node.children.forEach { walk(it) }
            is UiNode.Section -> {
                if (node.title.isNotBlank()) texts += node.title
                node.children.forEach { walk(it) }
            }
            is UiNode.Grid -> node.children.forEach { walk(it) }
            is UiNode.Cell -> walk(node.child)
            is UiNode.Text -> texts += node.text
            is UiNode.Stat -> texts += node.label + " " + node.value
            is UiNode.Badge -> texts += node.text
            is UiNode.Bars -> {
                if (node.label.isNotBlank()) texts += node.label
                node.items.forEach { texts += it.label + " " + it.value }
            }
            is UiNode.Sparkline -> if (node.label.isNotBlank()) texts += node.label
            is UiNode.Columns -> {
                if (node.label.isNotBlank()) texts += node.label
                texts += node.values.joinToString(",")
            }
            is UiNode.Area -> {
                if (node.label.isNotBlank()) texts += node.label
                texts += node.values.joinToString(",")
            }
            is UiNode.DotMatrix -> {
                if (node.label.isNotBlank()) texts += node.label
                texts += node.value
            }
            is UiNode.Ring -> texts += node.label.ifBlank { "进度" } + " " + node.value
            is UiNode.Progress -> if (node.label.isNotBlank()) texts += node.label
            is UiNode.Table -> {
                if (node.caption.isNotBlank()) texts += node.caption
                if (node.headers.isNotEmpty()) texts += node.headers.joinToString(" ")
                node.rows.forEach { texts += it.joinToString(" ") }
            }
            is UiNode.Icon -> texts += node.glyph
            is UiNode.Image -> texts += node.alt.ifBlank { node.ref }
            is UiNode.Button -> texts += node.label
            is UiNode.Input -> texts += node.hint.ifBlank { node.submitLabel }
            is UiNode.Stepper -> texts += node.label.ifBlank { "计数" } + " " + node.value + node.unit
            is UiNode.HeatStrip -> texts += node.labels.joinToString(" ").ifBlank { node.values.size.toString() + " 格" }
            is UiNode.Notice -> texts += node.text
            is UiNode.Spacer -> Unit
            UiNode.Divider -> Unit
        }
    }
    walk(this)
    val visible = texts.filter { it.isNotBlank() }
    val title = visible.firstOrNull() ?: "（一张脚本卡片）"
    return title to visible.drop(1).joinToString(" · ")
}

/**
 * 应用运行时：内核 + 领域服务 + 界面状态。
 *
 * 构造分两步（先建空壳、启动内核、再挂上去），因为**内核在启动过程中就会调用授权对话框**，
 * 那时候运行时对象必须已经存在  否则第一轮授权无处可问，
 * 插件会被「没有可用的授权界面」默认拒绝掉。
 */
class AppRuntime private constructor() {

    lateinit var kernel: Kernel
        private set

    lateinit var planService: PlanService
        private set

    lateinit var storeFile: File
        private set

    lateinit var pluginsDir: File
        private set

    lateinit var home: File
        private set

    // ---- AI 生成插件的状态 ----

    /** 正在生成。界面据此禁用按钮，避免用户连点出三次请求。 */
    var agentBusy by mutableStateOf(false)
        private set

    /** 生成过程的流水账，给界面实时显示：第几次请求、卡在哪、最后成没成。 */
    var agentLog by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * 上一次生成的结果预览。
     *
     * 生成成功后界面直接把它摊开：卡片长什么样、挂在哪儿、看不看得见。
     * 不然用户只会看到一句「装好了」，然后回首页一个字一个字地找不同。
     */
    var lastGenerated by mutableStateOf<GeneratedPreview?>(null)
        private set

    /**
     * 测试用：把模型换成假的。
     *
     * 生产的默认实现是 [HttpLlmClient]。留这个口子是为了能端到端测整条链路
     * （假模型 -> 真校验 -> 真装进内核 -> 界面上真的多一张卡片），
     * 而不用发一次网络请求、也不用一个真 key。
     */
    internal var llmOverride: LlmClient? = null
        set(value) {
            field = value
            // 假模型只会背台词，不会调工具。换上假模型就默认把「先查环境」关掉，
            // 否则它会把背好的第一条台词当成格式错误，白白吃掉一次机会。
            if (value != null) researchFirst = false
        }

    /**
     * 生成插件时，先让它用工具把环境查清楚。
     *
     * 关掉就是直接盲写（老路）。留着这个开关是因为两条路都真的能用，
     * 而且盲写那条在假模型下更好测。
     */
    internal var researchFirst: Boolean = true

    /** 有没有配模型 key。没配时界面给配置指引，而不是让它失败。 */
    fun llmConfigured(): Boolean = LlmConfigLoader.load(home) != null

    fun llmSetupHint(): String = LlmConfigLoader.setupHint(home)

    // ---- 删除撤销 ----

    /** 刚被删掉、还能撤销的那条任务。 */
    data class PendingDelete(val task: Task, val deadlineMs: Long)

    var pendingDelete by mutableStateOf<PendingDelete?>(null)
        private set

    private var undoJob: Job? = null

    private val undoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 撤回窗口：5 秒，够看清「删错了」，又不至于让界面一直挂着一个条。 */
    private val undoWindowMs = 5_000L

    // ---- 对话式管理员 ----

    /** 对话页的全部内容：用户发言、管理员回答、以及它干活的过程。 */
    val chat = mutableStateListOf<ChatLine>()

    /**
     * 任务输入框的焦点请求器。
     *
     * 任务区现在是插件贡献的卡片（`dev.planui`），但宿主按 Ctrl+K 也得能聚焦到
     * 同一个输入框 —— 焦点不能因为「界面是谁画的」而分裂成两份状态。
     */
    val taskFocus = FocusRequester()

    /** 正在跑一轮（流式回答或工具循环）；界面据此禁用输入。 */
    var chatBusy by mutableStateOf(false)
        private set

    /**
     * 当前在哪一页。
     *
     * 被提到这里来，不是为了别的：agent 要能回答「我现在看到的是什么」。
     * 页签状态留在 Compose 里，它对屏幕就永远是瞎的 —— `describe_screen` 只能猜。
     */
    var currentTab by mutableStateOf(initialTab())

    private var nextChatLineId = 1L

    /** 会话列表（按最近说话排序）；左边栏用。 */
    val chatSessions = mutableStateListOf<ChatSession>()

    /** 当前会话 id；null = 这一段还没落盘（发第一句后才有 id）。 */
    var currentChatId by mutableStateOf<String?>(null)
        private set

    private lateinit var chatStore: ChatStore

    /** 发给模型的历史（只有正式对话，不含过程）；上限 20 条，防止越聊越贵。 */
    private val chatHistory = mutableListOf<LlmMessage>()

    /**
     * 跑对话的 scope 挂在 runtime 上，**不能挂在聊天页上**。
     *
     * 挂在页面上的后果已经踩过一次：agent 调 `open_tab` 切页 → 聊天页离开组合 →
     * `rememberCoroutineScope` 被取消 → 正跑的一轮被掐死，界面上留下
     * 「模型没回上来：The coroutine scope left the composition」。
     * 管理员的活属于程序（它还要能自己切页、截图），不属于某一页。
     */
    private val chatScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var chatJob: Job? = null

    /** 从界面发起一轮对话：job 挂在 runtime 上，切页不会把它掐断。 */
    fun ask(request: String) {
        if (chatBusy) return
        chatJob = chatScope.launch { sendChat(request) }
    }

    /** 用户主动喊停。停掉的那一轮会留一行「已停止」，不会假装跑完了。 */
    fun stopChat() {
        chatJob?.cancel()
    }

    /** 复制结论到系统剪贴板；失败不弹窗，只在按钮上提示。 */
    fun copyToClipboard(text: String) {
        runCatching {
            val selection = java.awt.datatransfer.StringSelection(text)
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }
    }

    /**
     * 流式回显时该给人看哪一段。
     *
     * 工具循环里模型的原始输出是协议 JSON（`{"tool":…}` / `{"done":…}` / 围栏里的
     * json 块），那不是「它说了什么」，是「它在想什么」。协议一出现就截断回显，
     * 等这一轮结束由 ManagerAgent 剥壳后的答复覆盖——用户不该盯着 500 行 JSON。
     */
    private fun visibleReply(raw: String): String {
        val markers = listOf("{\"tool\"", "{\"done\"", "{\"cannotDo\"", "```json", "```")
        var cut = -1
        for (marker in markers) {
            val index = raw.indexOf(marker)
            if (index >= 0 && (cut < 0 || index < cut)) cut = index
        }
        return if (cut < 0) raw else raw.substring(0, cut).trimEnd()
    }

    /** 开一段新对话：清空当前视图，但不删旧记录（旧的那段已经在盘上了）。 */
    fun newChat() {
        if (chatBusy) return
        currentChatId = null
        chat.clear()
        chatHistory.clear()
        nextChatLineId = 1L
    }

    /** 打开一段历史会话；也把最近 20 条正式发言接回模型上下文。 */
    fun openChat(id: String) {
        if (chatBusy) return
        val session = chatStore.read(id) ?: return
        currentChatId = session.id
        chat.clear()
        chat += session.lines
        nextChatLineId = (session.lines.maxOfOrNull { it.id } ?: 0L) + 1
        chatHistory.clear()
        session.lines
            .filter { it.role == ChatRole.User || it.role == ChatRole.Assistant }
            .takeLast(20)
            .forEach { line ->
                chatHistory += LlmMessage(
                    if (line.role == ChatRole.User) LlmRole.User else LlmRole.Assistant,
                    line.text,
                )
            }
    }

    fun deleteChat(id: String) {
        if (chatBusy) return
        chatStore.delete(id)
        loadChatSessions()
        if (currentChatId == id) newChat()
    }

    private fun loadChatSessions() {
        if (!::chatStore.isInitialized) return
        chatSessions.clear()
        chatSessions += chatStore.list()
    }

    /**
     * 把当前这一段落盘。
     *
     * 每轮结束（含停止、出错）都存：用户宁可看到半截记录，也不愿意「刚才那几句没了」。
     * 标题取第一句用户发言 —— 事后找会话时，人认的是「我当时问的那句话」。
     */
    private fun persistChat() {
        if (!::chatStore.isInitialized || chat.isEmpty()) return
        val now = System.currentTimeMillis()
        val id = currentChatId ?: ("chat-" + now)
        currentChatId = id
        val title = chat.firstOrNull { it.role == ChatRole.User }
            ?.text?.trim()?.take(24)?.takeIf { it.isNotBlank() } ?: "新对话"
        val createdAt = chatStore.read(id)?.createdAt ?: now
        chatStore.save(ChatSession(id, title, createdAt, now, chat.toList()))
        loadChatSessions()
    }

    // ---- 一轮的撤销与差异 ----

    /**
     * 一轮开始时宿主的可变状态。
     *
     * 只快照「宿主自己管的东西」：布局、界面偏好、逐插件卡片样式。
     * 插件的装/删不在这里 —— 那是内核的文件与数据，完整撤销要走内核快照能力；
     * v1 只把它列进差异并明说「不可撤销」，不假装能退。
     */
    private data class HostSnapshot(
        val cardLayout: Map<ExtensionPointId, List<List<SlotCell>>>,
        val uiPrefs: UiPrefs,
        val pluginCardStyles: Map<PluginId, PluginCardStyle>,
        val pluginIds: Set<String>,
    )

    private val roundSnapshots = mutableMapOf<Long, HostSnapshot>()
    private val roundOrder = mutableListOf<Long>()
    private val undoneRounds = mutableSetOf<Long>()

    fun hasRoundActions(key: Long): Boolean = roundSnapshots.containsKey(key)

    fun roundIsUndone(key: Long): Boolean = key in undoneRounds

    private fun snapshotHost(): HostSnapshot = HostSnapshot(
        cardLayout = cardLayout.toMap(),
        uiPrefs = uiPrefs,
        pluginCardStyles = pluginCardStyles.toMap(),
        pluginIds = kernel.plugins().map { it.manifest.id.value }.toSet(),
    )

    private fun rememberRound(key: Long) {
        roundSnapshots[key] = snapshotHost()
        roundOrder += key
        while (roundOrder.size > 12) {
            val dropped = roundOrder.removeAt(0)
            roundSnapshots.remove(dropped)
            undoneRounds.remove(dropped)
        }
    }

    private fun layoutText(rows: List<List<SlotCell>>?): String =
        rows?.joinToString(" | ") { row -> row.joinToString(" ") { it.pluginId.value + ":" + it.span } }
            ?: "（无）"

    /** 这一轮到底动了什么。没有改动返回空列表（界面据此说「这一轮没动你的东西」）。 */
    fun diffForRound(key: Long): List<String> {
        val before = roundSnapshots[key] ?: return emptyList()
        val after = snapshotHost()
        val lines = mutableListOf<String>()
        if (before.uiPrefs != after.uiPrefs) {
            lines += "界面偏好：" + before.uiPrefs.describe() + " → " + after.uiPrefs.describe()
        }
        for (point in before.cardLayout.keys + after.cardLayout.keys) {
            val a = before.cardLayout[point]
            val b = after.cardLayout[point]
            if (a != b) lines += "布局 " + point.value + "：" + layoutText(a) + " → " + layoutText(b)
        }
        for (id in before.pluginCardStyles.keys + after.pluginCardStyles.keys) {
            val a = before.pluginCardStyles[id]
            val b = after.pluginCardStyles[id]
            if (a != b) {
                lines += "卡片样式 " + id.value + "：" +
                    (a?.let { it.card + " · 圆角 " + it.radius } ?: "跟随全局") + " → " +
                    (b?.let { it.card + " · 圆角 " + it.radius } ?: "跟随全局")
            }
        }
        val added = after.pluginIds - before.pluginIds
        val removed = before.pluginIds - after.pluginIds
        if (added.isNotEmpty()) lines += "装了插件：" + added.joinToString("、") + "（不可撤销）"
        if (removed.isNotEmpty()) lines += "移除了插件：" + removed.joinToString("、") + "（不可撤销）"
        return lines
    }

    /** 撤销这一轮对宿主状态的改动；插件装删只说明、不假装回滚。 */
    fun undoRound(key: Long): String {
        val before = roundSnapshots[key] ?: return "程序重启过，这一轮没有可撤销的快照"
        if (key in undoneRounds) return "这一轮已经撤销过了"
        val pluginsNow = snapshotHost().pluginIds
        cardLayout = before.cardLayout
        layoutStore.save(cardLayout)
        uiPrefs = before.uiPrefs
        uiPrefsStore.save(uiPrefs)
        pluginCardStyles = before.pluginCardStyles
        pluginStyleStore.save(pluginCardStyles)
        undoneRounds += key
        tick += 1
        val pluginChange = pluginsNow != before.pluginIds
        return if (pluginChange) {
            "已撤销界面与偏好的改动；插件的安装/删除不在这条撤销里，需要手动处理"
        } else {
            "已撤销这一轮对界面与偏好的改动"
        }
    }

    private fun addChatLine(role: ChatRole, text: String = ""): ChatLine {
        val line = ChatLine(nextChatLineId++, role, text, System.currentTimeMillis())
        chat += line
        return line
    }

    /**
     * 当前状态块：给管理员看「这台机器上现在是什么样」。
     *
     * 它不进静态前缀 —— 装一个插件就变一次。放在静态规则之后、对话历史之前，
     * 前缀缓存命中不受影响，模型看到的又永远是最新事实。
     */
    private suspend fun managerState(): String = buildString {
        appendLine("内核：" + kernel.currentState)
        val live = kernel.liveCatalog()
        appendLine("已装插件：" + live.size + " 个")
        if (live.isEmpty()) appendLine("- （一个都没装）")
        live.forEach { manifest ->
            appendLine(
                "- " + manifest.id.value + "　" + manifest.name + "：" +
                    manifest.description.take(60),
            )
        }
        if (disabledPlugins.isNotEmpty()) {
            appendLine("已停用：" + disabledPlugins.joinToString("、") { it.value })
        }
        appendLine("待审批：" + if (pendingApproval == null) "无" else "有 1 个")
    }

    // ---- 眼睛和手 ----

    /**
     * 文字版的「截图」。
     *
     * 为什么先做文字版：模型拿到 PNG 也未必看得懂（很多配置是纯文本模型），
     * 而「当前页有什么、每张卡写着什么、哪个挂载点是空的」恰好是调试插件时
     * 唯一需要的信息。像素截图是给人的；这一份是给模型的。
     */
    suspend fun describeScreen(): String = buildString {
        appendLine("当前页：" + tabName(currentTab))
        when (currentTab) {
            TAB_TASKS -> {
                appendLine(
                    "数据：待办 " + (summary.total - summary.done) + "，已完成 " + summary.done +
                        "，逾期 " + summary.overdue + "，连续 " + summary.streakDays +
                        " 天，完成率 " + summary.completionRate + "%",
                )
                appendLine(pointBlock("首页头部", Slots.HomeHeader.id))
                appendLine(pointBlock("首页区块", Slots.HomeSection.id))
                appendLine(pointBlock("首页图表", Slots.ReportChart.id))
                if (tasks.isEmpty()) appendLine(pointBlock("空状态", Slots.EmptyState.id))
                appendLine(
                    "任务列表：" + if (tasks.isEmpty()) {
                        "（空）"
                    } else {
                        tasks.take(10).joinToString("；") {
                            it.title + if (it.isDone) "（已完成）" else ""
                        }
                    },
                )
            }

            TAB_CHAT -> {
                appendLine("对话记录 " + chat.size + " 行")
                chat.lastOrNull()?.let { appendLine("最后一行：" + it.text.take(100)) }
            }

            TAB_PLUGINS -> {
                appendLine(
                    "插件 " + pluginInfos.size + " 个" +
                        if (disabledPlugins.isEmpty()) "" else "，其中停用 " + disabledPlugins.size + " 个",
                )
                pluginInfos.take(12).forEach { appendLine("- " + it.manifest.id.value) }
                appendLine(pointBlock("设置页分组", Slots.SettingsSection.id))
            }

            else -> {
                appendLine("内核错误 " + kernelErrors.size + " 条")
                kernelErrors.take(5).forEach { appendLine("- " + it) }
            }
        }
        appendLine()
        appendLine(pointBlock("全局浮层", Slots.AppOverlay.id))
        if (pendingApproval != null) appendLine("有一个待审批请求挂在界面上，等用户点允许或拒绝。")
    }

    /** 某个挂载点上现在有什么。空的时候也明说，这比什么都不说有用。 */
    private suspend fun pointBlock(label: String, point: ExtensionPointId): String {
        val items = mutableListOf<String>()
        kernel.plugins().forEach { info ->
            kernel.extensions.contributionsOf(info.manifest.id).forEach { contribution ->
                if (contribution.pointId != point) return@forEach
                val preview = when (val value = contribution.value) {
                    is TemplateSlot -> value.title to value.body
                    is TextSlot -> value.title to value.body
                    is DynamicSlot -> runCatching {
                        value.build(currentDataContext()).toPreview()
                    }.getOrElse { "（这张卡片画不出来）" to (it.message ?: "") }
                    else -> null
                } ?: return@forEach
                val body = if (preview.second.isBlank()) "" else "：" + preview.second.take(120)
                items += "- [" + contribution.owner.value + "] " + preview.first.take(60) + body
            }
        }
        return if (items.isEmpty()) label + "：（空）" else label + "：\n" + items.joinToString("\n")
    }

    private fun tabName(tab: Int): String = when (tab) {
        TAB_TASKS -> "任务"
        TAB_CHAT -> "对话"
        TAB_PLUGINS -> "插件"
        else -> "诊断"
    }

    /**
     * 切页。
     *
     * 模型看不见屏幕，用鼠标点页签基本是瞎点；但「切到插件页再看看」是它真需要的动作 ——
     * 插件卡片、设置分组、诊断错误都在各自的页上。给一个语义动作，比让它猜坐标可靠。
     */
    fun openTab(tab: String): String {
        val target = when (tab.trim().lowercase()) {
            "任务", "tasks", "task" -> TAB_TASKS
            "对话", "chat" -> TAB_CHAT
            "插件", "plugins", "plugin" -> TAB_PLUGINS
            "诊断", "diag", "diagnostics" -> TAB_DIAG
            else -> return "没有这一页：" + tab + "。只有 任务 / 对话 / 插件 / 诊断。"
        }
        currentTab = target
        return "切到了「" + tabName(target) + "」页。"
    }

    /**
     * 敲一个键。
     *
     * 输入框要回车提交、弹窗要 Esc 关，这些用剪贴板替代不了。
     * 只放行五个键：放开全键盘等于给了一只手，而这里只需要「提交」和「关掉」。
     */
    fun pressKey(key: String): String {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return "当前环境没有屏幕（headless），按不了键。"
        }
        val code = when (key.trim().lowercase()) {
            "enter", "回车" -> java.awt.event.KeyEvent.VK_ENTER
            "tab" -> java.awt.event.KeyEvent.VK_TAB
            "escape", "esc" -> java.awt.event.KeyEvent.VK_ESCAPE
            "backspace", "退格" -> java.awt.event.KeyEvent.VK_BACK_SPACE
            "space", "空格" -> java.awt.event.KeyEvent.VK_SPACE
            else -> return "只认 enter / tab / escape / backspace / space，不认「" + key + "」。"
        }
        return try {
            val robot = java.awt.Robot()
            robot.keyPress(code)
            robot.keyRelease(code)
            "按了 " + key
        } catch (t: Throwable) {
            "按不动：" + (t.message ?: (t::class.simpleName ?: "未知错误"))
        }
    }

    /** 最近一次截图覆盖的区域；click 的坐标以它为准。 */
    private var lastCapture: java.awt.Rectangle? = null

    /**
     * 把应用窗口截下来：存一份 PNG 给人看，同时缩一张给模型看。
     *
     * 存盘而不是只回一句「截好了」：用户能在对话里点开看。给模型的图按最长边
     * 1280 缩过 —— 原图塞进请求又贵又容易超限，而判「卡片画出来没有」够用了。
     */
    fun captureScreen(): ScreenCapture {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return ScreenCapture("当前环境没有屏幕（headless），截不了图。", null)
        }
        return try {
            val rect = appWindowBounds()
            val image = java.awt.Robot().createScreenCapture(rect)
            val dir = File(home, "diag/screenshots").apply { mkdirs() }
            val file = File(dir, "shot-" + System.currentTimeMillis() + ".png")
            javax.imageio.ImageIO.write(image, "png", file)
            lastCapture = rect
            ScreenCapture(
                "截图已保存：" + file.absolutePath +
                    "（" + rect.width + "×" + rect.height + " 像素，click 的坐标从这张图左上角算）",
                downscaledDataUrl(image),
            )
        } catch (t: Throwable) {
            ScreenCapture("截图失败：" + (t.message ?: (t::class.simpleName ?: "未知错误")), null)
        }
    }

    /** 把应用窗口截下来存成 PNG，返回路径；给不看图的地方用。 */
    fun takeScreenshot(): String = captureScreen().text

    /** 缩到最长边 1280 再转 data URL（OpenAI 兼容的 image_url 格式）。 */
    private fun downscaledDataUrl(image: java.awt.image.BufferedImage): String? = try {
        val maxSide = 1_280
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(image.width, image.height))
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        val scaled = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        val bytes = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(scaled, "png", bytes)
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())
    } catch (t: Throwable) {
        null
    }

    /**
     * 按最近一张截图的坐标点一下。
     *
     * 坐标越界一律拒绝：宁可让它重新截图，也不要让鼠标在用户桌面上乱跑。
     */
    fun clickScreen(x: Int, y: Int): String {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return "当前环境没有屏幕（headless），点不了。"
        }
        val rect = lastCapture
            ?: return "先 take_screenshot 一张，再按那张截图的坐标点。"
        if (x < 0 || y < 0 || x >= rect.width || y >= rect.height) {
            return "坐标 (" + x + ", " + y + ") 不在最近那张截图里（" +
                rect.width + "×" + rect.height + "）。重新截图再算坐标。"
        }
        return try {
            val robot = java.awt.Robot()
            robot.mouseMove(rect.x + x, rect.y + y)
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            robot.delay(60)
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            "点了截图坐标 (" + x + ", " + y + ")"
        } catch (t: Throwable) {
            "点不动：" + (t.message ?: (t::class.simpleName ?: "未知错误"))
        }
    }

    /**
     * 往当前焦点里打一段字。
     *
     * 走剪贴板而不是逐键敲：中文和标点用 KeyEvent 打不出来，剪贴板一次搞定；
     * 完事恢复原来的剪贴板内容，别把用户复制的东西弄丢。
     */
    fun typeText(text: String): String {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return "当前环境没有屏幕（headless），打不了字。"
        }
        return try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val old = runCatching {
                clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
            }.getOrNull()
            clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
            val robot = java.awt.Robot()
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL)
            robot.keyPress(java.awt.event.KeyEvent.VK_V)
            robot.keyRelease(java.awt.event.KeyEvent.VK_V)
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL)
            robot.delay(80)
            if (old != null) {
                clipboard.setContents(java.awt.datatransfer.StringSelection(old), null)
            }
            "输入了 " + text.length + " 个字"
        } catch (t: Throwable) {
            "打不了字：" + (t.message ?: (t::class.simpleName ?: "未知错误"))
        }
    }

    private fun appWindowBounds(): java.awt.Rectangle {
        val window = java.awt.Window.getWindows()
            .firstOrNull { it.isVisible && it.width > 300 && it.height > 200 }
        if (window != null) return window.bounds
        val size = java.awt.Toolkit.getDefaultToolkit().screenSize
        return java.awt.Rectangle(0, 0, size.width, size.height)
    }

    /**
     * 和管理员说一句话。
     *
     * 它不是「生成插件」的另一个入口，而是**插件的主人**：造新的、停旧的、
     * 改坏的、调布局都走这里。它先自己判断这轮该聊天还是该动手，动手时
     * 过程逐行吐到对话里，判断和干活用的是同一份上下文。
     *
     * 返回 null 语义没有沿用 [generatePlugin]：对话页永远有反馈（没 key 时
     * 把配置指引当一行过程贴出来），所以这里不返回可空。
     */
    suspend fun sendChat(request: String) {
        val text = request.trim()
        if (text.isEmpty() || chatBusy) return

        val config = LlmConfigLoader.load(home)
        if (config == null) {
            addChatLine(ChatRole.Step, llmSetupHint())
            return
        }

        val llm = llmOverride ?: HttpLlmClient(config)
        chatBusy = true
        addChatLine(ChatRole.User, text)
        rememberRound(chat.last().id)
        val answer = addChatLine(ChatRole.Assistant, "")
        // 原始流和「给人看的文本」分开存：工具协议是给程序读的 JSON，
        // 边流边糊进聊天气泡的话，用户看到的全是 {"tool": ...}。
        var answerText = ""
        var displayText = ""

        fun renderAnswer() {
            val index = chat.indexOfFirst { it.id == answer.id }
            if (index >= 0) chat[index] = answer.copy(text = displayText)
        }

        try {
            val catalog = CapabilityCatalog.of(kernel.extensions, RENDERED_MOUNT_POINTS)
            val installer = KernelPluginInstaller(pluginsDir, kernel)
            val selfTest = PluginSelfTest()
            val tools = PluginTools(kernel, installer, selfTest, pluginsDir, catalog)
            // 手和眼睛：模型看不见屏幕、也点不动按钮，插件调试就只能靠猜。
            val screenTools = ScreenTools(this)
            // 布局也是一只手：没有它，「把三张卡片横着排」只能被拒绝。
            val layoutTools = LayoutTools(this)
            val styleTools = UiStyleTools(this)
            val allTools = tools.all + screenTools.all + layoutTools.all + styleTools.all
            val guidance = tools.guidance + "\n\n" + screenTools.guidance +
                "\n\n" + layoutTools.guidance + "\n\n" + styleTools.guidance

            // 静态前缀一次算好：规则、工具目录、怎么说话这部分每轮都不变，
            // 提供商的前缀缓存才可能命中。变的只有状态和任务模块。
            val prompt = ManagerPrompt(
                ManagerPrompt.buildBase(catalog, allTools, guidance),
            )
            val agent = ManagerAgent(
                llm = llm,
                prompt = prompt,
                tools = allTools,
                // 不设步数额度：查五个插件、逐个试跑本来就是十几步的事。
                maxSteps = null,
                // 「试过、装上了」只适用于造/改插件。调布局、管理插件用同一道闸，
                // 会把已经摆好的说成「没做成」—— 实测会连着退六次、烧满 10 步。
                beforeFinishing = { intent ->
                    if (intent == AgentIntent.CreatePlugin) tools.reviewBeforeFinishing() else null
                },
            )

            val result = agent.respond(
                state = managerState(),
                request = text,
                history = chatHistory.toList(),
                onDelta = { delta ->
                    answerText += delta
                    displayText = visibleReply(answerText)
                    renderAnswer()
                },
                onLog = { line -> addChatLine(ChatRole.Step, line) },
            )

            if (result.text.isNotBlank()) {
                answerText = result.text
                displayText = result.text
            }
            renderAnswer()
            if (result.ok) {
                addChatLine(ChatRole.Status, "—— 本轮完成")
            } else {
                addChatLine(ChatRole.Status, "—— 本轮没做成，原因见上")
            }

            chatHistory += LlmMessage(LlmRole.User, text)
            chatHistory += LlmMessage(LlmRole.Assistant, displayText)
            while (chatHistory.size > 20) chatHistory.removeAt(0)

            // 动过插件就把界面上的东西重新取一遍：新卡片、停用状态、计数都变了。
            if (result.intent != AgentIntent.Chat && result.ok) {
                val deployed = tools.deployedId
                if (deployed != null) lastGenerated = buildPreview(PluginId(deployed))
                refresh()
            }
        } catch (cancel: CancellationException) {
            // 用户点了「停止」、或程序正在退出：半截回答留在原地，补一行说明就收。
            // 不重抛 —— 重抛会让 job 以取消收场，界面反而看不出「这一轮到底停没停」。
            addChatLine(ChatRole.Status, "—— 已停止")
        } catch (t: Throwable) {
            val reason = t.message ?: (t::class.simpleName ?: "未知错误")
            if (displayText.isEmpty()) {
                displayText = "出错了：" + reason
                renderAnswer()
            } else {
                addChatLine(ChatRole.Step, "出错了：" + reason)
            }
            addChatLine(ChatRole.Status, "—— 本轮出错，停在上面那行")
        } finally {
            persistChat()
            chatBusy = false
        }
    }

    /**
     * 一句话 -> 能装上的插件。
     *
     * 返回 null 表示**根本没开始**（没配 key），界面该显示配置指引。
     */
    suspend fun generatePlugin(request: String): AgentOutcome? {
        val config = LlmConfigLoader.load(home) ?: return null

        agentBusy = true
        agentLog = emptyList()
        try {
            // 每次现取：装了什么插件，模型能用的清单就是什么
            val catalog = CapabilityCatalog.of(kernel.extensions, RENDERED_MOUNT_POINTS)
            // 已经装着的也告诉模型。不告诉它，它会一遍遍重造同一个东西，
            // 用户看到的是一堆名字不同、功能一样的插件。
            val existing = kernel.liveCatalog().map {
                ExistingPlugin(it.id.value, it.name, it.description)
            }
            val llm = llmOverride ?: HttpLlmClient(config)
            val installer = KernelPluginInstaller(pluginsDir, kernel)
            val selfTest = PluginSelfTest()
            val system = PromptBuilder.system(catalog, existing)

            if (!researchFirst) {
                return authorByTrial(config, request, catalog, existing, installer, selfTest, llm)
            }

            // 第一步：让它自己动手查一遍。
            // 以前是「问一句、盲写一段」，它看不见已装插件、看不见有哪些事件和动作，
            // 只能猜。现在它可以用工具把环境问清楚，写完还能先试跑。
            val tools = PluginTools(kernel, installer, selfTest, pluginsDir, catalog)
            agentLog = agentLog + "第 1 步：让它先查清楚环境，再动手写"
            val research = try {
                ToolAgent(
                    llm = llm,
                    tools = tools.all,
                    maxSteps = 10,
                    beforeFinishing = { tools.reviewBeforeFinishing() },
                ).run(system + "\n\n" + tools.guidance, request) { line -> agentLog = agentLog + line }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                agentLog = agentLog + ("查环境这一步出错：" + (t.message ?: "未知错误"))
                null
            }

            val deployed = tools.deployedId
            if (deployed != null) {
                lastGenerated = buildPreview(PluginId(deployed))
                refresh()
                return AgentOutcome.Installed(deployed, 1)
            }
            if (research != null && research.refused) {
                // 它主动说不做，就照它说的回话 —— 别再退回逐轮改写逼它硬造一个。
                agentLog = agentLog + ("它没做。理由是：" + research.message)
                return AgentOutcome.Refused(research.message, 1)
            }
            if (research != null && research.ok) {
                // 它自己说做完了却没装 —— 那就是一次「老实说做不到」，原话给用户。
                agentLog = agentLog + ("它没有装。理由是：" + research.message)
                return AgentOutcome.Refused(research.message, 1)
            }

            // 第二步：查环境没成，退回逐轮改写的老路。两条路都在，比赌一条强。
            agentLog = agentLog + "改用逐轮改写：写完自查，不过就重来"
            return authorByTrial(config, request, catalog, existing, installer, selfTest, llm)
        } finally {
            agentBusy = false
        }
    }

    /** 逐轮改写：写完自查，不过就把问题原样喂回去重来。 */
    private suspend fun authorByTrial(
        config: dev.trellis.ai.LlmConfig,
        request: String,
        catalog: CapabilityCatalog,
        existing: List<ExistingPlugin>,
        installer: KernelPluginInstaller,
        selfTest: PluginSelfTest,
        llm: LlmClient,
    ): AgentOutcome {
        try {
            val flow = AgentFlow(
                llm = llm,
                catalog = catalog,
                installer = installer,
                existing = existing,
                selfTest = selfTest,
                onEvent = { event -> agentLog = agentLog + describe(event) },
            )
            val outcome = try {
                flow.run(request)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                // 网络断了、模块缺了、解析崩了：一律变成一条日志。
                // 让用户看见「出错了：xxx」，而不是一个
                // `java/lang/NoClassDefFoundError` 的原始对话框。
                val reason = t.message ?: (t::class.simpleName ?: "未知错误")
                agentLog = agentLog + ("出错了：" + reason)
                AgentOutcome.GaveUp(0, listOf(reason))
            }
            if (outcome is AgentOutcome.Installed) {
                lastGenerated = buildPreview(PluginId(outcome.pluginId))
            }
            refresh()
            return outcome
        } finally {
            agentBusy = false
        }
    }

    /**
     * 把刚装上的插件摊开给人看。
     *
     * 读的是**内核里真实的贡献**，不是模型说了什么：模型可能说自己挂了三张，
     * 实际只有一张成功注册。以内核为准。
     */
    internal suspend fun buildPreview(pluginId: PluginId): GeneratedPreview? {
        val manifest = kernel.plugin(pluginId)?.manifest ?: return null
        val context = currentDataContext()

        val cards = kernel.extensions.contributionsOf(pluginId)
            .mapNotNull { contribution ->
                val raw = when (val value = contribution.value) {
                    is TemplateSlot ->
                        kernel.extensions.renderTemplate(value.title, context) to
                            kernel.extensions.renderTemplate(value.body, context)
                    is TextSlot ->
                        kernel.extensions.renderTemplate(value.title, context) to
                            kernel.extensions.renderTemplate(value.body, context)
                    // 脚本插件的卡片是运行时画出来的节点树，得先让脚本画一遍才知道长什么样。
                    // 画失败也要给预览一句实话，不能让用户以为是「没放卡片」。
                    is DynamicSlot ->
                        runCatching { value.build(context) }
                            .map { it.toPreview() }
                            .getOrElse { t -> "（这张卡片画不出来）" to (t.message ?: "未知原因") }
                    else -> return@mapNotNull null
                }
                PreviewCard(
                    mountPointId = contribution.pointId.value,
                    mountPointLabel = Slots.byId(contribution.pointId)?.contractName
                        ?: contribution.pointId.value,
                    visible = contribution.pointId in RENDERED_MOUNT_POINTS,
                    title = raw.first,
                    body = raw.second,
                )
            }
            .sortedBy { it.mountPointId }

        return GeneratedPreview(
            pluginId = pluginId.value,
            name = manifest.name,
            description = manifest.description,
            usage = manifest.usage,
            limitation = manifest.limitation,
            cards = cards,
        )
    }

    private fun describe(event: AgentEvent): String = when (event) {
        is AgentEvent.RequestingModel -> "第 " + event.attempt + "/" + event.maxAttempts + " 次请求模型"
        is AgentEvent.ModelReplied -> "模型回了 " + event.raw.length + " 个字符"
        is AgentEvent.SelfCheckFailed ->
            "自查没过，把问题喂回去：\n" + event.problems.joinToString("\n") { "  · " + it }
        is AgentEvent.InstallFailed -> "安装失败，把原因喂回去：" + event.reason
        is AgentEvent.Installed -> "装好了：" + event.pluginId
        is AgentEvent.Refused -> "它说这个做不到：" + event.reason
    }

    var tasks by mutableStateOf<List<Task>>(emptyList())
        private set

    var summary by mutableStateOf(PlanSummary())
        private set

    var goal by mutableStateOf<Plan?>(null)
        private set

    var pluginInfos by mutableStateOf<List<PluginInfo>>(emptyList())
        private set

    /**
     * 每次刷新加一。模板卡片拿它当「该重新求值了吗」的信号。
     *
     * 这是**轮询方案的一部分**，不是最终形态：真实产品里应该由领域层发变更通知，
     * 渲染层订阅。但先让它动起来  一个每秒更新一次的假信号，
     * 比一个永远不会更新的真架构更早暴露问题。
     */
    var tick by mutableStateOf(0)
        private set

    /** 被停用的插件。它们不加载，但随时能启用回来。 */
    var disabledPlugins by mutableStateOf<Set<PluginId>>(emptySet())
        private set

    /**
     * 卡片布局：每个挂载点一组行，行里按插件排。
     *
     * 宿主原来只会一张张往下叠 —— 「把三个插件横着排」这种请求，插件再强也做不到，
     * 因为布局不在它手里。现在布局是一份用户（或 agent）可以改的数据，见 [LayoutStore]。
     */
    var cardLayout: Map<ExtensionPointId, List<List<SlotCell>>> by mutableStateOf(emptyMap())
        private set

    private lateinit var layoutStore: LayoutStore

    /**
     * 界面偏好：配色、密度、卡片外观、圆角、字号。
     *
     * 宿主原来把样式写死在 MaterialTheme 默认值里 —— 用户说「优化一下界面」，
     * agent 只能回答「做不到」。现在样式是一份可改的数据，见 [UiPrefs]；
     * agent 的改法在 [UiStyleTools]，翻译成主题在 [AppTheme]。
     */
    var uiPrefs: UiPrefs by mutableStateOf(UiPrefs())
        private set

    private lateinit var uiPrefsStore: UiPrefsStore

    /**
     * 单个插件的卡片样式覆盖：用户说「心情打卡换成填充底，别的别动」时走这里。
     *
     * 没点名的插件不出现在这个 map 里，渲染时回到 [uiPrefs] 的全局卡片样式 ——
     * 跟布局一样：偏好只影响被点名的那一张，不做连带修改。
     */
    var pluginCardStyles: Map<PluginId, PluginCardStyle> by mutableStateOf(emptyMap())
        private set

    private lateinit var pluginStyleStore: PluginStyleStore

    /** 渲染时按 owner 查覆盖；没设过返回 null，让 SlotCard 落回全局样式。 */
    fun pluginCardStyle(owner: PluginId?): DynamicCardStyle? {
        val style = owner?.let { pluginCardStyles[it] } ?: return null
        val radius = (style.radius ?: uiPrefs.radius).coerceIn(0, 24)
        return when (style.card) {
            "flat" -> DynamicCardStyle(border = false, radius = radius.dp)
            "filled" -> DynamicCardStyle(border = false, filled = true, radius = radius.dp)
            "bento" -> DynamicCardStyle(border = true, radius = radius.coerceAtMost(4).dp, hardShadow = true)
            else -> DynamicCardStyle(border = true, radius = radius.dp)
        }
    }

    /** 给 agent 的 hand：把某个插件的卡片换成 bordered / flat / filled，可带圆角。 */
    fun setPluginCardStyle(pluginId: String, card: String? = null, radius: Int? = null): String {
        val id = PluginId(pluginId.trim())
        val known = pluginInfos.firstOrNull { it.manifest.id == id }
        if (known == null) {
            val names = pluginInfos.joinToString("、") { it.manifest.id.value }
            return "没有这个插件：" + pluginId + if (names.isBlank()) "" else "。现在装着的是：" + names
        }
        if (card != null && card !in PLUGIN_CARDS) {
            return "卡片样式只认 " + PLUGIN_CARDS.joinToString(" / ") + "，不认「" + card + "」"
        }
        val old = pluginCardStyles[id] ?: PluginCardStyle()
        val next = PluginCardStyle(
            card = card ?: old.card,
            radius = if (radius == null) old.radius else radius,
        ).normalized()
        pluginCardStyles = pluginCardStyles + (id to next)
        if (::pluginStyleStore.isInitialized) pluginStyleStore.save(pluginCardStyles)
        tick += 1
        return "把 " + id.value + "（" + known.manifest.name + "）的卡片改成 " + next.card +
            (next.radius?.let { "，圆角 " + it + "dp" } ?: "，圆角跟随全局")
    }

    /**
     * 插件贡献出来的主题包（L2）。key 用 "<插件id>/<主题id>"：
     * 插件 id 全仓唯一，主题 id 只在插件内唯一，拼起来就不会撞。
     *
     * 为什么主题要跟着插件走：主题的来历、许可、版本、卸载清理全都由插件体系兜着，
     * 宿主不用再发明一套「主题市场」。插件停用/删除，它的主题自然一起消失。
     */
    fun themeOptions(): List<Pair<String, String>> {
        val contributions = runCatching {
            kernel.extensions.contributionsAt(KernelPoints.Theme)
        }.getOrElse { return emptyList() }
        return contributions.mapNotNull { contribution ->
            val theme = contribution.value as? ThemeContribution ?: return@mapNotNull null
            val key = contribution.owner.value + "/" + theme.id
            key to (theme.name + "（" + contribution.owner.value + "）")
        }
    }

    fun themeContribution(key: String): ThemeContribution? {
        val trimmed = key.trim()
        if (!trimmed.contains("/")) return null
        val owner = trimmed.substringBefore("/")
        val themeId = trimmed.substringAfter("/")
        if (owner.isBlank() || themeId.isBlank()) return null
        val contributions = runCatching {
            kernel.extensions.contributionsAt(KernelPoints.Theme)
        }.getOrElse { return null }
        return contributions.firstOrNull {
            it.owner.value == owner && (it.value as? ThemeContribution)?.id == themeId
        }?.value as? ThemeContribution
    }

    /** 当前主题的配色；没选主题（builtin）返回 null，由 AppTheme 落回配色方案。 */
    fun themeScheme(dark: Boolean): ColorScheme? {
        val key = uiPrefs.theme
        if (key.isBlank() || key == "builtin") return null
        val theme = themeContribution(key) ?: return null
        val scheme = themeSchemeOf(theme) ?: return null
        return if (dark) scheme.dark else scheme.light
    }

    /** 当前主题带的外壳参数（圆角/字族/缩放）；没有主题就是默认值。 */
    internal fun chromeStyle(): ChromeStyle = chromeOf(themeContribution(uiPrefs.theme))

    /** 数据/状态色：主题给了 extra 就用，没给就从配色方案推一组默认。 */
    internal fun extraColors(dark: Boolean): ExtraColors {
        val theme = themeContribution(uiPrefs.theme)
        val scheme = themeScheme(dark) ?: if (dark) darkSchemeFor(uiPrefs) else schemeFor(uiPrefs)
        return extraColorsOf(theme, dark, scheme)
    }

    /** 选主题：builtin 回配色方案；其它必须来自当前装着的插件、且校验通过。 */
    fun applyTheme(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isBlank() || trimmed == "builtin") {
            return updateUiPrefs(uiPrefs.copy(theme = "builtin"))
        }
        val theme = themeContribution(trimmed)
        if (theme == null) {
            val options = themeOptions()
            val names = if (options.isEmpty()) {
                "。现在没有任何插件主题，只能用 builtin"
            } else {
                "。现在能选的是：" + options.joinToString("、") { it.second + "（" + it.first + "）" }
            }
            return "没有这个主题：" + trimmed + names
        }
        val problems = themeProblems(theme.light, theme.dark) + chromeProblems(theme) +
            extraProblems(theme.extra, theme.light)
        if (problems.isNotEmpty()) {
            return "主题「" + theme.name + "」不能用：" + problems.joinToString("；")
        }
        return updateUiPrefs(uiPrefs.copy(theme = trimmed))
    }

    /**
     * 插件卡片里的图片。
     *
     * 只认插件自己目录里的文件（边界见 [pluginAssetFile]），按「路径 + 修改时间」缓存：
     * 插件更新后换了图，缓存键也跟着变，不会拿旧图糊在新卡片上。
     * 解析不出来返回 null，界面降级成 alt 文字，不显示破图。
     */
    fun pluginImage(owner: PluginId?, ref: String): ImageBitmap? {
        if (owner == null) return null
        val file = pluginAssetFile(pluginsDir, owner, ref) ?: return null
        val key = file.absolutePath + "@" + file.lastModified()
        if (imageCache.containsKey(key)) return imageCache[key]
        val bytes = runCatching { file.readBytes() }.getOrNull()
        val bitmap = bytes
            ?.takeIf { looksLikeImage(it) }
            ?.let { raw ->
                runCatching { ImageIO.read(ByteArrayInputStream(raw))?.toComposeImageBitmap() }.getOrNull()
            }
        imageCache[key] = bitmap
        return bitmap
    }

    private val imageCache = mutableMapOf<String, ImageBitmap?>()

    /**
     * 回收站：已经删除、还在 7 天后悔期里的插件。
     *
     * 它和被停用的插件**必须分开显示**：停用是「暂时不用」，删除是「我不要了」。
     * 混在一张表里，用户会以为删除没生效（因为插件还在列表里）。
     */
    var recycleBin by mutableStateOf<List<RecycleEntry>>(emptyList())
        private set

    /** 见过的 manifest 缓存  插件被停用后就不在 plugins() 里了，但界面上还想显示它的名字。 */
    private val manifestCache = mutableMapOf<PluginId, PluginManifest>()

    /** 待用户回答的审批询问。非空时界面弹出确认框。 */
    var pendingApproval by mutableStateOf<ActionRequest?>(null)
        private set

    private var approvalAnswer: CompletableDeferred<Boolean>? = null

    val kernelErrors: List<KernelError> get() = kernel.errors

    fun answerApproval(allow: Boolean) {
        pendingApproval = null
        approvalAnswer?.complete(allow)
        approvalAnswer = null
    }

    /** 某个插件贡献了什么（卡片、服务）。点开详情才需要。 */
    fun contributionsOf(id: PluginId): List<Contribution> = kernel.extensions.contributionsOf(id)

    fun servicesOf(id: PluginId): List<ServiceBinding> = kernel.extensions.servicesOf(id)

    fun openPluginsFolder() {
        runCatching { java.awt.Desktop.getDesktop().open(pluginsDir) }
    }

    suspend fun refresh() {
        goal = planService.plans().firstOrNull()
        tasks = planService.tasks()
        val now = System.currentTimeMillis()
        summary = planService.summary(now, endOfTodayMs(now))
        pluginInfos = kernel.plugins()
        // 从**已安装目录**（不是运行列表）填缓存  停用的插件也要能显示名字
        for (manifest in kernel.catalog()) manifestCache[manifest.id] = manifest
        disabledPlugins = kernel.disabledPlugins()
        recycleBin = kernel.pendingPurges()
            .map { (id, deadline) -> RecycleEntry(id, nameOf(id), deadline) }
            .sortedBy { it.deadlineMs }
        tick++
    }

    /**
     * 新建一条任务。
     *
     * [dueAt] 是截止时刻（毫秒）；界面上的「今天 / 明天 / 选日期」都换算成
     * 那一天的 23:59:59 再传进来。`null` 表示不设截止时间。
     */
    suspend fun addTask(title: String, dueAt: Long? = null) {
        if (title.isBlank()) return
        planService.addTask(title.trim(), planId = goal?.id, dueAt = dueAt)
        refresh()
    }

    suspend fun toggle(task: Task) {
        planService.setStatus(task.id, if (task.isDone) TaskStatus.TODO else TaskStatus.DONE)
        refresh()
    }

    /**
     * 删除一条任务，但给 5 秒撤回窗口。
     *
     * 为什么不一上来就弹确认框：删除是高频动作，确认框会让每次删除多一次决策；
     * 「先删、给撤回」在桌面端更顺手（删错了按撤销，撤销条自己会消失）。
     */
    suspend fun delete(task: Task) {
        planService.deleteTask(task.id)
        refresh()
        pendingDelete = PendingDelete(task, System.currentTimeMillis() + undoWindowMs)
        undoJob?.cancel()
        undoJob = undoScope.launch {
            delay(undoWindowMs)
            if (pendingDelete?.task?.id == task.id) {
                pendingDelete = null
            }
        }
    }

    /** 把刚删掉的任务原样放回来（标题/截止/状态/投入/总结都还原）。 */
    suspend fun undoDelete() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        undoJob?.cancel()
        val old = pending.task
        val restored = planService.addTask(
            old.title,
            planId = old.planId,
            dueAt = old.dueAt,
            estimateMinutes = old.estimateMinutes,
        )
        planService.save(
            restored.copy(
                status = old.status,
                spentMinutes = old.spentMinutes,
                note = old.note,
                completedAt = old.completedAt,
            ),
        )
        refresh()
    }

    /**
     * 卡片上的按钮被按下去时走这里。
     *
     * 界面只负责转发。授权、审批、执行、留痕全在内核 performAction 里 ——
     * 如果界面自己判断「这个插件能不能干」，那按钮就成了绕过审批门的后门。
     */
    suspend fun performSlotAction(owner: PluginId?, action: SlotAction): ActionOutcome =
        kernel.performAction(owner ?: PluginId("unknown"), action.call)

    suspend fun logTime(task: Task, minutes: Int) {
        planService.logTime(task.id, minutes)
        refresh()
    }

    /** 停用：不加载，但文件和数全都留着，随时能启用回来。 */
    suspend fun disablePlugin(id: String) {
        kernel.disable(PluginId(id))
        refresh()
    }

    suspend fun enablePlugin(id: String) {
        kernel.enable(PluginId(id))
        refresh()
    }

    /** 删除：停用 + 数据进入 7 天回收窗口。 */
    suspend fun removePlugin(id: String) {
        kernel.remove(PluginId(id))
        refresh()
    }

    /**
     * 彻底删除：立刻清掉回收站里的一个插件，不等 7 天。
     *
     * 不可撤销。所以按钮在界面上要点两下，而且只在回收站里出现
     * 已经在「已停用」里的插件不该长这个按钮，那会把一个能一键回来的状态变成不可逆。
     */
    suspend fun purgePlugin(raw: String) {
        kernel.purgeNow(PluginId(raw))
        refresh()
    }

    /** 停用的插件不在 plugins() 里，名字只能靠缓存。 */
    fun nameOf(id: PluginId): String = manifestCache[id]?.name?.ifBlank { id.value } ?: id.value

    fun isBuiltin(id: PluginId): Boolean = manifestCache[id]?.builtin ?: false

    suspend fun shutdown() {
        chatScope.cancel()
        undoScope.cancel()
        if (this::kernel.isInitialized) kernel.shutdown()
    }

    /** 这个插件声明了哪些能力、已经拿到哪些。 */
    fun capabilitiesOf(id: PluginId): Pair<Set<CapabilityId>, Set<CapabilityId>> {
        val info = kernel.plugins().firstOrNull { it.manifest.id == id }
        val declared = info?.manifest?.capabilities ?: emptySet()
        return declared to kernel.grants.grantedTo(id)
    }

    /**
     * 授予权限，然后**重载这个插件**。
     *
     * 为什么必须重载：能力是在 `init()` 时算好交给插件的
     * （`ctx.capabilities` 是一个固定集合）。不重启它，插件不会知道自己刚拿到了新权限。
     */
    suspend fun grantAndReload(id: PluginId, capabilities: Set<CapabilityId>) {
        kernel.grants.grant(id, capabilities)
        kernel.uninstall(id)
        kernel.install(id)
        refresh()
    }

    private fun wireApprovalDialog() {
        val gate = kernel.approvals as? DefaultApprovalGate
        if (gate == null) {
            // 这一条要是发生，所有需要人工批准的操作都会被内核按安全默认值拒绝，
            // 界面上却什么都不会出现。所以必须留痕，不然只能靠猜。
            appLog("审批界面没接上：kernel.approvals 不是 DefaultApprovalGate")
            return
        }
        gate.setManualHandler { request ->
            appLog(
                "请求确认：" + request.action + " / 插件 " + request.pluginId.value +
                    " / " + request.actor + " / " + request.capability.value,
            )
            val answer = CompletableDeferred<Boolean>()
            approvalAnswer = answer
            pendingApproval = request
            val approved = answer.await()
            appLog((if (approved) "已允许：" else "已拒绝：") + request.action)
            approved
        }
    }

    /** 审批这件事必须能在磁盘上复盘：界面上「什么都没发生」是最难查的故障。 */
    private fun appLog(line: String) {
        runCatching {
            File(home, "app.log").appendText(
                "[" + java.time.Instant.now() + "] " + line + "\n",
                Charsets.UTF_8,
            )
        }
    }

    /** 某个挂载点当前的排版：外层是行，内层是同一行的格子（插件 + 栏数）。空 = 全部纵向。 */
    fun layoutFor(point: ExtensionPointId): List<List<SlotCell>> = cardLayout[point].orEmpty()

    /**
     * 给某个挂载点排版。
     *
     * 只认**已经装好、并且真的往这个点交过卡片**的插件。写错一个 id 就整份拒绝，
     * 不静默排一半 —— 静默的那一半在界面上看不出问题，只会让人以为布局坏了。
     */
    fun arrangeCards(point: String, rows: List<List<SlotCell>>): String {
        val pointId = ExtensionPointId(point)
        val extensionPoint = Slots.byId(pointId)
        if (pointId !in RENDERED_MOUNT_POINTS || extensionPoint == null) {
            return "这个挂载点现在没上屏：" + point +
                "。能排的是：" + RENDERED_MOUNT_POINTS.joinToString("、") { it.value }
        }
        val available = kernel.extensions.contributionsAt(extensionPoint).map { it.owner }.toSet()
        val cleaned = rows.map { row -> row.filter { it.pluginId.value.isNotBlank() } }
            .filter { it.isNotEmpty() }
        val unknown = cleaned.flatten().map { it.pluginId }.filter { it !in available }.distinct()
        if (unknown.isNotEmpty()) {
            return "这些插件没往 " + point + " 交卡片，排不了：" +
                unknown.joinToString("、") { it.value } +
                "。这个点上现在有卡片的插件是：" +
                available.joinToString("、") { it.value }.ifEmpty { "（空的）" }
        }
        val layout = if (cleaned.isEmpty()) emptyList() else cleaned
        cardLayout = if (layout.isEmpty()) cardLayout - pointId else cardLayout + (pointId to layout)
        if (::layoutStore.isInitialized) layoutStore.save(cardLayout)
        tick += 1
        return if (layout.isEmpty()) {
            "清空了 " + point + " 的排版，恢复成一张张往下叠。"
        } else {
            "排好了 " + point + "：" + layout.joinToString("；") { row ->
                row.joinToString(" | ") { cell ->
                    if (cell.span <= 1) cell.pluginId.value else cell.pluginId.value + ":" + cell.span
                }
            }
        }
    }

    /**
     * 改界面偏好。
     *
     * 非法值不拒绝整份，而是按 [UiPrefs.normalized] 收进合法区间，再把实际生效的值回给
     * 调用方 —— agent 改错一个字段也不会把界面改成不可用，而且它立刻知道真实结果。
     */
    fun updateUiPrefs(next: UiPrefs): String {
        val clean = next.normalized()
        uiPrefs = clean
        if (::uiPrefsStore.isInitialized) uiPrefsStore.save(clean)
        tick += 1
        return "界面偏好已更新：" + clean.describe()
    }

    /** 这个点上有哪些卡片，给 list_cards 用：插件 id -> 卡片标题。 */
    fun cardsOn(point: ExtensionPointId): List<Pair<PluginId, String>> {
        val extensionPoint = Slots.byId(point) ?: return emptyList()
        return kernel.extensions.contributionsAt(extensionPoint).map { contribution ->
            contribution.owner to when (val value = contribution.value) {
                is TemplateSlot -> value.title.ifBlank { "（卡片）" }
                is TextSlot -> value.title.ifBlank { "（卡片）" }
                else -> "（脚本卡片）"
            }
        }
    }

    companion object {

        /** [home] 可注入，测试就不必污染真实的家目录。 */
        suspend fun create(
            home: File = System.getenv("TRELLIS_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".trellis"),
        ): AppRuntime {
            val runtime = AppRuntime()
            val storeFile = File(home, "store.json")
            val pluginsDir = File(home, "plugins").apply { mkdirs() }

            val kernel = Kernel.boot(
                KernelConfig(
                    sdkVersion = SemVer.parse("1.0.0"),
                    platform = Platform.JVM,
                    pluginsDir = pluginsDir.absolutePath,
                    loaders = listOf(
                        BuiltinLoader(listOf(PlanCorePlugin(), DangerZonePlugin(), PlanUiPlugin(runtime))),
                        DslLoader(JvmPluginFileSystem()),
                        ScriptLoader(JvmPluginFileSystem()),
                    ),
                    storage = FileStorageBackend(storeFile),
                    // 「删除」要真的动文件：移进 <plugins>/.trash，7 天后连数据一起清。
                    pluginFiles = JvmPluginFileStore(pluginsDir),
                    // 刻意**不**在这里挂授权对话框：
                    // 内核启动时会调用它，而那时界面还没起来  弹不出框也答不了，
                    // 唯一的结果就是死锁（这个坑真踩到了）。
                    // 授权改成「插件详情页里点一下」，见 grantAndReload()。
                ),
            )
            val plan = kernel.extensions.service(PlanService::class)
                ?: error("core.plan 没有发布 PlanService")

            runtime.kernel = kernel
            runtime.planService = plan
            runtime.storeFile = storeFile
            runtime.pluginsDir = pluginsDir
            runtime.home = home
            runtime.layoutStore = LayoutStore(home)
            runtime.cardLayout = runtime.layoutStore.load()
            runtime.uiPrefsStore = UiPrefsStore(home)
            runtime.uiPrefs = runtime.uiPrefsStore.load()
            runtime.pluginStyleStore = PluginStyleStore(home)
            runtime.pluginCardStyles = runtime.pluginStyleStore.load()
            runtime.chatStore = ChatStore(home)
            runtime.loadChatSessions()
            // 聊天器的默认行为就是「接着上次说」：启动时把最近一段接回去，
            // 而不是每次都从空白开始、让人以为记录丢了。
            runtime.chatSessions.firstOrNull()?.let { runtime.openChat(it.id) }
            runtime.wireApprovalDialog()

            if (plan.plans().isEmpty()) plan.createPlan("我的第一个计划")
            runtime.refresh()
            return runtime
        }
    }
}

/**
 * 启动时落在哪一页。默认任务页；`TRELLIS_TAB=chat|plugins|diag` 可用于演示/截图，
 * 省得每次手动点导航（也省得用鼠标自动化去点 —— 那个把窗口点最小化过）。
 */
private fun initialTab(): Int = when (System.getenv("TRELLIS_TAB")?.trim()?.lowercase()) {
    "chat" -> TAB_CHAT
    "plugins" -> TAB_PLUGINS
    "diag" -> TAB_DIAG
    else -> TAB_TASKS
}

/**
 * 「今天」的边界由**界面层**给出，领域层不该猜时区  这是 PlanService.summary 的约定。
 * 这里用 JVM 的系统时区算，Android 上换成平台 API 即可。
 */
private fun endOfTodayMs(nowMs: Long): Long {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(nowMs)
        .atZone(zone)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
}

/** 回收站里的一项：删掉的插件，以及它什么时候会被真正清掉。 */
data class RecycleEntry(val id: PluginId, val name: String, val deadlineMs: Long) {

    /** 还剩几天。向上取整  剩 3 小时也该说「还有 1 天」，说 0 天像过期了。 */
    fun daysLeft(nowMs: Long): Int =
        ((deadlineMs - nowMs + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(0)
}
