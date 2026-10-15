package dev.trellis.feature.plan

import dev.trellis.domain.PlanService
import dev.trellis.domain.PlanSummary
import dev.trellis.domain.TaskQuery
import dev.trellis.domain.TaskStatus
import dev.trellis.kernel.api.BootMode
import dev.trellis.kernel.api.Kernel
import dev.trellis.kernel.api.KernelConfig
import dev.trellis.kernel.api.KernelState
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.plugin.PluginState
import dev.trellis.kernel.spi.BuiltinLoader
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.storage.InMemoryStorageBackend
import dev.trellis.kernel.storage.StorageBackend
import dev.trellis.kernel.time.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val HOUR = 60L * 60L * 1000L
private const val DAY = 24L * HOUR

private class Harness(
    val kernel: Kernel,
    val plan: PlanService,
    val clock: FakeClock,
)

private suspend fun boot(
    storage: StorageBackend = InMemoryStorageBackend(),
    clock: FakeClock = FakeClock(1_700_000_000_000L),
): Harness {
    val kernel = Kernel.boot(
        KernelConfig(
            sdkVersion = SemVer.parse("1.0.0"),
            platform = Platform.JVM,
            loaders = listOf(BuiltinLoader(listOf(PlanCorePlugin()))),
            clock = clock,
            storage = storage,
            bootMode = BootMode.NORMAL,
        ),
    )
    val service = kernel.extensions.service(PlanService::class)
        ?: error("core.plan 没有发布 PlanService")
    return Harness(kernel, service, clock)
}

class PlanCoreTest {

    @Test
    fun theRequiredPluginBootsAndPublishesTheService() = runTest {
        val h = boot()
        assertEquals(KernelState.ACTIVE, h.kernel.currentState)
        assertEquals(PluginState.ACTIVE, h.kernel.plugin(PluginId("core.plan"))!!.state)
        assertTrue(h.kernel.errors.isEmpty(), h.kernel.errors.toString())
        h.kernel.shutdown()
    }

    @Test
    fun createPlanAndTasks() = runTest {
        val h = boot()
        val plan = h.plan.createPlan("6 周通过软考中级", targetDate = h.clock.nowMs() + 42 * DAY)

        val t1 = h.plan.addTask("第 1 章 计算机组成", planId = plan.id, dueAt = h.clock.nowMs() + DAY)
        h.plan.addTask("第 2 章 操作系统", planId = plan.id)

        assertEquals(1, h.plan.plans().size)
        assertEquals("6 周通过软考中级", h.plan.plans().first().title)
        assertEquals(2, h.plan.tasks(TaskQuery(planId = plan.id)).size)
        assertEquals(TaskStatus.TODO, t1.status)
        assertEquals(0, t1.spentMinutes)
        h.kernel.shutdown()
    }

    @Test
    fun unfinishedTasksComeFirstThenByDueDate() = runTest {
        val h = boot()
        val now = h.clock.nowMs()
        h.plan.addTask("最晚", dueAt = now + 3 * DAY)
        h.plan.addTask("最早", dueAt = now + DAY)
        h.plan.addTask("没截止")
        val done = h.plan.addTask("已完成", dueAt = now + 2 * DAY)
        h.plan.setStatus(done.id, TaskStatus.DONE)

        assertEquals(
            listOf("最早", "最晚", "没截止", "已完成"),
            h.plan.tasks().map { it.title },
        )
        assertEquals(
            listOf("最早", "最晚", "没截止"),
            h.plan.tasks(TaskQuery(includeDone = false)).map { it.title },
            "过滤掉已完成的应当只影响这一个条件，不改变其余顺序",
        )
        h.kernel.shutdown()
    }

    @Test
    fun completingATaskStampsTheCompletionTime() = runTest {
        val h = boot()
        val task = h.plan.addTask("写周报")
        assertNull(task.completedAt)

        h.clock.advanceBy(2 * HOUR)
        val done = h.plan.setStatus(task.id, TaskStatus.DONE)!!

        assertEquals(TaskStatus.DONE, done.status)
        assertEquals(h.clock.nowMs(), done.completedAt)
        assertTrue(done.isDone)

        // 重新打开时完成时间要清掉，否则统计会把它当成今天做完的
        val reopened = h.plan.setStatus(task.id, TaskStatus.TODO)!!
        assertNull(reopened.completedAt)
        h.kernel.shutdown()
    }

    @Test
    fun timeTrackingAndSummaryAccumulate() = runTest {
        val h = boot()
        val task = h.plan.addTask("刷题", estimateMinutes = 60)
        h.plan.logTime(task.id, 25)
        h.plan.logTime(task.id, 35)
        assertEquals(60, h.plan.task(task.id)!!.spentMinutes)

        h.plan.summarize(task.id, "知识点 3.2 还不熟")
        assertEquals("知识点 3.2 还不熟", h.plan.task(task.id)!!.note)
        h.kernel.shutdown()
    }

    @Test
    fun summarySplitsOverdueFromDueTodayAndIgnoresFinished() = runTest {
        val h = boot()
        val now = h.clock.nowMs()
        val dayEnd = now + DAY

        val a = h.plan.addTask("已完成的逾期任务", dueAt = now - HOUR)
        h.plan.setStatus(a.id, TaskStatus.DONE)
        h.plan.addTask("逾期的", dueAt = now - HOUR)
        val c = h.plan.addTask("今天的", dueAt = now + HOUR)
        h.plan.addTask("没截止的")
        h.plan.logTime(c.id, 30)

        assertEquals(
            PlanSummary(total = 4, done = 1, doing = 0, overdue = 1, dueToday = 1, spentMinutes = 30),
            h.plan.summary(now, dayEnd),
        )
        h.kernel.shutdown()
    }

    @Test
    fun deletingAPlanDetachesItsTasksInsteadOfDestroyingThem() = runTest {
        val h = boot()
        val plan = h.plan.createPlan("临时计划")
        h.plan.addTask("任务一", planId = plan.id)
        h.plan.addTask("任务二", planId = plan.id)

        h.plan.deletePlan(plan.id)

        assertNull(h.plan.plan(plan.id))
        assertEquals(2, h.plan.tasks().size, "用户的笔记不该因为整理归类而消失")
        assertTrue(h.plan.tasks().all { it.planId == null }, "它们应当降级成普通待办")
        h.kernel.shutdown()
    }

    @Test
    fun deletingATaskActuallyRemovesIt() = runTest {
        val h = boot()
        val task = h.plan.addTask("不要了")
        h.plan.deleteTask(task.id)
        assertNull(h.plan.task(task.id))
        assertEquals(0, h.plan.tasks().size)
        h.kernel.shutdown()
    }

    /**
     * 这条是「存储做对了没有」的判据：**换一个内核实例，数据还在。**
     *
     * 同一个 [StorageBackend] 就是同一份磁盘。真实后端落地后，
     * 这条测试一个字都不用改，只会从「内存里还在」变成「重启 App 还在」。
     */
    @Test
    fun dataSurvivesAKernelRestart() = runTest {
        val storage = InMemoryStorageBackend()
        val clock = FakeClock(1_700_000_000_000L)

        val first = boot(storage, clock)
        val plan = first.plan.createPlan("6 周通过软考中级")
        first.plan.addTask("第 1 章 计算机组成", planId = plan.id, dueAt = clock.nowMs() + DAY)
        val done = first.plan.addTask("第 2 章 操作系统", planId = plan.id)
        first.plan.setStatus(done.id, TaskStatus.DONE)
        first.kernel.shutdown()

        val second = boot(storage, clock)
        assertEquals(1, second.plan.plans().size)
        assertEquals("6 周通过软考中级", second.plan.plans().first().title)
        val tasks = second.plan.tasks(TaskQuery(planId = plan.id))
        assertEquals(2, tasks.size)
        assertEquals(listOf("第 1 章 计算机组成", "第 2 章 操作系统"), tasks.map { it.title })
        assertEquals(1, tasks.count { it.isDone })

        // 自增序列也要接着走，不能从 1 重来（否则会覆盖已有记录）
        val next = second.plan.addTask("第 3 章")
        assertTrue(
            tasks.none { it.id == next.id },
            "重启后新建的任务 id 撞上了已有记录：" + next.id,
        )
        second.kernel.shutdown()
    }

    @Test
    fun uninstallingPlanCoreRetainsItsDataByDefault() = runTest {
        val storage = InMemoryStorageBackend()
        val clock = FakeClock(1_700_000_000_000L)

        val first = boot(storage, clock)
        first.plan.createPlan("别弄丢了")
        // required 插件本来就不允许卸载  这条正好验证拒绝路径
        val rejected = first.kernel.uninstall(PluginId("core.plan"))
        assertTrue(rejected is dev.trellis.kernel.api.UninstallResult.Rejected, rejected.toString())
        assertEquals(1, first.plan.plans().size)
        first.kernel.shutdown()
    }
}
