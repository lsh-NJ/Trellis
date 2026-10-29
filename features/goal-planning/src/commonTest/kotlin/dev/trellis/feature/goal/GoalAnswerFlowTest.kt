package dev.trellis.feature.goal

import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import dev.trellis.kernel.storage.KvStore
import dev.trellis.kernel.time.KernelClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 「在卡片上把信息答进去」这条链路。
 *
 * 这是用户实际卡住的地方：侧条上原来只有一个「去回答」按钮，点了只是切到对话页；
 * 到了对话页，模型用一段话把问题说了一遍，没有选项也没有输入口；用户随手打一句答案，
 * 那句话又被判成「聊天」——而聊天按设计没有工具，模型只能回「我这没有能记录的工具」。
 *
 * 所以这里验三件事：
 * 1. 问题真的出现在卡片上，带选项；
 * 2. 点一下真的写进契约，并且刷出下一个问题；
 * 3. 用户把答案打在对话里，也能被认出来并落库（不经过模型）。
 */
class GoalAnswerFlowTest {

    private val today = "2026-10-29"

    private class MemoryKv : KvStore {
        private val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun put(key: String, value: String) {
            map[key] = value
        }
        override suspend fun delete(key: String) {
            map.remove(key)
        }
        override suspend fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
        override suspend fun clear() = map.clear()
    }

    private fun fixedClock(nowMs: Long): KernelClock = object : KernelClock {
        override fun nowMs(): Long = nowMs
        override fun monotonicMs(): Long = nowMs
    }

    private fun collectingService(): GoalService {
        val service = GoalService(
            kit = GoalKit(MemoryKv()),
            clock = fixedClock(0L),
            dailyMinutes = { 90 },
            today = { today },
            effort = { null },
        )
        return service
    }

    private fun texts(node: UiNode): List<String> = when (node) {
        is UiNode.Text -> listOf(node.text)
        is UiNode.Column -> node.children.flatMap { texts(it) }
        is UiNode.Row -> node.children.flatMap { texts(it) }
        is UiNode.Section -> node.children.flatMap { texts(it) }
        is UiNode.Grid -> node.children.flatMap { texts(it) }
        else -> emptyList()
    }

    private fun buttons(node: UiNode): List<Pair<String, UiNode.Button>> = when (node) {
        is UiNode.Button -> listOf(node.label to node)
        is UiNode.Column -> node.children.flatMap { buttons(it) }
        is UiNode.Row -> node.children.flatMap { buttons(it) }
        is UiNode.Section -> node.children.flatMap { buttons(it) }
        is UiNode.Grid -> node.children.flatMap { buttons(it) }
        else -> emptyList()
    }

    private fun tapArgs(button: UiNode.Button): Map<String, String> =
        (button.onTap as? UiTap.Plugin)?.args.orEmpty()

    @Test
    fun everyRequiredFieldHasAShortSetOfOptions() {
        RequiredFields.of(GoalKind.ACADEMIC).forEach { key ->
            val options = GoalQuestions.options(key)
            assertTrue(options.size in 2..GoalQuestions.MAX_OPTIONS, key.name + " 的选项数不对：" + options)
            assertEquals(options.size, options.distinct().size, key.name + " 的选项有重复：" + options)
            options.forEach { option ->
                assertTrue(option.isNotBlank(), key.name + " 有空选项")
                assertTrue(
                    option.length <= GoalQuestions.MAX_OPTION_CHARS,
                    key.name + " 的选项太长，卡片上会折行：" + option,
                )
            }
        }
        RequiredFields.of(GoalKind.JOB).forEach { key ->
            assertTrue(GoalQuestions.options(key).size >= 2, key.name + " 没有可用选项")
        }
    }

    @Test
    fun theCardAsksOneQuestionWithOptionsAndWritesTheAnswerBack() = runTest {
        val service = collectingService()
        service.update {
            GoalEngine.start(it, "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L)
        }
        var openedChat = false
        val slot = GoalAsideSlot(service, { today }, { openedChat = true }, { 90 })

        // ① 卡片上出现第一个待问字段，并且带选项。
        val card = slot.build(DataContext.ofNow(0L))
        val lines = texts(card)
        assertTrue(
            FieldKey.DAILY_MINUTES.label in lines,
            "卡片上要直接出现第一个问题：" + lines,
        )
        val labels = buttons(card).map { it.first }
        assertTrue("4 小时以上" in labels, "选项要真的画出来：" + labels)
        assertTrue(GoalQuestions.SKIP_LABEL in labels, "必须有「跳过」：" + labels)

        // ② 点一个选项：写进契约，并把下一个问题刷出来。
        val option = buttons(card).first { it.first == "4 小时以上" }.second
        val args = tapArgs(option)
        assertEquals("answer", (option.onTap as UiTap.Plugin).handler)
        val outcome = slot.perform("answer", args)
        assertTrue(outcome.ok, outcome.message)
        assertEquals(
            "4 小时以上",
            service.mirror().contract.field(FieldKey.DAILY_MINUTES).value,
            "点了选项就得写进契约，不能只是回一句话",
        )
        assertFalse(openedChat, "在卡片上就能答，不该再把人踢去对话页")

        val next = texts(slot.build(DataContext.ofNow(0L)))
        assertTrue(FieldKey.DEADLINE.label in next, "答完要刷出下一个问题：" + next)
        assertFalse(FieldKey.DAILY_MINUTES.label in next, "答过的字段不该再问一遍：" + next)
    }

    @Test
    fun theCardAcceptsATypedAnswerToo() = runTest {
        val service = collectingService()
        service.update {
            GoalEngine.start(it, "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L)
        }
        val slot = GoalAsideSlot(service, { today }, { }, { 90 })

        // 输入框提交时宿主会额外塞一个 value（见 DynamicSlotView 的 Input 实现）。
        val outcome = slot.perform(
            "answer",
            mapOf("field" to FieldKey.DAILY_MINUTES.name, "value" to "每天大概两小时"),
        )
        assertTrue(outcome.ok, outcome.message)
        assertEquals("每天大概两小时", service.mirror().contract.field(FieldKey.DAILY_MINUTES).value)

        // 空内容是错误，不能静默把空字符串写进契约。
        val empty = slot.perform("answer", mapOf("field" to FieldKey.DEADLINE.name, "value" to "  "))
        assertFalse(empty.ok, "空内容要被拒绝")
        assertEquals(FieldState.UNKNOWN, service.mirror().contract.field(FieldKey.DEADLINE).state)
    }

    @Test
    fun skippingIsRecordedAsUnknownNotAsAnAnswer() = runTest {
        val service = collectingService()
        service.update {
            GoalEngine.start(it, "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L)
        }
        val slot = GoalAsideSlot(service, { today }, { }, { 90 })

        val outcome = slot.perform("skip", mapOf("field" to FieldKey.DAILY_MINUTES.name))
        assertTrue(outcome.ok, outcome.message)
        val field = service.mirror().contract.field(FieldKey.DAILY_MINUTES)
        assertEquals(FieldState.UNKNOWN, field.state, "跳过是「不知道」，不是给了个答案")
        assertEquals(1, field.skips)
    }

    @Test
    fun onceNothingIsLeftToAskTheCardOffersToDraft() = runTest {
        val service = collectingService()
        service.update {
            GoalEngine.start(it, "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L)
        }
        // 把该问的都答掉（或答到轮数上限），卡片上就不该再问，而是给出「出计划」。
        RequiredFields.of(GoalKind.ACADEMIC).forEachIndexed { index, key ->
            service.answer(key, "第 " + index + " 项")
        }
        val slot = GoalAsideSlot(service, { today }, { }, { 90 })
        val card = slot.build(DataContext.ofNow(0L))
        val labels = buttons(card).map { it.first }
        assertTrue("出计划" in labels, "问完了要给出下一步，而不是继续问：" + labels)

        val outcome = slot.perform("draft", emptyMap())
        assertTrue(outcome.ok, outcome.message)
        val mirror = service.mirror()
        assertEquals(GoalPhase.DRAFT, mirror.phase)
        val plan = assertNotNull(mirror.dayPlan(today), "出计划之后今天必须有安排")
        assertTrue(plan.entries.size >= 2, "今天至少两条：" + plan.entries.map { it.text })
    }

    // ---------------------------------------------------------------- 对话里打回来的答案

    @Test
    fun aTypedDurationIsCapturedEvenIfItIsNotOneOfTheOptions() = runTest {
        val service = collectingService()
        service.update {
            GoalEngine.start(it, "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L)
        }

        // 用户不会照着你给的选项说话。「每天大概两小时」这种句子也要能记下来，
        // 否则他打了字、界面却说「没记上」，功能就等于没有。
        val captured = assertNotNull(service.captureSpokenAnswer("每天大概两小时"))
        assertEquals(FieldKey.DAILY_MINUTES, captured.field)
        assertEquals("每天大概两小时", service.mirror().contract.field(FieldKey.DAILY_MINUTES).value)

        // 长句子不当答案：那更可能是在抱怨今天多累，不是在回答「截止时间」。
        assertNull(
            service.captureSpokenAnswer("今天加班到很晚，只剩下不到六十分钟能用来学习，实在不想动了"),
            "长句不该被当成某个字段的答案",
        )
    }

    @Test
    fun anOptionTypedIntoTheChatIsCapturedBeforeTheModelSeesIt() = runTest {
        val service = collectingService()
        service.update {
            GoalEngine.start(it, "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L)
        }

        // 用户就是这么答的：点选项或直接打「4 小时以上」。这句话判不出意图，
        // 会被路由成聊天；不在这里拦住，模型只能回「我没有能记录的工具」。
        val captured = assertNotNull(
            service.captureSpokenAnswer("4 小时以上"),
            "当前问的就是「每天能投入多久」，这句话必须被认出来",
        )
        assertEquals(FieldKey.DAILY_MINUTES, captured.field)
        assertTrue(captured.message.contains("记下了"), captured.message)
        assertEquals("4 小时以上", service.mirror().contract.field(FieldKey.DAILY_MINUTES).value)

        // 不是选项的闲聊不能被误记。
        assertNull(service.captureSpokenAnswer("今天好累，先不聊这个"), "闲聊不该写进契约")
        // 已经不在收集阶段时也不该动契约。
        service.update { GoalEngine.confirm(GoalEngine.toDraft(it, today, 90)) }
        assertNull(service.captureSpokenAnswer("1 小时"), "已经开始推进了，别再把闲聊当答案")
    }
}
