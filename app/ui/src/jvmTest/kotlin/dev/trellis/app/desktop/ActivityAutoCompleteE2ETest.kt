package dev.trellis.app.desktop

import dev.trellis.activity.ActivityStore
import dev.trellis.activity.Attribution
import dev.trellis.activity.AttributionSource
import dev.trellis.activity.AwBucket
import dev.trellis.activity.RawEvent
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * 屏幕使用（活动归因）→ 计划节点自动完成的 E2E 验收。
 *
 * 三件事必须成立：
 * 1. 屏幕使用归到某个本周节点、累计够 max(预估, 5) 分钟时，节点被勾掉，账本记 `kind=time / source=activity`，
 *    计划页卡片能撤销（撤回复原 + 进屏蔽名单）；
 * 2. 不够时长（4 分钟 < 预估 20 分钟）绝不勾；
 * 3. 归因是外部写进 `activity.db` 的（演示口径），切回计划页刷新一次也能对账并勾上。
 *
 * 事件是拟真的 AW 原始事件（window bucket + web.tab.current bucket，浏览器窗口贴 B 站 URL），
 * 归因使用桌面端约定的 `occurrence:<id>` taskRef。全程临时 home，不碰真实数据。
 */
class ActivityAutoCompleteE2ETest {

    private class Harness(
        val home: File,
        val runtime: AppRuntime,
        val store: LearningPlanService,
        val planId: String,
        val week: String,
        val today: LocalDate,
    ) {
        /** 本周所有排期（先不经过 runtime，避免还没刷新就被对账改状态）。 */
        fun weekItems() = store.week(planId, week).days.flatMap { it.items }

        fun statusOf(occurrenceId: String): String =
            weekItems().first { it.occurrenceId == occurrenceId }.status
    }

    private val bucketWindow = "aw-watcher-window_e2e"
    private val bucketWeb = "aw-watcher-web_e2e"

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-activity-auto").toFile().apply { deleteOnExit() }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.activity.auto-complete",
    )

    private fun weekOf(date: LocalDate): String =
        "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    /**
     * 起运行时 + 建一份「本周两个 20 分钟节点」的计划。
     *
     * 刻意**不**在这里刷新计划页：测试要先把活动数据写进 `activity.db`，再走各自的触发点
     * （归因写入口 / 计划页刷新），这样断言的是真实路径而不是夹具。
     */
    private suspend fun harness(today: LocalDate): Harness {
        val home = tempHome()
        val zone = ZoneId.systemDefault()
        val runtime = AppRuntime.create(home)
        runtime.applySimulatedToday(today)
        val store: LearningPlanService = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { runtime.clock.nowMs() },
            zone,
        )
        val created = store.createProcess(
            env(),
            "屏幕使用验收",
            "活动归因 → 自动完成",
            weeklyCapacityMinutes = 60 * 7,
        ).unwrap()
        val week = weekOf(today)
        val draft = store.saveDraft(
            env(created.planId),
            created.planId,
            null,
            listOf(
                UnitDraft("第 1 章", "第 1 节", 20),
                UnitDraft("第 2 章", "第 2 节", 20),
            ),
            listOf(CommitmentDraft(week, listOf(0, 1))),
        ).unwrap()
        store.acceptDraft(env(created.planId), draft.versionId, week).unwrap()
        return Harness(home, runtime, store, created.planId, week, today)
    }

    /** 注入「看 B 站高数视频」的拟真原始事件：窗口 bucket + 浏览器标签页 bucket，同区间。 */
    private fun injectScreenUse(home: File, today: LocalDate, minutes: Int) {
        val startMs = today.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val durMs = minutes * 60_000L
        val store = ActivityStore.open(File(home, "activity/activity.db").toPath())
        try {
            store.upsertBucket(AwBucket(bucketWindow, "currentwindow"))
            store.upsertBucket(AwBucket(bucketWeb, "web.tab.current"))
            store.upsertEvents(
                listOf(
                    RawEvent(
                        bucket = bucketWindow,
                        awId = 1,
                        tsMs = startMs,
                        durMs = durMs,
                        app = "Google Chrome",
                        title = "【高等数学】第 1 章 第 1 节 - 哔哩哔哩",
                    ),
                    RawEvent(
                        bucket = bucketWeb,
                        awId = 1,
                        tsMs = startMs,
                        durMs = durMs,
                        title = "第 1 节",
                        url = "https://www.bilibili.com/video/BV1xx411c7mD",
                    ),
                ),
            )
        } finally {
            store.close()
        }
    }

    private fun screenUseRange(today: LocalDate, minutes: Int): Pair<Long, Long> {
        val fromMs = today.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return fromMs to fromMs + minutes * 60_000L
    }

    /**
     * 计划页刷新触发的对账是**后台协程**（不阻塞界面），测试用真实时间轮询等它完成。
     * `runTest` 的虚拟时间不会等真实线程，所以这里用 `Dispatchers.Default` 上的真实 delay。
     */
    private suspend fun awaitUntil(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        error("等待超时：$what")
    }

    // ------------------------------------------------------------------
    // 1) 屏幕使用够时长 → 自动完成 + 撤销
    // ------------------------------------------------------------------

    @Test
    fun screenUseCompletesTheNodeAndUndoRestoresIt() = runTest {
        val today = LocalDate.now()
        val h = harness(today)
        try {
            val occurrenceId = h.weekItems().first().occurrenceId
            injectScreenUse(h.home, today, minutes = 30)
            // 先把计划页的数据装起来（此时还没有归因，不该有任何自动完成）。
            h.runtime.refreshPlan(focus = h.planId)
            assertTrue(
                h.runtime.planLinkWeekCandidates().any { it.occurrenceId == occurrenceId },
                "本周应该能拿到这个节点的联动候选",
            )
            assertTrue(h.runtime.planAutoCompletions.isEmpty(), "还没归因，不该有自动完成")
            assertEquals("planned", h.statusOf(occurrenceId))

            val (fromMs, toMs) = screenUseRange(today, 30)
            val problems = h.runtime.activityAddAttribution(
                fromMs = fromMs,
                toMs = toMs,
                taskRef = h.runtime.occurrenceTaskRef(occurrenceId),
            )
            assertTrue(problems.isEmpty(), "归因应写入成功：$problems")

            val entry = assertNotNull(
                h.runtime.planAutoCompletions.firstOrNull { it.occurrenceId == occurrenceId },
                "屏幕使用 30 分钟 ≥ 预估 20 分钟，节点应被自动完成",
            )
            assertEquals(AutoCompletionKind.Time, entry.kind)
            assertEquals("activity", entry.source, "来源要记成活动归因（不是计时）")
            assertEquals(30, entry.actualMinutes)
            assertEquals(20, entry.plannedMinutes)
            assertEquals(
                "【高等数学】第 1 章 第 1 节 - 哔哩哔哩",
                entry.sourceTitle,
                "卡片要写清「学习 · 具体网页标签」（取停留最久的窗口标题）",
            )
            assertTrue(
                entry.evidence.contains("屏幕使用 30 分钟"),
                "证据要写「屏幕使用」而不是「实际计时」：${entry.evidence}",
            )
            assertEquals("done", h.statusOf(occurrenceId), "节点本身要真的被勾掉")
            assertTrue(
                h.runtime.planMessage?.contains("屏幕使用") == true,
                "页面要说明这是屏幕使用来源：${h.runtime.planMessage}",
            )
            // 账本落盘：类型 time + 来源 activity + 实际分钟，重启后卡片仍能按来源显示。
            val ledgerText = File(h.home, "plans/auto-completions.json").readText(Charsets.UTF_8)
            assertTrue(ledgerText.contains("\"kind\": \"time\""), "账本要记类型：$ledgerText")
            assertTrue(ledgerText.contains("\"source\": \"activity\""), "账本要记来源：$ledgerText")
            assertTrue(ledgerText.contains("\"actualMinutes\": 30"), "账本要记实际分钟：$ledgerText")

            // 撤销：节点回到 planned、账本记 undone、进屏蔽名单（以后不再自动勾）。
            h.runtime.undoAutoCompletion(entry.id)
            assertEquals("planned", h.statusOf(occurrenceId), "撤销后节点必须回到没做")
            assertTrue(
                h.runtime.planAutoCompletions.none { it.occurrenceId == occurrenceId },
                "撤销后不该再出现在「AI 自动完成」列表里",
            )
            assertTrue(
                occurrenceId in h.runtime.autoCompletionBlocked(),
                "撤销过的节点要进屏蔽名单",
            )
        } finally {
            h.runtime.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 2) 控制组：4 分钟不够
    // ------------------------------------------------------------------

    @Test
    fun fourMinutesOfScreenUseDoesNotCompleteTheNode() = runTest {
        val today = LocalDate.now()
        val h = harness(today)
        try {
            val occurrenceId = h.weekItems().first().occurrenceId
            injectScreenUse(h.home, today, minutes = 4)
            h.runtime.refreshPlan(focus = h.planId)

            val (fromMs, toMs) = screenUseRange(today, 4)
            val problems = h.runtime.activityAddAttribution(
                fromMs = fromMs,
                toMs = toMs,
                taskRef = h.runtime.occurrenceTaskRef(occurrenceId),
            )
            assertTrue(problems.isEmpty(), "归因应写入成功：$problems")

            // 归因本身是生效的（聚合器能读到 4 分钟）——只是没到阈值。
            assertEquals(
                4 * 60_000L,
                h.runtime.activityOccurrenceMinutes(today)[occurrenceId],
                "活动侧应该已经算出 4 分钟",
            )
            assertTrue(h.runtime.planAutoCompletions.isEmpty(), "4 分钟 < max(预估 20, 5)，不该自动完成")
            assertEquals("planned", h.statusOf(occurrenceId))
        } finally {
            h.runtime.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 3) 外部写库后刷新计划页也能对账
    // ------------------------------------------------------------------

    @Test
    fun refreshingThePlanPageReconcilesExternallyWrittenAttribution() = runTest {
        val today = LocalDate.now()
        val h = harness(today)
        try {
            val occurrenceId = h.weekItems().first().occurrenceId
            injectScreenUse(h.home, today, minutes = 30)
            // 绕过 AppRuntime：直接写归因，模拟「另一个进程/外部工具改了 activity.db」。
            val (fromMs, toMs) = screenUseRange(today, 30)
            val activity = ActivityStore.open(File(h.home, "activity/activity.db").toPath())
            try {
                activity.insertAttribution(
                    Attribution(
                        fromMs = fromMs,
                        toMs = toMs,
                        taskRef = h.runtime.occurrenceTaskRef(occurrenceId),
                        source = AttributionSource.MANUAL,
                    ),
                )
            } finally {
                activity.close()
            }
            assertTrue(h.runtime.planAutoCompletions.isEmpty(), "还没刷新计划页，不该已经勾上")
            assertEquals("planned", h.statusOf(occurrenceId))

            // 只刷新计划页：对账由 refreshPlan 末尾的后台协程完成（演示口径）。
            h.runtime.refreshPlan(focus = h.planId)
            awaitUntil("刷新计划页后自动完成出现") {
                h.runtime.planAutoCompletions.any { it.occurrenceId == occurrenceId }
            }

            val entry = h.runtime.planAutoCompletions.first { it.occurrenceId == occurrenceId }
            assertEquals("activity", entry.source)
            assertEquals(30, entry.actualMinutes)
            assertEquals(20, entry.plannedMinutes)
            assertEquals("done", h.statusOf(occurrenceId))
            assertTrue(
                h.runtime.planMessage?.contains("屏幕使用") == true,
                "页面要说明这是屏幕使用来源：${h.runtime.planMessage}",
            )
        } finally {
            h.runtime.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 4) 账本 source 落盘 / 读回
    // ------------------------------------------------------------------

    private class FixedClock(private val baseMs: Long, override val zone: ZoneId) : TrellisClock {
        override fun nowMs(): Long = baseMs
        override fun monotonicMs(): Long = baseMs
        override fun today(): LocalDate = Instant.ofEpochMilli(baseMs).atZone(zone).toLocalDate()
        override fun isoWeek(): String = isoWeekOf(today())
        override fun simulatedDate(): LocalDate? = null
    }

    @Test
    fun completionSourceRoundTripsThroughTheLedger() {
        val home = Files.createTempDirectory("trellis-auto-completion-source").toFile().apply { deleteOnExit() }
        val clock = FixedClock(Instant.parse("2026-11-09T02:00:00Z").toEpochMilli(), ZoneOffset.ofHours(8))
        val store = AutoCompletionStore(home, clock)
        val fromActivity = AutoCompletionEntry(
            id = "ac_test0001",
            planId = "pln_1",
            occurrenceId = "occ_1",
            title = "第 1 节",
            chapter = "第 1 章",
            day = "2026-11-09",
            noteId = "",
            noteTitle = "",
            evidence = "计划 20 分钟，屏幕使用 30 分钟（150%）",
            confidence = 1.0,
            at = 1_700_000_000_000L,
            kind = AutoCompletionKind.Time,
            actualMinutes = 30,
            plannedMinutes = 20,
            source = "activity",
        )
        // 笔记来源（以及旧的计时记录）不带 source，保持老格式。
        val fromNote = AutoCompletionEntry(
            id = "ac_test0002",
            planId = "pln_1",
            occurrenceId = "occ_2",
            title = "第 2 节",
            chapter = "",
            day = "2026-11-09",
            noteId = "n_1",
            noteTitle = "笔记",
            evidence = "正文逐字证据",
            confidence = 0.9,
            at = 1_700_000_000_001L,
        )
        assertTrue(store.save(AutoCompletionLedger(entries = listOf(fromActivity, fromNote))))
        val text = store.file.readText(Charsets.UTF_8)
        assertTrue(text.contains("\"source\": \"activity\""), "source 应落盘：$text")
        assertEquals(1, Regex("\"source\"").findAll(text).count(), "只有非空 source 才写，笔记记录保持旧格式")

        val loaded = store.load()
        assertEquals("activity", loaded.entries.single { it.occurrenceId == "occ_1" }.source)
        assertEquals("", loaded.entries.single { it.occurrenceId == "occ_2" }.source)
        // 旧账本（没有 source 字段）解析成空串 = 按计时来源显示，不会崩。
        store.file.writeText(
            """
            {"version":1,"entries":[{"id":"ac_old","planId":"pln_1","occurrenceId":"occ_old","title":"旧记录",
            "chapter":"","day":"2026-11-01","noteId":"","noteTitle":"","evidence":"老证据","confidence":1.0,
            "at":1700000000000,"kind":"time","actualMinutes":10,"plannedMinutes":5,"status":"auto"}],"blocked":[]}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        assertEquals("", store.load().entries.single().source, "旧账本没有 source 字段时解析成空串")
    }
}
