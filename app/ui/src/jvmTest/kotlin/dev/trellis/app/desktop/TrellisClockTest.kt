package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 时间源与开发者跳日期。
 *
 * 规范见 `docs/time-conventions.md`：业务时间只有一个来源；模拟日期只在安全的数据目录下生效；
 * 拨日期只影响「今天」，不碰单调时间（计时器/超时）。
 */
class TrellisClockTest {

    @Test
    fun theDebugClockOnlyMovesTheDateNotTheMonotonicClock() {
        val real = SystemTrellisClock()
        val clock = DebugTrellisClock(real)

        assertNull(clock.simulatedDate(), "默认是真实时钟")
        assertEquals(real.today(), clock.today())

        clock.setSimulatedDate(LocalDate.parse("2026-11-02"))

        assertEquals(LocalDate.parse("2026-11-02"), clock.today())
        assertEquals("2026-W45", clock.isoWeek(), "2026-11-02 是周一，属于 W45")
        // 单调时间不能跟着跳：计时器/超时/看门狗不该因为拨日期而变化。
        assertTrue(clock.monotonicMs() >= 0)
        // 时刻仍取真实 wall clock 的时分秒：记录不会整片变成 00:00。
        val realNow = java.time.Instant.ofEpochMilli(real.nowMs()).atZone(clock.zone)
        val simulatedNow = java.time.Instant.ofEpochMilli(clock.nowMs()).atZone(clock.zone)
        assertEquals("2026-11-02", simulatedNow.toLocalDate().toString())
        assertEquals(realNow.toLocalTime().hour, simulatedNow.toLocalTime().hour)

        clock.setSimulatedDate(null)
        assertNull(clock.simulatedDate())
        assertEquals(real.today(), clock.today())
    }

    @Test
    fun isoWeekUsesTheIsoWeekBasedYearAcrossTheBoundary() {
        // 2026-12-31 属于 2026-W53（2026 年是 53 周年份）；跨到 2027-01-01 就是 2026-W53 的最后几天。
        assertEquals("2026-W53", isoWeekOf(LocalDate.parse("2026-12-31")))
        assertEquals("2026-W53", isoWeekOf(LocalDate.parse("2027-01-01")))
        // 2027 年第 1 周从 2027-01-04（周一）开始。
        assertEquals("2027-W01", isoWeekOf(LocalDate.parse("2027-01-04")))
        // 普通周：2026-10-29 是周四。
        assertEquals("2026-W44", isoWeekOf(LocalDate.parse("2026-10-29")))
    }

    @Test
    fun theDevClockFileRemembersTheDateAndIsDeletedOnReset() {
        val home = Files.createTempDirectory("trellis-clock").toFile()
        try {
            val store = DevClockStore(home)
            assertNull(store.load(), "没有文件时就是真实时间")

            store.save(LocalDate.parse("2026-11-02"))
            assertEquals(LocalDate.parse("2026-11-02"), store.load())
            assertTrue(File(home, "dev-clock.json").isFile)

            store.save(null)
            assertNull(store.load(), "退出模拟要把文件删掉，否则下次启动又悄悄生效")
            assertFalse(File(home, "dev-clock.json").exists())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun theEnvironmentDateIsOnlyHonouredOutsideTheDefaultDataDirectory() {
        val home = Files.createTempDirectory("trellis-demo-home").toFile()
        val defaultHome = Files.createTempDirectory("trellis-default-home").toFile()
        try {
            val env = mapOf("TRELLIS_TODAY" to "2026-11-02")

            val enabled = createTrellisClock(home, env = env, defaultHome = defaultHome)
            assertTrue(enabled.simulated, "演示目录应该允许模拟时间")
            assertEquals(LocalDate.parse("2026-11-02"), enabled.clock.simulatedDate())
            assertEquals("2026-W45", enabled.clock.isoWeek())

            // 默认数据目录：拒绝，避免模拟日期把真实计划写满 missed / 结算事件。
            val refused = createTrellisClock(defaultHome, env = env, defaultHome = defaultHome)
            assertFalse(refused.simulated)
            assertNull(refused.clock.simulatedDate())
            assertFalse(refused.allowed)
            assertTrue(refused.rejectedReason!!.contains("TRELLIS_DEV_ALLOW_REAL_HOME"), refused.rejectedReason!!)

            // 显式确认要冒险，就允许。
            val override = createTrellisClock(
                defaultHome,
                env = env + ("TRELLIS_DEV_ALLOW_REAL_HOME" to "1"),
                defaultHome = defaultHome,
            )
            assertTrue(override.simulated)
        } finally {
            home.deleteRecursively()
            defaultHome.deleteRecursively()
        }
    }

    @Test
    fun aMalformedDateIsRejectedWithAReadableReason() {
        val home = Files.createTempDirectory("trellis-bad-clock").toFile()
        try {
            val setup = createTrellisClock(
                home,
                env = mapOf("TRELLIS_TODAY" to "下周一"),
                defaultHome = File(home, "not-this-one"),
            )
            assertFalse(setup.simulated)
            assertNull(setup.clock.simulatedDate())
            assertTrue(setup.rejectedReason!!.contains("YYYY-MM-DD"), setup.rejectedReason!!)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun devModeLoadsThePersistedDateOnStartup() {
        val home = Files.createTempDirectory("trellis-dev-mode").toFile()
        try {
            DevClockStore(home).save(LocalDate.parse("2026-11-05"))

            // 没开开发者模式：不读文件，避免普通用户重启后突然「穿越」。
            val normal = createTrellisClock(home, env = emptyMap(), defaultHome = File(home, "default"))
            assertFalse(normal.simulated)
            assertNull(normal.clock.simulatedDate())

            // 开了开发者模式：恢复上次拨到的日期。
            val dev = createTrellisClock(
                home,
                env = mapOf("TRELLIS_DEV" to "1"),
                defaultHome = File(home, "default"),
            )
            assertTrue(dev.simulated)
            assertEquals(LocalDate.parse("2026-11-05"), dev.clock.simulatedDate())
        } finally {
            home.deleteRecursively()
        }
    }
}
