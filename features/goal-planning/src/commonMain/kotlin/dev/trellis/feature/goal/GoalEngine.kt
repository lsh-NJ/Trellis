package dev.trellis.feature.goal

/**
 * 插件核心的可测部分：全部是 `GoalMirror -> GoalMirror` 的纯函数。
 *
 * IO 在 [GoalKit] 里，这里只处理「状态怎么变」。分开的理由很实际：
 * 置信度、视野、压缩、推送幂等这几条规则一旦混进 `ctx.kv` 的读写，
 * 就只能起一个真实内核来测，而真实内核里时间是流动的 —— 测不出「同一天第二次不推」。
 */
object GoalEngine {

    /** 开一份新规划：记下用户原话，进入采集。 */
    fun start(
        mirror: GoalMirror,
        statement: String,
        kind: GoalKind,
        clarity: Clarity,
        today: String,
        nowMs: Long,
    ): GoalMirror {
        val contract = mirror.contract.copy(
            statement = statement.trim(),
            kind = kind,
            clarity = clarity,
            startDate = mirror.contract.startDate.ifBlank { today },
            revisionLog = mirror.contract.revisionLog + Revision(
                atMs = nowMs,
                field = "statement",
                oldValue = mirror.contract.statement,
                newValue = statement.trim(),
                source = RevisionSource.DIALOGUE,
            ),
        )
        return mirror.copy(
            phase = GoalPhase.COLLECTING,
            contract = contract,
            collectingRounds = 0,
        )
    }

    /**
     * 回答一个字段。
     *
     * 同一字段连续跳过 2 次后权重降到 0.5 —— 这不是惩罚用户，是让公式别再被它拖住，
     * 同时把它从「下一个要问的」里排到最后。
     */
    fun answer(
        mirror: GoalMirror,
        key: FieldKey,
        value: String,
        state: FieldState = FieldState.CONFIRMED,
        nowMs: Long,
    ): GoalMirror {
        val updated = mirror.contract.withField(key, value.trim(), state, RevisionSource.DIALOGUE, nowMs)
        return mirror.copy(contract = updated)
    }

    /** 跳过：记 UNKNOWN，累计跳过次数，超过 2 次降权。 */
    fun skip(mirror: GoalMirror, key: FieldKey, nowMs: Long): GoalMirror {
        val old = mirror.contract.field(key)
        val skips = old.skips + 1
        val weight = if (skips >= 2) 0.5 else old.weight
        val entry = Revision(nowMs, key.name, old.value, "（跳过）", RevisionSource.DIALOGUE)
        return mirror.copy(
            contract = mirror.contract.copy(
                profile = mirror.contract.profile + (key to Field("", FieldState.UNKNOWN, weight, skips)),
                revisionLog = (mirror.contract.revisionLog + entry).takeLast(200),
            ),
        )
    }

    /**
     * 这一轮该问哪几个。
     *
     * 每轮最多 2 个：一次抛 5 个问题，用户会全选「跳过」，然后你一个信息都拿不到。
     * 终止条件是「或」关系 —— 用户说停、轮数到顶、置信度到线，任一满足就停。
     */
    fun questions(mirror: GoalMirror, limit: Int = 2): List<FieldKey> {
        if (mirror.phase != GoalPhase.COLLECTING) return emptyList()
        if (shouldStopAsking(mirror)) return emptyList()
        return RequiredFields.missing(mirror.contract).take(limit)
    }

    fun shouldStopAsking(mirror: GoalMirror): Boolean {
        if (mirror.phase != GoalPhase.COLLECTING) return true
        if (Confidence.of(mirror.contract) >= Confidence.STOP) return true
        if (mirror.collectingRounds >= Confidence.MAX_ROUNDS) return true
        return RequiredFields.missing(mirror.contract).isEmpty()
    }

    /**
     * 出草稿：生成今天 + 明天两天。
     *
     * 只生成两天，不做 30 天预排。预排看起来很有诚意，但第三天开始就是猜的，
     * 而用户会把它当成承诺。
     */
    fun toDraft(mirror: GoalMirror, today: String, dailyMinutes: Int): GoalMirror {
        val milestone = mirror.milestone ?: GoalPlanner.defaultMilestone(mirror.contract)
        val tomorrow = IsoDate.plusDays(today, 1) ?: today
        val days = mirror.days.toMutableMap()
        days[today] = GoalPlanner.planFor(mirror.contract, today, dailyMinutes, milestone, dayIndex = 0)
        days[tomorrow] = GoalPlanner.planFor(mirror.contract, tomorrow, dailyMinutes, milestone, dayIndex = 1)
        return GoalStateMachine.move(mirror.copy(milestone = milestone), GoalPhase.DRAFT)
            .getOrElse { mirror.copy(milestone = milestone) }
            .copy(days = days, settledThrough = today, cycleStartedOn = today)
    }

    fun confirm(mirror: GoalMirror): GoalMirror =
        GoalStateMachine.move(mirror, GoalPhase.ACTIVE).getOrElse { mirror }

    /** 结算结果：变了什么，以及给人看的一句话。 */
    data class SettleResult(
        val mirror: GoalMirror,
        val changed: Boolean,
        val note: String,
    )

    /**
     * 启动/进入时结算：把昨天压成摘要，补上明天，清掉越界的未来。
     *
     * 幂等：同一天第二次调用返回 [SettleResult.changed] = false，
     * 不动数据也不写新摘要 —— 否则每次切页都会多出一条「昨天」。
     */
    fun settle(mirror: GoalMirror, today: String, dailyMinutes: Int): SettleResult {
        if (mirror.isEmpty) return SettleResult(mirror, false, "")
        val already = mirror.settledThrough == today
        val tomorrow = IsoDate.plusDays(today, 1) ?: today

        val days = mirror.days.toMutableMap()
        val digests = mirror.dayDigests.toMutableList()
        val notes = mutableListOf<String>()

        // ① 结算昨天：只结算「有安排且还没结算过」的那一天。
        val yesterday = IsoDate.plusDays(today, -1)
        if (yesterday != null) {
            val plan = days[yesterday]
            if (plan != null && digests.none { it.date == yesterday }) {
                val digest = GoalCompressor.dayDigest(plan)
                digests += digest
                notes += "昨天 " + digest.done + "/" + digest.total
            }
        }

        // ② 补明天：没有就排。
        val milestone = mirror.milestone ?: GoalPlanner.defaultMilestone(mirror.contract)
        if (days[tomorrow] == null && mirror.phase != GoalPhase.EMPTY) {
            days[tomorrow] = GoalPlanner.planFor(
                mirror.contract,
                tomorrow,
                dailyMinutes,
                milestone,
                dayIndex = days.size,
            )
            notes += "已排明天"
        }

        // ③ 清越界：任何晚于 tomorrow 的日期都不该留在库里。
        val lastVisibleEpoch = IsoDate.epochDay(tomorrow)
        if (lastVisibleEpoch != null) {
            val dropped = days.keys.filter { date ->
                val epoch = IsoDate.epochDay(date)
                epoch != null && epoch > lastVisibleEpoch
            }
            dropped.forEach { days.remove(it) }
            if (dropped.isNotEmpty()) notes += "清掉 " + dropped.size + " 天越界安排"
        }

        // ④ 周摘要：攒够 7 条就滚一次，原始日摘要保留（压缩不是删除）。
        val weeks = mirror.weekDigests.toMutableList()
        if (digests.size >= 7 && digests.size % 7 == 0 && weeks.none { it.weekOf == digests.last().date }) {
            weeks += GoalCompressor.weekDigest(digests.takeLast(7))
        }
        val phase = if (weeks.isEmpty()) mirror.phaseDigest else GoalCompressor.phaseDigest(weeks.takeLast(4))

        val next = mirror.copy(
            milestone = milestone,
            days = days,
            dayDigests = digests,
            weekDigests = weeks,
            phaseDigest = phase,
            settledThrough = today,
        )
        val changed = !already || notes.isNotEmpty()
        return SettleResult(next, changed, notes.joinToString("；"))
    }

    /** 一条推送。 */
    data class PushNote(val text: String, val mirror: GoalMirror)

    /**
     * 到点推送。同一天最多一条（[GoalMirror.lastPushedDate] 是唯一依据）。
     *
     * 应用没运行 = 没有推送；下次启动按日期键补推一次。所以这里的判据只有日期，
     * 不看「现在是不是刚好 8:00」—— 补推的时候早就过了 8 点。
     */
    fun pushDue(mirror: GoalMirror, today: String, hour: Int, minute: Int): PushNote? {
        if (mirror.phase != GoalPhase.ACTIVE) return null
        if (mirror.lastPushedDate == today) return null
        val settings = mirror.settings.normalized()
        val nowMinutes = hour * 60 + minute
        val dueMinutes = settings.pushHour * 60 + settings.pushMinute
        if (nowMinutes < dueMinutes) return null

        val plan = mirror.dayPlan(today) ?: return null
        if (plan.entries.isEmpty()) return null
        val text = "今天 " + plan.entries.size + " 件事，共 " + plan.totalMinutes + " 分钟。"
        return PushNote(text, mirror.copy(lastPushedDate = today))
    }

    /** 勾掉一条。 */
    fun complete(mirror: GoalMirror, date: String, entryId: String): GoalMirror {
        val plan = mirror.days[date] ?: return mirror
        val updated = plan.copy(
            entries = plan.entries.map { if (it.id == entryId) it.copy(done = !it.done) else it },
        )
        return mirror.copy(days = mirror.days + (date to updated))
    }

    /** 反馈周期到了没有。 */
    fun reviewDue(mirror: GoalMirror, today: String): Boolean {
        if (mirror.phase != GoalPhase.ACTIVE) return false
        val started = mirror.cycleStartedOn ?: return false
        val days = IsoDate.daysBetween(started, today) ?: return false
        return days >= mirror.settings.normalized().feedbackCycleDays
    }

    /** 删除这份规划：业务数据清空，插件本体保留，状态回 EMPTY。 */
    fun reset(mirror: GoalMirror, today: String): GoalMirror = GoalMirror(
        phase = GoalPhase.EMPTY,
        settings = mirror.settings,
        settledThrough = today,
    )

    /**
     * 进 AI 上下文的那一块。
     *
     * 结构：目标契约（谁、多清楚、什么约束、什么还没定）+ 压缩过的历史 + 今天 + 明天。
     * 总长硬限 [GoalCompressor.CONTEXT_BUDGET]；超了先砍历史，最后才砍今天。
     */
    fun contextBlock(mirror: GoalMirror, today: String): String {
        val open = "<goal_context>\n"
        val close = "\n</goal_context>"
        // 预算算的是**整块**（含标签）：调用方关心的是「塞进提示词有多长」，
        // 不是「里面那段有多长」。差这 30 个字符，800 的硬限就守不住。
        val room = GoalCompressor.CONTEXT_BUDGET - open.length - close.length

        if (mirror.isEmpty) {
            return open + "【目标】还没有目标。用户说想做什么时，先帮他把目标说清楚。" + close
        }
        val contract = renderContract(mirror.contract, today)
        val rest = GoalCompressor.contextBlock(
            phase = mirror.phaseDigest,
            days = mirror.dayDigests,
            today = mirror.dayPlan(today),
            tomorrow = IsoDate.plusDays(today, 1)?.let { mirror.dayPlan(it) },
        )
        val restRoom = (room - contract.length - 1).coerceAtLeast(80)
        val body = buildString {
            append(contract)
            if (rest.isNotBlank()) append('\n').append(GoalCompressor.clamp(rest, restRoom))
        }
        return open + GoalCompressor.clamp(body, room) + close
    }

    /** 契约摘要。最多约 300 字。 */
    fun renderContract(contract: GoalContract, today: String): String = buildString {
        append("【目标】")
        append(contract.statement.ifBlank { "（未填写）" })
        append("（").append(clarityLabel(contract.clarity)).append("，").append(kindLabel(contract.kind)).append("）")
        append('\n')
        if (contract.hardConstraints.isNotEmpty()) {
            append("【硬约束】").append(contract.hardConstraints.joinToString("、")).append('\n')
        }
        val known = contract.profile.entries
            .filter { it.value.resolved }
            .map { it.key.label + "=" + it.value.value }
        if (known.isNotEmpty()) {
            append("【已知】").append(known.joinToString("；")).append('\n')
        }
        if (contract.unknownDecisions.isNotEmpty()) {
            append("【待决策】").append(contract.unknownDecisions.joinToString("、")).append('\n')
        }
        append("【状态】")
        append(
            if (Confidence.of(contract) >= Confidence.STOP) "信息齐全"
            else "还差 " + Confidence.remaining(contract) + " 个信息",
        )
        append("；今天 ").append(today)
        append("，只能看到今天和明天（更远的不排也不看）")
    }

    fun clarityLabel(clarity: Clarity): String = when (clarity) {
        Clarity.VAGUE -> "只有方向"
        Clarity.DIRECTED -> "有方向有约束"
        Clarity.CONCRETE -> "已经明确"
    }

    fun kindLabel(kind: GoalKind): String = when (kind) {
        GoalKind.GENERAL -> "通用"
        GoalKind.ACADEMIC -> "学业"
        GoalKind.JOB -> "求职"
    }
}
