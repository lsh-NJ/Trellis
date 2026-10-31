package dev.trellis.feature.goal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 插件的六条硬规则。每条都是「可执行的定义」，不是「大致做到」。
 *
 * 这些断言刻意不碰 UI、不碰内核：规则本身必须是纯的，
 * 否则它只能在某个特定界面、某个特定时钟下成立。
 */
class GoalRulesTest {

    private val today = "2026-10-29"
    private val tomorrow = "2026-10-30"

    private fun academicVague(): GoalMirror = GoalEngine.start(
        mirror = GoalMirror(),
        statement = "我想考研",
        kind = GoalKind.ACADEMIC,
        clarity = Clarity.VAGUE,
        today = today,
        nowMs = 0L,
    )

    private fun fillAll(mirror: GoalMirror, exclude: Set<FieldKey> = emptySet()): GoalMirror {
        var out = mirror
        RequiredFields.of(out.contract.kind)
            .filter { it !in exclude }
            .forEachIndexed { index, key ->
                out = GoalEngine.answer(out, key, "已确认-" + key.name, FieldState.CONFIRMED, index.toLong())
            }
        return out
    }

    // ---------------------------------------------------------------- 1 置信度门

    @Test
    fun missingTwoFieldsKeepsAskingThenStops() {
        val required = RequiredFields.of(GoalKind.ACADEMIC)
        val partial = fillAll(academicVague(), exclude = setOf(required[0], required[1]))

        assertTrue(Confidence.of(partial.contract) < Confidence.STOP, "缺 2 个必备字段不该到线")
        assertTrue(GoalEngine.questions(partial).isNotEmpty(), "缺字段时必须继续提问")
        assertEquals(2, GoalEngine.questions(partial).size, "每轮最多问 2 个")

        val full = fillAll(partial)
        val score = Confidence.of(full.contract)
        assertTrue(score >= Confidence.STOP, "补齐后应到线，实际 " + score)
        assertTrue(GoalEngine.questions(full).isEmpty(), "到线后必须停止提问")
    }

    @Test
    fun skippingTwiceDowngradesTheWeightInsteadOfAskingForever() {
        var mirror = academicVague()
        val key = RequiredFields.of(GoalKind.ACADEMIC).first()

        mirror = GoalEngine.skip(mirror, key, 0L)
        assertEquals(1.0, mirror.contract.field(key).weight, "跳过一次还不降权")

        mirror = GoalEngine.skip(mirror, key, 1L)
        assertEquals(0.5, mirror.contract.field(key).weight, "第二次跳过要降到 0.5")
        // 降到 0.5 之后它排到队尾，不会一直占着「下一个要问的位置」。
        assertEquals(key, GoalEngine.questions(mirror, limit = 8).last())
    }

    @Test
    fun modelAdjustmentNeedsAReasonAndIsClamped() {
        val filled = fillAll(academicVague())
        val noReason = filled.contract.copy(adjustment = 0.05, adjustmentReason = "")
        assertEquals(0.0, Confidence.effectiveAdjustment(noReason), "没写理由的调整不算")

        val clamped = filled.contract.copy(adjustment = 0.5, adjustmentReason = "用户补充了口头信息")
        assertEquals(0.05, Confidence.effectiveAdjustment(clamped), "最多只能动 0.05")
    }

    // ---------------------------------------------------------------- 2 通用性

    @Test
    fun vaguePlansNeverNameSchoolsOrCompanies() {
        // 用户原话里就有「清华」，但清晰度还是 VAGUE —— 这时替他挑学校就是越界。
        val mirror = GoalEngine.start(
            GoalMirror(),
            "我想考清华，但还没定",
            GoalKind.ACADEMIC,
            Clarity.VAGUE,
            today,
            0L,
        )
        val milestone = GoalPlanner.defaultMilestone(mirror.contract)

        val plan = GoalPlanner.planFor(mirror.contract, today, 90, milestone)
        assertTrue(plan.entries.isNotEmpty(), "必须给出条目，不能因为不明确就不排")
        plan.entries.forEach { entry ->
            val hit = EntityLexicon.hits(entry.text + entry.output + entry.doneCriteria)
            assertTrue(hit.isEmpty(), "VAGUE 状态下出现了具体实体 " + hit + "：" + entry)
        }
    }

    @Test
    fun vaguePlansCarryADecisionTaskInsteadOfGuessing() {
        val mirror = academicVague().copy(
            contract = academicVague().contract.copy(
                unknownDecisions = listOf("目标院校"),
            ),
        )
        val milestone = GoalPlanner.defaultMilestone(mirror.contract)
        val plan = GoalPlanner.planFor(mirror.contract, today, 90, milestone, dayIndex = 0)

        val decision = plan.entries.firstOrNull { it.kind == EntryKind.DECISION }
        assertNotNull(decision, "有未决策项时必须排一条决策任务，而不是等它消失")
        assertTrue(decision.text.startsWith("查"), "决策任务要动词开头：" + decision.text)
        assertTrue(decision.output.isNotBlank(), "决策任务也要有产出")
    }

    @Test
    fun aVagueGoalGetsItsUndecidedForkWrittenDownAndScheduled() {
        // 「想考研」时谁都不知道考哪儿。这条断言守的是：它必须**被写下来并排进今天**，
        // 而不是被悄悄跳过 —— 跳过等于把「选学校」这件事永远留给以后。
        val drafted = GoalEngine.toDraft(fillAll(academicVague()), today, 90)

        assertTrue(drafted.contract.unknownDecisions.isNotEmpty(), "VAGUE 必须留下未决策项")
        val decision = drafted.dayPlan(today)!!.entries.firstOrNull { it.kind == EntryKind.DECISION }
        assertNotNull(decision, "未决策项要变成今天的决策任务：" + drafted.dayPlan(today)!!.entries.map { it.text })
        assertTrue(decision.text.startsWith("查"), "决策任务动词开头：" + decision.text)
        assertTrue(
            EntityLexicon.hits(decision.text + decision.output).isEmpty(),
            "决策任务里不许出现具体院校/公司：" + decision.text,
        )
    }

    @Test
    fun anUndecidedForkSomeoneAlreadyWroteIsNotOverwritten() {
        val mirror = fillAll(academicVague()).let {
            it.copy(contract = it.contract.copy(unknownDecisions = listOf("要不要脱产备考")))
        }
        val drafted = GoalEngine.toDraft(mirror, today, 90)
        assertEquals(
            listOf("要不要脱产备考"),
            drafted.contract.unknownDecisions,
            "用户/模型写过的分叉优先，模板不许覆盖",
        )

        // CONCRETE 不再推断：对象已经说死了，还问「去哪儿」是没听懂。
        val concrete = mirror.copy(contract = mirror.contract.copy(clarity = Clarity.CONCRETE, unknownDecisions = emptyList()))
        assertTrue(
            GoalEngine.toDraft(concrete, today, 90).contract.unknownDecisions.isEmpty(),
            "CONCRETE 不该再凭空生成分叉",
        )
    }

    @Test
    fun concretePlansMayNameTheTarget() {
        val concrete = GoalEngine.start(
            GoalMirror(),
            "考前要拿到目标学校的复试资格",
            GoalKind.ACADEMIC,
            Clarity.CONCRETE,
            today,
            0L,
        ).let { m ->
            m.copy(
                contract = m.contract.copy(
                    statement = "考清华计算机",
                    clarity = Clarity.CONCRETE,
                ),
            )
        }
        val plan = GoalPlanner.planFor(
            concrete.contract,
            today,
            90,
            GoalPlanner.defaultMilestone(concrete.contract),
        )
        val named = plan.entries.filter { EntityLexicon.hasEntity(it.text + it.output) }
        assertTrue(named.isNotEmpty(), "CONCRETE 时应当给出针对性条目：" + plan.entries.map { it.text })
    }

    // ---------------------------------------------------------------- 3 视野窗口

    @Test
    fun askingBeyondTomorrowReturnsNothingWithAReason() {
        val book = mapOf(
            today to DayPlan(today, listOf(entry("a"))),
            tomorrow to DayPlan(tomorrow, listOf(entry("b"))),
            "2026-10-31" to DayPlan("2026-10-31", listOf(entry("c"))),
        )

        val beyond = DayWindow.select(book, today, today, "2026-10-31")
        assertTrue(beyond is WindowResult.OutOfWindow, "today + 2 必须被拒绝，实际 " + beyond)
        beyond as WindowResult.OutOfWindow
        assertTrue(beyond.reason.contains("超出视野"), "要给原因：" + beyond.reason)
        assertTrue(beyond.reason.contains(tomorrow), "原因里要说清边界是哪天：" + beyond.reason)

        val inside = DayWindow.select(book, today, today, tomorrow)
        assertTrue(inside is WindowResult.Ok, "today + 1 应当可见")
        assertEquals(2, (inside as WindowResult.Ok).days.size)
    }

    @Test
    fun visibleDaysStopsAtTomorrow() {
        val existing = setOf("2026-10-27", today, tomorrow, "2026-10-31")
        val visible = DayWindow.visibleDays(today, existing)
        assertEquals(listOf("2026-10-27", today, tomorrow), visible)
    }

    @Test
    fun settlingDropsAnythingBeyondTomorrow() {
        val mirror = fillAll(academicVague()).let { m ->
            GoalEngine.confirm(GoalEngine.toDraft(m, today, 60))
        }
        val polluted = mirror.copy(
            days = mirror.days + ("2026-11-02" to DayPlan("2026-11-02", listOf(entry("late")))),
        )
        val settled = GoalEngine.settle(polluted, today, 60)
        assertTrue(settled.mirror.days.keys.none { it > tomorrow }, "越界日期要清掉：" + settled.mirror.days.keys)
    }

    // ---------------------------------------------------------------- 4 密度

    @Test
    fun entriesWithoutMinutesAreRejected() {
        val bad = DayPlan(today, listOf(entry("a").copy(minutes = 0), entry("b")))
        val rejections = DayPlanValidator.validate(bad, 60)
        assertTrue(rejections.any { it.field.endsWith("minutes") }, "缺 minutes 必须拒绝：" + rejections)
    }

    @Test
    fun overloadBeyondTheDailyBudgetIsRejected() {
        val heavy = DayPlan(today, listOf(entry("a").copy(minutes = 50), entry("b").copy(minutes = 50)))
        val rejections = DayPlanValidator.validate(heavy, 60)
        assertTrue(rejections.any { it.field == "totalMinutes" }, "超过 1.2 倍必须拒绝：" + rejections)
        assertEquals(100, heavy.totalMinutes)
        assertTrue(60 * DayPlanValidator.OVERLOAD_RATIO < 100, "100 分钟确实超过 72 的上限")
    }

    @Test
    fun hollowOutputAndVagueWordingAreRejected() {
        val hollow = DayPlan(
            today,
            listOf(entry("a").copy(output = "复习完这一章"), entry("b")),
        )
        assertTrue(
            DayPlanValidator.validate(hollow, 60).any { it.field.endsWith("output") },
            "「复习完」不是产出",
        )

        val vague = DayPlan(
            today,
            listOf(entry("a").copy(text = "看点相关资料"), entry("b")),
        )
        assertTrue(
            DayPlanValidator.validate(vague, 60).any { it.field.endsWith("text") },
            "含模糊词要拒绝",
        )
    }

    @Test
    fun generatedPlansAlwaysPassTheirOwnValidator() {
        // 生成器写出来的东西如果过不了自己的校验，用户看到的就是「排不出来」。
        // 这条把「模板」和「规则」钉在一起：改任何一边，这里会先红。
        listOf(GoalKind.GENERAL, GoalKind.ACADEMIC, GoalKind.JOB).forEach { kind ->
            listOf(15, 60, 90, 240).forEach { minutes ->
                val contract = GoalContract(statement = "测试目标", kind = kind, clarity = Clarity.VAGUE)
                val plan = GoalPlanner.planFor(
                    contract,
                    today,
                    minutes,
                    GoalPlanner.defaultMilestone(contract),
                )
                val rejections = DayPlanValidator.validate(plan, minutes)
                assertTrue(
                    rejections.isEmpty(),
                    kind.name + " / " + minutes + " 分钟生成的计划被自己的校验拒绝：" + rejections,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 5 压缩

    @Test
    fun thirtyDaysStillFitIntoEightHundredChars() {
        var mirror = fillAll(academicVague()).let { GoalEngine.confirm(GoalEngine.toDraft(it, today, 60)) }
        // 造 30 天历史：每天一条日摘要 + 一条计划。
        val days = mirror.days.toMutableMap()
        val digests = mutableListOf<DayDigest>()
        for (offset in 30 downTo 1) {
            val date = IsoDate.plusDays(today, -offset)!!
            val plan = DayPlan(
                date,
                listOf(
                    entry("h1-" + offset).copy(done = true),
                    entry("h2-" + offset).copy(done = offset % 2 == 0),
                    entry("h3-" + offset).copy(done = false),
                ),
            )
            days[date] = plan
            digests += GoalCompressor.dayDigest(plan, reason = "时间不足")
        }
        mirror = mirror.copy(days = days, dayDigests = digests)

        val block = GoalEngine.contextBlock(mirror, today)
        assertTrue(
            block.length <= GoalCompressor.CONTEXT_BUDGET,
            "30 天记录压缩后必须 ≤ " + GoalCompressor.CONTEXT_BUDGET + "，实际 " + block.length,
        )
        assertTrue(block.contains("【目标】"), "目标契约不能被砍掉")
        assertTrue(block.contains("【今天】"), "今天的条目最后才砍，应当还在：" + block)
        assertTrue(block.contains(today), "要能看出是哪一天")
    }

    // ---------------------------------------------------------------- 6 补推幂等

    @Test
    fun pushHappensOncePerDayEvenAfterRestart() {
        val active = fillAll(academicVague()).let { GoalEngine.confirm(GoalEngine.toDraft(it, today, 60)) }

        // 08:00 之前不推。
        assertNull(GoalEngine.pushDue(active, today, 7, 59), "没到点不该推")

        val first = GoalEngine.pushDue(active, today, 8, 0)
        assertNotNull(first, "到点要推")
        assertTrue(first.text.contains("件事"), "推送文案要说清今天几件事：" + first.text)

        // 同一天再启动一次：不重复。
        assertNull(GoalEngine.pushDue(first.mirror, today, 9, 30), "同一天不能推第二次")
        assertNull(GoalEngine.pushDue(first.mirror, today, 23, 59), "一天结束前都不能再推")

        // 时钟跳到次日：补推一次（应用重新打开时走的就是这条路）。
        val nextDay = IsoDate.plusDays(today, 1)!!
        val second = GoalEngine.pushDue(first.mirror, nextDay, 9, 0)
        assertNotNull(second, "次日要能补推一次")
        assertEquals(nextDay, second.mirror.lastPushedDate)
    }

    // ---------------------------------------------------------------- 8 排期用谁的时间

    @Test
    fun thePlanUsesTheGoalsOwnDailyBudgetNotTheGlobalSetting() {
        fun contractWith(value: String): GoalContract = GoalEngine.start(
            GoalMirror(), "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L,
        ).contract.let { c ->
            c.copy(profile = c.profile + (FieldKey.DAILY_MINUTES to Field(value, FieldState.CONFIRMED)))
        }

        // 全局设置是 15 分钟，但用户自己答了 4 小时 —— 排期听用户的。
        assertEquals(240, GoalEngine.budgetOf(contractWith("4 小时以上"), 15))
        assertEquals(90, GoalEngine.budgetOf(contractWith("90 分钟"), 15))
        assertEquals(120, GoalEngine.budgetOf(contractWith("两小时"), 15))
        assertEquals(180, GoalEngine.budgetOf(contractWith("三小时"), 15))
        // 没答过才用全局值兜底。
        assertEquals(15, GoalEngine.budgetOf(contractWith(""), 15))

        val planned = GoalEngine.toDraft(
            fillAll(academicVague()).let { m ->
                m.copy(contract = m.contract.copy(
                    profile = m.contract.profile + (FieldKey.DAILY_MINUTES to Field("4 小时以上", FieldState.CONFIRMED)),
                ))
            },
            today,
            15,
        )
        val total = planned.dayPlan(today)!!.totalMinutes
        assertTrue(
            total > 15 * 1.2,
            "全局是 15 分钟、目标是 4 小时，排出来不该还是十几分钟：" + total,
        )
    }

    // ---------------------------------------------------------------- 7 实际投入

    @Test
    fun withoutActivityDataTheEffortLineNeverAppears() {
        val active = fillAll(academicVague()).let { GoalEngine.confirm(GoalEngine.toDraft(it, today, 60)) }

        // 0 分不是「没干活」，更常见的是「没同步」——所以它连显示资格都没有。
        assertTrue(!TodayEffort(0, hasAfkData = true).visible, "0 分钟不显示")
        assertTrue(TodayEffort(12, hasAfkData = true).visible, "有分钟的才显示")

        val none = GoalEngine.contextBlock(active, today, null)
        assertTrue(!none.contains("【投入】"), "没有活动数据就不该出现投入这一行：" + none)

        val zero = GoalEngine.contextBlock(active, today, TodayEffort(0, hasAfkData = true))
        assertTrue(!zero.contains("【投入】"), "0 分钟同样不出现：" + zero)
    }

    @Test
    fun effortIsShownForComparisonButNeverReschedulesTomorrow() {
        val active = fillAll(academicVague()).let { GoalEngine.confirm(GoalEngine.toDraft(it, today, 60)) }
        val plannedToday = active.dayPlan(today)!!.totalMinutes

        val block = GoalEngine.contextBlock(active, today, TodayEffort(62, hasAfkData = true))
        assertTrue(block.contains("【投入】"), "有数据就该显示：" + block)
        assertTrue(
            block.contains("计划 " + plannedToday + " 分 · 实际 62 分"),
            "要给出计划/实际的对照：" + block,
        )
        assertTrue(block.contains("不参与次日排期"), "必须说清这个数不能拿来改计划：" + block)
        assertTrue(
            block.length <= GoalCompressor.CONTEXT_BUDGET,
            "加上投入这一行也不能撑破 " + GoalCompressor.CONTEXT_BUDGET + "，实际 " + block.length,
        )

        // 排期是承诺：昨天一条没勾，新的一天还是按每日投入排，不会自己缩水。
        // settle() 的入参里根本没有「实际投入」这一项，这是结构上的保证，不是巧合。
        val nextToday = IsoDate.plusDays(today, 1)!!
        val settled = GoalEngine.settle(active, nextToday, 60).mirror
        val fresh = GoalPlanner.planFor(
            settled.contract,
            IsoDate.plusDays(nextToday, 1)!!,
            60,
            settled.milestone!!,
            dayIndex = 0,
        )
        assertTrue(
            fresh.totalMinutes >= 40,
            "昨天没完成不该让今天的计划缩水，实际只排了 " + fresh.totalMinutes + " 分钟",
        )
    }

    @Test
    fun windowOnlyDataIsLabelledInsteadOfBeingTrusted() {
        val active = fillAll(academicVague()).let { GoalEngine.confirm(GoalEngine.toDraft(it, today, 60)) }

        // 只装了窗口采集：activeMs 就是「窗口开着」，人在不在不知道。界面和上下文都要说出来。
        val block = GoalEngine.contextBlock(active, today, TodayEffort(62, hasAfkData = false))
        assertTrue(block.contains("仅窗口统计，可能偏高"), "没有 AFK 记录时必须标注：" + block)

        val trusted = GoalEngine.contextBlock(active, today, TodayEffort(62, hasAfkData = true))
        assertTrue(!trusted.contains("仅窗口统计"), "有 AFK 记录就不该再标：" + trusted)
    }

    // ---------------------------------------------------------------- 附带

    private fun entry(id: String): DayEntry = DayEntry(
        id = id,
        text = "背 30 个单词",
        output = "一页默写纸",
        minutes = 20,
        goalRef = "milestone-1",
        doneCriteria = "能默写出来",
    )
}
