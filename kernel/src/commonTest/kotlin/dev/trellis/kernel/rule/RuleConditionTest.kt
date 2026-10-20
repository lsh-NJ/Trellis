package dev.trellis.kernel.rule

import dev.trellis.kernel.action.ActionArgs
import dev.trellis.kernel.action.ActionParam
import dev.trellis.kernel.action.KernelAction
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.event.KernelEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 规则的 when 条件。
 *
 * 起因是一次真机上的 bug：用户说「每完成一个任务就加一条复习任务」，
 * AI 老老实实写了 on: task.completed -> addTask「复习：{{ event.title }}」。
 * 结果用户把那条复习任务划完，又长出一条「复习：复习：…」，永远停不下来。
 *
 * 规则自己也会发事件，所以「什么情况下**不**触发」和「什么情况下触发」
 * 一样重要。**没有 when 的规则是一把没有刹车的车。**
 */
class RuleConditionTest {

    private val created = mutableListOf<String>()

    private fun addTask() = object : KernelAction {
        override val id = "addTask"
        override val order = 100
        override val label = "新建任务"
        override val params = listOf(ActionParam("title", "标题"))
        override val capability: CapabilityId? = null
        override suspend fun execute(args: ActionArgs): String {
            val title = args.require("title")
            created += title
            return "建好了 " + title
        }
    }

    private fun engine() = RuleEngine { listOf(addTask()) }

    private fun reviewRule(condition: String?) = RuleDecl(
        on = "task.completed",
        actions = listOf(ActionCall("addTask", mapOf("title" to "复习：{{ event.title }}"))),
        condition = condition,
    )

    @Test
    fun aRuleWithoutAConditionAlwaysRuns() = runTest {
        val engine = engine()
        engine.install("ai.review", listOf(reviewRule(null)))

        engine.handle(KernelEvent("task.completed", mapOf("title" to "完成实验")))

        assertEquals(listOf("复习：完成实验"), created)
    }

    @Test
    fun aFalseConditionStopsTheRule() = runTest {
        val engine = engine()
        engine.install("ai.review", listOf(reviewRule("!startsWith(event.title, '复习：')")))

        engine.handle(KernelEvent("task.completed", mapOf("title" to "复习：完成实验")))

        assertTrue(created.isEmpty(), "条件不成立时不该动手")
    }

    @Test
    fun theReviewCascadeStopsInsteadOfRunningForever() = runTest {
        val engine = engine()
        engine.install("ai.review", listOf(reviewRule("!startsWith(event.title, '复习：')")))

        // 完成一个普通任务 -> 该长出复习任务
        engine.handle(KernelEvent("task.completed", mapOf("title" to "完成实验")))
        assertEquals(listOf("复习：完成实验"), created)

        // 把那条复习任务也划完 -> 不该再长东西
        engine.handle(KernelEvent("task.completed", mapOf("title" to "复习：完成实验")))
        assertEquals(listOf("复习：完成实验"), created, "复习任务完成时不该再生成复习任务")
    }

    @Test
    fun withoutAConditionTheCascadeReallyHappens() = runTest {
        // 复现当时的 bug。留着这条是为了记住：when 不是可选的装饰。
        val engine = engine()
        engine.install("ai.review", listOf(reviewRule(null)))

        var title = "完成实验"
        repeat(3) {
            engine.handle(KernelEvent("task.completed", mapOf("title" to title)))
            title = created.last()
        }

        assertEquals(
            listOf("复习：完成实验", "复习：复习：完成实验", "复习：复习：复习：完成实验"),
            created,
        )
    }

    @Test
    fun aConditionThatCannotBeEvaluatedCountsAsFalse() = runTest {
        // 算不出来就不做。宁可少做一件事，也不要莫名其妙地改用户数据。
        val engine = engine()
        engine.install("ai.review", listOf(reviewRule("event.nothing > 1")))

        engine.handle(KernelEvent("task.completed", mapOf("title" to "完成实验")))

        assertTrue(created.isEmpty())
    }
}
