package dev.trellis.app.desktop

import dev.trellis.feature.goal.Clarity
import dev.trellis.feature.goal.Confidence
import dev.trellis.feature.goal.DayState
import dev.trellis.feature.goal.DayWindow
import dev.trellis.feature.goal.EntityLexicon
import dev.trellis.feature.goal.EntryKind
import dev.trellis.feature.goal.FieldKey
import dev.trellis.feature.goal.FieldState
import dev.trellis.feature.goal.GoalEngine
import dev.trellis.feature.goal.GoalKind
import dev.trellis.feature.goal.GoalPhase
import dev.trellis.feature.goal.GoalService
import dev.trellis.feature.goal.IsoDate
import dev.trellis.feature.goal.RequiredFields
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 两条演示路径，跑在**真内核 + 真 kv + 真服务**上。
 *
 * 为什么不用手点界面：这两条路径的关键是「第几轮之后状态变成什么」，
 * 点界面点不出来，只能按时序推。这里每一步都对应一个具体动作
 * （start / answer / toDraft / confirm / refresh），和用户在对话里连说三轮是同一串状态转移。
 *
 * 和纯逻辑测试的区别：这一份会真的写进 `ctx.kv`（重启后还在），用的是宿主注入的
 * 「今天」和「每日投入」，所以它证明的是**接起来能用**，不只是**函数各算各的对**。
 */
class GoalDemoPathTest {

    private val today: String = LocalDate.now().toString()

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-goal-demo").toFile().apply { deleteOnExit() }

    private fun serviceOf(runtime: AppRuntime): GoalService {
        val plugin = assertNotNull(runtime.goalPlugin, "宿主必须留着插件实例")
        return assertNotNull(plugin.serviceOrNull(), "插件启动过了，服务不该是空")
    }

    /** 演示时用户会说的话。写得像真人，才不会把「尴尬的空字段」当成设计问题。 */
    private fun demoAnswer(key: FieldKey): String = when (key) {
        FieldKey.DAILY_MINUTES -> "90 分钟"
        FieldKey.DEADLINE -> "2026-12-20"
        FieldKey.CURRENT_LEVEL -> "专业课零基础，英语过了四级"
        FieldKey.HARD_CONSTRAINTS -> "在职，只有晚上和周末"
        FieldKey.MAJOR -> "计算机"
        FieldKey.TARGET_TIER -> "211 及以上"
        FieldKey.WEAK_SUBJECTS -> "数学一"
        FieldKey.EXAM_SUBJECTS -> "政治、英语一、数学一、408"
        FieldKey.TARGET_ROLE -> "后端开发"
        FieldKey.TARGET_INDUSTRY -> "互联网"
        FieldKey.EXPERIENCE -> "两年 Java"
        FieldKey.PORTFOLIO -> "GitHub 上有一个项目"
    }

    // ---------------------------------------------------------------- 用户 A：目标模糊

    @Test
    fun userA_threeRoundsThenAGenericPlanWithADecisionTask() = runTest {
        val runtime = AppRuntime.create(tempHome())
        val service = serviceOf(runtime)

        // 第 1 轮：用户只说了「我想考研」。原话不改写，先进采集。
        service.update { GoalEngine.start(it, "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L) }
        var mirror = service.mirror()
        assertEquals("我想考研", mirror.contract.statement, "原话要一字不改地留着")
        assertEquals(GoalPhase.COLLECTING, mirror.phase)
        assertEquals(2, GoalEngine.questions(mirror).size, "每轮最多问 2 个")
        println("[用户 A] 第 1 轮问题：" + GoalEngine.questions(mirror).map { it.label })

        // 第 2 轮起：按 questions() 给的顺序答，每轮最多 2 个。
        var round = 0
        while (true) {
            val asking = GoalEngine.questions(service.mirror())
            if (asking.isEmpty()) break
            round += 1
            // 第 1 轮是「说出目标」，所以这一句问的是第 round + 1 轮。
            println("[用户 A] 第 " + (round + 1) + " 轮问题：" + asking.map { it.label })
            service.update { m ->
                val answered = asking.fold(m) { acc, key ->
                    GoalEngine.answer(acc, key, demoAnswer(key), FieldState.CONFIRMED, round.toLong())
                }
                answered.copy(collectingRounds = m.collectingRounds + 1)
            }
        }
        mirror = service.mirror()
        assertTrue(GoalEngine.shouldStopAsking(mirror), "必须停下来出计划，不能一直追问")
        assertTrue(round <= Confidence.MAX_ROUNDS, "采集轮数不能超过 " + Confidence.MAX_ROUNDS + "，实际 " + round)

        // 出通用计划 → 用户点「就这样开始」。
        service.update { GoalEngine.confirm(GoalEngine.toDraft(it, today, 90)) }
        mirror = service.mirror()
        assertEquals(GoalPhase.ACTIVE, mirror.phase)

        val plan = assertNotNull(mirror.dayPlan(today), "确认之后今天必须有一份安排")
        val decision = plan.entries.firstOrNull { it.kind == EntryKind.DECISION }
        assertNotNull(decision, "目标还模糊时必须排一条决策任务：" + plan.entries.map { it.text })
        println("[用户 A] 今天的安排：" + plan.entries.joinToString(" / ") { it.text + "(" + it.minutes + "分)" })

        // R4：VAGUE 下一条实体都不许出现 —— 包括那条决策任务。
        plan.entries.forEach { entry ->
            val hits = EntityLexicon.hits(entry.text + entry.output + entry.doneCriteria)
            assertTrue(hits.isEmpty(), "VAGUE 计划里出现了具体院校/公司 " + hits + "：" + entry.text)
        }
        assertTrue(plan.totalMinutes <= 90, "总时长不该超过每日投入 90 分钟，实际 " + plan.totalMinutes)

        // R3：明天看得见，后天看不见，而且越界要给理由。
        val tomorrow = assertNotNull(IsoDate.plusDays(today, 1))
        assertNotNull(mirror.dayPlan(tomorrow), "明天应当已经预生成")
        assertNull(mirror.dayPlan(IsoDate.plusDays(today, 2).orEmpty()), "后天不许存在")
        assertEquals(DayState.FUTURE, DayWindow.stateOf(IsoDate.plusDays(today, 2).orEmpty(), today))

        // R2：到点推一条，同一天不推第二条。
        service.update { m -> m.copy(settings = m.settings.copy(pushHour = 0)) }
        service.refresh()
        assertNotNull(service.pushNote, "0 点之后启动，应当补推一条今天要做的事")
        println("[用户 A] 推送：" + service.pushNote)
        assertNull(
            GoalEngine.pushDue(service.mirror(), today, 23, 59),
            "同一天第二次启动不能再推一条",
        )

        runtime.shutdown()
    }

    // ---------------------------------------------------------------- 用户 B：目标明确

    @Test
    fun userB_goesStraightToADraftWithJobSpecificEntries() = runTest {
        val runtime = AppRuntime.create(tempHome())
        val service = serviceOf(runtime)

        service.update {
            GoalEngine.start(it, "我要 6 月前拿到后端岗 offer", GoalKind.JOB, Clarity.CONCRETE, today, 0L)
        }
        RequiredFields.of(GoalKind.JOB).forEach { key ->
            service.update { GoalEngine.answer(it, key, demoAnswer(key), FieldState.CONFIRMED, 0L) }
        }

        // 明确目标：直接出草稿，停在「等确认」，不自动开工。
        service.update { GoalEngine.toDraft(it, today, 120) }
        val mirror = service.mirror()
        assertEquals(GoalPhase.DRAFT, mirror.phase, "明确目标也先给草稿，不替用户拍板")
        assertTrue(mirror.contract.unknownDecisions.isEmpty(), "CONCRETE 不该再有未决策分叉")

        val plan = assertNotNull(mirror.dayPlan(today))
        val texts = plan.entries.map { it.text }
        println("[用户 B] 今天的安排：" + texts)
        assertTrue(
            texts.any { it.contains("简历") } && texts.any { it.contains("面试") },
            "求职目标要落在简历和面试上，不能退回通用层：" + texts,
        )
        assertTrue(
            plan.entries.none { it.kind == EntryKind.DECISION && it.text.contains("院校") },
            "求职目标不该出现查院校这种学业动作：" + texts,
        )
        assertTrue(plan.totalMinutes <= 120, "总时长不该超过每日投入 120 分钟，实际 " + plan.totalMinutes)

        runtime.shutdown()
    }
}
