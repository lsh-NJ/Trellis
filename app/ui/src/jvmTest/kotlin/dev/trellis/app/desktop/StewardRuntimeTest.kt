package dev.trellis.app.desktop

import dev.trellis.ai.LlmMessage
import dev.trellis.ai.steward.StewardRitual
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 管家的仪式判定：什么时候该说、说一次就停、会话忙时不丢 checkpoint、没说成就退模板。
 *
 * 这里不碰数据库和模型：`StewardPorts` 是假实现，事实由测试直接给。
 */
class StewardRuntimeTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private var now = millis("2026-11-04T09:00")

    private fun millis(local: String): Long =
        OffsetDateTime.parse("$local+08:00").toInstant().toEpochMilli()

    private fun home(): File = Files.createTempDirectory("trellis-steward-rt").toFile()

    private fun runtime(store: StewardStore, ports: FakePorts): StewardRuntime =
        StewardRuntime(store, ports, { now }, zone)

    private class FakePorts(
        var facts: StewardPlanFacts? = StewardPlanFacts(
            planTitle = "Linux 编程速通",
            plannedToday = 3,
            doneToday = 1,
            todayMinutes = 90,
            overdueThisWeek = mapOf("2026-11-02" to 2, "2026-11-03" to 1),
            backlogCount = 5,
            remainingMinutesInWeek = 240,
        ),
        var busy: Boolean = false,
        var focused: Boolean = true,
        var polished: String? = null,
        var polishFails: Boolean = false,
        var proposal: StewardProposal? = null,
    ) : StewardPorts {
        val appended = mutableListOf<String>()
        val notifications = mutableListOf<Pair<String, String>>()
        val proposalsSent = mutableListOf<StewardProposal?>()
        var polishCalls = 0

        override suspend fun planFacts(today: LocalDate): StewardPlanFacts? = facts

        override suspend fun appendProactive(sessionId: String, text: String, proposal: StewardProposal?): Boolean {
            if (busy) return false
            appended += text
            proposalsSent += proposal
            return true
        }

        override suspend fun proposal(today: LocalDate): StewardProposal? = proposal

        override suspend fun polish(messages: List<LlmMessage>): String? {
            polishCalls++
            if (polishFails) error("模型不可用")
            return polished
        }

        override fun notify(title: String, body: String) {
            notifications += title to body
        }

        override fun appFocused(): Boolean = focused
    }

    @Test
    fun morningIsSentOncePerAppDay() = runTest {
        val ports = FakePorts()
        val runtime = runtime(StewardStore(home()), ports)

        assertEquals(StewardRunResult.Morning, runtime.check())
        assertEquals(1, ports.appended.size)
        assertTrue(ports.appended.single().startsWith("早上好。"), ports.appended.single())
        // 同一天再 tick / 回到窗口：不重复。
        assertEquals(StewardRunResult.Nothing, runtime.check())

        // 第二天同一时间再来一次。
        now = millis("2026-11-05T09:10")
        assertEquals(StewardRunResult.Morning, runtime.check())
        assertEquals(2, ports.appended.size)
    }

    @Test
    fun missedEveningIsMergedIntoTheMorningMessage() = runTest {
        val store = StewardStore(home())
        // 昨天早上的发过，昨晚没收尾；今天 09:00 打开。
        store.saveCheckpoints(StewardCheckpoints(lastMorningDay = "2026-11-03", lastEveningDay = "2026-11-02"))
        store.saveDigest("2026-11-03", "昨晚完成 2 件、剩 1 件。")
        val ports = FakePorts()
        val runtime = runtime(store, ports)

        assertEquals(StewardRunResult.MorningWithEveningCatchUp, runtime.check())
        val text = ports.appended.single()
        assertTrue(text.startsWith("昨晚完成 2 件、剩 1 件。"), text)
        assertTrue(text.contains("早上好。"), text)
        // 补发的昨晚算作已发；今晨也记上。
        assertEquals("2026-11-03", store.checkpoints().lastEveningDay)
        assertEquals("2026-11-04", store.checkpoints().lastMorningDay)
        assertEquals(StewardRunResult.Nothing, runtime.check())
    }

    @Test
    fun eveningWaitsForItsTimeAndOnlyFiresOnce() = runTest {
        val store = StewardStore(home())
        store.saveCheckpoints(StewardCheckpoints(lastMorningDay = "2026-11-04", lastEveningDay = "2026-11-03"))
        val ports = FakePorts()
        val runtime = runtime(store, ports)

        now = millis("2026-11-04T22:00")
        assertEquals(StewardRunResult.Nothing, runtime.check())
        assertTrue(ports.appended.isEmpty())

        now = millis("2026-11-04T22:31")
        assertEquals(StewardRunResult.Evening, runtime.check())
        assertTrue(ports.appended.single().startsWith("今天结束了。"), ports.appended.single())
        assertEquals("2026-11-04", store.checkpoints().lastEveningDay)
        // 晚间发完，当晚不再重复。
        assertEquals(StewardRunResult.Nothing, runtime.check())
    }

    @Test
    fun aProposalRidesAlongWithTheMessage() = runTest {
        val proposal = StewardProposal(
            id = "prop-test",
            title = "把 1 件没做完的挪到明天",
            detail = "本周还有 1 件没做。",
            changes = listOf(StewardChange.Move("网络层", "occ_1", "2026-11-05")),
        )
        val ports = FakePorts(proposal = proposal)
        val runtime = runtime(StewardStore(home()), ports)

        assertEquals(StewardRunResult.Morning, runtime.check())
        assertEquals(listOf<StewardProposal?>(proposal), ports.proposalsSent)
    }

    @Test
    fun aBusyConversationDoesNotBurnTheCheckpoint() = runTest {
        val store = StewardStore(home())
        val ports = FakePorts(busy = true)
        val runtime = runtime(store, ports)

        assertEquals(StewardRunResult.Busy, runtime.check())
        assertNull(store.checkpoints().lastMorningDay)
        assertTrue(ports.appended.isEmpty())

        ports.busy = false
        assertEquals(StewardRunResult.Morning, runtime.check())
        assertEquals("2026-11-04", store.checkpoints().lastMorningDay)
    }

    @Test
    fun polishFallsBackToTheDeterministicTemplate() = runTest {
        val ports = FakePorts(polishFails = true)
        val runtime = runtime(StewardStore(home()), ports)

        assertEquals(StewardRunResult.Morning, runtime.check())
        assertTrue(ports.appended.single().contains("Linux 编程速通"), ports.appended.single())
        assertEquals(1, ports.polishCalls)
    }

    @Test
    fun noActivePlanMeansNothingIsSaid() = runTest {
        val ports = FakePorts(facts = null)
        val runtime = runtime(StewardStore(home()), ports)

        assertEquals(StewardRunResult.Nothing, runtime.check())
        assertTrue(ports.appended.isEmpty())
    }

    @Test
    fun disabledRitualsStaySilent() = runTest {
        val store = StewardStore(home())
        store.saveSettings(StewardSettings(morningEnabled = false, eveningEnabled = false))
        val ports = FakePorts()
        val runtime = runtime(store, ports)

        assertEquals(StewardRunResult.Nothing, runtime.check())
        assertTrue(ports.appended.isEmpty())
    }

    @Test
    fun polishedTextIsPreferredAndNotificationsOnlyFireInBackground() = runTest {
        val ports = FakePorts(polished = "今天先做「网络层」那一节。", focused = false)
        val runtime = runtime(StewardStore(home()), ports)

        assertEquals(StewardRunResult.Morning, runtime.check())
        assertEquals(listOf("今天先做「网络层」那一节。"), ports.appended)
        // 失焦 + 桌面通知开着 → 弹一次系统通知。
        assertEquals("管家" to "今天先做「网络层」那一节。", ports.notifications.single())
    }

    @Test
    fun focusedAppKeepsTheMessageInTheChatBadgeOnly() = runTest {
        val ports = FakePorts(polished = "早上好，今天 3 件。", focused = true)
        val runtime = runtime(StewardStore(home()), ports)

        assertEquals(StewardRunResult.Morning, runtime.check())
        assertTrue(ports.notifications.isEmpty())
    }

    @Test
    fun userRepliesAreRememberedVerbatim() = runTest {
        val store = StewardStore(home())
        val runtime = runtime(store, FakePorts())

        runtime.rememberUserReply(LocalDate.parse("2026-11-04"), "今天先不做，明天再说")

        val memory = store.recentMemory("2026-11-04", days = 7)
        assertEquals(1, memory.size)
        assertEquals(StewardMemoryKind.UserReply, memory.single().kind)
        assertEquals("今天先不做，明天再说", memory.single().quote)
    }

    @Test
    fun forceRunIgnoresTheClockAndDoesNotBurnCheckpoints() = runTest {
        val store = StewardStore(home())
        store.saveCheckpoints(StewardCheckpoints(lastMorningDay = "2026-11-04", lastEveningDay = "2026-11-04"))
        val ports = FakePorts()
        val runtime = runtime(store, ports)

        // 真实检查：今天都发过了，什么都不说。
        assertEquals(StewardRunResult.Nothing, runtime.check())

        // 调试触发：不看时间、不看 checkpoint，可以反复发。
        assertEquals(StewardRunResult.Morning, runtime.forceRun(StewardRitual.Morning))
        assertEquals(StewardRunResult.Morning, runtime.forceRun(StewardRitual.Morning))
        assertEquals(2, ports.appended.size)

        // 调试不占用当天的仪式机会。
        assertEquals("2026-11-04", store.checkpoints().lastMorningDay)
        assertEquals("2026-11-04", store.checkpoints().lastEveningDay)
    }

    @Test
    fun forceRunCanFireTheEveningRitualBeforeItsTime() = runTest {
        val store = StewardStore(home())
        val ports = FakePorts()
        val runtime = runtime(store, ports)

        // 现在是 09:00，真实晚间要到 22:30；调试可以立刻发。
        assertEquals(StewardRunResult.Evening, runtime.forceRun(StewardRitual.Evening))
        assertTrue(ports.appended.single().startsWith("今天结束了。"), ports.appended.single())
        assertEquals(ports.appended.single(), store.digest("2026-11-04")?.trim())
        assertNull(store.checkpoints().lastEveningDay)
    }

    @Test
    fun forceRunReturnsBusyWithoutTouchingTheStore() = runTest {
        val store = StewardStore(home())
        val ports = FakePorts(busy = true)
        val runtime = runtime(store, ports)

        assertEquals(StewardRunResult.Busy, runtime.forceRun(StewardRitual.Morning))
        assertTrue(ports.appended.isEmpty())
        assertNull(store.checkpoints().lastMorningDay)
        assertTrue(store.recentMemory("2026-11-04", days = 7).isEmpty())
    }

    @Test
    fun forceRunNotifiesEvenWhileTheAppIsFocused() = runTest {
        val ports = FakePorts(focused = true)
        val runtime = runtime(StewardStore(home()), ports)

        assertEquals(StewardRunResult.Morning, runtime.forceRun(StewardRitual.Morning))
        assertEquals(1, ports.notifications.size)
    }
}
