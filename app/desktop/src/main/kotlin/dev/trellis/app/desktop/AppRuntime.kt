package dev.trellis.app.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.LlmConfig
import dev.trellis.ai.LlmImport
import dev.trellis.ai.HttpLlmClient
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmConfigLoader
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import dev.trellis.ai.manager.AgentIntent
import dev.trellis.ai.manager.ManagerAgent
import dev.trellis.ai.tool.DeepSeekMarkup
import dev.trellis.ai.manager.ManagerPrompt
import dev.trellis.ai.manager.ManagerRouter
import dev.trellis.domain.Plan
import dev.trellis.domain.PlanService
import dev.trellis.domain.PlanSummary
import dev.trellis.domain.Task
import dev.trellis.domain.TaskId
import dev.trellis.domain.TaskStatus
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.DayView
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.LearningPlanInfo
import dev.trellis.domain.plan.OverdueView
import dev.trellis.domain.plan.PoolUnit
import dev.trellis.domain.plan.RhythmResult
import dev.trellis.domain.plan.WeekDay
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.activity.ActivityAggregator
import dev.trellis.activity.ActivityClassifier
import dev.trellis.activity.ActivitySettings
import dev.trellis.activity.ActivityStore
import dev.trellis.activity.ActivitySync
import dev.trellis.activity.ActivityTaxonomy
import dev.trellis.activity.ActivityTaxonomyStore
import dev.trellis.activity.ActivityWatchClient
import dev.trellis.activity.AppSlice
import dev.trellis.activity.ClassificationExplanation
import dev.trellis.activity.DaySummary
import dev.trellis.activity.RangeSummary
import dev.trellis.activity.Segment
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
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.approval.ActionRequest
import dev.trellis.kernel.approval.DefaultApprovalGate
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.audit.AuditEntry
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
import java.time.LocalDate
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

/** 计划页：上面按周排，下面看选中周里选中那一天的安排。 */
internal const val TAB_PLAN = 1

internal const val TAB_CHAT = 2
internal const val TAB_PLUGINS = 3
internal const val TAB_DIAG = 4

/** 设置页：宿主自己认的设置项都在这一页，插件也能在这里挂卡片。 */
internal const val TAB_SETTINGS = 5

/**
 * 活动页：ActivityWatch 原始数据分类后的日视图与范围总览。
 *
 * 序号放 6 而不是插进中间：前进/后退/持久化都可能按数字记页，改老页面的编号会把
 * 「上次停在设置」变成「停在别的页」。导航顺序由 AppScreen 的 NAV_TABS 决定，跟数字无关。
 */
internal const val TAB_ACTIVITY = 6

/**
 * 知识库页：本地 Markdown 笔记（`<home>/knowledge/notes/` 下每个 `.md` 一篇）。
 *
 * 序号同样往后放而不是插进中间，理由见 [TAB_ACTIVITY]。
 */
internal const val TAB_KNOWLEDGE = 7

internal val RENDERED_MOUNT_POINTS: Set<ExtensionPointId> = setOf(
    Slots.HomeHeader.id,
    Slots.HomeSection.id,
    Slots.EmptyState.id,
    Slots.AppOverlay.id,
    Slots.ReportChart.id,
    Slots.SettingsSection.id,
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

    /**
     * 应用唯一时间源（规范见 `docs/time-conventions.md`）。
     *
     * 业务时间（今天 / 本周 / 事件时间戳）只能从这里取；计时器/超时仍走单调时间。
     * 默认是真实时钟；开发者模式下可以拨「应用内今天」（见 [applySimulatedToday]）。
     */
    lateinit var clock: DebugTrellisClock
        private set

    /** 当前模拟日期；null = 真实时间。界面用它显示「模拟时间」徽章。 */
    var simulatedToday by mutableStateOf<LocalDate?>(null)
        private set

    /** 开发者模式（`TRELLIS_DEV=1`）：设置页显示调试区。 */
    internal var devModeEnabled: Boolean = false
        private set

    /** 当前数据目录是否允许改模拟时间（默认目录不允许，防止模拟日期污染真实数据）。 */
    internal var devClockAllowed: Boolean = false
        private set

    /** 请求了模拟但被拒绝的原因；给日志和设置页显示。 */
    internal var devClockRejectedReason: String? = null
        private set

    private lateinit var devClockStore: DevClockStore

    /**
     * 是否正在用模拟时间。计划页/对话页顶部要显示徽章——不允许静默生效。
     */
    val isSimulatingTime: Boolean get() = simulatedToday != null

    /**
     * 开发者模式：把「应用内今天」拨到指定日期；传 null 回真实时间。
     *
     * 真的会写计划数据（结算旧周、补承诺、生成复盘），所以默认数据目录被拒绝；
     * 允许的前提是 `TRELLIS_HOME` 指向非默认目录，或显式 `TRELLIS_DEV_ALLOW_REAL_HOME=1`。
     */
    fun applySimulatedToday(date: LocalDate?) {
        if (!devClockAllowed) {
            planMessage = devClockRejectedReason ?: "当前数据目录不允许模拟时间（换 TRELLIS_HOME 再试）"
            return
        }
        clock.setSimulatedDate(date)
        simulatedToday = date
        devClockStore.save(date)
        appendAppLog(if (date != null) "调试：模拟时间 → $date" else "调试：退出模拟时间（回真实时间）")
        // 拨完时间要立刻让计划自己走一遍：结算/补承诺/铺天/复盘都在 refreshPlan 里触发。
        refreshPlan()
        tick++
    }

    fun jumpSimulatedDays(days: Long) {
        val base = simulatedToday ?: clock.today()
        applySimulatedToday(base.plusDays(days))
    }

    fun jumpSimulatedWeeks(weeks: Long) {
        val base = simulatedToday ?: clock.today()
        applySimulatedToday(base.plusWeeks(weeks))
    }

    /**
     * 测试用：把模型换成假的。
     *
     * 生产的默认实现是 [HttpLlmClient]。留这个口子是为了能端到端测整条链路
     * （假模型 -> 真校验 -> 真装进内核 -> 界面上真的多一张卡片），
     * 而不用发一次网络请求、也不用一个真 key。
     */
    internal var llmOverride: LlmClient? = null

    /** 有没有配模型 key。没配时界面给配置指引，而不是让它失败。 */
    fun llmConfigured(): Boolean = LlmConfigLoader.load(home) != null

    fun llmSetupHint(): String = LlmConfigLoader.setupHint(home)

    /** 现在生效的模型配置（环境变量优先）；没配返回 null。 */
    fun llmConfig(): LlmConfig? = LlmConfigLoader.load(home)

    /** 这份配置从哪来：环境变量 / llm.json / 未配置。 */
    fun llmSource(): String = LlmConfigLoader.source(home)

    /**
     * 一次性小调用用的客户端（知识库抽概念这类后台整理）。
     *
     * 没配模型返回 null：调用方直接跳过，不要弹错误——「没配 key」不是错误，
     * 只是这个增强功能暂时不生效（用户仍可手动标概念）。
     */
    internal fun llmClientOrNull(): LlmClient? {
        val config = LlmConfigLoader.load(home) ?: return null
        return llmOverride ?: HttpLlmClient(config)
    }

    /** 一次导入的结果：失败时 message 是能照着改的一句话。 */
    data class LlmImportOutcome(val ok: Boolean, val message: String)

    /**
     * 「一键导入」：把用户粘的东西解析成 [LlmConfig]，写进 `llm.json`。
     *
     * 客户端是每次发消息才按配置新建的（见 [ask]），所以这里写完就生效，不用重启。
     * 唯一会「导入成功但不生效」的情况是环境变量覆盖了文件 —— 那句话必须说出来。
     */
    fun importLlmConfig(raw: String, endpoint: String, model: String): LlmImportOutcome {
        val result = LlmImport.parse(raw, endpoint, model)
        val config = result.config
            ?: return LlmImportOutcome(false, result.error ?: "这段没法识别成一份配置。")
        LlmConfigLoader.save(home, config)
        tick += 1
        val host = config.endpoint.substringAfter("://").substringBefore('/')
        val tail = if (llmSource() == "环境变量") {
            "。但当前生效的是环境变量 " + LlmConfigLoader.ENV_KEY + "，改文件不会覆盖它。"
        } else {
            "，下一句就能用。"
        }
        return LlmImportOutcome(true, "已导入 " + config.model + " @ " + host + tail)
    }

    // ---- 删除撤销 ----

    /** 刚被删掉、还能撤销的那条任务。 */
    data class PendingDelete(val task: Task, val deadlineMs: Long)

    var pendingDelete by mutableStateOf<PendingDelete?>(null)
        private set

    private var undoJob: Job? = null

    private val undoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 撤回窗口：5 秒，够看清「删错了」，又不至于让界面一直挂着一个条。 */
    private val undoWindowMs = 5_000L

    // ---- 计时器小窗 ----

    /** 计时窗口开着没有。关窗会收掉节点计时还开着的 session，但不会往待办任务里写东西。 */
    var timerVisible by mutableStateOf(false)
        private set

    var timerTaskId by mutableStateOf<TaskId?>(null)
        private set

    var timerTaskTitle by mutableStateOf("")
        private set

    var timerMode by mutableStateOf(FocusMode.Countdown)
        private set

    /** 还没开始时选的番茄钟长度（分钟）。 */
    var timerMinutes by mutableStateOf(25)
        private set

    /** 倒计时的总秒数；正计时恒为 0。 */
    var timerTotalSeconds by mutableStateOf(0)
        private set

    var timerElapsedSeconds by mutableStateOf(0)
        private set

    var timerRunning by mutableStateOf(false)
        private set

    var timerFinished by mutableStateOf(false)
        private set

    /**
     * 计时器现在计的是学习计划的哪个节点；计待办任务时是 null。
     *
     * 节点不是 [Task]，落不了 [logTime]，所以走计划侧的 session（`source = "timer"`）。
     */
    internal var timerNodeTarget by mutableStateOf<TimerNodeTarget?>(null)
        private set

    /** 节点计时开着的那段 session；正式点「开始」才创建，收掉就置空。 */
    internal var timerSessionId by mutableStateOf<String?>(null)
        private set

    /** 现在计的是计划节点（不是待办任务）：小窗据此把「直接记 25 分钟」藏起来。 */
    val timerOnPlanNode: Boolean
        get() = timerNodeTarget != null

    /** 已经开过表（或跑过）——这时不再允许切换模式/改分钟数，免得把进行中的计时改乱。 */
    val timerStarted: Boolean
        get() = timerElapsedSeconds > 0 || timerRunning || timerFinished

    /** 从任务行点 ⏱ 进来：默认番茄钟 25 分钟，等人点「开始」。 */
    fun openTimer(task: Task) {
        endNodeSession()
        timerNodeTarget = null
        timerTaskId = task.id
        timerTaskTitle = task.title
        timerVisible = true
        timerMode = FocusMode.Countdown
        timerMinutes = 25
        timerTotalSeconds = 0
        timerElapsedSeconds = 0
        timerRunning = false
        timerFinished = false
    }

    fun closeTimer() {
        endNodeSession()
        timerNodeTarget = null
        timerVisible = false
        timerRunning = false
    }

    fun chooseTimerMode(mode: FocusMode) {
        if (timerStarted) return
        timerMode = mode
    }

    fun bumpTimerMinutes(delta: Int) {
        timerMinutes = clampTimerMinutes(timerMinutes + delta)
    }

    fun chooseTimerMinutes(minutes: Int) {
        timerMinutes = clampTimerMinutes(minutes)
    }

    fun startTimer() {
        timerTotalSeconds = if (timerMode == FocusMode.Countdown) timerMinutes * 60 else 0
        timerElapsedSeconds = 0
        timerFinished = false
        timerRunning = true
        startNodeSession()
    }

    /** 「再来一轮」：同一任务、同样的长度，重新开始。 */
    fun restartTimer() {
        startTimer()
    }

    fun toggleTimerPause() {
        if (timerFinished) return
        if (timerElapsedSeconds >= timerTotalSeconds && timerMode == FocusMode.Countdown && timerTotalSeconds > 0) return
        timerRunning = !timerRunning
    }

    /** 番茄钟加时：总时长变长，接着跑。 */
    fun extendTimer(minutes: Int) {
        if (timerMode != FocusMode.Countdown) return
        timerTotalSeconds += minutes * 60
        timerFinished = false
        timerRunning = true
    }

    /** 每秒被窗口里的协程调一次。倒计时到 0 就停住并置 finished。 */
    fun timerTick(seconds: Int = 1) {
        if (!timerRunning) return
        val step = advanceTimer(timerElapsedSeconds, seconds, timerTotalSeconds, timerMode)
        timerElapsedSeconds = step.elapsedSeconds
        if (step.finished) {
            timerFinished = true
            timerRunning = false
        }
    }

    /** 不跑计时、直接把固定分钟数记到这条任务上（老的「记 25 分钟」挪进了窗口）。 */
    suspend fun logManualMinutes(minutes: Int) {
        val task = timerTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
        if (task != null) logTime(task, minutes)
        closeTimer()
    }

    /**
     * 结束并记入。
     *
     * 待办任务记「投入分钟」（不足一分钟按一分钟算）；计划节点没有分钟字段，落的是 session ——
     * 计划侧要的是「这段时间在做什么」，分钟由 session 的起止自己算。
     */
    suspend fun stopTimerAndLog() {
        val minutes = minutesToLog(timerElapsedSeconds)
        val node = timerNodeTarget
        if (node != null) {
            endNodeSession()
            if (minutes > 0) planMessage = "记了 $minutes 分钟：" + node.title
        } else {
            val task = timerTaskId?.let { id -> tasks.firstOrNull { it.id == id } }
            if (task != null && minutes > 0) logTime(task, minutes)
        }
        closeTimer()
    }

    /** 从待办板的计划节点点 ⏱ 进来：和任务共用同一个小窗，只是落账走 session。 */
    internal fun openNodeTimer(node: PlanNodeRow) {
        val occurrenceId = node.occurrenceId ?: return
        endNodeSession()
        timerNodeTarget = TimerNodeTarget(node.planId, occurrenceId, node.title)
        timerTaskId = null
        timerTaskTitle = node.title
        timerVisible = true
        timerMode = FocusMode.Countdown
        timerMinutes = 25
        timerTotalSeconds = 0
        timerElapsedSeconds = 0
        timerRunning = false
        timerFinished = false
    }

    /**
     * 把节点计时的 session 开起来。
     *
     * 失败只提示、不拦住计时：记不上账是账本的问题，不该让秒表停下来。
     */
    private fun startNodeSession() {
        val target = timerNodeTarget ?: return
        if (timerSessionId != null) return
        val service = learningPlan() ?: return
        val result = runCatching { service.startSession(uiCommand(target.planId), target.occurrenceId, "timer") }
            .getOrElse {
                planMessage = "这段计时没能记进计划：" + (it.message ?: "未知原因")
                return
            }
        val ref = result.value
        if (!result.ok || ref == null) {
            planMessage = result.error?.message ?: "这段计时没能记进计划。"
            return
        }
        timerSessionId = ref.sessionId
    }

    /**
     * 收掉节点计时的 session（没开过就什么都不做）。
     *
     * session 记的是墙上的时间段，不是计时器数出来的秒数 —— 中间暂停过两者会差一点，
     * 但计划侧要的是「这段时间在用」，差这点不影响判断。
     */
    private fun endNodeSession() {
        val sessionId = timerSessionId ?: return
        timerSessionId = null
        val planId = timerNodeTarget?.planId
        val service = learningPlan() ?: return
        runCatching { service.endSession(uiCommand(planId), sessionId) }
            .onFailure { planMessage = "这段计时没记上：" + (it.message ?: "未知原因") }
    }

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
     * 上一轮留给下一轮的意图：它问完问题、等用户点选项时（`ask_choices`）记下；
     * 或者那一轮动手中途失败时记下，方便用户说「再试一次」时沿用同一套工具。
     *
     * 真实事故：AI 问「每天能投入多少分钟」，用户点「每天 60 分钟」——
     * 这句没有任何意图关键词，重新分类成 Chat（零工具），模型想调
     * `create_learning_plan` 却调不到，只能回「我这边没有那只工具」。
     */
    private var pendingManagerIntent: AgentIntent? = null

    /**
     * 当前在哪一页。
     *
     * 被提到这里来，不是为了别的：agent 要能回答「我现在看到的是什么」。
     * 页签状态留在 Compose 里，它对屏幕就永远是瞎的 —— `describe_screen` 只能猜。
     */
    var currentTab by mutableStateOf(initialTab())

    /** 知识库上次打开的笔记 id；切页回来接着看同一篇（只记在内存，不进配置）。 */
    var knowledgeNoteId by mutableStateOf<String?>(null)

    private var nextChatLineId = 1L

    /**
     * 本轮工作清单在 [chat] 里的行 id；没有清单时为 null。
     *
     * 只记一个：「一轮一个清单」是刻意的限制 —— 同时跑两张清单，界面没法回答
     * 「现在卡在哪一步」，用户也读不过来。中途要改计划就覆盖它。
     */
    private var activePlanId: Long? = null

    /** 会话列表（按最近说话排序）；左边栏用。 */
    val chatSessions = mutableStateListOf<ChatSession>()

    /** 当前会话 id；null = 这一段还没落盘（发第一句后才有 id）。 */
    var currentChatId by mutableStateOf<String?>(null)
        private set

    private lateinit var chatStore: ChatStore

    /** 「计划检查」主动消息的「今天已经发过」标记（`<home>/plan-check.json`）。 */
    private lateinit var planCheckStore: PlanCheckStore

    /** 会话列表里有没有没看过的主动消息：导航「对话」和对应会话行据此显示红点。 */
    val hasUnreadChat: Boolean get() = chatSessions.any { it.unread }

    private var planCheckJob: Job? = null

    /** 计划检查串行化：启动那一次和焦点触发可能同时到，别把同一条消息发两遍。 */
    private val planCheckMutex = Mutex()

    /** 知识库笔记：文件是真相，以后加的索引只是从这些文件重建的投影。 */
    internal lateinit var knowledgeStore: KnowledgeStore

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

    /** 演示/截图用：启动后过两秒把一句话发出去，走的是完整流程。 */
    internal fun launchDemoSend(line: String) {
        chatScope.launch {
            kotlinx.coroutines.delay(2500)
            sendChat(line)
        }
    }

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
        // 模型有时走它自己那套标记语言调工具（见 DeepSeekMarkup）：整块都别显示。
        val markup = DeepSeekMarkup.indexOf(raw)
        if (markup >= 0 && (cut < 0 || markup < cut)) cut = markup
        return if (cut < 0) raw else raw.substring(0, cut).trimEnd()
    }

    /**
     * 带进对话页的草稿。
     *
     * 插件页点「让 AI 改」时先把这句话放这儿再切页 —— 用户落地就能顺着说完，
     * 不用自己回忆插件 id。对话页取走即清空。
     */
    var pendingChatPrompt by mutableStateOf<String?>(null)
        private set

    /**
     * 这一轮里模型要求用户拍板的选项，贴在输入框上方、竖着排。
     *
     * 只在**模型主动要问**时出现（它调 `ask_choices`），不是运行时替它猜下一步。
     * 以前这里是运行时按「查过资源但没落待办」自动塞三个按钮，看着聪明，其实越界：
     * 要不要动待办是用户的选择，不是运行时的推断。
     */
    var chatChoices by mutableStateOf<List<ChatChoice>>(emptyList())
        private set

    /** 模型开了个选择：把选项竖着摆到输入框上方，等用户点。 */
    fun offerChoices(options: List<String>) {
        val clean = options.map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        if (clean.isEmpty()) return
        chatChoices = clean.map { ChatChoice(it, it) }
    }

    /** 点了某个选项：把选项里的原话当成用户发的，接着跑。 */
    fun applyChoice(choice: ChatChoice) {
        chatChoices = emptyList()
        ask(choice.text)
    }

    fun dismissChoices() {
        chatChoices = emptyList()
    }

    /** 从别的页面把一句话带进对话：切页 + 预填草稿。 */
    fun askInChat(text: String) {
        pendingChatPrompt = text
        currentTab = TAB_CHAT
    }

    /** 对话页取走草稿后清空，免得下次进对话页又被填一遍。 */
    fun takePendingChatPrompt() {
        pendingChatPrompt = null
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
        // 打开即已读：用户人就在这段里，红点不该还留着。
        markChatRead(session.id)
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

    /** 打开/正在看这段会话：清掉未读并落盘（只改这一段，不重排列表）。 */
    fun markChatRead(id: String) {
        val index = chatSessions.indexOfFirst { it.id == id }
        if (index < 0 || !chatSessions[index].unread) return
        val session = chatSessions[index].copy(unread = false)
        chatSessions[index] = session
        chatStore.save(session)
    }

    /**
     * 主窗口重新获得焦点：检查一次计划，需要时往「计划检查」会话里留一条主动消息。
     *
     * 触发是**状态事件**不是定时器：应用启动、从别的窗口切回来都会走这里；
     * 每天最多一次（[PlanCheckStore]），没逾期就什么都不发。重活在后端查询，
     * 这里不调模型——文案是确定的，免得「AI 提醒」变成又一段需要读的长篇。
     */
    fun onWindowFocused() {
        if (planCheckJob?.isActive == true) return
        planCheckJob = chatScope.launch {
            runCatching { maybePostPlanCheck() }
                .onFailure { appendAppLog("计划检查失败：" + it) }
        }
    }

    /**
     * 计划检查：读后端事实 → 需要时往全局「计划检查」会话追加一条普通 Assistant 行。
     *
     * 只提示、不动数据：真正的重排/补做仍由用户回话后走工具与确认。
     * 不自动切页、不打断正在跑的一轮（`chatBusy` 时直接跳过，焦点下次再来）。
     */
    internal suspend fun maybePostPlanCheck() {
        planCheckMutex.withLock { postPlanCheckIfDue() }
    }

    private suspend fun postPlanCheckIfDue() {
        if (chatBusy) return
        if (!::planCheckStore.isInitialized) return
        val today = clock.today().toString()
        if (planCheckStore.lastDay() == today) return
        val service = learningPlan() ?: return
        val planId = service.activePlanId() ?: return
        val view = service.overdue(planId, today)
        if (view.thisWeek.isEmpty() && view.backlog.isEmpty()) return
        val planTitle = service.plans().firstOrNull { it.id == planId }?.title ?: "学习计划"
        val now = clock.nowMs()
        val existing = chatStore.read(PLAN_CHECK_SESSION_ID)
        val lines = (existing?.lines ?: emptyList()) + ChatLine(
            id = (existing?.lines?.maxOfOrNull { it.id } ?: 0L) + 1,
            role = ChatRole.Assistant,
            text = buildPlanCheckText(planTitle, view),
            atMs = now,
        )
        chatStore.save(
            ChatSession(
                id = PLAN_CHECK_SESSION_ID,
                title = PLAN_CHECK_TITLE,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lines = lines,
                unread = true,
            ),
        )
        planCheckStore.save(today)
        loadChatSessions()
        appendAppLog(
            "计划检查：$today 留下主动消息（本周隔日 ${view.thisWeek.size} 项，欠账 ${view.backlog.size} 项）",
        )
    }

    /** 计划检查的确定性文案：不靠模型数数，也和工具循环无关，用户回什么都行。 */
    private fun buildPlanCheckText(planTitle: String, view: OverdueView): String {
        val out = StringBuilder()
        out.append("🔔 计划检查「").append(planTitle).append("」\n")
        if (view.thisWeek.isNotEmpty()) {
            out.append("\n本周有 ").append(view.thisWeek.size).append(" 个排期没做完：")
            view.thisWeek.groupBy { it.day }.forEach { (day, items) ->
                out.append("\n- ").append(day.removePrefix(view.week.substring(0, 4) + "-"))
                    .append("：")
                    .append(items.joinToString("、") { it.title })
            }
            out.append("\n")
        }
        if (view.backlog.isNotEmpty()) {
            out.append("\n还有 ").append(view.backlog.size).append(" 个在未安排桶里：")
            out.append(view.backlog.take(5).joinToString("、") { it.title })
            if (view.backlog.size > 5) out.append(" 等")
            out.append("\n")
        }
        out.append("\n今天到周日还剩约 ").append(view.remainingMinutes).append(" 分钟。")
        out.append("\n想怎么处理？回我就行：补做、顺延、少排一点，或者这周先放过。")
        return out.toString()
    }

    /**
     * 把当前这一段落盘。
     *
     * 每轮结束（含停止、出错）都存：用户宁可看到半截记录，也不愿意「刚才那几句没了」。
     * 标题取第一句用户发言 —— 事后找会话时，人认的是「我当时问的那句话」。
     */
    private fun persistChat() {
        if (!::chatStore.isInitialized || chat.isEmpty()) return
        val now = clock.nowMs()
        val id = currentChatId ?: ("chat-" + now)
        currentChatId = id
        val existing = chatStore.read(id)
        // 全局「计划检查」会话是一段常驻的检查线程：标题不跟着用户回的那句话改。
        val title = existing?.title?.takeIf { it == PLAN_CHECK_TITLE }
            ?: chat.firstOrNull { it.role == ChatRole.User }
                ?.text?.trim()?.take(24)?.takeIf { it.isNotBlank() } ?: "新对话"
        chatStore.save(ChatSession(id, title, existing?.createdAt ?: now, now, chat.toList()))
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
     * agent 交来一份计划：开清单，插在「用户那句话」下面。
     *
     * 为什么插在那儿而不是追加到末尾：清单要回答的是「你打算怎么干」，
     * 它得在应答之前出现；追加到流末尾会变成「干完了才说计划」。
     * 本轮已经有清单时按新步骤覆盖，已打勾的步骤按内容保留勾选状态。
     */
    fun beginPlan(steps: List<String>): String {
        require(steps.isNotEmpty())
        val previous = activePlanId?.let { id -> chat.firstOrNull { it.id == id }?.plan }.orEmpty()
        val now = System.currentTimeMillis()
        val plan = restartablePlan(
            plan = steps.map { item -> PlanStep(item) },
            previous = previous,
            now = now,
        )
        return writePlan(plan, now)
    }

    /** 清单里一行的「计划内容」：标题、会改动什么、能不能撤销、依赖哪几步。 */
    data class PlanStepSpec(
        val title: String,
        val impact: String = "",
        val undoable: Boolean = false,
        val dependsOn: List<Int> = emptyList(),
    )

    /** 和 [beginPlan] 一样，但允许模型给每步带上「会改动什么 / 依赖哪一步」。 */
    /** 和 [beginPlan] 一样，但允许模型给每步带上「会改动什么 / 依赖哪一步」。 */
    fun beginPlanSpecs(specs: List<PlanStepSpec>): String {
        require(specs.isNotEmpty())
        val previous = activePlanId?.let { id -> chat.firstOrNull { it.id == id }?.plan }.orEmpty()
        val now = System.currentTimeMillis()
        val plan = restartablePlan(
            plan = specs.map { spec ->
                PlanStep(
                    text = spec.title,
                    impact = spec.impact,
                    undoable = spec.undoable,
                    dependsOn = spec.dependsOn.filter { it >= 0 }.distinct(),
                )
            },
            previous = previous,
            now = now,
        )
        return writePlan(plan, now)
    }

    /**
     * 重新摆清单时把已完成的步骤按内容认回来（连耗时一起），
     * 然后把第一个待办亮成「进行中」—— 清单刚摆好，第一步就是它此刻要干的事。
     */
    private fun restartablePlan(
        plan: List<PlanStep>,
        previous: List<PlanStep>,
        now: Long,
    ): List<PlanStep> {
        val doneByText = previous.filter { it.state == PlanStepState.Done }.associateBy { it.text }
        val kept = plan.map { step ->
            val done = doneByText[step.text]
            if (done != null) done else step
        }
        val firstPending = kept.indexOfFirst { it.state == PlanStepState.Pending }
        if (firstPending < 0) return kept
        return kept.mapIndexed { index, step ->
            if (index == firstPending) {
                step.copy(state = PlanStepState.Running, startedMs = now)
            } else {
                step
            }
        }
    }

    /** 把清单写进本轮那一行（没有就新插一条，紧跟用户那句话）。 */
    private fun writePlan(plan: List<PlanStep>, now: Long): String {
        val existing = activePlanId?.let { id -> chat.indexOfFirst { it.id == id } } ?: -1
        if (existing >= 0 && activePlanId != null) {
            chat[existing] = chat[existing].copy(plan = plan, planFinishedMs = 0L)
        } else {
            val line = ChatLine(
                id = nextChatLineId++,
                role = ChatRole.Plan,
                text = "",
                atMs = now,
                plan = plan,
                planStartedMs = now,
            )
            val userAt = chat.indexOfLast { it.role == ChatRole.User }
            val insertAt = if (userAt >= 0) userAt + 1 else chat.size
            chat.add(insertAt, line)
            activePlanId = line.id
        }
        persistChat()
        return "记下了 " + plan.size + " 步：第一步「" + plan.first().text + "」"
    }

    /** 把清单第 [index] 步标成完成/失败。越界返回一句人话，不抛异常。 */
    fun finishPlanStep(index: Int, ok: Boolean, note: String = ""): String {
        val id = activePlanId ?: return "这一轮还没有清单，先调 set_plan"
        val at = chat.indexOfFirst { it.id == id }
        if (at < 0) return "清单已经不在了（可能开了一轮新对话）"
        val line = chat[at]
        val plan = line.plan
        if (index !in plan.indices) {
            return "清单里没有第 " + (index + 1) + " 步（共 " + plan.size + " 步，index 从 0 开始）"
        }
        val step = plan[index]
        if (step.state == PlanStepState.Done || step.state == PlanStepState.Failed || step.state == PlanStepState.Skipped) {
            return "第 " + (index + 1) + " 步已经记过了"
        }
        val now = System.currentTimeMillis()
        val updated = plan.toMutableList()
        updated[index] = step.copy(
            state = if (ok) PlanStepState.Done else PlanStepState.Failed,
            note = note,
            ms = if (step.startedMs > 0L) now - step.startedMs else 0L,
        )
        // 失败会连坐：依赖它的待办步骤根本不会跑，直接标跳过，别让人一直等。
        // 跳过也要继续往下传：B 依赖 A、C 依赖 B，A 失败时 B、C 都得标掉。
        var changed = true
        while (changed) {
            changed = false
            val blocked = updated.withIndex()
                .filter { it.value.state == PlanStepState.Failed || it.value.state == PlanStepState.Skipped }
                .map { it.index }
                .toSet()
            for (i in updated.indices) {
                val candidate = updated[i]
                if (candidate.state == PlanStepState.Pending &&
                    candidate.dependsOn.any { it in blocked }
                ) {
                    updated[i] = candidate.copy(state = PlanStepState.Skipped)
                    changed = true
                }
            }
        }
        // 勾掉当前步后，把下一步亮起来：界面不用再猜「现在进行到哪儿」。
        val nextPending = updated.indexOfFirst { it.state == PlanStepState.Pending }
        if (nextPending >= 0) {
            updated[nextPending] = updated[nextPending].copy(
                state = PlanStepState.Running,
                startedMs = now,
            )
        }
        val settled = updated.count {
            it.state == PlanStepState.Done || it.state == PlanStepState.Failed || it.state == PlanStepState.Skipped
        }
        val finishedMs = if (settled == updated.size) now else 0L
        chat[at] = line.copy(plan = updated, planFinishedMs = finishedMs)
        persistChat()
        return (if (ok) "第 " + (index + 1) + " 步完成" else "第 " + (index + 1) + " 步没做成") +
            "（" + settled + "/" + updated.size + "）"
    }

    /**
     * 有新工具开始执行时把清单里第一个待办亮成「进行中」，并记下工具名和开始时间。
     *
     * 工具名从过程行「第 N 步：tool_name」里取 —— 宿主本来就知道，不用让模型再报一次。
     */
    private fun markPlanRunning(tool: String = "") {
        val id = activePlanId ?: return
        val at = chat.indexOfFirst { it.id == id }
        if (at < 0) return
        val plan = chat[at].plan
        if (plan.any { it.state == PlanStepState.Running }) return
        val nextPending = plan.indexOfFirst { it.state == PlanStepState.Pending }
        if (nextPending < 0) return
        val now = System.currentTimeMillis()
        val updated = plan.toMutableList()
        updated[nextPending] = updated[nextPending].copy(
            state = PlanStepState.Running,
            tool = tool.ifBlank { updated[nextPending].tool },
            startedMs = now,
        )
        chat[at] = chat[at].copy(plan = updated)
    }

    /** 当前会话最后一份清单（含刚结束的那份），给右栏与输入框上方的常驻条用。 */
    fun latestPlanLine(): ChatLine? = chat.lastOrNull { it.role == ChatRole.Plan && it.plan.isNotEmpty() }

    /**
     * 一轮结束时收尾清单。
     *
     * 真实事故：模型想调一个当前意图没有的工具（「每天 60 分钟」被判成 Chat），
     * 清单上第 2 步一直停在「正在执行」，右侧写着「本轮没有改动」—— 用户看到的是
     * 一个永远推进不了的任务。这里把结论同步给清单：
     * - 成功：还没打勾的当前步按完成收尾（它确实做完了，只是没来得及调 step_done）；
     * - 失败/停止：当前步标失败，并记下完成时间（界面据此收起/换文案）。
     */
    private fun settlePlanAfterRound(ok: Boolean) {
        val id = activePlanId ?: return
        val at = chat.indexOfFirst { it.id == id }
        if (at < 0) return
        val line = chat[at]
        if (line.plan.isEmpty() || line.planFinishedMs != 0L) return
        val running = line.plan.indexOfFirst { it.state == PlanStepState.Running }
        val now = System.currentTimeMillis()
        val plan = if (running < 0) {
            line.plan
        } else {
            line.plan.mapIndexed { index, step ->
                if (index != running) {
                    step
                } else {
                    step.copy(
                        state = if (ok) PlanStepState.Done else PlanStepState.Failed,
                        note = if (ok) "本轮结束" else "这一轮没走完",
                        ms = if (step.startedMs > 0) now - step.startedMs else 0L,
                    )
                }
            }
        }
        chat[at] = line.copy(plan = plan, planFinishedMs = now)
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

            TAB_PLAN -> {
                appendLine(
                    "计划：" + (planTitle ?: "还没有计划") +
                        if (planWeekCards.isEmpty()) "" else "，共 " + planWeekCards.size + " 周",
                )
                planWeekCards.forEach { card ->
                    appendLine(
                        "- " + card.label + " " + card.chapter + "（" + card.unitCount + " 个单元，" +
                            card.rangeLabel + "）" + if (card.week == planSelectedWeek) " ← 选中" else "",
                    )
                }
                planDayView?.let {
                    appendLine("选中那天：" + it.weekdayLabel + "，" + it.headline)
                    it.occurrences.forEach { o -> appendLine("- " + o.title + "（" + o.minutes + " 分钟，" + o.chapter + "）") }
                    it.completed.forEach { o -> appendLine("- 已完成 " + o.title) }
                }
            }

            TAB_KNOWLEDGE -> {
                val noteList = if (this@AppRuntime::knowledgeStore.isInitialized) knowledgeStore.list() else emptyList()
                appendLine("知识库：" + noteList.size + " 篇笔记")
                noteList.take(8).forEach { note ->
                    appendLine("- " + displayTitle(note.title) + "（" + note.chars + " 字符）")
                }
            }

            TAB_ACTIVITY -> {
                val summary = activityDay(System.currentTimeMillis())
                if (summary == null) {
                    appendLine("活动：暂时读不到（" + (activityMessage ?: "库还没打开") + "）")
                } else {
                    appendLine(
                        "活动（今天）：观察 " + summary.observedMs / 60_000 + " 分钟，AFK " +
                            summary.afkMs / 60_000 + " 分钟，活跃 " + summary.activeMs / 60_000 +
                            " 分钟，时间轴 " + summary.segmentCount + " 段",
                    )
                    summary.categories.take(8).forEach { slice ->
                        appendLine("- " + slice.path + "：" + slice.totalMs / 60_000 + " 分钟")
                    }
                    if (summary.isEmpty) appendLine("（今天还没有采集到任何活动）")
                }
                activityProblems().take(3).forEach { appendLine("分类规则问题：" + it) }
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
        TAB_PLAN -> "计划"
        TAB_CHAT -> "对话"
        TAB_ACTIVITY -> "活动"
        TAB_KNOWLEDGE -> "知识库"
        TAB_PLUGINS -> "插件"
        TAB_SETTINGS -> "设置"
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
            "计划", "plan", "plans" -> TAB_PLAN
            "对话", "chat" -> TAB_CHAT
            "活动", "activity", "activities" -> TAB_ACTIVITY
            "知识库", "知识", "knowledge", "kb" -> TAB_KNOWLEDGE
            "插件", "plugins", "plugin" -> TAB_PLUGINS
            "诊断", "diag", "diagnostics" -> TAB_DIAG
            "设置", "settings", "setting" -> TAB_SETTINGS
            else -> return "没有这一页：" + tab + "。只有 任务 / 计划 / 对话 / 活动 / 知识库 / 插件 / 诊断 / 设置。"
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
     * 它是**插件的主人**，也是唯一入口：造新的、停旧的、改坏的、调布局都走这里。
     * 它先自己判断这轮该聊天还是该动手，动手时过程逐行吐到对话里，判断和干活
     * 用的是同一份上下文。
     *
     * 没有可空返回：对话页永远有反馈（没 key 时把配置指引当一行过程贴出来）。
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
        // 上一轮「等你点选项 / 等你重试」留下的意图：这一轮如果是没有意图关键词的短句
        // （「每天 60 分钟」「再试一次」），沿用它的工具和提示词，别退回零工具的聊天。
        val forcedIntent = pendingManagerIntent
        pendingManagerIntent = null
        chatBusy = true
        activePlanId = null
        chatChoices = emptyList()
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
            // 让用户看见「它打算怎么干」：清单比过程日志早一步。
            val workflowTools = WorkflowTools(this)
            // 外面世界的一只手：查学习资源。只读 B 站元数据，不下载不播放。
            val bilibiliTools = BilibiliTools()
            // 待办也是一只手：分集要变成用户能勾的任务，而不是一段回答里的文字。
            val taskTools = TaskTools(this)
            // 计划页是三只手之外的第四个去处：`set_plan` 只是这一轮的动作清单，
            // 真正的周/单元要 createProcess → saveDraft → acceptDraft 才写得进去。
            val planTools = PlanTools(
                { learningPlan() },
                { refreshPlan() },
                { studyPrefs.weeklyMinutes },
                planDbError = { planDbError },
                clock = clock,
            )
            // 计划的「维护」是另一套手：读现状、排下周、勾节点、顺延、复盘、改每天投入。
            // 没有它，AI 只能建计划，用户还是得自己去计划页点。
            val planAdminTools = PlanAdminTools(
                { learningPlan() },
                { refreshPlan() },
                dbError = { planDbError },
                clock = clock,
            )
            // 眼睛之外再给一只「看内部状态」的手：工具报错时自己查日志/模块/库状态，
            // 而不是让用户去看一眼错误提示（用户修不好 java/sql/DriverManager 这种问题）。
            val diagnosticsTools = DiagnosticsTools { diagnosticsReport() }
            val allTools = tools.all + screenTools.all + layoutTools.all + styleTools.all +
                workflowTools.all + bilibiliTools.all + taskTools.all + planTools.all +
                planAdminTools.all + diagnosticsTools.all
            val guidance = tools.guidance + "\n\n" + screenTools.guidance +
                "\n\n" + layoutTools.guidance + "\n\n" + styleTools.guidance +
                "\n\n" + workflowTools.guidance + "\n\n" + bilibiliTools.guidance +
                "\n\n" + taskTools.guidance + "\n\n" + planTools.guidance +
                "\n\n" + planAdminTools.guidance + "\n\n" + diagnosticsTools.guidance

            // 意图 → 工具 → 提示词，一次定完：每条工具的目录只出现在它该出现的那一版
            // 提示词里。以前是把整套工具和整段前缀一股脑塞进去，模型总会挑一个
            // 「看起来像在干活」的 —— 「我要速通操作系统」变成装插件就是这么来的。
            // 静态前缀按意图缓存：同一意图的多轮请求逐字节一致，前缀缓存仍能命中。
            val router = ManagerRouter(catalog, allTools, guidance)
            val agent = ManagerAgent(
                llm = llm,
                router = router,
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
                forcedIntent = forcedIntent,
                onDelta = { delta ->
                    answerText += delta
                    displayText = visibleReply(answerText)
                    renderAnswer()
                },
                onLog = { line ->
                    addChatLine(ChatRole.Step, line)
                    // 过程行是「第 N 步：tool_name」；新的一步开始，清单上就该亮起来，
                    // 顺手把工具名记进去 —— 展开区要写「这一步在做什么」。
                    if (line.startsWith("第 ")) {
                        markPlanRunning(Regex("：([a-z_]+)").find(line)?.groupValues?.get(1).orEmpty())
                    }
                },
            )

            if (result.text.isNotBlank()) {
                answerText = result.text
                displayText = result.text
            }
            renderAnswer()

            // 清单不能一直挂在「进行中」：这一轮结束（成功/没做成）都要收尾。
            settlePlanAfterRound(result.ok)

            // 意图有歧义时不往下走，把「可能的意思」摆成按钮让用户点。
            // 这是流程里的「先问清楚」：问完再选工具、再拼提示词、再发模型。
            if (result.choices.isNotEmpty()) {
                chatChoices = result.choices.map { ChatChoice(it, it) }
            }

            // 给下一轮留「承接上一轮」的信号：
            // - 模型用 ask_choices 问了用户（不是意图歧义）→ 用户下一句多半是在回答，沿用本意图；
            // - 动手中途失败 → 用户说「再试一次」时沿用同一套工具，而不是退回零工具的聊天。
            pendingManagerIntent = when {
                result.clarified -> null
                result.choices.isNotEmpty() && result.intent != AgentIntent.Chat -> result.intent
                !result.ok && result.intent != AgentIntent.Chat -> result.intent
                else -> null
            }

            if (result.ok) {
                addChatLine(ChatRole.Status, "—— 本轮完成")
            } else {
                addChatLine(ChatRole.Status, "—— 本轮没做成，原因见上")
            }

            chatHistory += LlmMessage(LlmRole.User, text)
            chatHistory += LlmMessage(LlmRole.Assistant, displayText)
            while (chatHistory.size > 20) chatHistory.removeAt(0)

            // 动过插件就把界面上的东西重新取一遍：新卡片、停用状态、计数都变了。
            if (result.intent != AgentIntent.Chat && result.ok) refresh()

            // 注意：这里**不再**替模型推断「下一步该问用户什么」。
            // 要问就让模型自己调 ask_choices（它会落到 offerChoices），运行时不猜。
        } catch (cancel: CancellationException) {
            // 用户点了「停止」、或程序正在退出：半截回答留在原地，补一行说明就收。
            // 不重抛 —— 重抛会让 job 以取消收场，界面反而看不出「这一轮到底停没停」。
            settlePlanAfterRound(ok = false)
            addChatLine(ChatRole.Status, "—— 已停止")
        } catch (t: Throwable) {
            val reason = t.message ?: (t::class.simpleName ?: "未知错误")
            if (displayText.isEmpty()) {
                displayText = "出错了：" + reason
                renderAnswer()
            } else {
                addChatLine(ChatRole.Step, "出错了：" + reason)
            }
            settlePlanAfterRound(ok = false)
            addChatLine(ChatRole.Status, "—— 本轮出错，停在上面那行")
        } finally {
            persistChat()
            chatBusy = false
        }
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
     * 学习节奏偏好：用户对自己「一天能投多少」的估计。
     *
     * 和 [uiPrefs] 分开存（`study.json`）：那个决定界面长什么样，这个决定计划怎么排。
     */
    var studyPrefs: StudyPrefs by mutableStateOf(StudyPrefs())
        private set

    private lateinit var studyPrefsStore: StudyPrefsStore

    /**
     * 插件「上次用」的记录：插件 id → 毫秒时间戳。
     *
     * 只记用户真做过的动作（应用主题、启用插件），不记推断 —— 见 [PluginUsageStore]。
     * 用 mutableStateMap 而不是普通 Map：插件页悬停时要立刻看到新时间，不能等重启。
     */
    internal val pluginUsage = mutableStateMapOf<String, Long>()

    private lateinit var pluginUsageStore: PluginUsageStore

    /** 记一次「用过了」。没有 store 时（测试/预览）只更新内存。 */
    internal fun notePluginUsed(id: String) {
        if (id.isBlank()) return
        val now = System.currentTimeMillis()
        pluginUsage[id] = now
        if (::pluginUsageStore.isInitialized) pluginUsageStore.save(pluginUsage)
        tick += 1
    }

    internal fun lastUsedAt(id: String): Long? = pluginUsage[id]

    /** 这个插件有没有提供主题（决定插件页要不要显示「应用主题」相关提示）。 */
    fun pluginProvidesTheme(id: String): Boolean =
        themeOptions().any { it.first.startsWith(id + "/") }

    /** 当前主题是不是这个插件提供的。 */
    fun themeIsActiveFrom(id: String): Boolean =
        id.isNotBlank() && uiPrefs.theme.startsWith(id + "/")

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
        // 换到插件主题 = 这个插件被用了一次；插件页悬停显示的「上次用」就写在这里
        notePluginUsed(trimmed.substringBefore("/"))
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

    /** 审计日志（最近的在后）。诊断页的「最近操作」就是它。 */
    val auditEntries: List<AuditEntry> get() = if (::kernel.isInitialized) kernel.audit.entries() else emptyList()

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

    // ---------------------------------------------------------------- 学习计划（计划页）

    /**
     * 计划页的数据都在 `<trellis home>/plans/plan.db` 里，由 `:features:learning-process` 的实现负责。
     *
     * 界面这边**只**拿 view model：周卡片用 [MapView]，选中周用 [WeekView]，选中日再用 [DayView]。
     * 数字（还差几件、多少分钟）都由后端算好；页面里不做四则运算，也不自己拼文案。
     */
    private var learningPlanService: LearningPlanService? = null
    private var learningPlanId: String? = null

    internal var planWeekCards by mutableStateOf<List<PlanWeekCard>>(emptyList())
        private set

    /** 计划页左栏：所有计划（含归档），按「全部计划 → 每个计划」摆。 */
    var planAll by mutableStateOf<List<LearningPlanInfo>>(emptyList())
        private set

    /** 这一页正在看哪个计划；null = 跟 activePlanId 走。 */
    var planFocusId by mutableStateOf<String?>(null)
        private set

    /** 计划的大类（显示用标签）：planId → 大类名。 */
    internal var planCategories by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private val planCategoryStore: PlanCategoryStore by lazy { PlanCategoryStore(home) }

    /** 还没被任何一周收进去的单元（节点池）：计划页上灰色的「待安排」。 */
    var planPool by mutableStateOf<List<PoolUnit>>(emptyList())
        private set

    /**
     * 「待安排」集合：池里的单元 + 还没铺到天的周里的单元。
     *
     * 周卡片只给「已排到天」的周用；没排的周不再各占一张卡，全都收进这一个集合，
     * 免得出现「卡片写待安排、里面又列着内容」这种自相矛盾的画面。
     */
    internal var planPendingNodes by mutableStateOf<List<PoolUnit>>(emptyList())
        private set

    /** 计划页右下：现在看的是「待安排」集合（true），还是某一周（false）。 */
    var planPendingSelected by mutableStateOf(false)
        private set

    /** 这个计划的「每天投入多少分钟」；没设就是 null（排程按 60 分钟兜底）。 */
    var planDailyMinutes by mutableStateOf<Int?>(null)
        private set

    /**
     * 逾期投影：本周已经过期还没做的节点（按路线顺序）+ 更早周欠下的单元。
     *
     * 只读，由 `overdue` 查询现算；计划页「待处理」卡片靠它渲染，不在这里做推荐。
     */
    var planOverdue by mutableStateOf<OverdueView?>(null)
        private set

    /**
     * 拖拽重排的侧栏是否展开。
     *
     * 两种打开方式：点标题旁的「重排」按钮；或者直接拖起某天里的一个节点（拖起来就把侧栏摊开，
     * 让用户能同时看到「还有哪些欠账」）。拖拽本身的状态在 `PlanRearrange.kt` 里。
     */
    var planRearrangeOpen by mutableStateOf(false)
        private set

    /**
     * 重排侧栏最近一次操作的提示（成功/失败）。
     *
     * 单独一个字段而不是复用 [planMessage]：这条提示要在 `refreshPlan()` 之后还看得见，
     * 而 planMessage 每次刷新都会清掉。开合侧栏时清空。
     */
    var planRearrangeHint by mutableStateOf<String?>(null)
        private set

    /** [planRearrangeHint] 是不是错误（UI 用它决定颜色）。 */
    var planRearrangeHintIsError by mutableStateOf(false)
        private set

    /** 侧栏里可拖的节点数：本周逾期 + 更早欠账。 */
    val planRearrangeCount: Int
        get() = planOverdue?.let { it.thisWeek.size + it.backlog.size } ?: 0

    /**
     * 计划设置弹窗是否打开。
     *
     * 这是「计划自己的属性」，不是全局设置：同一台机器上不同计划可以是不一样的每天投入，
     * 所以入口只放在计划页、只改当前这个计划。
     */
    var planSettingsOpen by mutableStateOf(false)
        private set

    /** 弹窗里正在编辑的每天投入（分钟）；打开时用当前值填。 */
    var planDailyDraft by mutableStateOf(60)
        private set

    /**
     * 待办板上的「学习计划节点」：所有没结束的计划各一组。
     *
     * 和 [planPool] 的区别：那个只看当前聚焦的计划、只给计划页用；这个跨全部计划，
     * 是首页待办要摊出来的东西。
     *
     * 只摊今天到期和之前逾期的节点：还没排期的在计划页的「待安排」里，不占待办。
     */
    internal var todoPlanNodes by mutableStateOf<List<PlanNodeGroup>>(emptyList())
        private set

    /** 顶部标题：库里的计划名；没有计划时为 null。 */
    var planTitle by mutableStateOf<String?>(null)
        private set

    var planDays by mutableStateOf<List<WeekDay>>(emptyList())
        private set

    var planSelectedWeek by mutableStateOf<String?>(null)
        private set

    var planSelectedDay by mutableStateOf<String?>(null)
        private set

    var planDayView by mutableStateOf<DayView?>(null)
        private set

    /** 库打不开 / 读失败时的一句话；成功时清空。 */
    var planMessage by mutableStateOf<String?>(null)
        private set

    /**
     * 计划库打不开的**具体原因**（比 [planMessage] 短、给工具/诊断用）。
     *
     * 工具报的是它，AI 才能看到 `NoClassDefFoundError: java/sql/DriverManager` 这种细节，
     * 而不是只对用户说「打不开，你去修」。
     */
    internal var planDbError by mutableStateOf<String?>(null)
        private set

    /**
     * 最近一次节奏推进（当前聚焦的那个计划）：结算/补承诺/铺天各做了什么。
     *
     * 系统自动跑的，不经过模型；页面用它显示「上周完成 X/Y · 本周已排 N 个」这类报告，
     * 也可以在什么都没发生时保持安静（note 为空就当没有）。
     */
    internal var planRhythmReport by mutableStateOf<RhythmResult?>(null)
        private set

    /** 这次节奏推进里有没有值得说一声的动作；什么都没有就不打扰（保持页面安静）。 */
    internal val planRhythmHasNews: Boolean
        get() = planRhythmReport?.let { report ->
            report.settledWeeks.isNotEmpty() || report.reviewedWeeks.isNotEmpty() ||
                report.appendedWeeks.isNotEmpty() || report.scheduledWeeks.isNotEmpty() ||
                report.missed > 0 || report.carried > 0
        } == true

    fun dismissPlanRhythmReport() {
        planRhythmReport = null
    }

    private fun learningPlan(): LearningPlanService? {
        learningPlanService?.let { return it }
        val file = File(home, "plans/plan.db")
        return runCatching {
            // clock/zone 必须和页面、AI 工具同源：否则会出现「页面显示 W46、后端按 W44 结算」。
            LearningPlanStore.open(
                file.toPath(),
                clock = { clock.nowMs() },
                zone = clock.zone,
            )
        }
            .onFailure { error ->
                planDbError = describeFailure(error)
                planMessage = "计划库打不开：$planDbError"
                appendAppLog("计划库打开失败：" + error.stackTraceToString())
            }
            .getOrNull()
            ?.also {
                learningPlanService = it
                planDbError = null
                // 成功也留一行：诊断时能确认「库到底有没有打开过」，不然只能靠猜。
                appendAppLog("计划库已打开：" + file.absolutePath)
            }
    }

    /**
     * 把异常翻译成「人和模型都能看懂、能拿来判断下一步」的一句话。
     *
     * 不能只留 `java/sql/DriverManager` 这种类名：用户不知道那是什么，
     * AI 也得靠它判断到底是打包缺模块、文件坏了还是权限问题。
     */
    private fun describeFailure(error: Throwable): String = buildString {
        append(error::class.simpleName ?: "错误")
        error.message?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
        val text = (error::class.simpleName ?: "") + (error.message ?: "")
        if (error is LinkageError || text.contains("java/sql") || text.contains("DriverManager")) {
            append("（运行环境缺少 JDK 模块，最常见的是打包时漏了 java.sql；")
            append("开发模式用 ./gradlew :app:desktop:run 不受影响，打包版需要重新打包）")
        }
    }

    /** 追加到 `<home>/app.log`；日志本身失败不能影响主流程。 */
    private fun appendAppLog(line: String) {
        runCatching {
            File(home, "app.log").appendText(
                "[" + java.time.Instant.now() + "] " + line + "\n",
                Charsets.UTF_8,
            )
        }
    }

    /**
     * 给 AI 的诊断报告（`get_diagnostics` 工具读它）。
     *
     * 为什么这不是「给用户看的错误页」而是给模型看的：用户修不好 `java/sql/DriverManager`
     * 这种问题，AI 必须能自己看到「计划库为什么打不开 / 运行时缺哪个模块 / 最近日志」，
     * 再决定是重试、绕开，还是给出一个用户真能执行的修复动作。
     */
    internal fun diagnosticsReport(): String = buildString {
        appendLine("【Trellis 诊断】")
        appendLine("home：" + home.absolutePath)
        appendLine("Java：" + System.getProperty("java.version") + "（" + System.getProperty("java.vendor") + "）")
        appendLine("OS：" + System.getProperty("os.name") + " " + System.getProperty("os.version"))

        val planDb = File(home, "plans/plan.db")
        append("计划库：" + planDb.absolutePath + "（")
        append(if (planDb.isFile) "存在，" + planDb.length() + " 字节" else "不存在")
        appendLine("）")
        // 调一次 learningPlan()：没打开过会在这里重试，顺便刷新 planDbError。
        val planService = learningPlan()
        appendLine(
            "计划库状态：" + if (planService != null) "可打开" else "打不开" +
                (planDbError?.let { " —— $it" } ?: ""),
        )
        planDbError?.let { appendLine("计划库错误：" + it) }

        // JDBC / 网络 / 图像这些是「打包版最容易漏」的模块，逐个点名，别让模型猜。
        appendLine("JDK 模块自检：")
        listOf(
            "java.sql.DriverManager" to "java.sql（计划库/活动库的 SQLite 驱动）",
            "java.net.http.HttpClient" to "java.net.http（LLM / B 站请求）",
            "javax.imageio.ImageIO" to "java.desktop（截图）",
            "javax.net.ssl.SSLContext" to "java.base / java.net（HTTPS）",
        ).forEach { (className, label) ->
            // 用系统类加载器：这些类是 JDK 平台模块里的，不能挂在 StringBuilder 的类加载器上判断。
            val available = runCatching { Class.forName(className, false, ClassLoader.getSystemClassLoader()) }
            appendLine("  - " + label + "：" + if (available.isSuccess) "可用" else "缺失 ← 就是它")
        }

        appendLine("LLM 配置：" + if (LlmConfigLoader.load(home) != null) "已配置" else "未配置（对话不会调模型）")

        val activityDb = File(home, "activity/activity.db")
        appendLine("活动库：" + activityDb.absolutePath + "（" + if (activityDb.isFile) "存在" else "还没建" + "）")

        val logFile = File(home, "app.log")
        if (logFile.isFile) {
            val tail = runCatching {
                logFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.takeLast(20)
            }.getOrDefault(emptyList())
            appendLine("app.log 最近 " + tail.size + " 行：")
            tail.forEach { appendLine("  | " + it.take(300)) }
        } else {
            appendLine("app.log：还没有（没有错误发生过）")
        }
    }.trimEnd()

    /**
     * 待办板要的节点：所有计划各算一遍。
     *
     * 计划页只关心聚焦的那一个计划，待办板要跨全部，所以单独查一遍。只问今天到期和之前
     * 逾期的那些（`openNodes` 自己按 `day <= 今天` 筛），还没排期的不进待办。
     * 单个计划读失败只让那一组缺失，不影响别的计划。
     */
    private fun collectTodoPlanNodes(service: LearningPlanService): List<PlanNodeGroup> = runCatching {
        val today = clock.today().toString()
        planNodeGroups(
            plans = planAll,
            today = today,
            openNodesOf = { id -> runCatching { service.openNodes(id, today) }.getOrDefault(emptyList()) },
        )
    }.getOrDefault(emptyList())

    /** 进计划页、切周切天、写完一条之后都走这里：一次取齐整页要显示的 view model。 */
    fun refreshPlan() {
        val service = learningPlan() ?: return
        runCatching {
            planAll = service.plans()
            // 先让计划自己往前滚（结算旧周 / 补下周承诺 / 铺天），再取待办与页面数据：
            // 否则今天新铺出来的节点要等下一次刷新才出现在待办板上。
            val rhythmReports = advancePlanRhythm(service)
            todoPlanNodes = collectTodoPlanNodes(service)
            planCategories = planCategoryStore.load()
            val today = clock.today().toString()
            val planId = planFocusId?.takeIf { id -> planAll.any { it.id == id } }
                ?: service.activePlanId()
                ?: planAll.firstOrNull()?.id
            planFocusId = planId
            learningPlanId = planId
            if (planId == null) {
                planTitle = null
                planWeekCards = emptyList()
                planDays = emptyList()
                planDayView = null
                planPool = emptyList()
                planRhythmReport = null
                planOverdue = null
                planMessage = null
                return
            }
            planRhythmReport = rhythmReports[planId]
            val state = service.planState(planId)
            planDailyMinutes = state.dailyMinutes
            planOverdue = runCatching { service.overdue(planId, today) }.getOrNull()

            val map = service.map(planId)
            planTitle = map.planTitle.ifBlank { state.title }
            planWeekCards = map.weeks.mapIndexed { index, week ->
                val view = service.week(planId, week.week)
                val units = view.commitment.units
                // 卡片第二行要「这一周在讲什么」：单元里出现最多的章节名，比承诺标签更具体。
                val chapter = units.groupingBy { it.chapter }.eachCount().maxByOrNull { it.value }?.key
                    ?: view.commitment.label
                PlanWeekCard(
                    week = week.week,
                    // 只给「第几周」，不把 2026-W44 这种 ISO 周号摆到台面上；具体日期在卡片底部。
                    label = "第 " + (index + 1) + " 周",
                    state = week.state,
                    scheduled = view.scheduled,
                    chapter = chapter,
                    units = units.map { it.title },
                    rangeLabel = view.rangeLabel,
                    unitCount = units.size,
                )
            }
            planPool = service.pool(planId)
            // 只把「还没被任何一周承诺收走」的单元摊进待安排：
            // 有承诺、只是还没铺到天的周现在是正常的「第 N 周」卡片，不再算进待安排，
            // 否则同一批单元会在灰卡和周卡里各出现一次。
            planPendingNodes = planPool
            if (planPendingSelected && planPendingNodes.isEmpty()) planPendingSelected = false
            // 选中的周可以是任何一张周卡：未来周还没铺天也能点开看它承诺了哪几个单元。
            val selectableWeeks = planWeekCards.map { it.week }
            val week = planSelectedWeek?.takeIf { w -> selectableWeeks.contains(w) }
                ?: state.week.takeIf { w -> selectableWeeks.contains(w) }
                ?: selectableWeeks.firstOrNull()
            planSelectedWeek = week
            if (week != null) {
                val weekView = service.week(planId, week)
                val days = weekView.days
                planDays = days
                val day = planSelectedDay?.takeIf { d -> days.any { it.day == d } }
                    ?: days.firstOrNull { it.today }?.day
                    ?: days.firstOrNull { it.items.isNotEmpty() }?.day
                    ?: days.firstOrNull()?.day
                planSelectedDay = day
                planDayView = day?.let { service.today(planId, it) }
            }
            planMessage = null
        }.onFailure { planMessage = "计划读不出来：" + (it.message ?: "未知原因") }
    }

    /**
     * 计划自己的节奏：对每个未结束的计划跑一次 `advanceRhythm`。
     *
     * 一次包含：结算已经结束的周、从「待安排」池里给本周/下周补承诺、生成周复盘、把周铺到天。
     * 这是系统自己的节奏（origin = scheduler），不经过模型；命令幂等，没有实际动作时后端不写库。
     * 单个计划失败只丢那一份节奏，不打断页面；下一次刷新还会再试。
     *
     * @return planId → 这次的推进结果（失败的为 null），页面挑当前聚焦的那个显示。
     */
    private fun advancePlanRhythm(service: LearningPlanService): Map<String, RhythmResult> {
        val reports = mutableMapOf<String, RhythmResult>()
        planAll.filter { it.status != "ended" }.forEach { info ->
            val command = CommandEnvelope(
                commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                actor = Actor.SYSTEM,
                planId = info.id,
                origin = "scheduler",
            )
            runCatching { service.advanceRhythm(command, info.id) }
                .getOrNull()?.value?.let { reports[info.id] = it }
        }
        return reports
    }

    /** "2026-W44" → 那一周的周一。 */
    private fun isoWeekMonday(week: String): java.time.LocalDate? = runCatching {
        val year = week.substringBefore("-").toInt()
        val number = week.substringAfter("-W").toInt()
        java.time.LocalDate.of(year, 1, 4)
            .with(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR, number.toLong())
            .with(java.time.DayOfWeek.MONDAY)
    }.getOrNull()

    /** 今天所在的 ISO 周，比如 "2026-W44"（走注入时钟，开发者模式拨日期后跟着变）。 */
    private fun currentIsoWeek(): String = clock.isoWeek()

    fun selectPlan(planId: String?) {
        planFocusId = planId
        planSelectedWeek = null
        planSelectedDay = null
        planPendingSelected = false
        refreshPlan()
    }
    /** 点待办板里的计划节点：切到计划页，并把这个计划选中。 */
    internal fun openPlanNode(planId: String) {
        selectPlan(planId)
        currentTab = TAB_PLAN
    }

    fun selectPlanWeek(week: String) {
        planSelectedWeek = week
        planSelectedDay = null
        planPendingSelected = false
        refreshPlan()
    }

    /** 点「待安排」那张卡：下面显示整个待安排集合，而不是某一周。 */
    fun selectPlanPending() {
        planPendingSelected = true
        refreshPlan()
    }

    // ---------------- 计划设置（当前计划自己的属性） ----------------

    fun openPlanSettings() {
        if (planTitle == null) return
        planDailyDraft = planDailyMinutes ?: 60
        planSettingsOpen = true
    }

    fun closePlanSettings() {
        planSettingsOpen = false
    }

    fun bumpPlanDailyDraft(delta: Int) {
        planDailyDraft = (planDailyDraft + delta).coerceIn(15, 240)
    }

    fun choosePlanDailyDraft(minutes: Int) {
        planDailyDraft = minutes.coerceIn(15, 240)
    }

    /** 保存「每天投入」：写到当前计划的 process 上，然后重取整页。 */
    fun savePlanDailyMinutes() {
        val service = learningPlan() ?: return
        val planId = learningPlanId
        if (planId == null) {
            planMessage = "还没有选中的计划。"
            return
        }
        val command = CommandEnvelope(
            commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
            actor = Actor.USER,
            planId = planId,
            origin = "ui.plan.settings",
        )
        val result = runCatching { service.setPlanDailyMinutes(command, planId, planDailyDraft) }
            .getOrElse {
                planMessage = "没存上：" + (it.message ?: "未知原因")
                null
            }
        if (result != null && !result.ok) {
            planMessage = result.error?.message
            return
        }
        planSettingsOpen = false
        planMessage = "每天投入已改成 " + planDailyDraft + " 分钟。"
        refreshPlan()
    }

    fun selectPlanDay(day: String) {
        planSelectedDay = day
        refreshPlan()
    }

    /** 勾掉一条。写完重取整页，界面不自己改数字。 */
    fun completePlanOccurrence(occurrenceId: String) {
        val service = learningPlan() ?: return
        val planId = learningPlanId ?: return
        val command = CommandEnvelope(
            commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
            actor = Actor.USER,
            planId = planId,
            origin = "ui.plan",
        )
        val result = runCatching { service.completeOccurrence(command, occurrenceId) }
            .getOrElse {
                planMessage = "这次没记上：" + (it.message ?: "未知原因")
                null
            }
        if (result != null && !result.ok) planMessage = result.error?.message
        refreshPlan()
        tick++
    }

    /** 把勾错的改回「没做」。只允许 done → planned。 */
    fun uncompletePlanOccurrence(occurrenceId: String) {
        val service = learningPlan() ?: return
        val planId = learningPlanId ?: return
        val command = CommandEnvelope(
            commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
            actor = Actor.USER,
            planId = planId,
            origin = "ui.plan",
        )
        val result = runCatching { service.uncompleteOccurrence(command, occurrenceId) }
            .getOrElse {
                planMessage = "这次没改回来：" + (it.message ?: "未知原因")
                null
            }
        if (result != null && !result.ok) planMessage = result.error?.message
        refreshPlan()
        tick++
    }

    // ---------------- 逾期项的三种手动处理（计划页「待处理」卡片） ----------------

    /**
     * 逾期项：挪到今天补做。
     *
     * 不检查今天还剩多少分钟——这是用户明确点的「今天做掉」，容量由人判断；
     * 真做不完时还能再顺延或跳过。
     */
    fun planDoToday(occurrenceId: String) {
        planMoveOccurrence(occurrenceId, clock.today().toString(), "今天补做")
    }

    /** 逾期项：往后顺延一天；已经到周日就提示等周结算，不硬塞到下周。 */
    fun planDeferOneDay(occurrenceId: String) {
        val overdue = planOverdue
        val weekEnd = overdue?.let { isoWeekMonday(it.week) }?.plusDays(6)
        val today = clock.today()
        val sourceDay = overdue?.thisWeek?.firstOrNull { it.occurrenceId == occurrenceId }?.day
            ?.let { day -> runCatching { LocalDate.parse(day) }.getOrNull() }
        val target = maxOf(today.plusDays(1), sourceDay?.plusDays(1) ?: today.plusDays(1))
        if (weekEnd == null || target > weekEnd) {
            planMessage = "这周已经排到周日了，顺延不过去；可以先做掉，或者等周结算把它放进欠账池。"
            return
        }
        planMoveOccurrence(occurrenceId, target.toString(), "顺延一天")
    }

    /** 逾期项：今天先跳过。只标这一天不做，不释放本周承诺；周结算时会按事实处理。 */
    fun planSkipToday(occurrenceId: String) {
        val service = learningPlan() ?: return
        val planId = learningPlanId ?: return
        val result = runCatching {
            service.skipOccurrence(planAdjustCommand(planId), occurrenceId, "今天先跳过")
        }.getOrElse {
            planMessage = "这次没跳过：" + (it.message ?: "未知原因")
            null
        }
        if (result != null && !result.ok) planMessage = result.error?.message
        refreshPlan()
        tick++
    }

    // ---------------- 拖拽重排（侧栏 → 日期） ----------------

    fun togglePlanRearrange() {
        planRearrangeOpen = !planRearrangeOpen
        planRearrangeHint = null
    }

    fun openPlanRearrange() {
        planRearrangeOpen = true
    }

    fun closePlanRearrange() {
        planRearrangeOpen = false
        planRearrangeHint = null
    }

    private fun planRearrangeReport(text: String, isError: Boolean) {
        planRearrangeHint = text
        planRearrangeHintIsError = isError
    }

    /** 重排后的目标日：过去的日期不给放，返回 null 并给一句人话。 */
    private fun planRearrangeTarget(day: String): String? {
        val today = clock.today()
        val target = runCatching { LocalDate.parse(day) }.getOrNull()
        if (target == null) {
            planMessage = "日期看不懂：$day"
            return null
        }
        if (target < today) {
            planMessage = "过去的日期不能重排；从今天开始往后放。"
            return null
        }
        return day
    }

    /** 拖一条「已排到某天」的节点到另一天（同周内）。 */
    fun planDragOccurrenceToDay(occurrenceId: String, day: String) {
        val service = learningPlan() ?: return
        val planId = learningPlanId ?: return
        val target = planRearrangeTarget(day) ?: return
        val targetWeek = isoWeekOf(LocalDate.parse(target))
        val currentWeek = planSelectedWeek
        if (currentWeek != null && currentWeek != targetWeek) {
            planRearrangeReport("一次只在同一周里挪；要跨周先把节点放到待安排池。", isError = true)
            return
        }
        val result = runCatching {
            service.moveOccurrence(planAdjustCommand(planId), occurrenceId, target, "拖拽重排")
        }.getOrNull()
        refreshPlan()
        tick++
        if (result?.ok == true) {
            planRearrangeReport("已挪到 " + target.takeLast(5) + "。", isError = false)
        } else {
            planRearrangeReport(result?.error?.message ?: "这次没挪成。", isError = true)
        }
    }

    /** 拖一条「待安排池」里的欠账节点到某天：后端会把单元追加进目标周承诺再铺天。 */
    fun planPlaceUnitToDay(unitId: String, day: String) {
        val service = learningPlan() ?: return
        val planId = learningPlanId ?: return
        val target = planRearrangeTarget(day) ?: return
        val week = isoWeekOf(LocalDate.parse(target))
        val result = runCatching {
            service.placeUnit(planAdjustCommand(planId), planId, week, unitId, target)
        }.getOrNull()
        refreshPlan()
        tick++
        if (result?.ok == true) {
            planRearrangeReport("已放到 " + target.takeLast(5) + "。", isError = false)
        } else {
            planRearrangeReport(result?.error?.message ?: "这次没放上。", isError = true)
        }
    }

    /**
     * 今天能多补多少：先记下用户答复的加时，再把本周逾期、且今天装得下的项补进来。
     *
     * 只补「今天容量 = 每天投入 + 加时 - 今天已排」以内的，按路线顺序；
     * 装不下的留在原处，等用户再顺延/跳过，或交给下一步的协商重排。
     */
    fun planCatchUpToday(extraMinutes: Int) {
        val service = learningPlan() ?: return
        val planId = learningPlanId ?: return
        val overdue = planOverdue ?: return
        val clean = extraMinutes.coerceIn(0, MAX_CATCH_UP_MINUTES)
        // 偏好先落盘：下次计划检查问「今天能多投入多少」时，默认值就是它。
        val prefs = studyPrefs.copy(catchUpMinutes = clean).normalized()
        studyPrefs = prefs
        if (::studyPrefsStore.isInitialized) studyPrefsStore.save(prefs)
        val today = clock.today().toString()
        val plannedToday = runCatching { service.today(planId, today) }
            .getOrNull()?.occurrences?.sumOf { it.minutes } ?: 0
        var remaining = (planDailyMinutes ?: overdue.dailyMinutes ?: DEFAULT_DAILY_MINUTES) +
            clean - plannedToday
        var moved = 0
        overdue.thisWeek.forEach { item ->
            if (item.minutes <= remaining) {
                val result = runCatching {
                    service.moveOccurrence(planAdjustCommand(planId), item.occurrenceId, today, "今天补做")
                }.getOrNull()
                if (result?.ok == true) {
                    remaining -= item.minutes
                    moved++
                }
            }
        }
        val leftover = overdue.thisWeek.size - moved
        planMessage = when {
            moved == 0 && leftover > 0 ->
                "今天没有余量了：把每天投入或加时调大，或者把这几条顺延/跳过。"
            leftover > 0 -> "今天补了 " + moved + " 项（记下加时 " + clean + " 分钟），还剩 " + leftover + " 项放不下。"
            moved > 0 -> "今天补了 " + moved + " 项，本周逾期的都排上了（加时 " + clean + " 分钟）。"
            else -> "这周没有逾期的项。"
        }
        refreshPlan()
        tick++
    }

    private fun planMoveOccurrence(occurrenceId: String, toDay: String, reason: String): Boolean {
        val service = learningPlan() ?: return false
        val planId = learningPlanId ?: return false
        val result = runCatching {
            service.moveOccurrence(planAdjustCommand(planId), occurrenceId, toDay, reason)
        }.getOrElse {
            planMessage = "没挪成：" + (it.message ?: "未知原因")
            null
        }
        if (result != null && !result.ok) planMessage = result.error?.message
        refreshPlan()
        tick++
        return result?.ok == true
    }

    private fun planAdjustCommand(planId: String): CommandEnvelope = CommandEnvelope(
        commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "ui.plan.adjust",
    )

    /** 这份计划的第一周早于本周：说明周次写错了（模型曾把 ISO 周写成去年）。 */
    val planStartsInPast: Boolean
        get() = planWeekCards.firstOrNull()?.week?.let { it < currentIsoWeek() } == true

    /**
     * 把「周次写在过去」的计划重排到本周起。
     *
     * 为什么不在原地改：plan_version 不可变、occurrence 身份不能改（后端契约），
     * 所以只能建一份新计划、把单元按原顺序搬过来，再结束旧的。
     */
    private fun uiCommand(planId: String?): CommandEnvelope = CommandEnvelope(
        commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "ui.plan.repair",
    )

    fun repairPastPlan() {
        val service = learningPlan() ?: return
        val planId = learningPlanId ?: return
        val cards = planWeekCards
        if (cards.isEmpty()) return
        val monday = isoWeekMonday(currentIsoWeek())
        var failure: String? = null
        runCatching {
            requireNotNull(monday) { "算不出本周的周一" }
            val units = mutableListOf<UnitDraft>()
            val grouped = mutableListOf<List<Int>>()
            // 同一个单元可能因为「欠账回池 -> 又被本周收走」出现在多张周卡里（旧计划尤其明显）。
            // 搬计划时按 (章节, 标题, 时长) 去重，只认第一次出现：重排不是复制历史。
            val seenUnits = mutableMapOf<String, Int>()
            cards.forEach { card ->
                val weekUnits = service.week(planId, card.week).commitment.units
                if (weekUnits.isEmpty()) return@forEach
                val indexes = weekUnits.mapNotNull { unit ->
                    val key = unit.chapter + "\u0000" + unit.title + "\u0000" + unit.estimateMinutes
                    if (seenUnits.containsKey(key)) return@mapNotNull null
                    units += UnitDraft(
                        chapter = unit.chapter,
                        title = unit.title,
                        estimateMinutes = unit.estimateMinutes.coerceIn(10, 45),
                    )
                    seenUnits[key] = units.lastIndex
                    units.lastIndex
                }
                if (indexes.isNotEmpty()) grouped += indexes
            }
            require(units.isNotEmpty()) { "这份计划里没有可搬的单元" }
            require(grouped.isNotEmpty()) { "这份计划里没有排进任何一周的单元" }
            val commitments = grouped.mapIndexed { index, unitIndexes ->
                CommitmentDraft(week = isoWeekOf(monday.plusWeeks(index.toLong())), unitIndexes = unitIndexes)
            }
            // 在原计划里出一版新草稿并生效：planId / 标题 / 单元都不变，只把周次平移到本周起。
            // 不新建计划——用户点「挪到本周」要的是「这份计划回到本周」，不是多一条计划。
            val saved = service.saveDraft(
                uiCommand(planId),
                planId = planId,
                versionId = null,
                units = units,
                commitments = commitments,
            ).also { require(it.ok) { it.error?.message ?: "存草稿失败" } }.value!!
            service.acceptDraft(
                uiCommand(planId),
                versionId = saved.versionId,
                firstWeek = isoWeekOf(monday),
            ).also { require(it.ok) { it.error?.message ?: "确认草稿失败" } }
            planMessage = "已经挪到本周起：从 ${isoWeekOf(monday)} 这周开始排，之前的完成记录留在上一版里。"
        }.exceptionOrNull()?.let { error -> failure = "没重排成：" + (error.message ?: "未知原因") }
        refreshPlan()
        failure?.let { planMessage = it }
        tick++
    }

    suspend fun refresh() {
        goal = planService.plans().firstOrNull()
        tasks = planService.tasks()
        val now = clock.nowMs()
        summary = planService.summary(now, endOfTodayMs(now, clock.zone))
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
        // 启用本身也是一次「用过」：用户刚把它请回来，插件页应当立刻看到今天的时间
        notePluginUsed(id)
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

    /**
     * 改「每天预计投入」。
     *
     * 越界的值不拒绝，按 [StudyPrefs.normalized] 收进合法区间 —— 设置页的加减按钮
     * 和以后的 agent 工具都走这里，夹取逻辑只留一处。
     */
    fun setDailyMinutes(minutes: Int): String {
        val clean = studyPrefs.copy(dailyMinutes = minutes).normalized()
        studyPrefs = clean
        if (::studyPrefsStore.isInitialized) studyPrefsStore.save(clean)
        return "每天预计投入改成了 " + clean.describe() + "。"
    }

    /** 设置页那对加减按钮：一次 15 分钟。 */
    fun bumpDailyMinutes(step: Int) {
        setDailyMinutes(studyPrefs.dailyMinutes + step)
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

    // ---------------------------------------------------------------- 活动（ActivityWatch）

    /**
     * 活动页的数据来自 ActivityWatch，但 AW 只当采集器：不读也不写它的 `category_sets`。
     *
     * 三份本地数据各管一件事：
     * - `activity.db`：从 AW 拉来的原始事件（丢了能重新采集）；
     * - `taxonomy.json`：用户手写的分类规则（丢了就没了，所以独立存、原子写）；
     * - `settings.json`：轮询间隔、排除进程、浏览器名单。
     *
     * 页面第一次打开才开库（同计划页）：不进活动页的人不为 SQLite 付启动时间。
     */
    private var activityStore: ActivityStore? = null
    private var activitySync: ActivitySync? = null
    private var activityAggregator: ActivityAggregator? = null
    private var activitySettingsCache: ActivitySettings? = null

    /** 分类树与它的存储；「分类规则」页读写这一份。 */
    private var activityTaxonomy: ActivityTaxonomy? = null
    private var activityTaxonomyStore: ActivityTaxonomyStore? = null

    private val activityLock = Mutex()

    /** 活动页最近一次失败的一句话；成功时清空。 */
    internal var activityMessage by mutableStateOf<String?>(null)
        private set

    /** 最近一次同步成功的时间（毫秒）；没跑过为 null。 */
    internal var activitySyncedAtMs by mutableStateOf<Long?>(null)
        private set

    /** 轮询间隔，来自 settings.json（默认 60 秒）。 */
    internal val activitySyncIntervalMs: Long
        get() = (activitySettingsCache?.syncIntervalSeconds ?: 60) * 1000L

    private fun activityDir(): File = File(home, "activity").apply { mkdirs() }

    /**
     * 打开活动库并装配同步器/整理器；已经开过就直接复用。
     *
     * 失败只记一句话，不抛异常 —— 和插件崩溃隔离同一条原则：活动采集坏了，
     * 任务页、计划页、对话页都必须照常能用。
     */
    internal suspend fun ensureActivityEngine(): ActivityAggregator? {
        activityAggregator?.let { return it }
        return activityLock.withLock {
            activityAggregator ?: withContext(Dispatchers.IO) {
                runCatching {
                    val dir = activityDir()
                    val settingsFile = File(dir, "settings.json")
                    val settings = ActivitySettings.load(settingsFile.toPath())
                    if (!settingsFile.exists()) ActivitySettings.save(settingsFile.toPath(), settings)

                    val taxonomyFile = ActivityTaxonomyStore.defaultFile(home.toPath())
                    val taxonomyStore = ActivityTaxonomyStore(taxonomyFile)
                    val taxonomy = taxonomyStore.load()
                    // 第一次运行就把出厂规则落盘：用户想改分类时至少有文件可改，而不是面对「不存在」。
                    if (!taxonomyFile.toFile().exists()) taxonomyStore.save(taxonomy)

                    val store = ActivityStore.open(File(dir, "activity.db").toPath())
                    activityStore = store
                    activitySync = ActivitySync(
                        source = ActivityWatchClient(baseUrl = settings.baseUrl, apiKey = settings.apiKey),
                        store = store,
                        settings = { settings },
                    )
                    activitySettingsCache = settings
                    activityTaxonomyStore = taxonomyStore
                    activityTaxonomy = taxonomy
                    activityAggregator = ActivityAggregator(store, settings, taxonomy)
                    activityAggregator
                }.getOrElse { error ->
                    activityMessage = "活动库打不开：" + (error.message ?: "未知原因")
                    null
                }
            }
        }
    }

    private suspend fun <T> activityIo(block: suspend () -> T): T =
        activityLock.withLock { withContext(Dispatchers.IO) { block() } }

    /**
     * 跑一轮同步，返回一句给界面看的话。
     *
     * AW 没开不算错误：`ActivitySync` 把网络错误收进 `SyncReport.error`，这里把它变成人话，
     * 用户开 AW/修配置后下一轮自己恢复。
     */
    internal suspend fun syncActivity(): String {
        ensureActivityEngine() ?: return activityMessage ?: "活动库打不开"
        val sync = activitySync ?: return "活动库打不开"
        val report = runCatching { activityIo { sync.syncOnce() } }
            .getOrElse { error ->
                val text = "活动同步失败：" + (error.message ?: "未知原因")
                activityMessage = text
                return text
            }
        return when {
            report.skipped -> {
                activityMessage = null
                "活动采集在 settings.json 里关掉了（enabled=false）"
            }

            report.error != null -> {
                val text = "活动同步失败：" + report.error
                activityMessage = text
                text
            }

            else -> {
                activityMessage = null
                activitySyncedAtMs = report.nowMs
                "已同步 " + report.buckets + " 个 bucket、" + report.rows + " 条事件"
            }
        }
    }

    /** 某一天的汇总；库没打开 / 读失败时返回 null（错误在 [activityMessage]）。 */
    internal suspend fun activityDay(dayMs: Long): DaySummary? {
        val aggregator = ensureActivityEngine() ?: return null
        return runCatching { activityIo { aggregator.daySummary(dayMs) } }
            .getOrElse { error ->
                activityMessage = "活动数据读取失败：" + (error.message ?: "未知原因")
                null
            }
    }

    /** 某一天的清洗后时间轴（时间轴 / 详情用）。 */
    internal suspend fun activitySegments(dayMs: Long): List<Segment> {
        val aggregator = ensureActivityEngine() ?: return emptyList()
        return runCatching { activityIo { aggregator.segments(dayMs) } }
            .getOrElse { error ->
                activityMessage = "活动数据读取失败：" + (error.message ?: "未知原因")
                emptyList()
            }
    }

    /** 一段日期范围的汇总（含首尾两天）。 */
    internal suspend fun activityRange(firstDayMs: Long, lastDayMs: Long): RangeSummary? {
        val aggregator = ensureActivityEngine() ?: return null
        return runCatching { activityIo { aggregator.rangeSummary(firstDayMs, lastDayMs) } }
            .getOrElse { error ->
                activityMessage = "活动数据读取失败：" + (error.message ?: "未知原因")
                null
            }
    }

    /** 应用排行；[categoryId] 非空时只看该分类及其所有后代。 */
    internal suspend fun activityAppRanking(dayMs: Long, categoryId: String? = null): List<AppSlice> {
        val aggregator = ensureActivityEngine() ?: return emptyList()
        return runCatching { activityIo { aggregator.appRanking(dayMs, categoryId) } }
            .getOrElse { error ->
                activityMessage = "活动数据读取失败：" + (error.message ?: "未知原因")
                emptyList()
            }
    }

    /** 「为什么归到这个分类」：详情浮层里的规则调试。 */
    internal suspend fun activityExplain(app: String?, title: String?, url: String?): ClassificationExplanation? {
        val aggregator = ensureActivityEngine() ?: return null
        return runCatching { aggregator.explain(app, title, url) }.getOrNull()
    }

    /** 分类规则里编译不过的正则（有问题时活动页要提示，而不是静默把时间算成未分类）。 */
    internal fun activityProblems(): List<String> = activityAggregator?.classifierProblems ?: emptyList()

    /** 当前分类树（「分类规则」页渲染用）；引擎没开时先开。 */
    internal suspend fun activityTaxonomySnapshot(): ActivityTaxonomy? {
        ensureActivityEngine() ?: return null
        return activityTaxonomy
    }

    /**
     * 保存分类树：先校验，再落盘，最后换掉整理器里的分类器 —— 不用关库，下一次取数就用新规则。
     *
     * 返回空列表表示保存成功；非空是给界面逐条显示的问题（校验错误 + 编译不过的正则）。
     * 保存失败时旧的 [activityTaxonomy] 不动，页面仍能继续用。
     */
    internal suspend fun saveActivityTaxonomy(next: ActivityTaxonomy): List<String> {
        ensureActivityEngine() ?: return listOf(activityMessage ?: "活动库打不开")
        val problems = (next.validate() + ActivityClassifier(next).problems).distinct()
        if (problems.isNotEmpty()) return problems
        val taxonomyStore = activityTaxonomyStore ?: return listOf("活动库打不开")
        val failure = runCatching {
            activityIo {
                taxonomyStore.save(next)
                activityTaxonomy = next
                val store = activityStore
                val settings = activitySettingsCache
                if (store != null && settings != null) {
                    activityAggregator = ActivityAggregator(store, settings, next)
                }
            }
        }.exceptionOrNull()
        if (failure != null) return listOf("分类规则保存失败：" + (failure.message ?: "未知原因"))
        activityMessage = null
        return emptyList()
    }

    companion object {

        /** [home] 可注入，测试就不必污染真实的家目录。 */
        suspend fun create(
            home: File = System.getenv("TRELLIS_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: File(System.getProperty("user.home"), ".trellis"),
            /**
             * 只给测试用：往内置插件里再塞几个。
             *
             * 产品里不该有「只为演示而存在」的插件——之前那个「危险操作」占着首页一格、
             * 又没有任何真实用途，已经移到这里之外的测试夹具里了。
             */
            extraBuiltins: List<Plugin> = emptyList(),
        ): AppRuntime {
            val runtime = AppRuntime()
            // 时间源先定：内核、计划后端、AI 工具都从同一个钟取时间（规范见 docs/time-conventions.md）。
            val clockSetup = createTrellisClock(home)
            runtime.clock = clockSetup.clock
            runtime.devModeEnabled = clockSetup.devMode
            runtime.devClockAllowed = clockSetup.allowed
            runtime.devClockRejectedReason = clockSetup.rejectedReason
            runtime.devClockStore = DevClockStore(home)
            val storeFile = File(home, "store.json")
            val pluginsDir = File(home, "plugins").apply { mkdirs() }

            val kernel = Kernel.boot(
                KernelConfig(
                    sdkVersion = SemVer.parse("1.0.0"),
                    platform = Platform.JVM,
                    pluginsDir = pluginsDir.absolutePath,
                    loaders = listOf(
                        BuiltinLoader(
                            listOf(
                                PlanCorePlugin(),
                                PlanUiPlugin(runtime),
                                // 默认主题包也是插件：可在插件页停用，停用后回落默认配色。
                                BentoThemePlugin(),
                            ) + extraBuiltins,
                        ),
                        DslLoader(JvmPluginFileSystem()),
                        ScriptLoader(JvmPluginFileSystem()),
                    ),
                    storage = FileStorageBackend(storeFile),
                    // 「删除」要真的动文件：移进 <plugins>/.trash，7 天后连数据一起清。
                    pluginFiles = JvmPluginFileStore(pluginsDir),
                    // 审计落盘：装/启用/停用/删除/彻底删除都留一行证据，重启后还能查。
                    auditLedger = FileAuditLedger(File(home, "audit.log")),
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
            // 模拟时间必须让人看见：启动日志 + 页面徽章，不允许静默生效。
            runtime.simulatedToday = clockSetup.clock.simulatedDate()
            if (runtime.simulatedToday != null) {
                runtime.appendAppLog(
                    "模拟时间：" + runtime.simulatedToday + "（真实 " + LocalDate.now() + "）" +
                        "；数据目录 " + home.absolutePath,
                )
            }
            clockSetup.rejectedReason?.let { runtime.appendAppLog("调试时钟未启用：$it") }
            runtime.layoutStore = LayoutStore(home)
            runtime.cardLayout = runtime.layoutStore.load()
            runtime.uiPrefsStore = UiPrefsStore(home)
            runtime.uiPrefs = runtime.uiPrefsStore.load()
            runtime.studyPrefsStore = StudyPrefsStore(home)
            runtime.studyPrefs = runtime.studyPrefsStore.load()
            runtime.pluginUsageStore = PluginUsageStore(home)
            runtime.pluginUsage.putAll(runtime.pluginUsageStore.load())
            // 启动时正在用的插件主题算「正在用」：插件页悬停那句「正在使用 · 刚刚」
            // 描述的就是这一刻，不该显示上次切主题的时间。
            runtime.uiPrefs.theme.substringBefore("/").takeIf { runtime.uiPrefs.theme.contains("/") }?.let {
                runtime.pluginUsage[it] = System.currentTimeMillis()
                runtime.pluginUsageStore.save(runtime.pluginUsage)
            }
            runtime.pluginStyleStore = PluginStyleStore(home)
            runtime.pluginCardStyles = runtime.pluginStyleStore.load()
            runtime.chatStore = ChatStore(home)
            runtime.planCheckStore = PlanCheckStore(home)
            runtime.knowledgeStore = KnowledgeStore(home, runtime.clock)
            runtime.loadChatSessions()
            // 聊天器的默认行为就是「接着上次说」：启动时把最近一段接回去，
            // 而不是每次都从空白开始、让人以为记录丢了。
            runtime.chatSessions.firstOrNull()?.let { runtime.openChat(it.id) }
            runtime.onWindowFocused()
            runtime.wireApprovalDialog()

            if (plan.plans().isEmpty()) plan.createPlan("我的第一个计划")
            runtime.refresh()
            // 待办板上的计划节点是启动时就要有的：不点计划页也得看得见。
            runtime.refreshPlan()

            // 截图/联调用：TRELLIS_DEMO_CHOICES="落成待办|排成计划" 直接模拟「模型调了 ask_choices」，
            // 把选项竖着摆到输入框上方。真实路径是模型调工具，这里只是省得每次都等模型。
            System.getenv("TRELLIS_DEMO_CHOICES")?.takeIf { it.isNotBlank() }?.let { raw ->
                runtime.offerChoices(raw.split("|").map { it.trim() })
            }
            // 演示「歧义先问」：TRELLIS_DEMO_SEND="帮我弄一下" 启动后自动发这句，
            // 走的是完整流程（意图 → 歧义 → 反问 + 按钮），不是摆假数据。
            System.getenv("TRELLIS_DEMO_SEND")?.takeIf { it.isNotBlank() }?.let { line ->
                runtime.launchDemoSend(line)
            }
            return runtime
        }
    }
}

/**
 * 启动时落在哪一页。默认任务页；`TRELLIS_TAB=plan|chat|knowledge|plugins|diag|settings` 可用于演示/截图，
 * 省得每次手动点导航（也省得用鼠标自动化去点 —— 那个把窗口点最小化过）。
 */
private fun initialTab(): Int = when (System.getenv("TRELLIS_TAB")?.trim()?.lowercase()) {
    "plan" -> TAB_PLAN
    "chat" -> TAB_CHAT
    "activity" -> TAB_ACTIVITY
    "knowledge", "kb" -> TAB_KNOWLEDGE
    "plugins" -> TAB_PLUGINS
    "diag" -> TAB_DIAG
    "settings" -> TAB_SETTINGS
    else -> TAB_TASKS
}

/**
 * 「今天」的边界由**界面层**给出，领域层不该猜时区  这是 PlanService.summary 的约定。
 * 用注入时钟的 zone（开发者模式拨日期后，边界也跟着模拟日期走）。
 */
private fun endOfTodayMs(nowMs: Long, zone: ZoneId): Long {
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
