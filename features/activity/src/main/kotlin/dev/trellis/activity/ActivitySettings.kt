package dev.trellis.activity

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

/**
 * 活动采集与整理的**全部设置**，一律存在本程序里。
 *
 * 为什么不用 AW 自己的 settings：那些设置（`startOfDay`、`category_sets`）属于 AW 的界面，
 * 用户在 Trellis 里改不到，而且 AW 的一级/二级分类是给人眼看的，不是给程序算账用的。
 * 这里的取值只影响 Trellis 怎么读、怎么整理、怎么给 AI 看，AW 那边一个字都不改。
 */
@Serializable
data class ActivitySettings(
    /** 总开关。关掉之后同步循环不跑，但已有的原始数据仍然可以查。 */
    val enabled: Boolean = true,
    val baseUrl: String = ActivityWatchClient.DEFAULT_BASE_URL,
    /** AW 默认不鉴权；开了鉴权再填。 */
    val apiKey: String = "",
    /** 前台每轮同步的间隔。AW 没有推送，只能轮询。 */
    val syncIntervalSeconds: Int = 60,
    /** 第一次接入时往前补采多少天的历史。 */
    val backfillDays: Int = 14,
    /**
     * 每轮重拉尾部多长时间。
     * 必须 > 0：aw-server 会不断延长「当前窗口」那条事件的 duration，
     * 只拉增量会永远漏掉最后一段。
     */
    val overlapSeconds: Int = 300,
    /** 原始事件保留天数；0 = 永久保留。 */
    val retentionDays: Int = 0,
    /**
     * 「一天」从几点开始算。
     * AW 的默认是 4:00（给夜猫子用），Trellis 默认 0:00 —— 这个差异会让两边的日统计对不上，
     * 所以它必须是显式设置，而不是隐式继承 AW。
     */
    val dayStartHour: Int = 0,
    /** 短于这个秒数的碎片（悬停、误触）在整理时并入「其他」，不给 AI 看。 */
    val minSegmentSeconds: Int = 5,
    /** 完全不计入的进程名（小写比较），例如密码管理器。 */
    val excludedApps: List<String> = emptyList(),
    /** 命中这些关键字的窗口标题在**输出前**被抹掉，原始库里仍然保留。 */
    val titleRedactions: List<String> = emptyList(),
    /** 给 AI 看多少。默认只给聚合数字，标题一律不出门。 */
    val aiExposure: AiExposure = AiExposure.AGGREGATES_ONLY,
) {

    /** 需要采集的 bucket 类型。浏览器 watcher 以后加进来只需要改这里。 */
    val watchedTypes: Set<String> get() = WATCHED_TYPES

    val minSegmentMs: Long get() = minSegmentSeconds.coerceAtLeast(0) * 1000L

    val overlapMs: Long get() = overlapSeconds.coerceAtLeast(0) * 1000L

    val backfillMs: Long get() = backfillDays.coerceAtLeast(0) * DAY_MS

    val retentionMs: Long get() = retentionDays.coerceAtLeast(0) * DAY_MS

    val excludedAppsLower: Set<String> get() = excludedApps.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** 以 `dayStartHour` 为界的「今天」起点。 */
    fun dayStartMs(nowMs: Long): Long {
        val zone = ZoneId.systemDefault()
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        val boundary = local.toLocalDate().atTime(dayStartHour.coerceIn(0, 23), 0).atZone(zone)
        return if (local.isBefore(boundary)) {
            boundary.minusDays(1).toInstant().toEpochMilli()
        } else {
            boundary.toInstant().toEpochMilli()
        }
    }

    /** 与 `dayStartMs` 配对的「今天」终点。 */
    fun dayEndMs(nowMs: Long): Long {
        val zone = ZoneId.systemDefault()
        val local = Instant.ofEpochMilli(dayStartMs(nowMs)).atZone(zone)
        return local.plusDays(1).toInstant().toEpochMilli()
    }

    companion object {
        const val DAY_MS: Long = 86_400_000L

        val WATCHED_TYPES: Set<String> = setOf("currentwindow", "afkstatus")

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** 文件不存在或读坏了都回到默认值 —— 采集是后台功能，不该因为配置文件坏了挡住启动。 */
        fun load(file: Path): ActivitySettings {
            if (!Files.exists(file)) return ActivitySettings()
            return runCatching { json.decodeFromString<ActivitySettings>(Files.readString(file)) }
                .getOrElse { ActivitySettings() }
        }

        fun save(file: Path, settings: ActivitySettings) {
            Files.createDirectories(file.toAbsolutePath().parent)
            Files.writeString(file, json.encodeToString(settings))
        }
    }
}

/** 给 AI 看的暴露级别。默认最保守。 */
@Serializable
enum class AiExposure {
    /** 一个字都不给 AI。 */
    NONE,

    /** 只给聚合数字（分类时长、占比），不含任何窗口标题或进程名细节。 */
    AGGREGATES_ONLY,

    /** 聚合数字 + 进程名（仍然不含窗口标题）。 */
    AGGREGATES_WITH_APPS,

    /** 聚合数字 + 进程名 + 经过 `titleRedactions` 过滤的标题。最宽松的一档，默认不用。 */
    AGGREGATES_WITH_TITLES,
}