package dev.trellis.feature.goal

import dev.trellis.kernel.api.ActionOutcome
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import dev.trellis.kernel.slot.UiTextSize
import dev.trellis.kernel.slot.UiTextStyle
import dev.trellis.kernel.slot.UiTextWeight
import dev.trellis.kernel.slot.UiTone

/**
 * 三处界面，全部只交 [UiNode] 数据。
 *
 * 插件不画像素：宿主得先看懂，才谈得上审查、降级和主题一致。
 * 所以这里的代码只决定「说什么、什么时候说」，不决定「长什么样」。
 */
private fun tap(handler: String, vararg args: Pair<String, String>): UiTap =
    UiTap.Plugin(handler, args.toMap())

private fun text(
    value: String,
    style: UiTextStyle = UiTextStyle.Body,
    tone: UiTone = UiTone.Default,
    size: UiTextSize = UiTextSize.Normal,
    weight: UiTextWeight = UiTextWeight.Normal,
): UiNode = UiNode.Text(value, style, tone, size, weight)

/**
 * 对话页常驻条。
 *
 * 它回答一个问题：「我上次定的事，现在到哪儿了」。所以每个状态都只给一句结论 + 一个动作，
 * 不做成仪表盘 —— 对话页的主体是对话，这条是背景板，抢戏就把对话挤没了。
 */
class GoalAsideSlot(
    private val service: GoalService,
    private val today: () -> String,
    private val onOpenChat: () -> Unit,
    /** 出计划时按多少分钟排。读宿主的 study.json，插件自己不存这份设置。 */
    private val dailyMinutes: () -> Int = { 60 },
) : DynamicSlot {

    override val id: String = "goal-bar"
    override val order: Int = 40

    override suspend fun build(context: DataContext): UiNode {
        val mirror = service.refresh()
        return when (mirror.phase) {
            GoalPhase.EMPTY, GoalPhase.ARCHIVED -> empty()
            GoalPhase.COLLECTING -> collecting(mirror)
            GoalPhase.DRAFT -> draft(mirror)
            GoalPhase.ACTIVE -> active(mirror)
            GoalPhase.REVIEWING -> reviewing(mirror)
        }
    }

    private fun empty(): UiNode = UiNode.Column(
        children = listOf(
            text("目标", style = UiTextStyle.Caption, tone = UiTone.Muted),
            text("还没有目标", weight = UiTextWeight.Medium),
            text("说一句「我想…」，它会先问清楚，再排成每天能做的事。", size = UiTextSize.Small, tone = UiTone.Muted),
            UiNode.Button("开始规划 →", tap("start")),
        ),
    )

    /**
     * 采集中的常驻条：**直接在这里问，直接在这里答**。
     *
     * 之前的版本只有一个「去回答」按钮，点了只是切到对话页 —— 用户到了对话页，
     * 看到的是模型用一段话把问题说了一遍，既没有选项也没有输入口；随手打一句答案，
     * 那句话又会被意图分类判成「聊天」，而聊天按设计不给任何工具，
     * 于是模型只能回「我这没有能记录的工具」。一条链路三处断，用户看到的就是「没让我回答」。
     *
     * 现在把问题放在卡片上：选项是枚举值，点一下直接写进契约，不经过模型、不经过意图分类。
     * 一次只问一个字段，答完刷出下一个；问完了给「出计划」，出完给「就这样开始」。
     */
    private fun collecting(mirror: GoalMirror): UiNode {
        val confidence = Confidence.of(mirror.contract)
        val missing = Confidence.remaining(mirror.contract)
        val children = mutableListOf<UiNode>()
        children += text("正在收集", style = UiTextStyle.Caption, tone = UiTone.Muted)
        children += text(mirror.contract.statement.ifBlank { "（还没说目标）" }, weight = UiTextWeight.Medium)
        children += text("还差 " + missing + " 个信息", size = UiTextSize.Small, tone = UiTone.Muted)
        children += UiNode.Progress((confidence * 100).toInt(), 100, "信息完整度")

        val key = GoalEngine.questions(mirror).firstOrNull()
        if (key == null) {
            // 问完了（或已经问满三轮）：决定权交回用户，别自动开工。
            children += text("信息够了，可以出计划了。", size = UiTextSize.Small)
            children += UiNode.Button("出计划", tap("draft"))
            return UiNode.Column(children = children)
        }

        children += text(key.label, size = UiTextSize.Small, weight = UiTextWeight.Medium)
        // 一列，不是两列：侧条只有 260dp，切成两栏后每个按钮只剩 5 个字，
        // 「在职，只有晚上」会被折成两行 —— 看着像排版坏了，也更容易点错。
        children += UiNode.Column(
            children = GoalQuestions.options(key).map { option ->
                UiNode.Button(option, tap("answer", "field" to key.name, "value" to option))
            },
        )
        // 选项是枚举值不是封闭集合：想自己说就写在框里，回车记下。
        children += UiNode.Input(
            hint = "或自己说一句",
            submitLabel = "记下",
            onSubmit = tap("answer", "field" to key.name),
        )
        children += UiNode.Button(GoalQuestions.SKIP_LABEL, tap("skip", "field" to key.name))
        return UiNode.Column(children = children)
    }

    private fun draft(mirror: GoalMirror): UiNode {
        val plan = mirror.dayPlan(today())
        return UiNode.Column(
            children = listOf(
                text("草稿", style = UiTextStyle.Caption, tone = UiTone.Muted),
                text(mirror.contract.statement, weight = UiTextWeight.Medium),
                text(
                    if (plan == null) "计划已生成" else "今天 " + plan.entries.size + " 件事，共 " + plan.totalMinutes + " 分钟",
                    size = UiTextSize.Small,
                    tone = UiTone.Muted,
                ),
                UiNode.Row(
                    children = listOf(
                        UiNode.Button("就这样开始", tap("confirm")),
                        UiNode.Button("再改改", tap("open")),
                    ),
                ),
            ),
        )
    }

    private fun active(mirror: GoalMirror): UiNode {
        val plan = mirror.dayPlan(today())
        val tomorrow = IsoDate.plusDays(today(), 1)?.let { mirror.dayPlan(it) }
        val children = mutableListOf<UiNode>()
        children += text("目标", style = UiTextStyle.Caption, tone = UiTone.Muted)
        children += text(mirror.contract.statement, weight = UiTextWeight.Medium)
        children += UiNode.Row(
            children = listOf(
                UiNode.Badge(GoalEngine.clarityLabel(mirror.contract.clarity)),
                UiNode.Badge(
                    if (plan == null) "今天没排" else "今天 " + plan.doneCount + "/" + plan.entries.size,
                    if (plan != null && plan.doneCount == plan.entries.size && plan.entries.isNotEmpty()) {
                        UiTone.Success
                    } else {
                        UiTone.Default
                    },
                ),
            ),
        )
        if (plan != null) {
            children += UiNode.Progress(plan.doneCount, plan.entries.size.coerceAtLeast(1), "今日完成")
        }
        // 明日只给「几条、多久」：具体条目在首页卡上看，这里再列一遍会把侧条撑长。
        if (tomorrow != null) {
            children += text(
                "明天 " + tomorrow.entries.size + " 条 · " + tomorrow.totalMinutes + " 分钟",
                size = UiTextSize.Small,
                tone = UiTone.Muted,
            )
        }
        service.pushNote?.let { note ->
            children += UiNode.Notice(note)
        }
        children += UiNode.Button(
            if (GoalEngine.reviewDue(mirror, today())) "该复盘了" else "复盘",
            tap("review"),
        )
        return UiNode.Column(children = children)
    }

    private fun reviewing(mirror: GoalMirror): UiNode = UiNode.Column(
        children = listOf(
            text("复盘", style = UiTextStyle.Caption, tone = UiTone.Muted),
            text("这一期结束了，花两分钟说说哪里没做到。", size = UiTextSize.Small, tone = UiTone.Muted),
            UiNode.Button("开始复盘", tap("review-start")),
            UiNode.Button("先继续推进", tap("review-skip")),
        ),
    )

    override suspend fun perform(handler: String, payload: Map<String, String>): ActionOutcome {
        return when (handler) {
            "start", "open" -> {
                onOpenChat()
                ActionOutcome(true, "已切到对话页")
            }
            // 卡片上的选项与输入框都走这里：写进契约、推进轮次、立刻刷出下一个问题。
            "answer" -> {
                val key = fieldOf(payload)
                    ?: return ActionOutcome(false, unknownField(payload["field"]))
                val value = payload["value"]?.trim().orEmpty()
                if (value.isEmpty()) {
                    return ActionOutcome(false, "没收到内容：点上面某个选项，或在框里写一句再按「记下」")
                }
                val mirror = service.answer(key, value)
                val next = GoalEngine.questions(mirror).firstOrNull()
                ActionOutcome(
                    true,
                    "记下了：" + key.label + " = " + value +
                        if (next == null) "。信息够了，点「出计划」。" else "。下一个：" + next.label,
                )
            }
            "skip" -> {
                val key = fieldOf(payload)
                    ?: return ActionOutcome(false, unknownField(payload["field"]))
                val mirror = service.skip(key)
                val next = GoalEngine.questions(mirror).firstOrNull()
                ActionOutcome(
                    true,
                    "跳过了「" + key.label + "」。" +
                        if (next == null) "点「出计划」安排今天。" else "下一个：" + next.label,
                )
            }
            "draft" -> {
                val plan = service.draft(dailyMinutes()).dayPlan(today())
                ActionOutcome(
                    true,
                    if (plan == null) "计划排好了" else "计划排好了：今天 " + plan.entries.size +
                        " 件事，共 " + plan.totalMinutes + " 分钟"
                )
            }
            "confirm" -> {
                service.update { GoalEngine.confirm(it) }
                ActionOutcome(true, "已开始推进")
            }
            "review" -> {
                val now = service.mirror()
                if (GoalEngine.reviewDue(now, today())) {
                    service.update { GoalStateMachine.move(it, GoalPhase.REVIEWING).getOrElse { _ -> it } }
                    ActionOutcome(true, "进入复盘")
                } else {
                    ActionOutcome(true, "还没到复盘周期，随时可以手动复盘")
                }
            }
            "review-start" -> {
                onOpenChat()
                ActionOutcome(true, "去对话里说这次哪里没做到")
            }
            "review-skip" -> {
                service.update { it.copy(phase = GoalPhase.ACTIVE, cycleStartedOn = today()) }
                ActionOutcome(true, "已顺延一期")
            }
            else -> ActionOutcome(
                false,
                "没有这个动作：" + handler +
                    "；可用的是 start / open / answer / skip / draft / confirm / review / review-start / review-skip",
            )
        }
    }

    private fun fieldOf(payload: Map<String, String>): FieldKey? =
        payload["field"]?.let { raw -> enumValues<FieldKey>().firstOrNull { it.name == raw } }

    /** 报错要能指导修改：把全部可用字段列出来。 */
    private fun unknownField(raw: String?): String =
        "没有这个字段：" + (raw ?: "（空）") + "；可用的是 " +
            enumValues<FieldKey>().joinToString { it.name }
}

/**
 * 首页卡片：今天要做的，可勾选。
 *
 * 勾选写回本插件的数据，同时（宿主侧）尝试给 PlanService 记一条完成事件 ——
 * 任务系统仍归 PlanService，这里只是「目标下的今天」，不是第二个待办清单。
 */
class GoalTodaySlot(
    private val service: GoalService,
    private val today: () -> String,
) : DynamicSlot {

    override val id: String = "goal-today"
    override val order: Int = 30

    override suspend fun build(context: DataContext): UiNode {
        val mirror = service.refresh()
        val date = today()
        if (mirror.isEmpty) {
            return UiNode.Column(
                children = listOf(
                    text("今天", style = UiTextStyle.Caption, tone = UiTone.Muted),
                    text("还没有目标", weight = UiTextWeight.Medium),
                    text("到对话里说一句想做什么，它会把今天排成 3 件能勾掉的事。", size = UiTextSize.Small, tone = UiTone.Muted),
                ),
            )
        }
        val plan = mirror.dayPlan(date)
        if (plan == null || plan.entries.isEmpty()) {
            return UiNode.Column(
                children = listOf(
                    text("今天", style = UiTextStyle.Caption, tone = UiTone.Muted),
                    text("今天还没排", weight = UiTextWeight.Medium),
                    text("明天会自动排好；也可以现在让它重新排一次。", size = UiTextSize.Small, tone = UiTone.Muted),
                    UiNode.Button("重新排今天", tap("replan")),
                ),
            )
        }

        val rows = plan.entries.map { entry ->
            UiNode.Row(
                children = listOf(
                    UiNode.Button(
                        if (entry.done) "✓" else "○",
                        tap("toggle", "id" to entry.id),
                    ),
                    text(
                        entry.text,
                        size = UiTextSize.Small,
                        tone = if (entry.done) UiTone.Muted else UiTone.Default,
                        weight = UiTextWeight.Medium,
                    ),
                    text(entry.minutes.toString() + " 分", size = UiTextSize.Small, tone = UiTone.Muted),
                ),
            )
        }

        // 实际投入：只有真拿到活动数据才显示。显示成 0 会被读成「今天什么都没干」，
        // 而真相往往是「活动采集没开」——两者不能混。
        val effort = service.todayEffort()?.takeIf { it.visible }
        val effortLine = effort?.let { e ->
            val diff = e.minutes - plan.totalMinutes
            "计划 " + plan.totalMinutes + " 分钟 · 实际 " + e.minutes + " 分钟" + when {
                diff >= 15 -> "（比计划多 " + diff + "）"
                diff <= -15 -> "（比计划少 " + (-diff) + "）"
                else -> "（基本吻合）"
            }
        }
        // 只装了窗口采集时，这个数里包含「窗口开着、人不在」的时间。不说清楚，
        // 用户会拿它跟计划比，然后得出「我今天很努力」这种错误结论。
        val effortCaveat = effort?.takeIf { !it.hasAfkData }
            ?.let { "这个数只统计了窗口，可能偏高。" }

        return UiNode.Column(
            children = listOf(
                text("今天 · " + plan.doneCount + "/" + plan.entries.size, style = UiTextStyle.Caption, tone = UiTone.Muted),
                text(mirror.contract.statement.ifBlank { "今天" }, weight = UiTextWeight.Medium),
                UiNode.Progress(plan.doneCount, plan.entries.size, ""),
            ) + rows + listOfNotNull(
                effortLine?.let { text(it, size = UiTextSize.Small, tone = UiTone.Muted) },
                effortCaveat?.let { text(it, size = UiTextSize.Small, tone = UiTone.Muted) },
                text(
                    "共 " + plan.totalMinutes + " 分钟；明天的安排已经排好，看得见但明天才算。",
                    size = UiTextSize.Small,
                    tone = UiTone.Muted,
                ),
            ),
        )
    }

    override suspend fun perform(handler: String, payload: Map<String, String>): ActionOutcome =
        when (handler) {
            "toggle" -> {
                val id = payload["id"] ?: return ActionOutcome(false, "缺少参数 id")
                var found: DayEntry? = null
                service.update { mirror ->
                    val plan = mirror.dayPlan(today()) ?: return@update mirror
                    found = plan.entries.firstOrNull { it.id == id }
                    GoalEngine.complete(mirror, today(), id)
                }
                if (found == null) {
                    ActionOutcome(false, "今天没有这条：" + id)
                } else {
                    ActionOutcome(true, if (found!!.done) "已取消「" + found!!.text + "」" else "完成「" + found!!.text + "」")
                }
            }
            "replan" -> {
                service.update { mirror ->
                    val milestone = mirror.milestone ?: GoalPlanner.defaultMilestone(mirror.contract)
                    mirror.copy(
                        milestone = milestone,
                        days = mirror.days + (
                            today() to GoalPlanner.planFor(
                                mirror.contract,
                                today(),
                                mirror.dayPlan(today())?.totalMinutes ?: 60,
                                milestone,
                            )
                            ),
                    )
                }
                ActionOutcome(true, "今天已重排")
            }
            else -> ActionOutcome(false, "没有这个动作：" + handler + "；可用的是 toggle / replan")
        }
}

/**
 * 设置页分组。
 *
 * 只放三件与这份规划直接相关的设置：复盘多久一次、几点推、要不要清空。
 * 「每天投入多少」不在这里 —— 那是全局学习节奏（宿主的 study.json），
 * 这里只读它、显示它，重复一份就会有两个真相。
 */
class GoalSettingsSlot(
    private val service: GoalService,
    private val dailyMinutes: () -> Int,
) : DynamicSlot {

    override val id: String = "goal-settings"
    override val order: Int = 30

    private var resetArmed = false

    override suspend fun build(context: DataContext): UiNode {
        val mirror = service.mirror()
        val settings = mirror.settings
        val children = mutableListOf<UiNode>()
        // 加「当前：」三个字：不加的话，进入 REVIEWING 时这一组的第一行就是「复盘周期到了」，
        // 读起来像一个小标题，用户会以为下面那个步进器是「设置复盘周期到了」。
        children += text("当前：" + GoalStateMachine.describe(mirror), size = UiTextSize.Small, tone = UiTone.Muted)
        children += UiNode.Stepper(
            label = "复盘周期",
            value = settings.feedbackCycleDays,
            unit = "天",
            min = GoalSettings.MIN_CYCLE_DAYS,
            max = GoalSettings.MAX_CYCLE_DAYS,
            onStep = tap("cycle"),
        )
        children += UiNode.Stepper(
            label = "每天推送",
            value = settings.pushHour,
            unit = "点",
            min = 0,
            max = 23,
            onStep = tap("push"),
        )
        children += text(
            "每天投入 " + dailyMinutes() + " 分钟（在「学习节奏」里改）；推送只在应用运行时生效，关闭期间不推，下次启动补一条。",
            size = UiTextSize.Small,
            tone = UiTone.Muted,
        )
        // 删除入口只在「真有东西可删」时出现。
        // 之前它一直显示，而 EMPTY 状态下点两次什么也不会变 —— 用户只会得出「这个按钮坏了」。
        if (mirror.isEmpty) {
            children += text("还没有目标，没有可删的东西。", size = UiTextSize.Small, tone = UiTone.Muted)
            return UiNode.Column(children = children)
        }
        if (!resetArmed) {
            children += UiNode.Button("删除这份规划", tap("reset"))
            return UiNode.Column(children = children)
        }
        // 确认态不再是「同一个按钮改个文案再点一次」：那既容易被误当成没反应，
        // 也没说清到底会删掉什么。改成一次说明 + 两个明确的出口。
        children += UiNode.Notice("删除会清掉目标、画像、日计划和压缩过的历史；插件本身保留，状态回到「还没有目标」。")
        children += UiNode.Row(
            children = listOf(
                UiNode.Button("确定删除", tap("reset-confirm")),
                UiNode.Button("取消", tap("reset-cancel")),
            ),
        )
        return UiNode.Column(children = children)
    }

    override suspend fun perform(handler: String, payload: Map<String, String>): ActionOutcome =
        when (handler) {
            "cycle" -> {
                val delta = payload["delta"]?.toIntOrNull() ?: 0
                service.update { mirror ->
                    val next = mirror.settings.copy(feedbackCycleDays = mirror.settings.feedbackCycleDays + delta)
                    mirror.copy(settings = next.normalized())
                }
                val value = service.mirror().settings.feedbackCycleDays
                ActionOutcome(true, "复盘周期 " + value + " 天")
            }
            "push" -> {
                val delta = payload["delta"]?.toIntOrNull() ?: 0
                service.update { mirror ->
                    val next = mirror.settings.copy(pushHour = mirror.settings.pushHour + delta)
                    mirror.copy(settings = next.normalized())
                }
                val value = service.mirror().settings.pushLabel()
                ActionOutcome(true, "推送时间 " + value)
            }
            "reset" -> {
                // 第一步只亮出确认，不删任何东西。
                if (service.mirror().isEmpty) {
                    ActionOutcome(false, "还没有目标，没有可删的东西")
                } else {
                    resetArmed = true
                    ActionOutcome(true, "确认要删的话，点「确定删除」；点「取消」则什么都不动")
                }
            }
            "reset-confirm" -> {
                // 没走过「亮确认」这一步就不许删：删除是不可逆的，
                // 不能因为界面状态被重建、或者误触发一次调用就把用户的东西清掉。
                if (!resetArmed) {
                    ActionOutcome(false, "先点「删除这份规划」，看清会删什么再确认")
                } else if (service.mirror().isEmpty) {
                    resetArmed = false
                    ActionOutcome(false, "还没有目标，没有可删的东西")
                } else {
                    resetArmed = false
                    service.reset()
                    ActionOutcome(true, "已删除：目标、画像、日计划都清空了，插件本身还在")
                }
            }
            "reset-cancel" -> {
                resetArmed = false
                ActionOutcome(true, "已取消，什么都没删")
            }
            else -> ActionOutcome(
                false,
                "没有这个动作：" + handler +
                    "；可用的是 cycle / push / reset / reset-confirm / reset-cancel",
            )
        }
}

/**
 * 进 AI 上下文的那一段。
 *
 * 交的是**事实**（目标、约束、今天排了什么），不是指令。宿主决定措辞、位置和怎么截断 ——
 * 插件没法借这段给自己加权限，这正是扩展点这样设计的理由。
 */
class GoalContextSection(
    private val service: GoalService,
    private val today: () -> String,
) : dev.trellis.kernel.context.ContextSection {

    override val id: String = "goal-context"
    override val order: Int = 20

    /** 硬预算 800：和 [GoalCompressor.CONTEXT_BUDGET] 同一个数，改一处必须改另一处。 */
    override val budgetChars: Int = GoalCompressor.CONTEXT_BUDGET

    override suspend fun render(): String {
        val mirror = service.refresh()
        return GoalEngine.contextBlock(mirror, today(), service.todayEffort())
    }
}
