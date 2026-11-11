package dev.trellis.domain.plan

/**
 * 计划系统的读写契约（`plan-system-backend.md` §5/§12）。
 *
 * 实现放在 `:features:learning-process`：领域 + 命令 + 查询 + 迁移，
 * 存储适配器（SQLite）只在这一层下面，契约不暴露表结构。
 *
 * 所有写方法接收 [CommandEnvelope]；返回 [CommandResult]，失败带人话错误。
 * 查询方法只读投影表（当前态），历史统计由 [asOfCompletedCount] / 复盘快照负责。
 */
interface LearningPlanService {

    // ---------------- 查询 ----------------

    /** 今天：occurrences + 已完成 + 待办。`planId` 为空时取唯一活跃计划。 */
    fun today(planId: String?, day: String): DayView
    fun week(planId: String, week: String): WeekView

    fun map(planId: String): MapView

    /** 周复盘；未生成时返回 state = not_ready 的空视图。 */
    fun review(planId: String, week: String): ReviewView

    fun planState(planId: String): PlanStateView

    /**
     * 当前唯一活跃计划的 id；没有计划时返回 null。
     *
     * 给界面用：进入计划页时先问「现在是哪个计划」，再拿 [map] / [week] / [today] 取内容。
     * 与 [today] 的 `planId = null` 语义一致（唯一活跃计划）。
     */
    fun activePlanId(): String?

    /** 所有计划（不分活跃/归档）；计划页左栏「全部计划」用。 */
    fun plans(): List<LearningPlanInfo>

    /**
     * 当前版本的学习阶段（按周区间切分，按 sort_order 排）。
     *
     * 阶段回答「这几个星期在攻什么」；进度数字（done/total）由后端按承诺单元的
     * occurrence 状态现算，UI 不自己数。没有阶段、或计划还没有生效版本时返回空列表。
     */
    fun stages(planId: String): List<StageView>

    /**
     * 当前版本里有多少单元、其中多少个已经有 `done` occurrence。
     *
     * 给「没有阶段」的计划判完成用：`stages` 为空时，完成态由
     * 「所有单元都至少做过一次 + 待安排池为空」决定，而不是「阶段」。
     * 有阶段的计划仍以 [StageView.done] / [StageView.total] 为准。
     *
     * 默认返回 0/0：不实现学习计划的适配器不必关心这件事。
     */
    fun unitProgress(planId: String): UnitProgress = UnitProgress(0, 0)

    // ---------------- 目标 / 项目 ----------------

    /** 所有目标；默认不含已归档。计划页顶部的「目标：X · 里程碑 M/N」用。 */
    fun goals(includeArchived: Boolean = false): List<GoalView>

    fun goal(goalId: String): GoalView?

    fun createGoal(
        env: CommandEnvelope,
        title: String,
        kind: String = "goal",
        intent: String = "",
        success: String = "",
        deadline: String? = null,
        parentId: String? = null,
    ): CommandResult<GoalRef>

    /** 只改传进来的字段；`status` 传 paused/archived/active 可暂停、归档、恢复。 */
    fun updateGoal(
        env: CommandEnvelope,
        goalId: String,
        title: String? = null,
        intent: String? = null,
        success: String? = null,
        deadline: String? = null,
        status: String? = null,
    ): CommandResult<GoalRef>

    fun addMilestone(env: CommandEnvelope, goalId: String, title: String, deadline: String? = null): CommandResult<MilestoneRef>

    fun completeMilestone(env: CommandEnvelope, milestoneId: String): CommandResult<MilestoneRef>

    fun uncompleteMilestone(env: CommandEnvelope, milestoneId: String): CommandResult<MilestoneRef>

    fun removeMilestone(env: CommandEnvelope, milestoneId: String): CommandResult<Unit>

    /** 把计划挂到一个目标上；`goalId = null` 表示解除关联。 */
    fun linkPlanToGoal(env: CommandEnvelope, planId: String, goalId: String?): CommandResult<Unit>

    /** 还没被「本周及以后」的承诺收进去的单元（节点池）；过去周没做完的节点也会回到这里。 */
    fun pool(planId: String): List<PoolUnit>

    /**
     * 计划当前欠的节点：本周过了日子还没做的排期 + 过去周没完成、也没被以后收走的单元，
     * 外加本周从今天到周日的剩余参考分钟（只做参考，不限制补做）。
     *
     * 只读事实；重排怎么做由上层协商后发命令，查询本身不写库、不猜方案。
     */
    fun overdue(planId: String, today: String): OverdueView

    /**
     * 今天到期和之前逾期、还没做完的计划节点，按天升序。
     *
     * 待办板要的就是这两拨：`day <= untilDay` 且 `status = 'planned'`。只有 planned 算数 ——
     * 周结算会把过了日子还没做的标成 `missed` 并（最多两条）结转进下一周，再把 missed
     * 摊出来，同一件事就会在待办里出现两次。哪一天算「今天」由调用方给截止日。
     *
     * 默认返回空：不实现学习计划的适配器不必关心这件事。
     */
    fun openNodes(planId: String, untilDay: String): List<OpenNode> = emptyList()

    /**
     * 每个计划节点实际计时的累计分钟数（只算已经结束的 session），用于「计时到点自动完成」。
     *
     * 只看 [untilDay] 及之前安排的节点——未来的节点计时不该被算成「做完了」。
     * 返回 occurrenceId → 累计分钟；默认返回空：不实现学习计划的适配器不必关心这件事。
     */
    fun actualMinutesByOccurrence(planId: String, untilDay: String): Map<String, Int> = emptyMap()

    fun todos(planId: String? = null): List<TodoView>

    /** 截至事件序号 [seq] 的某周完成单元数（历史不可变，用于审计/黄金测试）。 */
    fun asOfCompletedCount(planId: String, week: String, seq: Long): Int

    // ---------------- 计划层命令 ----------------

    fun createProcess(env: CommandEnvelope, title: String, goal: String, deadline: String? = null, weeklyCapacityMinutes: Int? = null): CommandResult<CreatedProcess>

    /**
     * 改「这个计划每天投入多少分钟」。这是计划的属性，不是全局设置：
     * 写进 `process.weekly_capacity_minutes`（= daily × 7），影响之后铺天的参考投入
     * （只做参考，不是硬上限：2026-11-08 用户定的）。
     */
    fun setPlanDailyMinutes(env: CommandEnvelope, planId: String, dailyMinutes: Int): CommandResult<Unit>

    fun saveDraft(env: CommandEnvelope, planId: String, versionId: String?, units: List<UnitDraft>, commitments: List<CommitmentDraft>): CommandResult<SavedDraft>

    fun acceptDraft(env: CommandEnvelope, versionId: String, firstWeek: String): CommandResult<AcceptedPlan>

    fun changeCommitment(env: CommandEnvelope, versionId: String, week: String, unitIds: List<String>, reason: String, diff: PlanDiff? = null): CommandResult<VersionRef>

    fun pausePlan(env: CommandEnvelope, planId: String, reason: String): CommandResult<Unit>

    fun resumePlan(env: CommandEnvelope, planId: String): CommandResult<Unit>

    fun endPlan(env: CommandEnvelope, planId: String, reason: String): CommandResult<Unit>

    /**
     * 放弃一个计划（「不学了」/「换方向了」）。
     *
     * 与 [endPlan] 的区别只在记账：两个都把计划和 process 置成 ended，放弃额外写
     * `process.abandoned_at`，[plans] 里映射成 `status = abandoned`，计划页据此显示
     * 「已放弃」而不是「已结束」。放弃后计划不能再排期、不能再加单元（status 已是 ended）。
     *
     * 允许 active / paused；已经 ended 的再放弃返回 E_STATE。
     */
    fun abandonPlan(env: CommandEnvelope, planId: String, reason: String = ""): CommandResult<Unit>

    /**
     * 从计划列表里删除一个「已放弃」的计划（软删除）。
     *
     * 只删可见性：`process.deleted_at` 写时间戳，[plans] / [activePlanId] 不再返回它，
     * 计划页也打不开；底层事件与计划数据保留在库里用于追溯（事件溯源不物理删）。
     * 只允许 [abandonPlan] 过的计划；进行中/暂停/正常结束的计划返回 E_STATE。
     */
    fun deletePlan(env: CommandEnvelope, planId: String): CommandResult<Unit>

    /**
     * 幂等替换当前版本的阶段列表。
     *
     * 校验：ISO 周格式、weekStart <= weekEnd、区间落在计划已承诺的周范围内
     * （计划还没有任何承诺时允许任意合法区间，空壳计划先定阶段就靠这条）、阶段名不重复。
     * 返回写入的阶段数。
     */
    fun setStages(env: CommandEnvelope, planId: String, stages: List<StageDraft>): CommandResult<Int>

    // ---------------- 计划文档同步 ----------------

    /**
     * 给当前生效版本**追加**一批单元（计划文档同步 / 项目拆解用）。
     *
     * 用途：计划文档（Markdown）里新冒出来的条目 —— 「计算机网络 3 节」「物理 2 节」这种 ——
     * 变成计划里能排期、能勾的 [UnitDraft]，落进「待安排池」，由用户/AI 之后决定铺到哪天。
     * 追加的单元**不自动进任何承诺**，所以不会凭空出现在日历上；要排期请走 [placeUnit] /
     * [changeCommitment]。
     *
     * 为什么不新开一个版本：单元身份按版本隔离，新开一版会换掉 unit id，已经铺过天的
     * occurrence 还挂在旧 id 上，会把「已完成」对丢。这里是纯 INSERT，不动任何旧行。
     *
     * **不可撤销**：非草稿版本的 `plan_unit` 由触发器禁止 UPDATE/DELETE（只允许 INSERT），
     * 追加进去的单元既删不掉也改不了，所以调用方写入前必须让用户确认。
     * 同理也没有 undo token 可退回。
     *
     * 约束：
     * - 计划必须存在、未 `ended`，且已有生效版本（没有就先 [saveDraft] + [acceptDraft]）；
     * - [drafts] 非空、最多 50 条；每条 title 非空；
     * - 估算时长按 `plan_unit` 的列约束夹到 10..45 分钟，minVersionMinutes 夹到 5..15；
     * - 章节为空时记成「未分类」；`sort_order` 接当前版本最大值往后排。
     *
     * @return 新单元的 id，顺序与 [drafts] 一致；`dryRun` 时只校验不落库。
     */
    /**
     * 建一个**空壳计划**：有 process/plan/生效版本，但还没有任何单元。
     *
     * 「由面到点」的第一步只出大纲（阶段、里程碑、计划文档）；具体的单元等每周
     * `plan_week` 排计划时再追加进来。空壳计划可以被 `appendUnits` 继续填。
     */
    fun createShell(env: CommandEnvelope, title: String, goal: String, deadline: String? = null): CommandResult<CreatedProcess>

    suspend fun appendUnits(env: CommandEnvelope, planId: String, drafts: List<UnitDraft>): CommandResult<List<String>>

    /**
     * 替换一个单元的整套资料（B 站分集 / 网页 / 文档…）。
     *
     * 全量替换而不是增删单条：资料列表很短、来源是 AI 一次性给的，按整表换最简单，
     * 重复调用结果一致（幂等）。单元必须属于当前生效版本；`kind` 归一成
     * bilibili | web | doc | other。返回写入的资料条数。
     */
    fun setUnitResources(env: CommandEnvelope, planId: String, unitId: String, resources: List<ResourceDraft>): CommandResult<Int>

    // ---------------- 日层命令（只减/移/缩，不加） ----------------

    fun scheduleUnit(env: CommandEnvelope, commitmentId: String, unitId: String, day: String): CommandResult<OccurrenceRef>

    /**
     * 把「待安排池」里的单元直接放进某一周的某一天（拖拽重排用）。
     *
     * 与 [scheduleUnit] 不同：拖出来的欠账单元不在任何「本周及以后」的承诺里，这里先把单元
     * 追加进目标周的承诺（INSERT 新行，不改旧行），再铺一条 occurrence；目标周还没有承诺时
     * 顺手建一条。只接受本周及以后的日期。
     */
    fun placeUnit(
        env: CommandEnvelope,
        planId: String,
        week: String,
        unitId: String,
        day: String,
    ): CommandResult<OccurrenceRef>

    fun moveOccurrence(env: CommandEnvelope, occurrenceId: String, toDay: String, reason: String? = null): CommandResult<OccurrenceRef>

    fun splitOccurrence(env: CommandEnvelope, occurrenceId: String, parts: List<SplitPart>): CommandResult<List<OccurrenceRef>>

    fun shortenOccurrence(env: CommandEnvelope, occurrenceId: String, toEstimateMinutes: Int): CommandResult<OccurrenceRef>

    fun skipOccurrence(env: CommandEnvelope, occurrenceId: String, reasonChip: String): CommandResult<Unit>

    fun completeOccurrence(env: CommandEnvelope, occurrenceId: String, note: String? = null): CommandResult<OccurrenceRef>

    /** 把「已完成」改回「没做」（误点恢复）。只允许 done → planned。 */
    fun uncompleteOccurrence(env: CommandEnvelope, occurrenceId: String): CommandResult<OccurrenceRef>

    fun undoLast(env: CommandEnvelope, undoToken: String): CommandResult<Unit>

    fun startSession(env: CommandEnvelope, occurrenceId: String, source: String = "manual"): CommandResult<SessionRef>

    fun endSession(env: CommandEnvelope, sessionId: String): CommandResult<Unit>

    // ---------------- 待办 ----------------

    fun addTodo(env: CommandEnvelope, title: String, estimateMinutes: Int? = null): CommandResult<TodoRef>

    fun completeTodo(env: CommandEnvelope, todoId: String): CommandResult<Unit>

    fun closeTodo(env: CommandEnvelope, todoId: String): CommandResult<Unit>

    /** 待办排期：复制成 occurrence（relation=scheduled_from），待办转 scheduled。 */
    fun scheduleTodo(env: CommandEnvelope, todoId: String, day: String, planId: String, commitmentId: String, estimateMinutes: Int = 25): CommandResult<OccurrenceRef>

    // ---------------- 周节奏（系统命令，幂等） ----------------

    /**
     * 把当前版本某一周的承诺铺到天（一天一个单元；已有 occurrence 则不动）。
     *
     * 触发时机由调用方决定：这周变成当周、或周日提前铺下周。幂等，可重复调。
     */
    fun scheduleWeek(env: CommandEnvelope, week: String): CommandResult<Unit>

    fun settleWeek(env: CommandEnvelope, planId: String, week: String, today: String): CommandResult<SettleResult>

    fun generateReview(env: CommandEnvelope, planId: String, week: String, today: String): CommandResult<ReviewRef>

    /**
     * 让计划自己往前滚一格（系统节奏，幂等）。
     *
     * 一次做四件事：①给「本周 / 下周」补承诺（从待安排池里取，已存在就跳过）；
     * ②结算所有已经结束却还没结账的周（missed、最多结转 2 个）；
     * ③为刚结账的周生成复盘；④把本周（周日额外加下周）铺到天。
     *
     * 只做「已经该发生」的事：没有实际动作时不写库、不写事件，返回空结果 + 一句 note。
     * 触发时机由调用方决定（应用启动、进计划页、跨天），不是定时器；失败只返回错误，
     * 不影响页面其它数据。
     */
    fun advanceRhythm(env: CommandEnvelope, planId: String): CommandResult<RhythmResult>

    // ---------------- 迁移 ----------------

    fun exportJsonl(planId: String): String

    fun importJsonl(jsonl: String): CommandResult<Unit>

    /**
     * 关掉这一份服务（释放 SQLite 连接）。
     *
     * 正常退出可以不调；调试里要删库（`DataWipe`）时必须先关，否则文件句柄还开着。
     * 关掉之后这个实例不能再用，调用方应把引用置空、下次重新打开。
     */
    fun close()
}

/** 草稿里的一章/一个单元；时长 10–45 分钟。 */
@kotlinx.serialization.Serializable
data class UnitDraft(
    val chapter: String,
    val title: String,
    val estimateMinutes: Int = 25,
    val minVersionMinutes: Int = 10,
    /** 单元类型：study 学习 / practice 实践 / review 复习。 */
    val kind: String = "study",
    val materialSegmentId: String? = null,
)

/** 草稿里的一周承诺：`week` 是 ISO 周，`unitIndexes` 指向 [UnitDraft] 列表下标。 */
@kotlinx.serialization.Serializable
data class CommitmentDraft(
    val week: String,
    val unitIndexes: List<Int>,
)

@kotlinx.serialization.Serializable
data class SplitPart(val day: String, val estimateMinutes: Int)

/** 一个学习阶段：ISO 周区间 + 一句重点；`sortOrder` 省略时按列表顺序排。 */
@kotlinx.serialization.Serializable
data class StageDraft(
    val name: String,
    val weekStart: String,
    val weekEnd: String,
    val focus: String = "",
    val sortOrder: Int = 0,
)

/** 单元的一条资料：`kind` 由后端归一成 bilibili / web / doc / other。 */
@kotlinx.serialization.Serializable
data class ResourceDraft(
    val kind: String,
    val title: String,
    val url: String = "",
    val locator: String = "",
    val source: String = "",
)
