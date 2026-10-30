package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 计划管理员的手：别再只是「会建计划」，而是能读现状、勾节点、顺延、推进周节奏。
 *
 * 和 [PlanToolsTest] 的分工：那边管「新建计划写进计划页」；这边管**已经有的那份**。
 * 数据用 `LearningPlanStore.inMemory()` 真跑后端命令，不 mock —— 这几只工具的
 * 坑（未确认就写、顺延后旧节点还在、advance 补不上承诺）都只在真命令里才看得见。
 */
class PlanAdminToolsTest {

    /** 今天所在的 ISO 周；weeksFromNow=1 就是下一周。 */
    private fun isoWeek(weeksFromNow: Long = 0): String {
        val date = LocalDate.now().plusWeeks(weeksFromNow)
        return "%04d-W%02d".format(
            date.get(IsoFields.WEEK_BASED_YEAR),
            date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
    }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test",
    )

    /**
     * 一份「本周承诺 2 个单元、池里还剩 2 个」的计划，并把本周铺到天：
     * 这样 get_learning_plan 有今天的节点可读，plan_next_week 也有池子可补。
     */
    private fun seededService(): LearningPlanService {
        val svc = LearningPlanStore.inMemory()
        val created = svc.createProcess(env(), "操作系统速通", "把这门课过一遍", weeklyCapacityMinutes = 60 * 7)
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        val units = (1..4).map {
            UnitDraft(chapter = "第 1 章", title = "U$it", estimateMinutes = 20, minVersionMinutes = 10)
        }
        val draft = svc.saveDraft(
            env(planId), planId, null, units,
            listOf(CommitmentDraft(isoWeek(), listOf(0, 1))),
        )
        assertTrue(draft.ok, draft.error?.message)
        val accepted = svc.acceptDraft(env(planId), draft.value!!.versionId, isoWeek())
        assertTrue(accepted.ok, accepted.error?.message)
        val scheduled = svc.scheduleWeek(env(planId), isoWeek())
        assertTrue(scheduled.ok, scheduled.error?.message)
        return svc
    }

    private fun tool(svc: LearningPlanService, name: String, onChanged: () -> Unit = {}): AgentTool =
        PlanAdminTools({ svc }, onChanged).all.single { it.name == name }

    @Test
    fun getLearningPlanListsTodaysNodesWithOccurrenceIds() = runTest {
        val svc = seededService()
        val planId = svc.activePlanId()!!
        val today = LocalDate.now().toString()
        val todayNodes = svc.today(planId, today).occurrences
        assertTrue(todayNodes.isNotEmpty(), "铺天之后今天应该有节点")

        val result = tool(svc, "get_learning_plan").call(buildJsonObject {})

        assertFalse(result.isError, result.content)
        todayNodes.forEach { node ->
            assertTrue(node.id in result.content, "get_learning_plan 要带出今日节点的 occurrenceId：${node.id}")
        }
        assertTrue("待安排池：2 个单元" in result.content, "池里还剩 2 个单元：" + result.content)
        assertTrue("carryPending" in result.content, result.content)
        assertTrue("上周复盘" in result.content, result.content)
    }

    @Test
    fun completingANodeNeedsConfirmationAndThenMarksItDone() = runTest {
        val svc = seededService()
        val planId = svc.activePlanId()!!
        val today = LocalDate.now().toString()
        val node = svc.today(planId, today).occurrences.first()
        var changed = 0
        val complete = tool(svc, "complete_plan_node") { changed++ }

        val refused = complete.call(buildJsonObject { put("occurrenceId", node.id) })
        assertTrue(refused.isError, "没确认不能勾：" + refused.content)
        assertTrue("确认" in refused.content, "拒绝时要告诉模型先确认：" + refused.content)
        assertTrue(svc.today(planId, today).occurrences.any { it.id == node.id }, "被拒时不能改数据")
        assertEquals(0, changed, "被拒时不能通知刷新")

        val done = complete.call(
            buildJsonObject {
                put("occurrenceId", node.id)
                put("confirmed", true)
            },
        )
        assertFalse(done.isError, done.content)
        assertEquals(1, changed, "勾完之后要通知界面刷新计划页")

        val day = svc.today(planId, today)
        assertTrue(day.occurrences.none { it.id == node.id }, "勾完就不该还在待做里")
        assertTrue(day.completed.any { it.id == node.id }, "confirmed 之后节点要变成 done")
    }

    @Test
    fun movingANodeChangesItsDay() = runTest {
        val svc = seededService()
        val planId = svc.activePlanId()!!
        val today = LocalDate.now()
        val target = today.plusDays(1).toString()
        val node = svc.today(planId, today.toString()).occurrences.first()
        var changed = 0
        val move = tool(svc, "move_plan_node") { changed++ }

        val refused = move.call(
            buildJsonObject {
                put("occurrenceId", node.id)
                put("toDay", target)
            },
        )
        assertTrue(refused.isError, "没确认不能顺延：" + refused.content)
        assertEquals(0, changed, "被拒时不能改数据")

        val moved = move.call(
            buildJsonObject {
                put("occurrenceId", node.id)
                put("toDay", target)
                put("confirmed", true)
            },
        )
        assertFalse(moved.isError, moved.content)
        assertEquals(1, changed)

        val open = svc.openNodes(planId, target)
        assertTrue(
            open.any { it.day == target && it.title == node.title },
            "顺延后节点要出现在新的一天 $target：" + open.joinToString { "${it.title}@${it.day}" },
        )
        assertTrue(
            svc.openNodes(planId, today.toString()).none { it.title == node.title },
            "旧的一天不能再留着同一个节点",
        )
    }

    @Test
    fun advancingRhythmFillsNextWeeksCommitmentFromThePool() = runTest {
        val svc = seededService()
        val planId = svc.activePlanId()!!
        val nextWeek = isoWeek(1)
        assertEquals(0, svc.week(planId, nextWeek).commitment.units.size, "下一周一开始还没承诺")
        var changed = 0
        val next = tool(svc, "plan_next_week") { changed++ }

        val result = next.call(buildJsonObject {})

        assertFalse(result.isError, result.content)
        assertEquals(1, changed, "推进周节奏后要通知界面刷新计划页")
        assertTrue(
            svc.week(planId, nextWeek).commitment.units.size > 0,
            "advanceRhythm 要从待安排池给下一周补承诺：" + result.content,
        )
        assertTrue("待安排剩 0 个" in result.content, "池里两个单元应该都被排进下周：" + result.content)
    }

    @Test
    fun aMissingPlanServiceIsReportedInPlainWords() = runTest {
        val result = PlanAdminTools({ null }, {}).all.single { it.name == "get_learning_plan" }
            .call(buildJsonObject {})
        assertTrue(result.isError, "计划库打不开要报错，不能装没事")
        assertTrue("计划库打不开" in result.content, result.content)
    }
}
