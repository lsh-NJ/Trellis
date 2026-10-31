package dev.trellis.app.desktop

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 计划检查主动消息的端到端验收：上一周排满没做完 → 打开应用 → 「计划检查」会话里出现一条
 * 未读的确定性摘要；同一天再触发不重复发；打开会话即已读。
 *
 * 这里不起界面，只把后端与 AppRuntime 串起来（和 [PlanRepairTest] 同一套路）。
 */
class PlanCheckRuntimeTest {

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-plan-check").toFile().apply { deleteOnExit() }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.plan.check",
    )

    private fun weekOf(date: LocalDate): String =
        "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    @Test
    fun theProactivePlanCheckLandsOncePerDayAndClearsWhenRead() = runTest {
        val home = tempHome()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastWeek = weekOf(monday.minusWeeks(1))

        // 上一周排满十个单元，一个都没做：铺到天后下一次推进结算会留下欠账。
        val store = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            System::currentTimeMillis,
            ZoneId.systemDefault(),
        )
        val created = store.createProcess(
            env(),
            "计划检查测试",
            "看看上一周落下了什么",
            weeklyCapacityMinutes = 60 * 7,
        ).unwrap()
        val units = (1..20).map { UnitDraft("第 1 章", "第 $it 节", 20) }
        val draft = store.saveDraft(
            env(created.planId),
            created.planId,
            null,
            units,
            listOf(
                CommitmentDraft(lastWeek, (0..9).toList()),
                // 下一周已经排了另一半：这样结算留下的欠账不会被「补下一周」自动收走。
                CommitmentDraft(weekOf(monday.plusWeeks(1)), (10..19).toList()),
            ),
        ).unwrap()
        store.acceptDraft(env(created.planId), draft.versionId, lastWeek).unwrap()
        // 手动把上一周铺到天：acceptDraft 只保证「生效」，过期周不会被自动铺。
        store.scheduleWeek(env(created.planId), lastWeek).unwrap()

        val runtime = AppRuntime.create(home)
        try {
            // refreshPlan 会推进节奏：先把欠账单元补进本周承诺，再结算上一周（missed / 结转 / 欠账）。
            runtime.refreshPlan()
            runtime.maybePostPlanCheck()

            val session = ChatStore(home).read(PLAN_CHECK_SESSION_ID)
            assertNotNull(session, "有欠账就该留下一条计划检查消息")
            assertEquals(PLAN_CHECK_TITLE, session.title)
            assertTrue(session.unread, "主动消息应当标未读")
            val line = assertNotNull(session.lines.singleOrNull(), "同一天只该发一条")
            assertTrue(line.text.contains("计划检查"), "摘要要能看出是计划检查：${line.text}")
            assertEquals(ChatRole.Assistant, line.role)

            val before = session.lines.size
            runtime.maybePostPlanCheck()
            assertEquals(before, ChatStore(home).read(PLAN_CHECK_SESSION_ID)?.lines?.size, "同一天再触发不该重复发")
            assertTrue(runtime.hasUnreadChat)

            runtime.markChatRead(PLAN_CHECK_SESSION_ID)
            assertFalse(ChatStore(home).read(PLAN_CHECK_SESSION_ID)!!.unread, "打开会话即已读")
            assertFalse(runtime.hasUnreadChat)
        } finally {
            runtime.shutdown()
        }
    }
}
