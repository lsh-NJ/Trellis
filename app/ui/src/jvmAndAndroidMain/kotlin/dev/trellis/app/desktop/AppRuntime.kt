package dev.trellis.app.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.LlmConfig
import dev.trellis.ai.LlmImport
import dev.trellis.ai.HttpLlmClient
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmConfigLoader
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import dev.trellis.ai.manager.ManagerAgent
import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.DeepSeekMarkup
import dev.trellis.ai.manager.ManagerPrompt
import dev.trellis.ai.manager.ManagerRouter
import dev.trellis.ai.steward.StewardPrompt
import dev.trellis.ai.steward.StewardRitual
import dev.trellis.ai.tool.ToolAgent
import dev.trellis.ai.tool.ToolTrace
import dev.trellis.domain.Plan
import dev.trellis.domain.PlanService
import dev.trellis.domain.PlanSummary
import dev.trellis.domain.Task
import dev.trellis.domain.TaskId
import dev.trellis.domain.TaskStatus
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.GoalView
import dev.trellis.domain.plan.DayOccurrence
import dev.trellis.domain.plan.DayView
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.LearningPlanInfo
import dev.trellis.domain.plan.OverdueView
import dev.trellis.domain.plan.PoolUnit
import dev.trellis.domain.plan.ReviewView
import dev.trellis.domain.plan.RhythmResult
import dev.trellis.domain.plan.StageView
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.domain.plan.WeekDay
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.feature.learning.PlanDocStore
import dev.trellis.feature.exams.ExamSeed
import dev.trellis.feature.exams.Exercise
import dev.trellis.feature.exams.ExerciseAttempt
import dev.trellis.feature.exams.ExerciseAttemptStore
import dev.trellis.feature.exams.ExerciseBank
import dev.trellis.feature.exams.ExerciseFileStore
import dev.trellis.feature.exams.SubjectMastery
import dev.trellis.feature.exams.masteryOf
import dev.trellis.activity.ActivityAggregator
import dev.trellis.activity.ActivityClassifier
import dev.trellis.activity.ActivitySettings
import dev.trellis.activity.ActivityStore
import dev.trellis.activity.ActivitySync
import dev.trellis.activity.ActivityTaxonomy
import dev.trellis.activity.ActivityTaxonomyStore
import dev.trellis.activity.ActivityWatchClient
import dev.trellis.activity.AppSlice
import dev.trellis.activity.Attribution
import dev.trellis.activity.AttributionSource
import dev.trellis.activity.AttributionStatus
import dev.trellis.activity.Attributions
import dev.trellis.activity.ClassificationExplanation
import dev.trellis.activity.DaySummary
import dev.trellis.activity.NO_TASK_REF
import dev.trellis.activity.RangeSummary
import dev.trellis.activity.Segment
import dev.trellis.activity.TaskTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
import kotlinx.coroutines.withTimeoutOrNull

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

/**
 * 练习二级页：不常驻导航，只在用户接受了一条练习提议后从会话页打开，只显示那一道题。
 *
 * 序号同样往后放而不是插进中间，理由见 [TAB_ACTIVITY]。
 */
internal const val TAB_PRACTICE = 8

/**
 * 目标 / 项目二级页：从计划页标题旁的「目标」进来。
 *
 * 序号同样往后放而不是插进中间，理由见 [TAB_ACTIVITY]。
 */
internal const val TAB_GOALS = 9

/** 练习提议去重用的 topic；同一单元提过一次就不再提。 */
private const val PRACTICE_PROPOSAL_TOPIC = "练习提议"

/** 这一次运行里已经提议过的练习；重开程序后从空开始（同一单元另有记忆里的去重）。 */
private val proposedPracticeExerciseIds = mutableSetOf<String>()

internal val RENDERED_MOUNT_POINTS: Set<ExtensionPointId> = setOf(
    Slots.HomeHeader.id,
    Slots.HomeSection.id,
    Slots.EmptyState.id,
    Slots.AppOverlay.id,
    Slots.ReportChart.id,
    Slots.SettingsSection.id,
)

/** 一次截图：[text] 是给人看的话（存到哪、多大），[dataUrl] 是给模型看的图片。 */
/**
 * 首页右栏的完成情况；由 [AppRuntime.refreshPlan] 从后端周视图算好。
 *
 * [done]/[total] 是「本周」，[allDone]/[allTotal] 是「所有未结束计划的全部节点」。
 * 任务页列的是跨计划的所有节点，进度只算某一份或跟着计划页选中的周走都是 bug。
 */
data class WeekProgress(
    val label: String,
    val done: Int,
    val total: Int,
    val allDone: Int,
    val allTotal: Int,
    val plannedMinutes: Int,
) {
    val remaining: Int get() = (total - done).coerceAtLeast(0)
    val allRemaining: Int get() = (allTotal - allDone).coerceAtLeast(0)
    val progress: Float get() = if (total == 0) 0f else done.toFloat() / total
    val allProgress: Float get() = if (allTotal == 0) 0f else allDone.toFloat() / allTotal
}

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
            is UiNode.ListRow -> texts += node.title + (node.trailing?.let { "（" + it + "）" } ?: "")
            is UiNode.Switch -> texts += node.label + (if (node.checked) " 开" else " 关")
            is UiNode.Chip -> texts += node.label
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
    /** 录演示时可以设 TRELLIS_HIDE_SIM_BANNER=1 把「模拟时间」提示条藏掉，避免画面里出现调试控件。 */
    val isSimulatingTime: Boolean
        get() = simulatedToday != null && System.getenv("TRELLIS_HIDE_SIM_BANNER")?.trim() != "1"

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
     * 调试用：清除数据（开发者模式）。
     *
     * 具体删什么见 [DataWipe]：配置（模型 key / 界面偏好 / 学习偏好 / 管家设置 / 活动设置）一律保留。
     * 先关掉会写文件的句柄再删，删完把内存里的视图状态复位；计划库下一次刷新会惰性重建一个空库。
     */
    internal fun clearData(scope: WipeScope): WipeResult {
        if (chatBusy) {
            return WipeResult(
                deleted = emptyList(),
                failed = listOf("正在对话/整理中，等这一轮结束再清"),
            )
        }
        if (scope == WipeScope.Plans || scope == WipeScope.All) {
            runCatching { learningPlanService?.close() }
            learningPlanService = null
            learningPlanId = null
            planDocBrowseId = null
            planAll = emptyList()
            planWeekCards = emptyList()
            planFocusId = null
            planOverdue = null
            planDayView = null
            planReview = null
            planPendingReviewWeek = null
            planSettingsOpen = false
            planMessage = null
        }
        if (scope == WipeScope.Activity || scope == WipeScope.All) {
            runCatching { activityStore?.close() }
            activityStore = null
            activitySync = null
            activityAggregator = null
            activityMessage = null
            activitySyncedAtMs = null
        }
        val result = DataWipe.wipe(home, scope)
        when (scope) {
            WipeScope.Plans -> {
                refreshPlan()
                refreshGoals()
            }
            WipeScope.Chats -> {
                loadChatSessions()
                if (currentChatId == null || chatSessions.none { it.id == currentChatId }) newChat()
            }
            WipeScope.Knowledge -> Unit // 知识库每次从文件现算，切回去就是空的
            WipeScope.Activity -> Unit // 下次进活动页会重新打开空库
            WipeScope.Practice -> reloadExamBank()
            WipeScope.All -> {
                loadChatSessions()
                newChat()
                reloadExamBank()
                refreshPlan()
                refreshGoals()
                tick++
            }
        }
        appendAppLog(
            "调试：清除数据（" + scope.title + "）删除 " + result.deleted.size + " 项" +
                if (result.failed.isEmpty()) "" else "，失败 " + result.failed.size + " 项：" + result.failed.joinToString("、"),
        )
        return result
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
        // 「只粘一个 key」是最常见的用法：界面上那两个框留空就该走默认值。
        // 以前直接把空串传下去，于是 normalizeEndpoint("") 返回 null，
        // 用户看到的是「地址要以 http:// 或 https:// 开头，现在拿到的是「」。」——
        // 明明他什么都没填，却被要求去填地址。
        val result = LlmImport.parse(
            raw,
            endpoint.ifBlank { LlmConfigLoader.DEFAULT_ENDPOINT },
            model.ifBlank { LlmConfigLoader.DEFAULT_MODEL },
        )
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
        openOccurrenceTimer(node.planId, node.occurrenceId ?: return, node.title)
    }

    /**
     * 按「某一次排期」开计时。
     *
     * 手机的计划页只有 [DayOccurrence]（日历条/单元弹层拿到的都是它），
     * 没有完整的 [PlanNodeRow]。以前只能从任务详情开计时，计划单元本身没有入口 ——
     * 明明「在这道题上花多久」比「在这个任务上花多久」更值得记。
     */
    internal fun openOccurrenceTimer(planId: String, occurrenceId: String, title: String) {
        endNodeSession()
        timerNodeTarget = TimerNodeTarget(planId, occurrenceId, title)
        timerTaskId = null
        timerTaskTitle = title
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
     * 当前在哪一页。
     *
     * 被提到这里来，不是为了别的：agent 要能回答「我现在看到的是什么」。
     * 页签状态留在 Compose 里，它对屏幕就永远是瞎的 —— `describe_screen` 只能猜。
     */
    var currentTab by mutableStateOf(initialTab())

    /** 知识库上次打开的笔记 id；切页回来接着看同一篇（只记在内存，不进配置）。 */
    var knowledgeNoteId by mutableStateOf<String?>(null)

    /** 别的页请求「打开某篇笔记并选中一段」；知识库页消费后清空。 */
    internal var pendingKnowledgeReveal by mutableStateOf<KnowledgeRevealTarget?>(null)

    /** 打开某篇笔记并选中一段（start/end 传 -1 表示只打开）。 */
    fun openKnowledgeReveal(noteId: String, start: Int, end: Int) {
        pendingKnowledgeReveal = KnowledgeRevealTarget(noteId, start, end)
        currentTab = TAB_KNOWLEDGE
    }

    /** 「AI 自动完成」列表里点来源笔记：打开它并选中当时的证据片段。 */
    fun openAutoCompletionNote(entryId: String) {
        val entry = readAutoCompletionLedger().entries.firstOrNull { it.id == entryId } ?: return
        val note = runCatching { knowledgeStore.read(entry.noteId) }.getOrNull()
        val start = note?.body?.indexOf(entry.evidence)?.takeIf { it >= 0 } ?: -1
        val end = if (start >= 0) start + entry.evidence.length else -1
        openKnowledgeReveal(entry.noteId, start, end)
    }

    // ---- 练习：学完一个单元后提议，接受后进二级页只做这一道 ----

    /**
     * 题库：打包的种子 JSONL + `<home>/exams/` 里的用户题库，按 id 去重合并。
     *
     * 题库没有浏览页；它只为两件事存在：①匹配「这一单元学完练哪道题」，
     * ②用户接受提议后按 id 取题渲染。
     */
    internal var examBank: ExerciseBank = ExerciseBank(emptyList())
        private set

    /** 题库加载问题（种子缺失 / 坏行）；不阻断其它功能，写进日志供排查。 */
    internal var examLoadNotes: List<String> = emptyList()
        private set

    /** 当前练习页展示的那一道题；只可能是用户刚接受的那道，null = 没在练习。 */
    internal var practiceExercise by mutableStateOf<Exercise?>(null)
        private set

    /** 练习页已勾选的选项标签（判断题也是 A/B）。 */
    internal var practiceSelected by mutableStateOf<Set<String>>(emptySet())
        private set

    internal var practiceAnswerVisible by mutableStateOf(false)
        private set

    /** 作答记录文件（`<home>/exams/attempts.jsonl`）；题库加载后初始化。 */
    private var attemptStore: ExerciseAttemptStore? = null

    /** 当前这道题是否已记录作答（一次练习只记一条，重复点「看答案」不重复记账）。 */
    private var practiceRecorded by mutableStateOf(false)

    /** 历史作答（`<home>/exams/attempts.jsonl`）；掌握度与推荐都从这里现算。 */
    internal var practiceAttempts by mutableStateOf(emptyList<ExerciseAttempt>())
        private set

    internal val practiceActive: Boolean get() = practiceExercise != null

    /** 当前这道题是否已经记过作答（决定知识点题的自评按钮还显不显示）。 */
    internal val practiceAnswered: Boolean get() = practiceRecorded

    /** 当前这道题所属学科的掌握度小计；没做过就是 null。 */
    internal val practiceMastery: SubjectMastery?
        get() = practiceExercise?.let { masteryOf(practiceAttempts, it.subject) }

    internal fun togglePracticeOption(label: String) {
        if (practiceAnswerVisible) return
        practiceSelected = if (label in practiceSelected) practiceSelected - label else practiceSelected + label
    }

    internal fun revealPracticeAnswer() {
        if (practiceAnswerVisible) return
        practiceAnswerVisible = true
        // 客观题在揭示答案时自动判分；知识点/简答没有可核对的标签，留给用户自评。
        val exercise = practiceExercise ?: return
        if (exercise.kind.isObjective) {
            val expected = exercise.normalizedAnswerLabels().map { it.uppercase() }.toSet()
            val chosen = practiceSelected.map { it.uppercase() }.toSet()
            recordPracticeAttempt(correct = expected.isNotEmpty() && chosen == expected, selfReported = false)
        }
    }

    /**
     * 知识点/简答题的「记住了 / 没记住」自评。
     *
     * 这类题没有可自动核对的标准答案，用自评也能给出可演示的掌握度；记录里
     * 用 `selfReported = true` 标出来，统计口径上不会被当成客观判分。
     */
    internal fun ratePracticeAnswer(remembered: Boolean) {
        val exercise = practiceExercise ?: return
        if (exercise.kind.isObjective) return
        recordPracticeAttempt(correct = remembered, selfReported = true)
    }

    /** 记一条作答（一次练习最多一条）；写盘失败只记日志，不影响做题。 */
    private fun recordPracticeAttempt(correct: Boolean, selfReported: Boolean) {
        if (practiceRecorded) return
        val exercise = practiceExercise ?: return
        practiceRecorded = true
        val attempt = ExerciseAttempt(
            at = clock.nowMs(),
            exerciseId = exercise.id,
            subject = exercise.subject,
            course = exercise.course,
            kind = exercise.kind,
            correct = correct,
            selfReported = selfReported,
        )
        runCatching { attemptStore?.append(attempt) }
            .onFailure { appendAppLog("练习记录没写进去：" + (it.message ?: it.javaClass.simpleName)) }
        practiceAttempts = practiceAttempts + attempt
    }

    /** 关闭练习页：回会话页；下次接受新提议会重新打开。 */
    internal fun closePractice() {
        practiceExercise = null
        practiceSelected = emptySet()
        practiceAnswerVisible = false
        practiceRecorded = false
        currentTab = TAB_CHAT
    }

    /**
     * 接受一条练习提议：把卡片标成已接受、落盘，并打开练习二级页只显示这一道题。
     *
     * 找不到题目（题库被换掉/删了）时只标记接受 + 写日志，不打开空页面。
     */
    internal fun acceptPracticeProposal(lineId: Long) {
        val index = chat.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val proposal = chat[index].practiceProposal ?: return
        chat[index] = chat[index].copy(practiceState = PracticeProposalState.Accepted)
        persistChat()
        val exercise = examBank.get(proposal.exerciseId)
        if (exercise == null) {
            appendAppLog("练习提议：题库里找不到 " + proposal.exerciseId)
            return
        }
        practiceExercise = exercise
        practiceSelected = emptySet()
        practiceAnswerVisible = false
        practiceRecorded = false
        currentTab = TAB_PRACTICE
    }

    /** 「先不练」：卡片记成已跳过，并把这条提议记进记忆，同一单元不再反复提。 */
    internal fun dismissPracticeProposal(lineId: Long) {
        val index = chat.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val proposal = chat[index].practiceProposal ?: return
        chat[index] = chat[index].copy(practiceState = PracticeProposalState.Dismissed)
        persistChat()
        stewardStore.appendMemory(
            StewardMemoryEvent(
                at = clock.nowMs(),
                day = clock.today().toString(),
                kind = StewardMemoryKind.Note,
                text = "这次先不练：" + proposal.subject,
                topics = listOf(PRACTICE_PROPOSAL_TOPIC),
                entities = listOf(proposal.planId, proposal.exerciseId),
            ),
        )
    }

    /**
     * 学完一个单元后看看题库里有没有匹配的练习；有就在管家会话里主动提一条。
     *
     * 只对「路线单元」触发（待办勾掉不算）；同一单元只提一次（memory 里记一条 Note，
     * 按 planId + unitId 去重）。匹配/去重都是本地的，不调模型。
     */
    private fun maybeProposePractice(planId: String, occurrence: DayOccurrence) {
        val unitId = occurrence.unitId ?: return
        if (examBank.isEmpty || chatBusy) return
        val planTitle = runCatching { learningPlan()?.planState(planId)?.title }.getOrNull().orEmpty()
        if (stewardStore.hasNote(PRACTICE_PROPOSAL_TOPIC, listOf(planId, unitId))) return
        val alreadyProposed = runCatching {
            chatStore.read(StewardRuntime.STEWARD_SESSION_ID)?.lines.orEmpty()
                .mapNotNull { line -> line.practiceProposal?.takeIf { it.planId == planId }?.exerciseId }
                .toSet()
        }.getOrDefault(emptySet()) + proposedPracticeExerciseIds
        val exercise = examBank.recommendFor(
            text = listOf(planTitle, occurrence.title, occurrence.chapter).joinToString(" "),
            attempts = practiceAttempts,
            limit = 5,
            excludeIds = alreadyProposed,
        ).firstOrNull()
        if (exercise == null) {
            // 别静默：现场出现过「完成 24 条、一次练习提议都没弹」，事后只能靠日志判断是
            // 题库没匹配上、还是都被去重了。
            appendAppLog(
                "练习提议：题库里没有匹配（计划=" + planTitle + "，单元=" + occurrence.title +
                    "，章节=" + occurrence.chapter + "，已提议 " + alreadyProposed.size + " 条）",
            )
            return
        }
        proposedPracticeExerciseIds += exercise.id
        val proposal = PracticeProposal(
            id = "practice-" + clock.nowMs(),
            planId = planId,
            planTitle = planTitle.ifBlank { "这个计划" },
            subject = exercise.subjectLabel,
            exerciseId = exercise.id,
            title = "学完「" + occurrence.title + "」，练一道题？",
            detail = buildString {
                append("题库里有匹配「" + exercise.subject + "」的练习，只显示这一道；做完就回会话。")
                masteryOf(practiceAttempts, exercise.subject)?.let {
                    append("你在这科" + it.label() + "。")
                }
            },
        )
        val text = "学完「" + occurrence.title + "」了。" +
            "题库里有匹配「" + exercise.subject + "」的练习，现在练一道吗？"
        if (appendStewardLine(StewardRuntime.STEWARD_SESSION_ID, text, practice = proposal)) {
            stewardStore.appendMemory(
                StewardMemoryEvent(
                    at = clock.nowMs(),
                    day = clock.today().toString(),
                    kind = StewardMemoryKind.Note,
                    text = "练习提议：" + exercise.subject + "｜" + exercise.id,
                    topics = listOf(PRACTICE_PROPOSAL_TOPIC),
                    entities = listOf(planId, unitId),
                ),
            )
        }
    }

    /**
     * 重新加载题库：种子资源 + `<home>/exams/` 下的 `.jsonl`。
     *
     * 种子资源只在桌面打包里（jvmMain resources），安卓上读不到就记一条问题、退化成空题库；
     * 用户自己放的题库走 [ExerciseFileStore]，坏行逐条记下来、不阻断其它文件。
     */
    internal fun reloadExamBank() {
        val notes = mutableListOf<String>()
        val banks = mutableListOf<ExerciseBank>()
        runCatching { ExamSeed.load() }
            .onSuccess { banks += it }
            .onFailure { notes += "种子题库没加载：" + (it.message ?: it.javaClass.simpleName) }
        runCatching { ExerciseFileStore.loadDirectory(File(home, "exams").toPath()) }
            .onSuccess { result ->
                banks += result.bank
                result.issues.take(5).forEach { notes += it.describe() }
                if (result.issues.size > 5) notes += "还有 " + (result.issues.size - 5) + " 条导入问题没列出"
            }
            .onFailure { notes += "用户题库没加载：" + (it.message ?: it.javaClass.simpleName) }
        val merged = LinkedHashMap<String, Exercise>()
        banks.forEach { bank -> bank.exercises.forEach { merged.putIfAbsent(it.id, it) } }
        examBank = ExerciseBank(merged.values.toList())
        examLoadNotes = notes
        notes.forEach { appendAppLog("题库：" + it) }
        runCatching {
            val store = ExerciseAttemptStore(File(home, "exams/attempts.jsonl").toPath())
            attemptStore = store
            practiceAttempts = store.load()
        }.onFailure {
            appendAppLog("练习记录没读出来：" + (it.message ?: it.javaClass.simpleName))
        }
    }

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

    /** 会话列表里有没有没看过的主动消息：导航「对话」和对应会话行据此显示红点。 */
    val hasUnreadChat: Boolean get() = chatSessions.any { it.unread }

    /** 管家的落盘：设置、checkpoint、记忆、摘要（`<home>/steward/`）。 */
    internal lateinit var stewardStore: StewardStore
        private set

    /** 管家仪式引擎：决定该不该说话、组织事实、润色、落盘。 */
    internal lateinit var stewardRuntime: StewardRuntime
        private set

    /** 每分钟 tick：应用一直开着时晚间仪式也能到点说话（关着就没办法）。 */
    private var stewardJob: Job? = null

    /** 主窗口有没有焦点：失焦时才弹系统通知，前台只用未读红点。 */
    private var windowFocused = true

    /** 系统通知实现；测试里换成 [Notifier.silent]。 */
    internal var notifier: Notifier = Notifier.silent

    /**
     * 发一条通知，能在点开时落到 [target]。
     *
     * 桌面那三条命令行没有回调，忽略目标；安卓走 `PendingIntent`。
     * 通知失败一律吞掉 —— 该说的话已经落进会话/计划里了，通知只是提醒。
     */
    internal fun notifyWithTarget(title: String, body: String, target: NotifyTarget) {
        runCatching {
            val current = notifier
            if (current is TargetedNotifier) {
                current.notify(title, body, target)
            } else {
                current.notify(title, body)
            }
        }
    }

    /** 管家设置（界面可改；落盘在 [StewardStore]）。 */
    internal var stewardSettings by mutableStateOf(StewardSettings())
        private set

    /** 今天已经自动采纳了几条管家建议（设置页显示用；换天归零）。 */
    internal val stewardAutoAdoptedToday: Int
        get() {
            if (!::stewardStore.isInitialized) return 0
            val checkpoints = runCatching { stewardStore.checkpoints() }.getOrNull() ?: return 0
            return if (checkpoints.autoAdoptDay == clock.today().toString()) checkpoints.autoAdoptCount else 0
        }

    internal fun updateStewardSettings(settings: StewardSettings) {
        if (!::stewardStore.isInitialized) return
        stewardStore.saveSettings(settings)
        stewardSettings = stewardStore.settings()
        tick += 1
    }

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

    /**
     * 复制结论到系统剪贴板；失败不弹窗，只在按钮上提示。
     *
     * 实现搬到了 [HostPlatform]：桌面是 AWT 剪贴板，手机是 `ClipboardManager`。
     */
    fun copyToClipboard(text: String) = HostPlatform.copyToClipboard(text)

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
        // 选项属于上一轮对话：换会话后还留着，点一下会当成新会话的提问发出去。
        chatChoices = emptyList()
        // 行 id 是**整个进程**单调递增的：会话之间、重启前后都不能撞，
        // 否则界面按 id 做 key、按 id 找行（采纳建议）都会指错行。
    }

    /** 打开一段历史会话；也把最近 20 条正式发言接回模型上下文。 */
    fun openChat(id: String) {
        if (chatBusy) return
        val session = chatStore.read(id) ?: return
        currentChatId = session.id
        chat.clear()
        chat += session.lines
        chatChoices = emptyList()
        // 只升不降：从任意一段历史会话切过来，新行都不能复用别的会话已有的 id。
        nextChatLineId = maxOf(nextChatLineId, (session.lines.maxOfOrNull { it.id } ?: 0L) + 1)
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
        // 启动时把计数器顶到所有会话的最大 id 之上（以前只按当前会话算，
        // 重启后给管家追加消息会撞上历史行 id）。
        val maxLineId = chatSessions.maxOfOrNull { session ->
            session.lines.maxOfOrNull { it.id } ?: 0L
        } ?: 0L
        nextChatLineId = maxOf(nextChatLineId, maxLineId + 1)
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
     * 主窗口重新获得焦点：管家检查一次「晨间 / 晚间仪式」是否该说话了。
     *
     * 触发是**状态事件**不是一次性定时器：应用启动、从别的窗口切回来都会走这里；
     * 是否真的发由管家的 checkpoints 决定（同一个应用内日期最多一次）。
     * 这一点继承自被删掉的「计划检查」：主动消息只在用户回到应用时出现，
     * 不装独立闹钟、不自动扣模型。
     */
    fun onWindowFocused() {
        windowFocused = true
        triggerStewardCheck()
    }

    fun onWindowLostFocus() {
        windowFocused = false
        // 用户刚离开：如果挂着一份「只挪动」的建议，这是自动采纳最合适的时机。
        if (::stewardRuntime.isInitialized && ::chatStore.isInitialized) {
            chatScope.launch {
                runCatching { autoAdoptStewardProposalIfAllowed() }
                    .onFailure { appendAppLog("自动采纳失败：$it") }
            }
        }
    }

    /** 回到应用时检查一次：该发就发，不该发由 checkpoint 决定。 */
    private fun triggerStewardCheck() {
        if (!::stewardRuntime.isInitialized) return
        chatScope.launch { runStewardCheck() }
    }

    /** 每分钟 tick：应用一直开着的时候，晚间仪式不用等用户切窗口。 */
    private fun startStewardTicker() {
        if (stewardJob?.isActive == true) return
        stewardJob = chatScope.launch {
            while (true) {
                delay(60_000)
                runStewardCheck()
            }
        }
    }

    /**
     * 计划 ↔ 会话绑定：建计划时如果人在某个普通会话里，就把这个会话记成它的「周计划会话」。
     *
     * 管家会话不绑定 —— 管家是代跑的人，不能既是执行者又是计划的长期上下文。
     */
    internal fun bindPlanSession(planId: String) {
        if (planId.isBlank()) return
        val sessionId = currentChatId ?: return
        val kind = chatSessions.firstOrNull { it.id == sessionId }?.kind ?: SessionKind.Manager
        if (kind == SessionKind.Steward) return
        runCatching { planSessionStore.bind(planId, sessionId) }
            .onFailure { appendAppLog("计划会话绑定失败：$it") }
    }

    /**
     * 在指定会话里跑一轮「排本周」：工具、提示词与普通对话完全一样，只是没有用户敲的那一句。
     *
     * 返回这一轮有没有成功；失败不抛（ticker 里调它，抛出去只会把管家循环带停）。
     */
    private suspend fun runWeeklyPlanRound(
        sessionId: String,
        planId: String,
        planTitle: String,
        asSteward: Boolean,
    ): Boolean {
        if (chatBusy) return false
        val config = LlmConfigLoader.load(home) ?: return false
        val llm = llmOverride ?: HttpLlmClient(config)
        val researchGateway: ResearchGateway? =
            if (llmOverride == null && config.apiKey.isNotBlank()) {
                webResearchLog = { message -> appendAppLog(message) }
                createWebResearch(config)
            } else {
                null
            }
        selectPlan(planId)
        val rig = buildChatRig(llm, researchGateway)
        val request = if (asSteward) {
            "【代排】计划「$planTitle」这一周开始了。请用 plan_week 排本周：先读计划文档和上周完成情况，" +
                "排完用一句话说明本周重点。"
        } else {
            "【周计划】这一周开始了。请用 plan_week 排本周：先读计划文档和上周完成情况，" +
                "排完用一句话说明本周重点。"
        }
        val router = if (asSteward) {
            val base = buildString {
                append(StewardPrompt.systemPrompt())
                append("\n\n")
                append(ToolAgent.catalogueOf(rig.allTools))
                if (rig.promptGuidance.isNotBlank()) {
                    append("\n\n")
                    append(rig.promptGuidance)
                }
            }
            ManagerRouter(prompt = ManagerPrompt(base = base), tools = rig.allTools)
        } else {
            ManagerRouter(rig.catalog, rig.allTools, rig.promptGuidance)
        }
        rig.pluginTools.beginRound()
        val agent = ManagerAgent(
            llm = llm,
            router = router,
            maxSteps = null,
            beforeFinishing = { message -> rig.pluginTools.reviewAfterRound(message) },
        )
        val result = runCatching {
            agent.respond(
                state = managerState(),
                request = request,
                history = emptyList(),
                onDelta = {},
                onLog = {},
            )
        }.getOrElse { error ->
            appendAppLog("周计划回合失败（$planTitle）：$error")
            return false
        }
        val now = clock.nowMs()
        appendSessionLines(
            sessionId,
            listOf(
                ChatLine(id = nextChatLineId++, role = ChatRole.User, text = request, atMs = now),
                ChatLine(
                    id = nextChatLineId++,
                    role = ChatRole.Assistant,
                    text = result.text.ifBlank { "（这一轮没有输出）" },
                    atMs = now,
                ),
                ChatLine(
                    id = nextChatLineId++,
                    role = ChatRole.Status,
                    text = if (result.ok) "—— 本周已排" else "—— 本周没排成，详见过程",
                    atMs = now,
                ),
            ),
        )
        if (result.ok) refresh()
        return result.ok
    }

    /** 往某个会话追几行；那个会话正开着就顺手刷新界面。 */
    private fun appendSessionLines(sessionId: String, lines: List<ChatLine>) {
        if (currentChatId == sessionId) {
            chat.addAll(lines)
            persistChat()
            return
        }
        val stored = runCatching { chatStore.read(sessionId) }.getOrNull() ?: return
        runCatching {
            chatStore.save(
                stored.copy(
                    lines = stored.lines + lines,
                    updatedAt = clock.nowMs(),
                    unread = true,
                ),
            )
            loadChatSessions()
        }.onFailure { appendAppLog("写周计划会话失败：$it") }
    }

    /**
     * 该跑「每周自动排周计划」的时候跑一次。
     *
     * 触发点是管家 ticker（60s）与窗口回到前台（都会走 [runStewardCheck]）。规则见
     * [shouldRunWeeklyPlan]：默认开、同一周只跑一次、周日 18:00 后或周一。
     * 每条计划优先进它自己的会话；没绑定/会话没了就由管家代跑并注明「代排」。
     */
    internal suspend fun maybeRunWeeklyPlanWeek() {
        if (!::planSessionStore.isInitialized || !::stewardStore.isInitialized || !::clock.isInitialized) return
        if (chatBusy) return
        val settings = stewardSettings
        val today = clock.today()
        val zoned = java.time.Instant.ofEpochMilli(clock.nowMs()).atZone(clock.zone)
        val checkpoints = stewardStore.checkpoints()
        if (!shouldRunWeeklyPlan(settings.weeklyPlan, today, zoned.hour, checkpoints.weeklyPlanWeek)) return
        val service = learningPlan() ?: return
        val plans = runCatching { service.plans() }.getOrElse { return }
        plans.filter { it.status != "ended" }.forEach { plan ->
            val bound = runCatching { planSessionStore.sessionFor(plan.id) }.getOrNull()
            val usable = bound != null && chatSessions.any { it.id == bound }
            if (usable) {
                runWeeklyPlanRound(bound!!, plan.id, plan.title, asSteward = false)
            } else if (::stewardRuntime.isInitialized) {
                runWeeklyPlanRound(StewardRuntime.STEWARD_SESSION_ID, plan.id, plan.title, asSteward = true)
            }
        }
        // 后台排完这一周，计划页要立刻看到：以前用户回到计划页还是旧的条目数，
        // 得退出重进（或等下一次刷新）才出现，现场测试当成「没排」。
        refreshPlan()
        tick++
        runCatching { stewardStore.saveCheckpoints(checkpoints.copy(weeklyPlanWeek = isoWeekOf(today))) }
            .onFailure { appendAppLog("记录周计划日期失败：$it") }
    }

    private suspend fun runStewardCheck() {
        runCatching { stewardRuntime.check() }
            .onSuccess { result ->
                if (result != StewardRunResult.Nothing) appendAppLog("管家：$result")
                // 仪式可能刚追加了一条建议；应用不在前台时顺手看看能不能自动采纳。
                if (result != StewardRunResult.Nothing && result != StewardRunResult.Busy) {
                    runCatching { autoAdoptStewardProposalIfAllowed() }
                        .onFailure { appendAppLog("自动采纳失败：$it") }
                }
                // 周日 18:00 后 / 周一：给每条计划排本周（在它自己的会话里，没绑定就管家代跑）。
                runCatching { maybeRunWeeklyPlanWeek() }
                    .onFailure { appendAppLog("自动排周计划失败：$it") }
            }
            .onFailure { appendAppLog("管家仪式失败：$it") }
    }

    /**
     * 开发者调试：立刻触发一次晨间 / 晚间仪式，不看时间、不管今天发过没有。
     *
     * 只在 `TRELLIS_DEV=1` 的设置页「时间调试」卡片里出现。强制触发不写 checkpoints
     * （同一天可以反复点），但消息、记忆、摘要、建议卡和系统通知都走真实链路；
     * 发完自动切到管家会话，省得再去侧栏找。
     */
    internal fun debugStewardRitual(morning: Boolean) {
        if (!::stewardRuntime.isInitialized) return
        chatScope.launch {
            val ritual = if (morning) StewardRitual.Morning else StewardRitual.Evening
            val result = runCatching { stewardRuntime.forceRun(ritual) }
                .onFailure { appendAppLog("管家调试失败：$it") }
                .getOrNull() ?: StewardRunResult.Nothing
            appendAppLog("管家调试：$ritual → $result")
            if (result != StewardRunResult.Nothing && result != StewardRunResult.Busy) {
                ensureStewardSession()
                openChat(StewardRuntime.STEWARD_SESSION_ID)
                currentTab = TAB_CHAT
            }
        }
    }

    /**
     * 把管家的主动消息写进那段常驻会话。
     *
     * 会话正忙（用户在对话里、模型正在回）时返回 false：这次不消耗 checkpoint，
     * 一分钟后再试 —— 不能在用户说话说到一半时往上面插一条系统发言。
     */
    private fun appendStewardLine(
        sessionId: String,
        text: String,
        proposal: StewardProposal? = null,
        practice: PracticeProposal? = null,
    ): Boolean {
        if (chatBusy) return false
        val now = clock.nowMs()
        val existing = chatStore.read(sessionId) ?: ChatSession(
            id = sessionId,
            title = StewardRuntime.STEWARD_TITLE,
            createdAt = now,
            updatedAt = now,
            lines = emptyList(),
            kind = SessionKind.Steward,
        )
        val line = ChatLine(
            nextChatLineId++,
            ChatRole.Assistant,
            text,
            now,
            proposal = proposal,
            practiceProposal = practice,
        )
        val viewing = currentChatId == sessionId
        chatStore.save(
            existing.copy(
                title = StewardRuntime.STEWARD_TITLE,
                updatedAt = now,
                lines = existing.lines + line,
                kind = SessionKind.Steward,
                unread = !viewing,
            ),
        )
        if (viewing) chat += line
        loadChatSessions()
        return true
    }

    /**
     * 采纳一条管家建议：逐条走计划后端已有命令。
     *
     * 不让模型来执行：建议是模板生成的、改动是明确的，直接调命令比再跑一轮模型
     * 便宜也更可预测；失败条数照实说，成功的不会因为旁边失败而回滚。
     */
    /** 一条建议执行完的统计：挪动几件、补排几个、几条没成。 */
    private data class ProposalAdoption(val moved: Int, val placed: Int, val failed: Int) {
        fun summary(prefix: String): String = buildString {
            append(prefix)
            if (moved > 0) append("挪 $moved 件")
            if (placed > 0) {
                if (moved > 0) append("、")
                append("补排 $placed 个")
            }
            if (failed > 0) append("，$failed 条没成")
            append("。")
        }
    }

    /**
     * 执行一条建议里的全部改动：逐条走计划后端命令，成功失败分别计数（不整批回滚）。
     *
     * 建议是模板生成的、改动是明确的，直接调命令比再跑一轮模型便宜也更可预测。
     */
    private fun executeProposal(
        service: LearningPlanService,
        planId: String,
        proposal: StewardProposal,
    ): ProposalAdoption {
        var moved = 0
        var placed = 0
        var failed = 0
        proposal.changes.forEach { change ->
            val result = when (change) {
                is StewardChange.Move -> service.moveOccurrence(
                    planAdjustCommand(planId),
                    change.occurrenceId,
                    change.day,
                    "管家建议",
                )
                is StewardChange.Place -> service.placeUnit(
                    planAdjustCommand(planId),
                    planId,
                    isoWeekOf(java.time.LocalDate.parse(change.day)),
                    change.unitId,
                    change.day,
                )
            }
            if (result.ok) {
                if (change is StewardChange.Move) moved++ else placed++
            } else {
                failed++
            }
        }
        return ProposalAdoption(moved, placed, failed)
    }

    /**
     * 采纳一条管家建议：逐条走计划后端已有命令。
     *
     * 不让模型来执行：建议是模板生成的、改动是明确的，直接调命令比再跑一轮模型
     * 便宜也更可预测；失败条数照实说，成功的不会因为旁边失败而回滚。
     */
    internal suspend fun adoptStewardProposal(lineId: Long) {
        val index = chat.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val line = chat[index]
        val proposal = line.proposal ?: return
        if (line.proposalState != ProposalState.Open) return
        val service = learningPlan() ?: return
        val planId = planFocusId ?: service.activePlanId() ?: return
        val stats = executeProposal(service, planId, proposal)
        chat[index] = line.copy(proposalState = ProposalState.Adopted)
        val summary = stats.summary("已采纳管家建议：")
        addChatLine(ChatRole.Status, "—— " + summary)
        refreshPlan()
        runCatching { stewardRuntime.rememberProposalDecision(clock.today(), adopted = true, summary = summary) }
        persistChat()
        appendAppLog(summary)
    }

    /**
     * 自动采纳：应用不在前台 + 开关打开（可选每日上限）时，自动执行管家建议。
     *
     * 用户把重排权限放开后，挪动逾期和补排欠账都会自动做（`autoAdoptDailyLimit = 0` 不限条数）。
     *
     * 不碰界面态：只改落盘的管家会话（建议行标 Adopted + 追加一条状态行）、写记忆、
     * 刷新计划页。用户回来翻管家会话就能看到「它替你挪了什么」。
     */
    internal suspend fun autoAdoptStewardProposalIfAllowed(): String? {
        if (!::stewardStore.isInitialized || !::chatStore.isInitialized) return null
        val today = clock.today()
        val checkpoints = runCatching { stewardStore.checkpoints() }.getOrNull() ?: return null
        val adoptedToday = if (checkpoints.autoAdoptDay == today.toString()) checkpoints.autoAdoptCount else 0
        if (!autoAdoptAllowed(stewardSettings, windowFocused, adoptedToday)) return null
        val session = runCatching { chatStore.read(StewardRuntime.STEWARD_SESSION_ID) }.getOrNull() ?: return null
        val line = session.lines.lastOrNull { it.proposal != null && it.proposalState == ProposalState.Open }
            ?: return null
        val proposal = line.proposal ?: return null
        if (!proposal.isAutoAdoptable()) return null
        val service = learningPlan() ?: return null
        val planId = planFocusId ?: service.activePlanId() ?: return null
        val stats = executeProposal(service, planId, proposal)
        if (stats.moved + stats.placed == 0) {
            // 一条都没动成：别把建议标成「已采纳」，用户回来还该看到它。
            appendAppLog("自动采纳失败：" + stats.summary(""))
            return null
        }
        val summary = stats.summary("自动采纳管家建议：")
        val stamp = clock.nowMs()
        val statusText = "—— " + summary + "（应用不在前台，已自动重排）"
        val updatedLines = session.lines.map { item ->
            if (item.id == line.id) item.copy(proposalState = ProposalState.Adopted) else item
        } + ChatLine(id = nextChatLineId++, role = ChatRole.Status, text = statusText, atMs = stamp)
        runCatching { chatStore.save(session.copy(lines = updatedLines, updatedAt = stamp)) }
        if (currentChatId == StewardRuntime.STEWARD_SESSION_ID) {
            chat.clear()
            chat.addAll(updatedLines)
        }
        loadChatSessions()
        stewardStore.saveCheckpoints(
            checkpoints.copy(autoAdoptDay = today.toString(), autoAdoptCount = adoptedToday + 1),
        )
        runCatching { stewardRuntime.rememberProposalDecision(today, adopted = true, summary = summary) }
        refreshPlan()
        appendAppLog(summary)
        return summary
    }

    /** 界面按钮点「一键采纳」：采纳要在协程里跑（落盘 + 刷新计划）。 */
    internal fun launchAdoptStewardProposal(lineId: Long) {
        chatScope.launch { adoptStewardProposal(lineId) }
    }

    /** 「先这样」：计划不动，只记下这次没采纳 —— 下次别老推同一条。 */
    internal fun dismissStewardProposal(lineId: Long) {
        val index = chat.indexOfFirst { it.id == lineId }
        if (index < 0) return
        val line = chat[index]
        val proposal = line.proposal ?: return
        if (line.proposalState != ProposalState.Open) return
        chat[index] = line.copy(proposalState = ProposalState.Dismissed)
        runCatching {
            stewardRuntime.rememberProposalDecision(clock.today(), adopted = false, summary = proposal.title)
        }
        persistChat()
    }

    /** 「调整一下」：把协商的话带进输入框，用户补完后半句再发。 */
    internal fun negotiateStewardProposal(lineId: Long) {
        val line = chat.firstOrNull { it.id == lineId } ?: return
        if (line.proposal == null) return
        askInChat("这条建议我想调整一下：")
    }

    /** 确保「管家」那段常驻会话存在：可以删，但下次启动会再建一个空壳。 */
    internal fun ensureStewardSession() {
        if (!::chatStore.isInitialized) return
        if (chatStore.read(StewardRuntime.STEWARD_SESSION_ID) != null) return
        val now = clock.nowMs()
        chatStore.save(
            ChatSession(
                id = StewardRuntime.STEWARD_SESSION_ID,
                title = StewardRuntime.STEWARD_TITLE,
                createdAt = now,
                updatedAt = now,
                lines = emptyList(),
                kind = SessionKind.Steward,
            ),
        )
        loadChatSessions()
    }

    /** 管家的端口实现：计划事实、会话写入、模型润色、系统通知。 */
    private fun stewardPorts(): StewardPorts = object : StewardPorts {
        override suspend fun planFacts(today: LocalDate): StewardPlanFacts? {
            val service = learningPlan() ?: return null
            val planId = planFocusId ?: service.activePlanId() ?: return null
            val overdue = service.overdue(planId, today.toString())
            val day = service.today(planId, today.toString())
            return StewardPlanFacts(
                planTitle = planTitle,
                plannedToday = day.occurrences.size,
                doneToday = day.completed.size,
                todayMinutes = day.occurrences.sumOf { it.minutes },
                overdueThisWeek = overdue.thisWeek.groupingBy { it.day }.eachCount(),
                backlogCount = overdue.backlog.size,
                remainingMinutesInWeek = overdue.remainingMinutes,
            )
        }

        override suspend fun appendProactive(sessionId: String, text: String, proposal: StewardProposal?): Boolean =
            appendStewardLine(sessionId, text, proposal)

        override suspend fun proposal(today: LocalDate): StewardProposal? {
            val service = learningPlan() ?: return null
            val planId = planFocusId ?: service.activePlanId() ?: return null
            val overdue = service.overdue(planId, today.toString())
            val weekEnd = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
            return StewardProposals.build(
                today = today,
                weekEnd = weekEnd,
                overdue = overdue.thisWeek.map { StewardOverdueItem(it.occurrenceId, it.title, it.day) },
                backlog = overdue.backlog.map { StewardBacklogItem(it.unitId, it.title) },
                id = "prop-" + clock.nowMs(),
            )
        }

        override suspend fun polish(messages: List<LlmMessage>): String? {
            val config = LlmConfigLoader.load(home) ?: return null
            return runCatching {
                withTimeoutOrNull(8_000) {
                    (llmOverride ?: HttpLlmClient(config)).complete(messages)
                }
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        }

        override fun notify(title: String, body: String) {
            // 管家招呼落在会话里，点通知就该落在会话上。
            notifyWithTarget(title, body, NotifyTarget.Chat)
        }

        override fun appFocused(): Boolean = windowFocused
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
        // 管家会话是常驻线程：标题固定，不跟着用户回的那句话改。
        val title = existing?.title?.takeIf { it.isNotBlank() && it != "新对话" }
            ?: chat.firstOrNull { it.role == ChatRole.User }
                ?.text?.trim()?.take(24)?.takeIf { it.isNotBlank() } ?: "新对话"
        chatStore.save(
            ChatSession(
                id = id,
                title = title,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lines = chat.toList(),
                kind = existing?.kind ?: SessionKind.Manager,
            ),
        )
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
     * 真实事故：模型在工具循环里建计划，清单上第 2 步却一直停在「正在执行」，
     * 右侧写着「本轮没有改动」—— 用户看到的是一个永远推进不了的任务。这里把结论同步给清单：
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
        val plan = line.plan.mapIndexed { index, step ->
            when {
                index == running -> step.copy(
                    state = if (ok) PlanStepState.Done else PlanStepState.Failed,
                    note = if (ok) "本轮结束" else "这一轮没走完",
                    ms = if (step.startedMs > 0) now - step.startedMs else 0L,
                )
                // 本轮没走到的步骤也要收尾：一直挂在「待执行」会让清单永远停在 2/4，
                // 用户看不出这一轮到底结束了没有。跳过不是失败，文案写清「本轮没执行到」。
                step.state == PlanStepState.Pending -> step.copy(
                    state = PlanStepState.Skipped,
                    note = "本轮没执行到",
                    ms = 0L,
                )
                else -> step
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
        TAB_PRACTICE -> "练习"
        TAB_GOALS -> "目标"
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
    fun pressKey(key: String): String = HostPlatform.pressKey(key)

    /** 最近一次截图覆盖的区域；click 的坐标以它为准。 */
    private var lastCapture: CaptureRect? = null

    /**
     * 把当前界面截下来：存一份 PNG 给人看，同时缩一张给模型看。
     *
     * 实现搬到了 [HostPlatform]：桌面用 AWT Robot 截应用窗口，手机没有这个能力
     * （系统沙箱不允许截别的应用），那边会返回一句明确的说明。
     */
    fun captureScreen(): ScreenCapture {
        val capture = HostPlatform.captureScreen(home)
        lastCapture = capture.rect
        return ScreenCapture(capture.text, capture.dataUrl)
    }

    /** 把界面截下来存成 PNG，返回路径；给不看图的地方用。 */
    fun takeScreenshot(): String = captureScreen().text

    /**
     * 按最近一张截图的坐标点一下。
     *
     * 坐标越界一律拒绝：宁可让它重新截图，也不要让鼠标在用户桌面上乱跑。
     * 手机端这个动作不存在（[HostPlatform.clickScreen] 会说明原因）。
     */
    fun clickScreen(x: Int, y: Int): String {
        val rect = lastCapture ?: return "先 take_screenshot 一张，再按那张截图的坐标点。"
        return HostPlatform.clickScreen(rect, x, y)
    }

    /** 往当前焦点里打一段字（桌面走剪贴板 + Ctrl+V，手机不支持）。 */
    fun typeText(text: String): String = HostPlatform.typeText(text)

    /**
     * 跨会话记忆块：每次对话开始前拼进 guidance 的尾巴，普通会话和管家会话共用。
     *
     * 内容来自 [stewardStore] 的 append-only 记忆日志：用户说过的长期目标 / 长期偏好 /
     * 近期决定。目标这类东西最需要跨会话记住 —— 用户上周说「想考软考」，这周开新会话时
     * 模型不该再问一遍「你想学什么」。
     *
     * 没有任何可注入的记忆时返回空串（调用方不要追加空块）；顺序固定
     * goal → preference → recent（见 [renderCrossSessionMemory]）。这段是提示词里
     * **唯一**会随记忆变化的部分，所以放在整段 guidance 的最后：前面的静态守则
     * 逐字节稳定，模型前缀缓存只从这一块开始失效。
     */
    internal fun memoryBlock(maxChars: Int = 600): String {
        if (!::stewardStore.isInitialized || !::clock.isInitialized) return ""
        return renderCrossSessionMemory(stewardStore, clock.today().toString(), maxChars)
    }

    /** 一轮对话要用的全套工具与提示词；普通会话和「计划会话里的周计划」共用。 */
    private class ChatRig(
        val catalog: CapabilityCatalog,
        val pluginTools: PluginTools,
        val allTools: List<AgentTool>,
        val promptGuidance: String,
    )

    private fun buildChatRig(llm: LlmClient, researchGateway: ResearchGateway?): ChatRig {
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
                { created ->
                    refreshPlan(focus = created)
                    // 计划建好就把「想法」落成文档：之后排周/重排时先读文档，不靠对话记忆。
                    ensurePlanDoc(created)
                    bindPlanSession(created)
                },
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
            val planDocTools = PlanDocTools(
                service = { runCatching { learningPlan() }.getOrNull() },
                docStore = { if (::planDocStore.isInitialized) planDocStore else null },
                clock = clock,
                onChanged = { refreshPlan() },
            )
            // 「只有目标、没有现成材料」时的第一只手：先调研出路径和两周安排，再落计划。
            // create_learning_plan 负责「已有材料」（bvid / 手写大纲），两者不要同时用。
            val researchTools = ResearchTools(
                service = { runCatching { learningPlan() }.getOrNull() },
                planDocStore = { if (::planDocStore.isInitialized) planDocStore else null },
                planDbError = { planDbError },
                weeklyCapacity = { studyPrefs.weeklyMinutes },
                clock = clock,
                llm = llm,
                research = researchGateway,
                onChanged = { created ->
                    refreshPlan(focus = created)
                    bindPlanSession(created)
                },
            )
            // 每周的局部计划：读大纲与上周完成，排这一周的具体条目并铺到天。
            val planWeekTools = PlanWeekTools(
                service = { runCatching { learningPlan() }.getOrNull() },
                planDocStore = { if (::planDocStore.isInitialized) planDocStore else null },
                weeklyCapacity = { studyPrefs.weeklyMinutes },
                clock = clock,
                llm = llm,
                onChanged = { refreshPlan() },
            )
            val diagnosticsTools = DiagnosticsTools { diagnosticsReport() }
            // 跨会话记忆也是手：用户说出长期目标/偏好时写进管家日志，要回忆过去时再检索。
            // 和管家仪式共用同一份 append-only 记忆 —— 目标不该只有晨/夜仪式才有机会落盘。
            val memoryTools = MemoryTools(stewardStore, clock)
            val goalTools = GoalTools(this)
            // 手机上「看」可以，「动手」不行：截屏/点击/按键/打字在安卓沙箱里没有对应能力，
            // 四条工具留在清单里只会让模型挑一个注定失败的动作，然后空跑一轮。
            // 所以按平台裁剪工具清单与说明 —— 不是改文案，是不给它做不到的选项。
            val mobile = !HostPlatform.isDesktop
            val screenToolList = if (mobile) {
                screenTools.all.filter { it.name == "describe_screen" }
            } else {
                screenTools.all
            }
            val screenGuidance = if (mobile) {
                "<how_to_see>\n手机上只能看，不能点：describe_screen 可用；截屏/点击/按键/打字在这台设备上不存在，" +
                    "不要调用，也不要承诺「我帮你点了」。需要用户动手时，把动作写成一句话让他自己做。\n</how_to_see>"
            } else {
                screenTools.guidance
            }
            val allTools = tools.all + screenToolList + layoutTools.all + styleTools.all +
                workflowTools.all + bilibiliTools.all + taskTools.all + planTools.all +
                planAdminTools.all + planDocTools.all + researchTools.all + planWeekTools.all +
                diagnosticsTools.all + memoryTools.all +
                goalTools.all
            val guidance = tools.guidance + "\n\n" + screenGuidance +
                "\n\n" + layoutTools.guidance + "\n\n" + styleTools.guidance +
                "\n\n" + workflowTools.guidance + "\n\n" + bilibiliTools.guidance +
                "\n\n" + taskTools.guidance + "\n\n" + researchTools.guidance + "\n\n" + planWeekTools.guidance +
                "\n\n" + planTools.guidance +
                "\n\n" + planAdminTools.guidance + "\n\n" + planDocTools.guidance +
                "\n\n" + diagnosticsTools.guidance + "\n\n" + memoryTools.guidance + "\n\n" + goalTools.guidance
            // 跨会话记忆块只随记忆内容变化，接在 guidance 最末尾：两条路径（普通 / 管家）
            // 都用同一份 promptGuidance，静态前缀逐字节稳定，前缀缓存只从记忆块开始失效。
            val memorySection = memoryBlock()
            val promptGuidance = if (memorySection.isBlank()) guidance else guidance + "\n\n" + memorySection
        return ChatRig(catalog, tools, allTools, promptGuidance)
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
        // 网络调研只在真跑模型时接（测试通过 llmOverride 注入假模型，不能顺手联网）。
        val researchGateway: ResearchGateway? =
            if (llmOverride == null && config.apiKey.isNotBlank()) {
                webResearchLog = { message -> appendAppLog(message) }
                createWebResearch(config)
            } else {
                null
            }
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
            val rig = buildChatRig(llm, researchGateway)


            // 一份提示词 + 全量工具：不再按意图裁工具、也不再换任务模块。
            // 模型自己决定这一轮是聊天还是动手（2026-11-07 删掉意图分类器）。
            // 管家会话只换人格：同一套工具、同一个审批闸门，
            // 静态前缀换成管家自己的（克制、只基于事实、一次只给一条建议）。
            val stewardSession = currentChatId
                ?.let { id -> chatSessions.firstOrNull { it.id == id }?.kind } == SessionKind.Steward
            if (stewardSession && ::stewardRuntime.isInitialized) {
                // 用户回管家的话原样记一条：下次仪式能读到「上次他怎么说」，
                // 不用再靠模型从上下文里猜。
                runCatching { stewardRuntime.rememberUserReply(clock.today(), text) }
            }
            val router = if (stewardSession) {
                val base = buildString {
                    append(StewardPrompt.systemPrompt())
                    append("\n\n")
                    append(ToolAgent.catalogueOf(rig.allTools))
                    if (rig.promptGuidance.isNotBlank()) {
                        append("\n\n")
                        append(rig.promptGuidance)
                    }
                }
                ManagerRouter(
                    prompt = ManagerPrompt(base = base),
                    tools = rig.allTools,
                )
            } else {
                ManagerRouter(rig.catalog, rig.allTools, rig.promptGuidance)
            }
            // 插件工具自己记账：这一轮碰过插件工具、又只看了看没动手，才在收尾时拦。
            // 不再按意图判断（意图分类器已删），也不再无条件要求「必须试过、装上了」。
            rig.pluginTools.beginRound()
            // 「第 N 步：tool_name」这条过程行对应的工具详情，会在 onTrace 里挂上去。
            var lastStepToolLineId: Long? = null
            val agent = ManagerAgent(
                llm = llm,
                router = router,
                // 不设步数额度：查五个插件、逐个试跑本来就是十几步的事。
                maxSteps = null,
                beforeFinishing = { message -> rig.pluginTools.reviewAfterRound(message) },
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
                onLog = { line ->
                    // 结果行已经并进工具行的详情里（见下面的 onTrace），过程行不再单独占一行。
                    val mergedIntoPrevious = line.startsWith("　→") &&
                        lastStepToolLineId != null
                    if (!mergedIntoPrevious) {
                        val added = addChatLine(ChatRole.Step, line)
                        if (Regex("^第 \\d+ 步：[a-z_]+$").matches(line)) lastStepToolLineId = added.id
                    }
                    // 过程行是「第 N 步：tool_name」；新的一步开始，清单上就该亮起来，
                    // 顺手把工具名记进去 —— 展开区要写「这一步在做什么」。
                    if (line.startsWith("第 ")) {
                        markPlanRunning(Regex("：([a-z_]+)").find(line)?.groupValues?.get(1).orEmpty())
                    }
                },
                onTrace = { trace ->
                    when (trace) {
                        is ToolTrace.Thinking -> addChatLine(ChatRole.Step, "思考：" + trace.text)
                        is ToolTrace.Call -> {
                            val detail = ChatToolTrace(
                                tool = trace.tool,
                                description = trace.description,
                                arguments = trace.arguments,
                                result = trace.result,
                                isError = trace.isError,
                            )
                            val index = lastStepToolLineId?.let { id -> chat.indexOfFirst { it.id == id } } ?: -1
                            if (index >= 0) {
                                chat[index] = chat[index].copy(tool = detail)
                            } else {
                                addChatLine(ChatRole.Step, "第 " + trace.step + " 步：" + trace.tool).also { added ->
                                    val at = chat.indexOfFirst { it.id == added.id }
                                    if (at >= 0) chat[at] = chat[at].copy(tool = detail)
                                    lastStepToolLineId = added.id
                                }
                            }
                        }
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

            // 不再有「意图歧义」这种停顿：模型想先问清楚时会自己调 ask_choices
            // （它落到 offerChoices），运行时不再替它猜、也不再留一个意图给下一轮。

            if (result.ok) {
                addChatLine(ChatRole.Status, "—— 本轮完成")
            } else {
                addChatLine(ChatRole.Status, "—— 本轮没做成，原因见上")
            }

            chatHistory += LlmMessage(LlmRole.User, text)
            chatHistory += LlmMessage(LlmRole.Assistant, displayText)
            while (chatHistory.size > 20) chatHistory.removeAt(0)

            // 每一轮都重取一次界面数据：工具可能动了插件、计划、样式中的任何一样，
            // 现在没有意图可以告诉我们「这轮动没动」，重取一遍最稳（开销很小）。
            if (result.ok) refresh()

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
        val themes = contributions.mapNotNull { contribution ->
            val theme = contribution.value as? ThemeContribution ?: return@mapNotNull null
            Triple(contribution.owner.value + "/" + theme.id, theme.name, contribution.owner.value)
        }
        return themes.map { (key, name, owner) ->
            // 界面上只显示主题名：插件 ID 是身份证号，不是给人读的
            //（评审 S6：「Bento（dev.bento.shift）」把实现细节漏到了设置页）。
            // 只有两个插件给同名主题时才拿 owner 消歧——否则用户根本分不清。
            val duplicated = themes.count { it.second == name } > 1
            key to if (duplicated) name + "（" + owner + "）" else name
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
            ?.let { raw -> HostPlatform.decodeImage(raw) }
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
        HostPlatform.openFolder(pluginsDir)
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

    /** 笔记 → 计划联动的账本：机器自动完成的节点 + 用户屏蔽的节点。 */
    private val autoCompletionStore: AutoCompletionStore by lazy { AutoCompletionStore(home, clock) }
    private var autoCompletionLedger: AutoCompletionLedger? = null

    /** 计划页「AI 自动完成」列表里的记录（只含还生效的，按完成时间倒序）。 */
    internal var planAutoCompletions by mutableStateOf<List<AutoCompletionEntry>>(emptyList())
        private set

    /** 被撤销/屏蔽、以后不再自动完成的节点数。 */
    internal var planAutoCompletionBlockedCount by mutableStateOf(0)
        private set

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
     * 计划设置弹窗里的错误文案（保存失败时显示在弹窗内部）。
     *
     * 为什么不用 [planMessage]：它是页面级的、渲染在周条一行小字里，弹窗打开时被 scrim
     * 盖住看不见；而且 [refreshPlan] 末尾会清空它——失败提示会闪一下就消失。
     */
    var planSettingsError by mutableStateOf<String?>(null)
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

    /**
     * 今天已经在计划里勾掉的节点。
     *
     * 任务页勾完节点后，`todoPlanNodes` 会立刻把它摘掉——行凭空消失，用户不知道勾成功没有。
     * 所以任务页还留一张「今日完成」的小清单，勾完从待办挪到这里，而不是原地蒸发。
     */
    var todayDonePlanNodes by mutableStateOf<List<TodayDoneNode>>(emptyList())
        private set

    /**
     * 本周进度：本周排到天里的节点数、已经做完几件、估时合计。
     *
     * 首页右栏只需要这三个数，但「本周」和后端算的当前周必须是同一周——所以由
     * [refreshPlan] 统一从 `week(planId, state.week)` 取，而不是页面自己拿 planDays 猜。
     */
    var planWeekProgress by mutableStateOf<WeekProgress?>(null)
        private set

    /** 顶部标题：库里的计划名；没有计划时为 null。 */
    var planTitle by mutableStateOf<String?>(null)
        private set

    var planDays by mutableStateOf<List<WeekDay>>(emptyList())
        private set

    var planSelectedWeek by mutableStateOf<String?>(null)
    /** 上一次刷新时「今天」所在的 ISO 周：用来判断是否自然跨周（含调试 +1 周）。 */
    private var lastPlanTodayWeek: String? = null
        private set

    var planSelectedDay by mutableStateOf<String?>(null)
        private set

    var planDayView by mutableStateOf<DayView?>(null)
        private set

    /** 库打不开 / 读失败时的一句话；成功时清空。 */
    var planMessage by mutableStateOf<String?>(null)
        private set

    /**
     * 计划页的错误提示条：**不会**被 `refreshPlan()` 清掉，只有用户点关闭或下一次成功动作才消失。
     *
     * 为什么另建一个：[planMessage] 渲染在周条里的一行小字上，而且每次刷新末尾都清空；
     * 任何「先写错误再刷新」的路径都会让提示闪一下就没了（弹窗里的错误还会被 scrim 挡住）。
     */
    var planNotice by mutableStateOf<String?>(null)
        private set

    fun dismissPlanNotice() {
        planNotice = null
    }

    private fun reportPlanProblem(text: String) {
        planNotice = text
        planMessage = text
    }

    // ---------------------------------------------------------------- 计划文档
    // 「想法」层：先在文档里写目标、按周条目、待安排，再确认落进计划库。
    // 文档在 <home>/plans/docs/<planId>.md；计划库仍是执行真相，两边不实时双向同步。

    internal lateinit var planDocStore: PlanDocStore
    internal lateinit var planSessionStore: PlanSessionStore

    /** 知识库里「计划文档」二级页当前打开的计划；非空时知识库页整页显示它。 */
    var planDocBrowseId by mutableStateOf<String?>(null)
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

    /**
     * 当前选中周的复盘（后端 `review(planId, week)`）。
     *
     * 没生成过时后端返回 `state = "not_ready"`；这里只保留 ready 的，页面据此决定
     * 是显示复盘卡还是「生成复盘」按钮。
     */
    internal var planReview by mutableStateOf<ReviewView?>(null)
        private set

    /** 已经结束但还没生成复盘的那一周（`planState.pendingReviewWeek`）；null = 不欠复盘。 */
    internal var planPendingReviewWeek by mutableStateOf<String?>(null)
        private set

    /**
     * 计划阶段（后端 `stages(planId)`）：阶段条与右侧阶段进度栏的数据来源。
     *
     * 阶段是「整份计划按大纲切成的几段」，不是周：`weekStart..weekEnd` 是 ISO 周号区间，
     * 进度 `done/total` 由后端按承诺单元现算。读失败时退化成空表（页面隐藏阶段条），
     * 不把整页拖进错误态。
     */
    internal var planStages by mutableStateOf<List<StageView>>(emptyList())
        private set

    /**
     * 「今天可以把这一周总结先做了」的那一周（后端 `planState.reviewableWeek`）。
     *
     * 只在今天是本周最后一天、且本周还没复盘时给值；null = 现在不是总结的时机。
     * 页面据此在周复盘卡里显示「生成本周总结」。
     */
    internal var planReviewableWeek by mutableStateOf<String?>(null)
        private set

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

    // ---------------------------------------------------------------- 目标 / 项目（G-A1）

    /** 目标/项目（含归档；界面自己按 status 分组）。 */
    internal var goals by mutableStateOf<List<GoalView>>(emptyList())
        private set

    /** 目标页的一次性提示（失败原因 / 完成反馈）。 */
    internal var goalMessage by mutableStateOf<String?>(null)

    internal fun clearGoalMessage() {
        goalMessage = null
    }

    /** 重新读目标列表；计划页头部与目标页都靠它。 */
    internal fun refreshGoals() {
        val service = learningPlan() ?: run {
            goals = emptyList()
            return
        }
        runCatching { service.goals(includeArchived = true) }
            .onSuccess { goals = it }
            .onFailure {
                appendAppLog("目标读取失败：$it")
                goalMessage = "目标读不出来：$it"
            }
    }

    internal fun openGoalScreen() {
        clearGoalMessage()
        refreshGoals()
        currentTab = TAB_GOALS
    }

    internal fun closeGoalScreen() {
        currentTab = TAB_PLAN
    }

    private fun goalCommand(): CommandEnvelope = CommandEnvelope(
        commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = null,
        origin = "ui.goals",
    )

    internal fun createGoal(title: String, kind: String, intent: String, success: String, deadline: String?): String? {
        val service = learningPlan() ?: return null
        val result = service.createGoal(goalCommand(), title.trim(), kind, intent.trim(), success.trim(), deadline)
        if (!result.ok) {
            goalMessage = result.error?.message ?: "目标没建成。"
            return null
        }
        refreshGoals()
        goalMessage = "目标建好了：" + title.trim()
        return result.value?.goalId
    }

    internal fun setGoalStatus(goalId: String, status: String) {
        val service = learningPlan() ?: return
        val result = service.updateGoal(goalCommand(), goalId, status = status)
        goalMessage = if (result.ok) {
            "已" + if (status == "archived") "归档" else "恢复"
        } else {
            result.error?.message ?: "没改成。"
        }
        refreshGoals()
    }

    internal fun addGoalMilestone(goalId: String, title: String, deadline: String?) {
        val service = learningPlan() ?: return
        val result = service.addMilestone(goalCommand(), goalId, title.trim(), deadline)
        goalMessage = if (result.ok) "加了一条里程碑：$title" else (result.error?.message ?: "没加上。")
        refreshGoals()
    }

    internal fun completeGoalMilestone(milestoneId: String) {
        val service = learningPlan() ?: return
        val result = service.completeMilestone(goalCommand(), milestoneId)
        if (!result.ok) goalMessage = result.error?.message ?: "没勾上。"
        refreshGoals()
    }

    internal fun uncompleteGoalMilestone(milestoneId: String) {
        val service = learningPlan() ?: return
        val result = service.uncompleteMilestone(goalCommand(), milestoneId)
        if (!result.ok) goalMessage = result.error?.message ?: "没改回来。"
        refreshGoals()
    }

    internal fun removeGoalMilestone(milestoneId: String) {
        val service = learningPlan() ?: return
        val result = service.removeMilestone(goalCommand(), milestoneId)
        goalMessage = if (result.ok) "删掉了这条里程碑。" else (result.error?.message ?: "没删成。")
        refreshGoals()
    }

    /** 把计划挂到目标上；`goalId = null` 是解绑。 */
    internal fun linkPlanToGoal(planId: String, goalId: String?) {
        val service = learningPlan() ?: return
        val result = service.linkPlanToGoal(
            CommandEnvelope(
                commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                actor = Actor.USER,
                planId = planId,
                origin = "ui.goals",
            ),
            planId,
            goalId,
        )
        goalMessage = if (result.ok) {
            if (goalId == null) "计划解绑了。" else "计划挂到目标上了。"
        } else {
            result.error?.message ?: "没挂上。"
        }
        refreshGoals()
        refreshPlan()
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
        //
        // 手机上的检查项不一样：`java.net.http` 与 `javax.imageio` 在安卓上**本来就不存在**，
        // 它们的能力由 `HttpURLConnection` / `BitmapFactory` 顶替（见 HostPlatform）。
        // 照着桌面那份清单报「缺失 ← 就是它」，只会让模型去修一个不该修的东西。
        if (HostPlatform.isDesktop) {
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
        } else {
            appendLine("平台自检：安卓 · 网络走 HttpURLConnection · 数据库走 SQLDroid · 截屏/点击/按键不支持（系统沙箱）")
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

    /** 各计划「今天已完成的节点」：任务页勾完要能看见结果，不能勾完就消失。 */
    private fun collectTodayDonePlanNodes(service: LearningPlanService): List<TodayDoneNode> = runCatching {
        val today = clock.today().toString()
        planAll.filter { it.status != "ended" }.flatMap { plan ->
            runCatching { service.today(plan.id, today) }.getOrNull()
                // `occurrences` 只装 status=planned 的；做完的在 `completed` 里（含「安排在今天、
                // 之前就勾掉」的那些）。这里要的是「今天完成的」，所以读 completed。
                ?.completed
                ?.map { occurrence ->
                    TodayDoneNode(
                        planId = plan.id,
                        planTitle = plan.title,
                        occurrenceId = occurrence.id,
                        title = occurrence.title,
                        // completed 只带标题不带分钟；分钟数交给计划页看，这里不硬凑。
                        minutes = 0,
                    )
                }
                ?: emptyList()
        }
    }.getOrDefault(emptyList())

    /**
     * 进计划页、切周切天、写完一条之后都走这里：一次取齐整页要显示的 view model。
     *
     * @param focus 新建计划后要切过去的 planId（`PlanTools`/`ResearchTools` 落库后回调传入）。
     *   不传就沿用当前聚焦的计划 —— 用户手动切计划时走 [selectPlan]。
     */
    fun refreshPlan(focus: String? = null) {
        val service = learningPlan() ?: return
        if (focus != null) planFocusId = focus
        refreshGoals()
        runCatching {
            planAll = service.plans()
            // 先让计划自己往前滚（结算旧周 / 补下周承诺 / 铺天），再取待办与页面数据：
            // 否则今天新铺出来的节点要等下一次刷新才出现在待办板上。
            val rhythmReports = advancePlanRhythm(service)
            todoPlanNodes = collectTodoPlanNodes(service)
            todayDonePlanNodes = collectTodayDonePlanNodes(service)
            planCategories = planCategoryStore.load()
            val today = clock.today().toString()
            // 放弃过的计划只保留在左栏里，不再当默认焦点：先落最后一个活跃计划，
            // 没有活跃计划（全都放弃/结束了）才退回列表第一项。
            val planId = planFocusId?.takeIf { id -> planAll.any { it.id == id } }
                ?: service.activePlanId()
                ?: planAll.firstOrNull { it.status != "abandoned" }?.id
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
                planReview = null
                planPendingReviewWeek = null
                planStages = emptyList()
                planReviewableWeek = null
                planMessage = null
                return
            }
            planRhythmReport = rhythmReports[planId]
            val state = service.planState(planId)
            planPendingReviewWeek = state.pendingReviewWeek
            planReviewableWeek = state.reviewableWeek
            planDailyMinutes = state.dailyMinutes
            // 自然跨周（含 dev 的 +1 周）时页面跟着到新的一周：
            // 用户自己点过别的周就不动，只有还停在上一次的「今天这周」才跟随。
            val todayWeek = isoWeekOf(clock.today())
            val previousTodayWeek = lastPlanTodayWeek
            if (previousTodayWeek != null && previousTodayWeek != todayWeek) {
                val selected = planSelectedWeek
                if (selected == null || selected == previousTodayWeek) planSelectedWeek = todayWeek
            }
            lastPlanTodayWeek = todayWeek
            // 阶段是页面上的「计划形状」，读失败只丢阶段条，不把整页拖进错误态。
            planStages = runCatching { service.stages(planId) }.getOrElse { emptyList() }
            planOverdue = runCatching { service.overdue(planId, today) }.getOrNull()

            val map = service.map(planId)
            planTitle = map.planTitle.ifBlank { state.title }
            // 全部节点（跨计划）的完成情况：在下面按周取视图时顺带累加，不为进度多查一遍库。
            var allDone = 0
            var allTotal = 0
            fun countWeek(view: dev.trellis.domain.plan.WeekView) {
                view.days.forEach { weekDay ->
                    weekDay.items.forEach { item ->
                        allTotal += 1
                        if (item.status == "done") allDone += 1
                    }
                }
            }
            planWeekCards = map.weeks.mapIndexed { index, week ->
                val view = service.week(planId, week.week)
                countWeek(view)
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
                planReview = runCatching { service.review(planId, week) }.getOrNull()
                    ?.takeIf { review -> review.state == "ready" }
            } else {
                planReview = null
            }
            // 首页右栏的完成情况：大数字对「所有未结束计划」，本周数字对后端「当前周」。
            planWeekProgress = runCatching {
                planAll.filter { it.status != "ended" && it.id != planId }.forEach { other ->
                    runCatching {
                        service.map(other.id).weeks.forEach { week -> countWeek(service.week(other.id, week.week)) }
                    }
                }
                val currentView = service.week(planId, state.week)
                val items = currentView.days.flatMap { it.items }
                val index = map.weeks.indexOfFirst { it.week == state.week }
                WeekProgress(
                    label = if (index >= 0) "第 " + (index + 1) + " 周" else "本周",
                    done = items.count { it.status == "done" },
                    total = items.size,
                    allDone = allDone,
                    allTotal = allTotal,
                    plannedMinutes = items.filter { it.status == "done" }.sumOf { it.minutes },
                )
            }.getOrNull()
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
        planSettingsError = null
        planSettingsOpen = true
    }

    fun closePlanSettings() {
        planSettingsOpen = false
        planSettingsError = null
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
            planSettingsError = "还没有选中的计划。"
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
                // 失败就停在弹窗里：不能把异常吞掉再显示「已改成」。
                planSettingsError = "没存上：" + (it.message ?: "未知原因")
                return
            }
        if (!result.ok) {
            planSettingsError = result.error?.message ?: "这次没存成。"
            return
        }
        planSettingsError = null
        planSettingsOpen = false
        planMessage = "每天投入已改成 " + planDailyDraft + " 分钟。"
        refreshPlan()
    }

    // ---------------------------------------------------------------- 计划文档

    /** 计划页只做跳转：打开知识库并定位到这份计划的文档。 */
    internal fun openPlanDocs(planId: String? = null) {
        val service = learningPlan()
        val target = planId ?: planFocusId ?: service?.activePlanId()
        if (service == null || target == null) {
            planMessage = "先在「计划」页建一份计划，计划文档会跟着计划走。"
            return
        }
        planDocBrowseId = target
        currentTab = TAB_KNOWLEDGE
    }

    internal fun closePlanDocs() {
        planDocBrowseId = null
    }

    /**
     * 计划建好之后，若还没有计划文档，就按计划库现状生成一份初版。
     *
     * 只在文档不存在时写：已有文档（AI 调研写的、用户手改过的）一律不覆盖。
     */
    private suspend fun ensurePlanDoc(planId: String) {
        if (!::planDocStore.isInitialized) return
        val store = planDocStore
        if (runCatching { store.load(planId) }.getOrNull() != null) return
        val service = learningPlan() ?: return
        val state = runCatching { service.planState(planId) }.getOrNull() ?: return
        val weeks = runCatching { service.map(planId) }.getOrNull()?.weeks.orEmpty()
        val pool = runCatching { service.pool(planId) }.getOrNull().orEmpty()
        val markdown = buildString {
            appendLine("# 目标与验收")
            appendLine("- 目标：" + state.title)
            appendLine("- 验收：按全局周表逐周完成，计划页勾掉即算做完。")
            weeks.forEach { week ->
                appendLine("## " + week.week)
                val detail = runCatching { service.week(planId, week.week) }.getOrNull()
                if (detail != null) {
                    detail.commitment.units.forEach { unit ->
                        appendLine("- " + unit.title + "（约 " + unit.estimateMinutes + " 分钟）")
                    }
                } else {
                    week.units.forEach { unit -> appendLine("- " + unit.title) }
                }
            }
            if (pool.isNotEmpty()) {
                appendLine("## 待安排")
                pool.forEach { unit ->
                    appendLine("- " + unit.title + "（约 " + unit.estimateMinutes + " 分钟）")
                }
            }
        }
        runCatching { store.save(planId, markdown) }
            .onFailure { appendAppLog("计划文档自动生成失败：" + it.message) }
    }

    fun selectPlanDay(day: String) {
        planSelectedDay = day
        refreshPlan()
    }

    /** 勾掉一条。写完重取整页，界面不自己改数字。 */
    fun completePlanOccurrence(occurrenceId: String) {
        val service = learningPlan() ?: return
        val planId = learningPlanId ?: return
        // 提议要用完成前那一条的标题/章节/unitId；刷新之后从视图里就找不到了。
        val occurrence = planDayView?.occurrences?.firstOrNull { it.id == occurrenceId }
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
        if (result != null && !result.ok) reportPlanProblem(result.error?.message ?: "这次没挪成。")
        if (result != null && result.ok && occurrence != null) {
            maybeProposePractice(planId, occurrence)
        }
        refreshPlan()
        tick++
    }

    /**
     * 待办板上勾掉一个计划节点：等于在计划页把这一天的 occurrence 勾完（决策 2）。
     *
     * 待办板是跨计划的，所以信封里的 planId 用节点自己那一个，不能拿当前聚焦的计划凑——
     * 否则「勾了别的计划的节点」会静默失败或者记到错的计划里。
     */
    internal fun completeTodoPlanNode(node: PlanNodeRow) {
        val service = learningPlan() ?: return
        val command = CommandEnvelope(
            commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
            actor = Actor.USER,
            planId = node.planId,
            origin = "ui.tasks.node",
        )
        val result = runCatching { service.completeOccurrence(command, node.occurrenceId) }
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
        // 用户手动改回「没做」的节点，以后不要再被笔记自动勾上。
        blockAutoCompletion(occurrenceId)
        refreshPlan()
        tick++
    }

    // ---------------- 笔记 → 计划联动：AI 按笔记自动完成 ----------------

    /**
     * 笔记整理完后调用：把模型判断变成真正的自动完成，并记账。
     *
     * 只会动「本周还没完成、且没被撤销过」的节点；一条笔记最多 3 个。
     * 写库失败不回滚已经成功的几条——账本里记着的才算数。
     */
    internal fun autoCompletePlanNodes(noteId: String, noteTitle: String, completions: List<PlanLinkCompletion>) {
        if (completions.isEmpty()) return
        val service = learningPlan() ?: return
        val planId = learningPlanId ?: return
        val ledger = readAutoCompletionLedger()
        val candidates = planLinkWeekCandidates().associateBy { it.occurrenceId }
        val accepted = completions
            .filter { completion ->
                completion.occurrenceId in candidates &&
                    !ledger.isBlocked(completion.occurrenceId) &&
                    ledger.activeCompletionFor(completion.occurrenceId) == null
            }
            .take(PLAN_LINK_MAX_AUTO_COMPLETIONS)
        if (accepted.isEmpty()) return
        var next = ledger
        val done = ArrayList<AutoCompletionEntry>()
        var problem: String? = null
        for (completion in accepted) {
            val candidate = candidates[completion.occurrenceId] ?: continue
            val command = CommandEnvelope(
                commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
                actor = Actor.SYSTEM,
                planId = planId,
                origin = "knowledge.auto-complete",
            )
            val result = runCatching { service.completeOccurrence(command, completion.occurrenceId) }
                .getOrElse { error ->
                    problem = error.message ?: "未知原因"
                    null
                } ?: continue
            if (!result.ok) {
                problem = result.error?.message ?: problem
                continue
            }
            val entry = AutoCompletionEntry(
                id = nextAutoCompletionEntryId(next),
                planId = planId,
                occurrenceId = completion.occurrenceId,
                title = candidate.title,
                chapter = candidate.chapter,
                day = candidate.day,
                noteId = noteId,
                noteTitle = noteTitle,
                evidence = completion.evidence,
                confidence = completion.confidence,
                at = clock.nowMs(),
            )
            next = next.withCompletion(entry)
            done += entry
        }
        if (done.isNotEmpty()) saveAutoCompletionLedger(next)
        refreshPlan()
        tick++
        if (done.isNotEmpty() && uiPrefs.notifyAutoCompletion) {
            notifyWithTarget(
                "Trellis · 计划自动完成",
                "《" + noteTitle.ifBlank { "笔记" } + "》自动完成了 " + done.size + " 个节点：" +
                    done.take(3).joinToString("、") { it.title },
                // 通知说的是「计划节点被勾掉了」，点开当然要落到计划页（能撤销的那一屏）。
                NotifyTarget.Plan,
            )
        }
        planMessage = if (done.isNotEmpty()) {
            "AI 根据《" + noteTitle.ifBlank { "笔记" } + "》自动完成了 " + done.size +
                " 个节点，可在下方「AI 自动完成」里撤销"
        } else {
            problem?.let { "AI 想完成计划节点，但没记上：" + it }
        }
    }

    /** 撤销一笔 AI 自动完成：改回「没做」，并把这个节点拉进屏蔽名单。 */
    fun undoAutoCompletion(entryId: String) {
        val ledger = readAutoCompletionLedger()
        val entry = ledger.entries.firstOrNull { it.id == entryId } ?: return
        val service = learningPlan() ?: return
        val planId = learningPlanId ?: return
        val command = CommandEnvelope(
            commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
            actor = Actor.USER,
            planId = planId,
            origin = "ui.auto-completion",
        )
        val result = runCatching { service.uncompleteOccurrence(command, entry.occurrenceId) }
            .getOrElse { error ->
                // 失败必须停在这里：不能一边改账本一边报「已撤销」。
                reportPlanProblem("这次没改回来：" + (error.message ?: "未知原因"))
                return
            }
        if (!result.ok) {
            reportPlanProblem(result.error?.message ?: "这次没改回来。")
            return
        }
        saveAutoCompletionLedger(ledger.withUndone(entryId, clock.nowMs()).withBlocked(entry.occurrenceId))
        refreshPlan()
        tick++
        planMessage = "已撤销「" + entry.title + "」的自动完成；这个节点以后不会再自动勾上"
    }

    /** 解除全部「不再自动完成」的屏蔽（想重新信任 AI 时用）。 */
    fun clearAutoCompletionBlocks() {
        val ledger = readAutoCompletionLedger()
        if (ledger.blocked.isEmpty()) return
        saveAutoCompletionLedger(ledger.copy(blocked = emptySet()))
        planMessage = "已解除全部「不再自动完成」的屏蔽"
    }

    /** 进计划页/笔记整理后刷新列表。 */
    fun refreshAutoCompletions() {
        publishAutoCompletions(readAutoCompletionLedger())
    }

    /** 本周还没完成、可以参与联动的计划节点（按周视图里 status=planned 的排期算）。 */
    internal fun planLinkWeekCandidates(): List<PlanLinkCandidate> {
        val service = learningPlan() ?: return emptyList()
        val planId = learningPlanId ?: return emptyList()
        val week = runCatching { service.planState(planId).week }.getOrNull() ?: return emptyList()
        val view = runCatching { service.week(planId, week) }.getOrNull() ?: return emptyList()
        val weekEnd = view.days.mapNotNull { it.day.takeIf { day -> day.isNotBlank() } }.maxOrNull()
        val chapters = weekEnd?.let { end ->
            runCatching { service.openNodes(planId, end) }
                .getOrDefault(emptyList())
                .associate { it.occurrenceId to it.chapter }
        }.orEmpty()
        return planLinkCandidates(view, chapters)
    }

    /** 已被用户屏蔽的节点（判定前先排除，省得白占 3 个名额）。 */
    internal fun autoCompletionBlocked(): Set<String> = readAutoCompletionLedger().blocked

    private fun readAutoCompletionLedger(): AutoCompletionLedger {
        autoCompletionLedger?.let { return it }
        val loaded = autoCompletionStore.load()
        autoCompletionLedger = loaded
        publishAutoCompletions(loaded)
        return loaded
    }

    private fun saveAutoCompletionLedger(ledger: AutoCompletionLedger) {
        autoCompletionLedger = ledger
        autoCompletionStore.save(ledger)
        publishAutoCompletions(ledger)
    }

    private fun publishAutoCompletions(ledger: AutoCompletionLedger) {
        planAutoCompletions = ledger.entries
            .filter { it.status == AutoCompletionStatus.Auto }
            .sortedByDescending { it.at }
        planAutoCompletionBlockedCount = ledger.blocked.size
    }

    private fun blockAutoCompletion(occurrenceId: String) {
        if (occurrenceId.isBlank()) return
        val ledger = readAutoCompletionLedger()
        if (ledger.isBlocked(occurrenceId)) return
        saveAutoCompletionLedger(ledger.withBlocked(occurrenceId))
    }

    private fun nextAutoCompletionEntryId(ledger: AutoCompletionLedger): String {
        val store = autoCompletionStore
        var id = store.newEntryId()
        var guard = 0
        while (ledger.entries.any { it.id == id } && guard < 20) {
            id = store.newEntryId()
            guard++
        }
        return id
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
        if (result != null && !result.ok) reportPlanProblem(result.error?.message ?: "这次没跳过。")
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
            reportPlanProblem("日期看不懂：$day")
            return null
        }
        if (target < today) {
            reportPlanProblem("过去的日期不能重排；从今天开始往后放。")
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
        // 偏好先落盘：下次管家问「今天能多投入多少」时，默认值就是它。
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

    /**
     * 生成周复盘（计划页「生成复盘」按钮）。
     *
     * 优先补「已经结束但还没复盘」的那一周（后端 `pendingReviewWeek` 给出具体周号）；
     * 没有欠账时就给当前选中的周生成一份。
     */
    fun generatePlanReview() {
        val service = learningPlan() ?: return
        val planId = planFocusId ?: service.activePlanId() ?: return
        val week = planPendingReviewWeek ?: planSelectedWeek ?: return
        val result = runCatching {
            service.generateReview(planAdjustCommand(planId), planId, week, clock.today().toString())
        }.getOrNull()
        if (result?.ok == true) {
            refreshPlan()
            planMessage = "复盘已生成：$week"
            tick += 1
        } else {
            planMessage = result?.error?.message ?: "复盘没生成，稍后再试。"
        }
    }

    /**
     * 只刷新阶段条（AI 写完大纲/阶段后，计划页想立刻看到新的阶段划分时用）。
     *
     * 失败不打断整页：阶段是「计划形状」的附加信息，读不出来就退化成空表、隐藏阶段条。
     */
    internal fun refreshPlanStages() {
        val service = learningPlan() ?: return
        val planId = planFocusId ?: return
        planStages = runCatching { service.stages(planId) }.getOrElse { emptyList() }
    }

    /**
     * 周日提前生成「本周」总结（计划页「生成本周总结」按钮）。
     *
     * 和 [generatePlanReview] 走同一条命令；区别是不要求这一周已经结束——
     * 后端 `reviewableWeek` 只在「今天是本周最后一天且还没复盘」时给值，
     * 其余时间点这个按钮后端会拒绝，错误原样显示在页面上。
     *
     * 用 `suspend` 是为了和页面里的 `rememberCoroutineScope().launch { ... }` 对齐；
     * 后端命令本身是同步 SQLite 写入，这里不额外切线程。
     */
    suspend fun generateWeekReview(week: String) {
        val service = learningPlan() ?: return
        val planId = planFocusId ?: service.activePlanId() ?: return
        val result = runCatching {
            service.generateReview(planAdjustCommand(planId), planId, week, clock.today().toString())
        }.getOrNull()
        if (result?.ok == true) {
            refreshPlan()
            planMessage = "本周总结已生成：$week"
            tick += 1
        } else {
            planMessage = result?.error?.message ?: "总结没生成，稍后再试。"
        }
    }

    /**
     * 放弃计划（计划设置里的危险操作）。
     *
     * 后端把计划与 process 都置成 ended，另写 `abandoned_at`（事件 `plan.abandoned`），
     * 所以左栏还能列出它、但不会再被排期；放弃后把焦点切走，避免停在「已结束」的页面上。
     * 「放弃」不等于「删除」：历史 occurrence 与复盘都还在。
     */
    fun abandonPlan(planId: String) {
        val service = learningPlan() ?: return
        val result = runCatching {
            service.abandonPlan(planAdjustCommand(planId), planId)
        }.getOrNull()
        if (result?.ok != true) {
            reportPlanProblem(result?.error?.message ?: "放弃没成功，稍后再试。")
            return
        }
        planSettingsOpen = false
        if (planFocusId == planId) {
            planFocusId = null
            planSelectedWeek = null
            planSelectedDay = null
            planPendingSelected = false
        }
        refreshPlan()
        planMessage = "已放弃这个计划；在左栏点它可以回来看，但它不会再排期。"
        tick += 1
    }

    /**
     * 从计划列表里删除一个「已放弃」的计划。
     *
     * 业务上是软删除：`process.deleted_at` 写时间戳，读侧（`plans()` / `activePlanId()`）
     * 过滤掉它，界面里等价于删掉了。底层事件溯源里的数据行保留（触发器禁止物理删），
     * 以后要恢复只需清掉这个时间戳，所以文案不要承诺「磁盘上的数据已清空」。
     *
     * 只有 abandoned 的计划能删：进行中/正常结束的计划误删代价太大，先放弃再删。
     */
    fun deleteAbandonedPlan(planId: String) {
        val service = learningPlan() ?: return
        val result = runCatching {
            service.deletePlan(planAdjustCommand(planId), planId)
        }.getOrNull()
        if (result?.ok != true) {
            reportPlanProblem(result?.error?.message ?: "删除没成功，稍后再试。")
            return
        }
        planSettingsOpen = false
        if (planFocusId == planId) {
            planFocusId = null
            planSelectedWeek = null
            planSelectedDay = null
            planPendingSelected = false
        }
        refreshPlan()
        // 删计划可能连带软删目标（或解绑），目标页的状态要跟着更新。
        refreshGoals()
        planMessage = "已从计划列表里删除这个计划。"
        tick += 1
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

    /** 计划页 UI 发起的命令（计时器开始/结束）：origin 只进审计。 */
    private fun uiCommand(planId: String?): CommandEnvelope = CommandEnvelope(
        commandId = "cmd_" + java.util.UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "ui.plan",
    )

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

    /**
     * 从 zip 装一个插件。返回 null 表示成功，否则是给人看的失败原因。
     *
     * 手机上没有「把文件放进目录再重启」这条路 —— 用户手里只有一个压缩包，
     * 所以安装必须由应用自己完成。桌面端同样受益：AI 生成的插件也是这个入口。
     */
    suspend fun installPluginArchive(file: File): String? {
        val files = runCatching { readPluginArchive(file) }.getOrElse {
            return "读不了这个压缩包：" + (it.message ?: "文件可能损坏")
        }
        if (files.isEmpty()) return "压缩包里没有插件文件（需要 manifest.json 与入口脚本）"
        if ("manifest.json" !in files) return "压缩包里没有 manifest.json（也许它多套了一层目录）"
        val error = KernelPluginInstaller(pluginsDir, kernel).install(files)
        refresh()
        return error
    }

    /**
     * 卸载第三方插件。
     *
     * 数据不立即销毁：内核把它放进回收站（[recycleBin]），过一阵才真正清理。
     * 手机上这是「不想要了」的唯一出口 —— 没有文件管理器可以让你删目录。
     */
    suspend fun uninstallPlugin(id: String): String {
        val pluginId = PluginId(id)
        val info = pluginInfos.firstOrNull { it.manifest.id == pluginId } ?: return "没有这个插件：$id"
        if (info.manifest.builtin) return "内置插件不能卸载，把开关关掉就行"
        val failure = runCatching { kernel.uninstall(pluginId) }.exceptionOrNull()
        if (failure != null) return "卸载失败：" + (failure.message ?: "未知错误")
        // 目录也要删：只从内核摘掉的话，下次启动扫描目录又会把它装回来。
        runCatching { File(pluginsDir, id).deleteRecursively() }
        refresh()
        return "已卸载「" + info.manifest.name + "」，数据在回收站里"
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

    /** AI 归因把未归因片段切成的小块：一块 20 分钟；短于 2 分钟的块不送（判断价值太低）。 */
    private val ATTRIBUTION_BLOCK_MS = 20 * 60_000L

    private val ATTRIBUTION_MIN_BLOCK_MS = 2 * 60_000L

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

    /**
     * 改活动数据源（手机上的刚需）。
     *
     * 桌面默认 `http://localhost:5600/api/0`；手机的 localhost 是手机自己，
     * 所以必须让用户能填局域网那台机器的地址。改完**当场重建引擎**，
     * 否则用户会以为没生效，只能靠重启。
     */
    internal suspend fun saveActivitySource(baseUrl: String, apiKey: String): String {
        val dir = activityDir()
        val file = File(dir, "settings.json")
        val current = runCatching { ActivitySettings.load(file.toPath()) }.getOrElse { ActivitySettings() }
        val next = current.copy(
            baseUrl = baseUrl.ifBlank { ActivityWatchClient.DEFAULT_BASE_URL },
            apiKey = apiKey,
        )
        val saved = runCatching { ActivitySettings.save(file.toPath(), next) }
        if (saved.isFailure) {
            return "保存失败：" + (saved.exceptionOrNull()?.message ?: "写不进 " + file.absolutePath)
        }
        // 丢掉缓存的引擎/同步器，下一次 ensure 会用新地址重建。
        activityAggregator = null
        activitySync = null
        activitySettingsCache = null
        val engine = ensureActivityEngine()
        return if (engine != null) "已保存数据源：" + next.baseUrl else (activityMessage ?: "数据源已保存，但活动库打不开")
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

    // ---------------------------------------------------------------- 活动 ↔ 任务归因

    /**
     * 归因的 `taskRef` 约定：学习计划的 occurrence（含 `todo_id` 的待办型 occurrence）。
     *
     * 这里只拼字符串，activity 模块不认识它；哪天换成别的锚点（任务型 PlanService、笔记），
     * 只改这一个函数与 UI 的候选列表，数据层不用动。
     */
    internal fun occurrenceTaskRef(occurrenceId: String): String = "occurrence:$occurrenceId"

    private fun activityDate(dayMs: Long): LocalDate =
        Instant.ofEpochMilli(dayMs).atZone(ZoneId.systemDefault()).toLocalDate()

    /** 某天（按 settings.dayStartHour 切）的全部归因，含 AI 待确认与已否掉的。 */
    internal suspend fun activityAttributions(dayMs: Long): List<Attribution> {
        ensureActivityEngine() ?: return emptyList()
        val settings = activitySettingsCache ?: return emptyList()
        val store = activityStore ?: return emptyList()
        return runCatching {
            activityIo { store.attributionsInRange(settings.dayStartMs(dayMs), settings.dayEndMs(dayMs)) }
        }.getOrElse { error ->
            activityMessage = "归因读取失败：" + (error.message ?: "未知原因")
            emptyList()
        }
    }

    /**
     * 写一条归因（手动标记 / 忽略区间 / 将来规则都走这里）。
     *
     * 返回空列表表示成功；非空是给界面显示的一句话。区间会被限制在当天之内吗？
     * 不会 —— 归因是独立的时间事实，允许跨天；这里是通用写入，不做业务裁剪。
     */
    internal suspend fun activityAddAttribution(
        fromMs: Long,
        toMs: Long,
        taskRef: String,
        source: String = AttributionSource.MANUAL,
        confidence: Double = 1.0,
        reason: String? = null,
    ): List<String> {
        if (toMs <= fromMs) return listOf("时间区间为空，没法归因")
        ensureActivityEngine() ?: return listOf(activityMessage ?: "活动库打不开")
        val store = activityStore ?: return listOf("活动库打不开")
        val failure = runCatching {
            activityIo {
                store.insertAttribution(
                    Attribution(
                        fromMs = fromMs,
                        toMs = toMs,
                        taskRef = taskRef,
                        source = source,
                        confidence = confidence,
                        reason = reason,
                    ),
                )
            }
        }.exceptionOrNull()
        if (failure != null) return listOf("归因保存失败：" + (failure.message ?: "未知原因"))
        activityMessage = null
        return emptyList()
    }

    /** 标记一段「与任何任务无关」：不参与任务时长，之后 AI 也不再分析这段。 */
    internal suspend fun activityIgnoreRange(fromMs: Long, toMs: Long): List<String> = activityAddAttribution(
        fromMs = fromMs,
        toMs = toMs,
        taskRef = NO_TASK_REF,
        reason = "用户标记为与任务无关",
    )

    internal suspend fun activityDeleteAttribution(id: Long): Boolean {
        ensureActivityEngine() ?: return false
        val store = activityStore ?: return false
        return runCatching { activityIo { store.deleteAttribution(id) } }.getOrDefault(false)
    }

    internal suspend fun activitySetAttributionStatus(id: Long, status: String): Boolean {
        ensureActivityEngine() ?: return false
        val store = activityStore ?: return false
        return runCatching { activityIo { store.updateAttributionStatus(id, status) } }.getOrDefault(false)
    }

    /** 某天按任务归因的总时长（只算 applied、不含 `#none`）。 */
    internal suspend fun activityTaskTimes(dayMs: Long): List<TaskTime> {
        val aggregator = ensureActivityEngine() ?: return emptyList()
        return runCatching { activityIo { aggregator.taskTimes(dayMs) } }.getOrDefault(emptyList())
    }

    /** 某段范围的按任务归因总时长（任务时间视图「本周」用）。 */
    internal suspend fun activityTaskTimes(firstDayMs: Long, lastDayMs: Long): List<TaskTime> {
        val aggregator = ensureActivityEngine() ?: return emptyList()
        return runCatching { activityIo { aggregator.taskTimes(firstDayMs, lastDayMs) } }.getOrDefault(emptyList())
    }

    /** 计划页徽标用：某天 occurrenceId → 已归因毫秒（只取 `occurrence:` 前缀的 taskRef）。 */
    internal suspend fun activityOccurrenceMinutes(day: LocalDate): Map<String, Long> {
        val dayMs = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return activityTaskTimes(dayMs)
            .mapNotNull { time ->
                val id = time.taskRef.removePrefix("occurrence:")
                if (id != time.taskRef && id.isNotEmpty()) id to time.durationMs else null
            }
            .toMap()
    }

    /**
     * 选中那一周的实际投入：把窗口活动按 `occurrence:` 归因到周内节点上求和。
     *
     * 取「选中周」而不是写死本周：卡片上说的数字必须和左边周卡/天条指着的是同一周，
     * 否则用户会看到「本周 12/14」配着别的周的时长。
     */
    internal suspend fun planWeekAttributedMinutes(): Long {
        val days = planDays
        if (days.isEmpty() || planSelectedWeek == null) return 0L
        val ids = days.flatMap { day -> day.items.map { it.occurrenceId } }.toSet()
        if (ids.isEmpty()) return 0L
        var total = 0L
        for (day in days) {
            val parsed = runCatching { LocalDate.parse(day.day) }.getOrNull() ?: continue
            val attributed = runCatching { activityOccurrenceMinutes(parsed) }.getOrDefault(emptyMap())
            total += attributed.filterKeys { it in ids }.values.sum()
        }
        return total
    }

    /** 任务时间视图：计划里当天的任务 + 已归因时长 + 未归因片段 + AI 待确认建议。 */
    internal suspend fun activityTaskReport(dayMs: Long): ActivityTaskReport {
        val aggregator = ensureActivityEngine() ?: return ActivityTaskReport(dayMs, emptyList(), emptyList(), emptyList(), null, false)
        val times = runCatching { activityIo { aggregator.taskTimes(dayMs) } }.getOrDefault(emptyList())
            .associate { it.taskRef to it.durationMs }
        val rows = ArrayList<ActivityTaskRow>()

        val service = learningPlan()
        val planId = planFocusId ?: service?.activePlanId()
        val day = if (service != null && planId != null) {
            runCatching { service.today(planId, activityDate(dayMs).toString()) }.getOrNull()
        } else {
            null
        }
        val doneIds = day?.completed?.map { it.id }?.toSet().orEmpty()
        day?.occurrences?.forEach { occurrence ->
            val ref = occurrenceTaskRef(occurrence.id)
            rows += ActivityTaskRow(
                taskRef = ref,
                title = occurrence.title,
                detail = listOfNotNull(
                    occurrence.chapter?.takeIf { it.isNotBlank() },
                    "预计 " + occurrence.minutes + " 分钟",
                ).joinToString(" · "),
                plannedMinutes = occurrence.minutes,
                attributedMs = times[ref] ?: 0L,
                done = occurrence.id in doneIds,
            )
        }

        // 有归因、但这天的计划里找不到对应 occurrence（任务被移走/延期/删了）：也列出来，不能丢时长。
        val known = rows.map { it.taskRef }.toSet()
        times.entries
            .filter { it.key !in known }
            .sortedByDescending { it.value }
            .forEach { (ref, ms) ->
                rows += ActivityTaskRow(
                    taskRef = ref,
                    title = "计划外的任务",
                    detail = ref,
                    plannedMinutes = null,
                    attributedMs = ms,
                    done = false,
                    unknown = true,
                )
            }

        val proposals = activityAttributions(dayMs).filter { it.status == AttributionStatus.PROPOSED }
        val unattributed = runCatching { activityIo { aggregator.unattributedSegments(dayMs) } }
            .getOrDefault(emptyList())
        return ActivityTaskReport(
            dayMs = dayMs,
            rows = rows,
            unattributed = unattributed,
            proposals = proposals,
            planTitle = planTitle.takeIf { day != null },
            hasPlan = day != null,
        )
    }

    /**
     * 让 AI 把未归因的片段对到当天的任务上，结果写成 `proposed` 等用户确认。
     *
     * 只分析 [Attributions.uncovered]（没被手动/规则/AI 判断过的空档）——
     * 用户明确要求「不能绑定的活动不要反复分析」，所以判断过的区间直接跳过。
     * 隐私：只发进程名 + 窗口标题（过 titleRedactions），URL 一律不发。
     */
    internal suspend fun activityProposeAttributions(dayMs: Long): String {
        val aggregator = ensureActivityEngine() ?: return activityMessage ?: "活动库打不开"
        val settings = activitySettingsCache ?: return "活动库打不开"
        if (!settings.attributionAiEnabled) {
            return "AI 归因在 activity/settings.json 里关掉了（attributionAiEnabled=false）"
        }
        val store = activityStore ?: return "活动库打不开"
        val fromMs = settings.dayStartMs(dayMs)
        val toMs = settings.dayEndMs(dayMs)
        val existing = runCatching { activityIo { store.attributionsInRange(fromMs, toMs) } }
            .getOrElse { return "归因读取失败：" + (it.message ?: "未知原因") }
        val gaps = Attributions.uncovered(fromMs, toMs, existing)
        if (gaps.isEmpty()) return "这一天的时间都已经判断过，没有需要再分析的片段"

        val segments = runCatching { activityIo { aggregator.segments(dayMs) } }
            .getOrElse { return "活动数据读取失败：" + (it.message ?: "未知原因") }
        val blocks = attributionBlocks(segments, gaps)
        if (blocks.isEmpty()) return "没有足够长的未归因片段（至少 2 分钟）"

        val candidates = attributionCandidates(dayMs)
        if (candidates.isEmpty()) return "这一天的计划里没有可对照的任务，先排好计划再让 AI 归因"

        val llm = llmClientOrNull() ?: return "还没配置模型（llm.json），AI 归因暂时用不了"
        val messages = attributionPrompt(settings, activityDate(dayMs), candidates, blocks.take(80))
        val raw = runCatching {
            withTimeoutOrNull(90_000) { llm.complete(messages) }
        }.getOrNull() ?: return "AI 归因超时或请求失败，稍后再试"
        val assignments = parseAttributionReply(raw)
        if (assignments.isEmpty()) return "AI 没有给出可用的归因（回复片段：" + raw.take(120) + "）"

        val byBlock = blocks.associateBy { it.id }
        val candidateRefs = candidates.map { it.ref }.toSet()
        val inserted = ArrayList<Attribution>()
        for (assignment in assignments) {
            val block = byBlock[assignment.block] ?: continue
            val ref = assignment.task.trim().let { if (it.startsWith("occurrence:")) it else occurrenceTaskRef(it) }
            if (ref !in candidateRefs) continue
            // 同一任务在同一段已经被建议/确认过就不再重复写。
            val duplicate = existing.any {
                it.taskRef == ref && Attributions.overlapMs(it, block.fromMs, block.toMs) * 2 >= block.durationMs
            }
            if (duplicate) continue
            inserted += Attribution(
                fromMs = block.fromMs,
                toMs = block.toMs,
                taskRef = ref,
                source = AttributionSource.AI,
                confidence = assignment.confidence,
                status = AttributionStatus.PROPOSED,
                reason = ("AI 建议（" + (block.app ?: "未知进程") + "）").take(120),
            )
        }
        if (inserted.isEmpty()) return "AI 的建议都对不上现有任务，已丢弃（可检查模型配置）"
        val failure = runCatching {
            activityIo { inserted.forEach { store.insertAttribution(it) } }
        }.exceptionOrNull()
        if (failure != null) return "AI 建议保存失败：" + (failure.message ?: "未知原因")
        return "AI 给出 " + inserted.size + " 条建议，请在上面确认或忽略"
    }

    private data class AttributionBlock(
        val id: Int,
        val fromMs: Long,
        val toMs: Long,
        val app: String?,
        val title: String?,
        val categoryPath: String,
    ) {
        val durationMs: Long get() = toMs - fromMs
    }

    /** 把未归因片段切成 ≤20 分钟的块：太长的一段模型很难判断「整段都在做同一件事」。 */
    private fun attributionBlocks(segments: List<Segment>, gaps: List<LongRange>): List<AttributionBlock> {
        val out = ArrayList<AttributionBlock>()
        var id = 0
        for (gap in gaps) {
            for (segment in segments) {
                val start = maxOf(segment.fromMs, gap.first)
                val end = minOf(segment.toMs, gap.last + 1)
                if (end <= start) continue
                var cursor = start
                while (cursor < end) {
                    val blockEnd = minOf(cursor + ATTRIBUTION_BLOCK_MS, end)
                    if (blockEnd - cursor >= ATTRIBUTION_MIN_BLOCK_MS) {
                        out += AttributionBlock(
                            id = id++,
                            fromMs = cursor,
                            toMs = blockEnd,
                            app = segment.app,
                            title = segment.title,
                            categoryPath = segment.categoryPath,
                        )
                    }
                    cursor = blockEnd
                }
            }
        }
        return out
    }

    private data class TaskCandidate(val ref: String, val title: String, val chapter: String?, val minutes: Int)

    private suspend fun attributionCandidates(dayMs: Long): List<TaskCandidate> {
        val service = learningPlan() ?: return emptyList()
        val planId = planFocusId ?: service.activePlanId() ?: return emptyList()
        val day = runCatching { service.today(planId, activityDate(dayMs).toString()) }.getOrNull()
            ?: return emptyList()
        return day.occurrences.map {
            TaskCandidate(occurrenceTaskRef(it.id), it.title, it.chapter, it.minutes)
        }
    }

    private fun attributionPrompt(
        settings: ActivitySettings,
        date: LocalDate,
        candidates: List<TaskCandidate>,
        blocks: List<AttributionBlock>,
    ): List<LlmMessage> {
        val system = """
            你是 Trellis 的学习时间归因助手：把用户电脑上的活动片段对到当天的学习任务上。

            输出规则（严格遵守）：
            1. 只输出一个 JSON 数组，不要任何解释、不要 markdown 代码块。
            2. 数组元素形如 {"block": 3, "task": "occurrence:xxx", "confidence": 0.8}；
               block 是片段编号，task 必须来自给定任务清单的 ref，confidence 是 0 到 1 的数。
            3. 只有确实可能是在做该任务时才输出；拿不准就省略这个片段（宁缺毋滥）。
            4. 看视频、游戏、闲聊、系统设置等与任务无关的片段不要输出；它们会被用户手动标成无关。
            5. 一段片段最多对到一个任务。
        """.trimIndent()
        val taskLines = candidates.joinToString("\n") { candidate ->
            val extra = listOfNotNull(
                candidate.chapter?.takeIf { it.isNotBlank() },
                "预计 " + candidate.minutes + " 分钟",
            ).joinToString("，")
            "- " + candidate.ref + " | " + candidate.title + if (extra.isBlank()) "" else "（$extra）"
        }
        val blockLines = blocks.joinToString("\n") { block ->
            val app = block.app ?: "（未知进程）"
            val title = if (settings.attributionAiSendTitles) redactTitle(block.title, settings) else null
            val parts = listOfNotNull(
                "#" + block.id,
                clockLabel(block.fromMs) + "-" + clockLabel(block.toMs),
                app,
                title?.takeIf { it.isNotBlank() },
                block.categoryPath,
            )
            parts.joinToString(" | ")
        }
        val user = """
            日期：$date

            当天的任务：
            $taskLines

            未归因的电脑活动片段（每段 ≤20 分钟）：
            $blockLines
        """.trimIndent()
        return listOf(LlmMessage(LlmRole.System, system), LlmMessage(LlmRole.User, user))
    }

    private fun redactTitle(title: String?, settings: ActivitySettings): String? {
        val source = title ?: return null
        var out = source
        for (pattern in settings.titleRedactions) {
            val regex = try {
                Regex(pattern, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                null
            }
            if (regex != null) out = regex.replace(out, "***")
        }
        return out
    }

    private fun clockLabel(ms: Long): String {
        val time = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
        return String.format("%02d:%02d", time.hour, time.minute)
    }

    private data class AiAssignment(val block: Int, val task: String, val confidence: Double)

    private fun parseAttributionReply(raw: String): List<AiAssignment> {
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val array = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonArray }
            .getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val block = obj["block"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val task = obj["task"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.5
            AiAssignment(block, task, confidence.coerceIn(0.0, 1.0))
        }
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
                    platform = if (HostPlatform.isDesktop) Platform.JVM else Platform.ANDROID,
                    pluginsDir = pluginsDir.absolutePath,
                    loaders = listOf(
                        BuiltinLoader(
                            listOf(
                                PlanCorePlugin(),
                                PlanUiPlugin(runtime),
                                // 默认主题包也是插件：可在插件页停用，停用后回落默认配色。
                                BentoThemePlugin(),
                                // 第二个内置主题（「书桌」）。两套并列是 2026-11-06 评审的
                                // 决策 1：先看截图再定谁当默认，不猜。
                                DeskThemePlugin(),
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
            runtime.planDocStore = PlanDocStore(home)
            runtime.chatStore = ChatStore(home) { line -> runtime.appendAppLog(line) }
            runtime.planSessionStore = PlanSessionStore(home)
            runtime.knowledgeStore = KnowledgeStore(home, runtime.clock)
            runtime.stewardStore = StewardStore(home)
            // 用户明确说过旧的「计划检查」可以直接删；迁移顺手清掉残留文件。
            runtime.stewardStore.migrateLegacyPlanCheck()
            runtime.stewardSettings = runtime.stewardStore.settings()
            runtime.notifier = Notifier.create()
            runtime.stewardRuntime = StewardRuntime(
                store = runtime.stewardStore,
                ports = runtime.stewardPorts(),
                clock = { runtime.clock.nowMs() },
                zone = runtime.clock.zone,
            )
            runtime.ensureStewardSession()
            runtime.loadChatSessions()
            // 聊天器的默认行为就是「接着上次说」：启动时把最近一段接回去，
            // 而不是每次都从空白开始、让人以为记录丢了。管家那段常驻在列表最上面，
            // 但不抢默认视图 —— 打开应用先看普通会话。
            runtime.chatSessions.firstOrNull { it.kind == SessionKind.Manager }
                ?.let { runtime.openChat(it.id) }
            runtime.onWindowFocused()
            runtime.startStewardTicker()
            runtime.reloadExamBank()
            runtime.wireApprovalDialog()

            if (plan.plans().isEmpty()) plan.createPlan("我的第一个计划")
            runtime.refresh()
            // 待办板上的计划节点是启动时就要有的：不点计划页也得看得见。
            runtime.refreshPlan()

            // 截图/联调用：TRELLIS_DEMO_CHOICES="看建议|开始|先不改" 直接模拟「模型调了 ask_choices」，
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
