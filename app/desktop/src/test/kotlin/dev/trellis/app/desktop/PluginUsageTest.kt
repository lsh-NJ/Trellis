package dev.trellis.app.desktop

import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「上次用」的记录与写法。
 *
 * 这两件事都容易写错：时间戳存成字符串会丢精度、相对时间的边界（59 秒 / 1 分钟）
 * 写法不一致会让同一天的两次显示互相矛盾。所以单独钉一遍。
 */
class PluginUsageTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val tempDirs = mutableListOf<File>()

    private fun at(local: String): Long =
        OffsetDateTime.parse(local + "+08:00").toInstant().toEpochMilli()

    private fun tempHome(): File =
        File.createTempFile("trellis-usage-", "").let {
            it.delete()
            it.mkdirs()
            tempDirs += it
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun describesRecentAndOlderTimesInChinese() {
        val now = at("2026-10-26T21:27:00")

        assertEquals("还没用过", PluginUsageStore.describe(now, null, zone))
        assertEquals("还没用过", PluginUsageStore.describe(now, 0L, zone))
        assertEquals("刚刚", PluginUsageStore.describe(now, now - 30_000L, zone))
        assertEquals("5 分钟前", PluginUsageStore.describe(now, now - 5 * 60_000L, zone))
        assertEquals("今天 09:12", PluginUsageStore.describe(now, at("2026-10-26T09:12:00"), zone))
        assertEquals("昨天 18:02", PluginUsageStore.describe(now, at("2026-10-25T18:02:00"), zone))
        assertEquals("10-23 09:12", PluginUsageStore.describe(now, at("2026-10-23T09:12:00"), zone))
    }

    @Test
    fun futureTimestampsAreTreatedAsUnused() {
        val now = at("2026-10-26T21:27:00")
        assertEquals("还没用过", PluginUsageStore.describe(now, now + 60_000L, zone))
    }

    @Test
    fun usageSurvivesARestart() {
        val home = tempHome()
        val now = at("2026-10-26T21:27:00")
        PluginUsageStore(home).save(mapOf("dev.bento.shift" to now, "ai.pushups" to now - 1_000L))

        val loaded = PluginUsageStore(home).load()

        assertEquals(2, loaded.size)
        assertEquals(now, loaded["dev.bento.shift"])
        assertEquals(now - 1_000L, loaded["ai.pushups"])
    }

    @Test
    fun brokenFileReadsAsEmptyInsteadOfCrashing() {
        val home = tempHome()
        File(home, "plugin-usage.json").writeText("{ 这不是 JSON", Charsets.UTF_8)

        assertTrue(PluginUsageStore(home).load().isEmpty())
    }

    @Test
    fun byteOrderMarkIsAccepted() {
        val home = tempHome()
        val now = at("2026-10-26T21:27:00")
        File(home, "plugin-usage.json")
            .writeText("\uFEFF{\"used\":{\"dev.bento.shift\":" + now + "}}", Charsets.UTF_8)

        assertEquals(now, PluginUsageStore(home).load()["dev.bento.shift"])
    }

    @Test
    fun hoverHintSaysWhetherTheThemeIsInUseRightNow() {
        val now = at("2026-10-26T21:27:00")
        val applied = at("2026-10-26T21:20:00")

        assertEquals(
            // 7 分钟前：一小时以内走相对时间，比「今天 21:20」更像「刚才还在用」
            "正在使用 · 7 分钟前",
            usageHint(now, applied, providesTheme = true, themeActive = true),
        )
        // 老 ui.json 里有主题、但没有记过时间：不编造时间，只说「正在使用」
        assertEquals("正在使用", usageHint(now, null, providesTheme = true, themeActive = true))
        assertEquals(
            "上次用：昨天 18:02",
            usageHint(now, at("2026-10-25T18:02:00"), providesTheme = true, themeActive = false),
        )
        assertEquals("还没用过", usageHint(now, null, providesTheme = true, themeActive = false))
        // 不提供主题、也没用过：悬停不长东西，避免布局跳
        assertEquals(null, usageHint(now, null, providesTheme = false, themeActive = false))
    }
}
