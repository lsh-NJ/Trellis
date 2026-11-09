package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.StageDraft
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 自主检查轮的接线验收：用户勾完最后一个节点后，管家应该**自己**在管家会话里说一句话，
 * 而不是等用户发消息。这里钉三件事：
 * 1. 隐藏请求不会落成用户气泡（用户没说过那句话）；
 * 2. 模型照常流式回话，落在管家会话里；
 * 3. 用户不在对话页时，这条消息要标未读。
 *
 * 模型是假的——我们测的是接线，不是它的建议好不好。
 */
class AutonomyRuntimeTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-autonomy-", "").let {
            it.delete()
            it.mkdirs()
            tempDirs += it
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private class StreamingFake(private val full: String) : LlmClient {
        override suspend fun complete(messages: List<LlmMessage>): String = full

        override suspend fun stream(
            messages: List<LlmMessage>,
            onDelta: suspend (String) -> Unit,
            onReasoning: suspend (String) -> Unit,
            jsonMode: Boolean,
        ): String {
            full.chunked(2).forEach { onDelta(it) }
            return full
        }
    }

    /** 记录这一轮真正送给模型的消息，用来验证隐藏请求不会漏进下一轮普通对话。 */
    private class CapturingFake(private val full: String) : LlmClient {
        var captured: List<LlmMessage> = emptyList()

        override suspend fun complete(messages: List<LlmMessage>): String = full

        override suspend fun stream(
            messages: List<LlmMessage>,
            onDelta: suspend (String) -> Unit,
            onReasoning: suspend (String) -> Unit,
            jsonMode: Boolean,
        ): String {
            captured = messages
            full.chunked(2).forEach { onDelta(it) }
            return full
        }
    }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.autonomy",
    )

    private fun weekOf(date: LocalDate): String =
        "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    /** 建一个「本周只有一个阶段、一个节点」的计划，把节点排到今天，方便一次勾完触发阶段完成。 */
    private suspend fun seededPlan(
        runtime: AppRuntime,
        home: File,
        today: LocalDate,
        withStage: Boolean = true,
    ): String {
        val store: LearningPlanService = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { runtime.clock.nowMs() },
            ZoneId.systemDefault(),
        )
        val created = store.createProcess(
            env(),
            "自主检查验收",
            "学完这一阶段，看看下一步",
            weeklyCapacityMinutes = 60 * 7,
        ).unwrap()
        val week = weekOf(today)
        val draft = store.saveDraft(
            env(created.planId),
            created.planId,
            null,
            listOf(UnitDraft("第 1 章", "第 1 节", 20)),
            listOf(CommitmentDraft(week, listOf(0))),
        ).unwrap()
        store.acceptDraft(env(created.planId), draft.versionId, week).unwrap()
        if (withStage) {
            store.setStages(
                env(created.planId),
                created.planId,
                listOf(StageDraft("第一阶段", week, week)),
            ).unwrap()
        }
        return created.planId
    }

    @Test
    fun completingAStageRunsAHiddenStewardTurn() = runBlocking {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )
        val runtime = AppRuntime.create(home)
        try {
            val today = LocalDate.now()
            runtime.applySimulatedToday(today)
            val planId = seededPlan(runtime, home, today)
            runtime.refreshPlan()

            val occurrence = assertNotNull(
                runtime.planDayView?.occurrences?.firstOrNull(),
                "今天应当有一个排好的节点",
            )
            val answerText = "这个阶段学完了。下一步可以做一个小项目，把学过的串起来。"
            runtime.llmOverride = StreamingFake(answerText)

            runtime.completePlanOccurrence(occurrence.id)

            withTimeout(10_000) {
                while (runtime.chat.none { it.role == ChatRole.Assistant && it.text == answerText }) {
                    delay(20)
                }
            }
            delay(100)

            assertFalse(runtime.chatBusy, "自主轮结束后输入要放开")
            assertTrue(
                runtime.chat.none { it.role == ChatRole.User },
                "隐藏请求不能落成用户气泡——用户没说过那句话",
            )
            assertTrue(
                runtime.chat.any { it.role == ChatRole.Assistant && it.text == answerText },
                "管家应当自己把话说在管家会话里",
            )
            assertEquals(
                true,
                runtime.chatSessions.firstOrNull { it.id == StewardRuntime.STEWARD_SESSION_ID }?.unread,
                "用户不在对话页时，这条自主消息要标未读",
            )
            assertTrue(
                runtime.completedPlanIds.contains(planId),
                "计划完成后应进入已完成集合，供计划页归入「已完成」分组",
            )

            // 用户接着点了选项：这是普通轮，既要拿回全量工具，也不能把隐藏请求当成
            // 「用户说过的话」带进上下文（它带着「本轮只读」的边界，会让模型误判工具集）。
            val next = CapturingFake("好的，我按这个方向排。")
            runtime.llmOverride = next
            runtime.sendChat("按这个走")
            val prompt = next.captured.joinToString("\n") { it.content }
            assertTrue(prompt.contains("update_plan") && prompt.contains("read_plan"), "普通轮应当拿回全量计划工具")
            assertFalse(prompt.contains("自主检查触发"), "隐藏请求不能漏进下一轮上下文")
        } finally {
            runtime.shutdown()
        }
    }

    /**
     * 没有阶段的计划（「三天速通」这类）：没有阶段 done/total 可看，要按「所有单元都做过」判完成，
     * 否则勾完最后一个节点也不会触发自主轮、不会进「已完成」分组。
     */
    @Test
    fun finishingTheLastUnitOfAStageLessPlanRunsAHiddenTurn() = runBlocking {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )
        val runtime = AppRuntime.create(home)
        try {
            val today = LocalDate.now()
            runtime.applySimulatedToday(today)
            val planId = seededPlan(runtime, home, today, withStage = false)
            runtime.refreshPlan()

            val occurrence = assertNotNull(
                runtime.planDayView?.occurrences?.firstOrNull(),
                "今天应当有一个排好的节点",
            )
            val answerText = "三天速通做完了。下一步可以补一条更完整的路线。"
            runtime.llmOverride = StreamingFake(answerText)

            runtime.completePlanOccurrence(occurrence.id)

            withTimeout(10_000) {
                while (runtime.chat.none { it.role == ChatRole.Assistant && it.text == answerText }) {
                    delay(20)
                }
            }
            delay(100)

            assertFalse(runtime.chatBusy, "自主轮结束后输入要放开")
            assertTrue(
                runtime.chat.none { it.role == ChatRole.User },
                "隐藏请求不能落成用户气泡",
            )
            assertTrue(
                runtime.chat.any { it.role == ChatRole.Assistant && it.text == answerText },
                "无阶段计划学完后，管家也应当自己开口",
            )
            assertEquals(
                true,
                runtime.chatSessions.firstOrNull { it.id == StewardRuntime.STEWARD_SESSION_ID }?.unread,
                "用户不在对话页时要标未读",
            )
            assertTrue(
                runtime.completedPlanIds.contains(planId),
                "无阶段计划做完所有单元后，也要进入已完成集合",
            )
        } finally {
            runtime.shutdown()
        }
    }
}
