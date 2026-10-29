package dev.trellis.activity

/**
 * 整理层：把 [ActivityStore] 里的原始事件算成「今天干了什么」。
 *
 * 只做四件事，前三件有实测依据（2026-10-27 的真实数据）：
 *
 * 1. **扣掉 AFK** —— 正确性问题，不是优化。那天 window 事件名义总时长 787.8 分钟，
 *    其中最长的一条 `QQ.exe` 名义 106.9 分钟，AFK 重叠 106.8 分钟：人根本不在。
 *    不扣就会写出「QQ 聊了 1 小时 47 分」这种报告，差两个数量级。
 * 2. **分类** —— 用 Trellis 自己的 [ActivityTaxonomy]，不读 AW 的 `category_sets`。
 * 3. **碎片合并** —— 只影响「流水能不能读」，对时长几乎没影响：
 *    那天 84.1% 的事件短于 5 秒，但它们加起来只占 16.8 / 787.8 分钟（2%）。
 *    所以它是内部清洗步骤，不是一个独立 API。
 * 4. **把浏览器标签页 URL 贴到窗口片段上** —— 窗口 watcher 只知道「chrome 在前台」，
 *    分类规则里的 [RuleField.DOMAIN] 需要 `web.tab.current` 那条流告诉它「在看 bilibili.com」。
 *    两条流按**时间重叠最多**配对，不是按启动顺序猜。
 *
 * 时段流水（时间轴）暂时不单独建表：现在没有消费方。等时间轴图表或任务归因真要开工时再加，
 * 免得先造一张只写不读的表。
 */
class ActivityAggregator(
    private val store: ActivityStore,
    private val settings: ActivitySettings = ActivitySettings(),
    private val taxonomy: ActivityTaxonomy = ActivityTaxonomy(),
) {

    private val classifier = taxonomy.classifier()

    /** 编译期发现的正则问题（空正则、编译不过）。设置页/诊断页应该把它显示出来。 */
    val classifierProblems: List<String> get() = classifier.problems

    /**
     * 一天的汇总。
     *
     * [DaySummary.categories] 里**同时**有直接时长和含子分类的总时长：
     * 「工作」这一行的 [CategorySlice.totalMs] 包含「工作/编程」「工作/办公」，
     * 但 [CategorySlice.directMs] 只算直接落在「工作」自己规则上的时间。
     * 两个数都留着，是因为饼图要 total（子分类不能重复占面积），
     * 而「工作大类里有多少是真的编程」要 direct。
     *
     * @param dayMs 这一天里的任意时刻（通常就是 `System.currentTimeMillis()`）。
     *   日界由 [ActivitySettings.dayStartHour] 决定，所以传进来的时刻只用来定位是哪一天。
     */
    fun daySummary(dayMs: Long): DaySummary {
        val snapshot = snapshot(dayMs)
        val segments = snapshot.pieces.map { it.toSegment(snapshot.tabs) }

        val directMs = LinkedHashMap<String, Long>()
        for (segment in segments) {
            directMs[segment.categoryId] = (directMs[segment.categoryId] ?: 0L) + segment.durationMs
        }

        // 向上累加：每个分类的总时长 = 直接时长 + 所有后代的总时长。环已经在 taxonomy.validate 里挡掉，
        // 这里仍然用 seen 兜一次底 —— 用户手改 JSON 写出环时，整理层不能死循环。
        val totalMs = LinkedHashMap<String, Long>()
        for (segment in segments) {
            var id: String? = segment.categoryId
            val seen = HashSet<String>()
            while (id != null && seen.add(id)) {
                totalMs[id] = (totalMs[id] ?: 0L) + segment.durationMs
                id = taxonomy.node(id)?.parentId
            }
        }

        val categories = totalMs.map { (id, total) ->
            val node = taxonomy.node(id)
            val path = taxonomy.pathNames(id).ifEmpty { listOf(node?.name ?: id) }
            CategorySlice(
                categoryId = id,
                name = path,
                color = node?.color ?: ActivityTaxonomy.UNCATEGORIZED_COLOR,
                depth = path.size,
                directMs = directMs[id] ?: 0L,
                totalMs = total,
            )
        }.sortedByDescending { it.totalMs }

        return DaySummary(
            dayStartMs = snapshot.fromMs,
            dayEndMs = snapshot.toMs,
            observedMs = snapshot.observedMs,
            afkMs = snapshot.afkMs,
            excludedMs = snapshot.excludedMs,
            activeMs = segments.sumOf { it.durationMs },
            segmentCount = segments.size,
            categories = categories,
            topApps = rank(segments, categoryId = null).take(TOP_APPS),
        )
    }

    /**
     * 按 app 排行的时长。
     *
     * @param categoryId 传了就只看这个分类及其所有后代（传 `"work"` 会包含 `work.coding`）。
     *   传 null = 所有分类。
     */
    fun appRanking(dayMs: Long, categoryId: String? = null): List<AppSlice> =
        rank(segments(dayMs), categoryId)

    /** 「为什么归到这个分类」——给规则调试用，直接转给 [ActivityClassifier.explain]。 */
    fun explain(app: String?, title: String?, url: String? = null): ClassificationExplanation =
        classifier.explain(app, title, url)

    /**
     * 清洗后的时间轴（内部用）。
     *
     * 每一段都满足：落在当天之内、不含 AFK 时间、短于 [ActivitySettings.minSegmentSeconds] 的碎片
     * 已经并进相邻段。同一时刻不会有两段重叠 —— 多台机器同时上报时按「先到先占」截断。
     */
    internal fun segments(dayMs: Long): List<Segment> {
        val snapshot = snapshot(dayMs)
        return snapshot.pieces.map { it.toSegment(snapshot.tabs) }
    }

    /** 一次读库，同时喂 [daySummary] 与 [segments]，避免同一天算两遍。 */
    private fun snapshot(dayMs: Long): Snapshot {
        val fromMs = settings.dayStartMs(dayMs)
        val toMs = settings.dayEndMs(dayMs)
        val buckets = store.buckets()
        val windowBuckets = buckets.filter { it.type == WINDOW_TYPE }.map { it.id }
        val afkBuckets = buckets.filter { it.type == AFK_TYPE }.map { it.id }
        val webBuckets = buckets.filter { it.type == ActivitySettings.WEB_TYPE }.map { it.id }

        val afkUnion = afkUnion(afkBuckets, fromMs, toMs)
        val afkMs = afkUnion.sumOf { it.durationMs() }

        val window = ArrayList<Piece>()
        var excludedMs = 0L
        for (bucket in windowBuckets) {
            for (event in store.eventsByRange(fromMs, toMs, bucket)) {
                val start = maxOf(event.tsMs, fromMs)
                val end = minOf(event.endMs, toMs)
                if (end <= start) continue
                val app = event.app
                if (app != null && app.trim().lowercase() in settings.excludedAppsLower) {
                    excludedMs += end - start
                    continue
                }
                window += Piece(start, end, app, event.title)
            }
        }

        // 先去掉跨 bucket 的重复计时，再拿名义总时长 —— 两台机器上报的同一段时间只能算一次。
        val windowClean = dedupe(window)
        val observedMs = windowClean.sumOf { it.durationMs() }

        val net = ArrayList<Piece>()
        for (piece in windowClean) {
            var cursor = piece.fromMs
            for (afk in afkUnion) {
                if (afk.toMs <= cursor) continue
                if (afk.fromMs >= piece.toMs) break
                if (afk.fromMs > cursor) {
                    net += Piece(cursor, minOf(afk.fromMs, piece.toMs), piece.app, piece.title)
                }
                cursor = maxOf(cursor, afk.toMs)
                if (cursor >= piece.toMs) break
            }
            if (cursor < piece.toMs) net += Piece(cursor, piece.toMs, piece.app, piece.title)
        }

        return Snapshot(
            fromMs = fromMs,
            toMs = toMs,
            observedMs = observedMs,
            afkMs = afkMs,
            excludedMs = excludedMs,
            pieces = absorb(net),
            tabs = tabPieces(webBuckets, fromMs, toMs),
        )
    }

    /**
     * 浏览器标签页事件，裁到当天之内。
     *
     * `web.tab.current` 的 `app` 是空的（浏览器自己知道是哪个 profile，但事件里没有进程名），
     * 所以这里只保留 url/time，等贴到窗口片段上时再用窗口的 app 判断是不是浏览器。
     */
    private fun tabPieces(webBuckets: List<String>, fromMs: Long, toMs: Long): List<TabPiece> {
        val out = ArrayList<TabPiece>()
        for (bucket in webBuckets) {
            for (event in store.eventsByRange(fromMs, toMs, bucket)) {
                val url = event.url?.takeIf { it.isNotBlank() } ?: continue
                val start = maxOf(event.tsMs, fromMs)
                val end = minOf(event.endMs, toMs)
                if (end > start) out += TabPiece(start, end, url, event.title)
            }
        }
        out.sortBy { it.fromMs }
        return out
    }

    /** 当天的 AFK 区间，合并成不重叠的并集（aw-server 可能把相邻两段分开上报）。 */
    private fun afkUnion(afkBuckets: List<String>, fromMs: Long, toMs: Long): List<Piece> {
        val raw = ArrayList<Piece>()
        for (bucket in afkBuckets) {
            for (event in store.eventsByRange(fromMs, toMs, bucket)) {
                if (event.status != AFK_STATUS) continue
                val start = maxOf(event.tsMs, fromMs)
                val end = minOf(event.endMs, toMs)
                if (end > start) raw += Piece(start, end, null, null)
            }
        }
        raw.sortBy { it.fromMs }
        val union = ArrayList<Piece>()
        for (piece in raw) {
            val last = union.lastOrNull()
            if (last != null && piece.fromMs <= last.toMs) {
                last.toMs = maxOf(last.toMs, piece.toMs)
            } else {
                union += piece
            }
        }
        return union
    }

    /** 排序、合并相邻的同窗口事件、截断同时刻的重复计时（多台机器 / 多个 bucket）。 */
    private fun dedupe(source: List<Piece>): List<Piece> {
        val sorted = source.sortedBy { it.fromMs }

        val merged = ArrayList<Piece>()
        for (piece in sorted) {
            val last = merged.lastOrNull()
            if (last != null && piece.fromMs <= last.toMs) {
                if (last.app == piece.app && last.title == piece.title) {
                    last.toMs = maxOf(last.toMs, piece.toMs)
                    continue
                }
                // 两个不同的窗口在同一时刻都有事件：截断，避免重复计时。
                if (piece.toMs > last.toMs) {
                    piece.fromMs = last.toMs
                    merged += piece
                }
                continue
            }
            merged += piece
        }
        return merged
    }

    /** 把短于 [ActivitySettings.minSegmentSeconds] 的碎片并进相邻段。 */
    private fun absorb(merged: List<Piece>): List<Piece> {
        val minMs = settings.minSegmentMs
        if (minMs <= 0) return merged

        val kept = ArrayList<Piece>()
        val leading = ArrayList<Piece>()
        for (piece in merged) {
            if (piece.durationMs() >= minMs) {
                // 开头那一串碎片（前面没有可并的段）并进第一个正常段。
                if (leading.isNotEmpty()) {
                    piece.fromMs = leading.first().fromMs
                    leading.clear()
                }
                kept += piece
                continue
            }
            val previous = kept.lastOrNull()
            if (previous != null && previous.toMs >= piece.fromMs) {
                previous.toMs = maxOf(previous.toMs, piece.toMs)
            } else if (previous == null) {
                leading += piece
            }
            // 碎片正好落在 AFK 断裂处（前后都不连续）时直接丢掉：它本身不足 minSegmentSeconds 秒。
        }

        return if (kept.isEmpty()) merged else kept
    }

    /**
     * 给浏览器窗口片段找重叠最多的那条标签页事件。
     *
     * 为什么取「重叠最多」而不是「包含片段起点」：切标签页的瞬间两条流的时间戳会差几十毫秒到几秒，
     * 起点落在哪条事件里带随机性；重叠最多的那条才是这段时间里真正在看的页面。
     * 只有浏览器进程才贴 URL —— 否则「在 chrome 里看 B 站」的 URL 会被贴到旁边的记事本上。
     */
    private fun attachUrl(piece: Piece, tabs: List<TabPiece>): String? {
        if (!settings.isBrowserApp(piece.app)) return null
        var best: String? = null
        var bestOverlap = 0L
        for (tab in tabs) {
            if (tab.toMs <= piece.fromMs) continue
            if (tab.fromMs >= piece.toMs) break
            val overlap = minOf(tab.toMs, piece.toMs) - maxOf(tab.fromMs, piece.fromMs)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = tab.url
            }
        }
        return best
    }

    private fun rank(segments: List<Segment>, categoryId: String?): List<AppSlice> {
        val acc = LinkedHashMap<String, Long>()
        for (segment in segments) {
            if (categoryId != null && !taxonomy.isDescendantOf(segment.categoryId, categoryId)) continue
            val app = segment.app?.takeIf { it.isNotBlank() } ?: UNKNOWN_APP
            acc[app] = (acc[app] ?: 0L) + segment.durationMs
        }
        return acc.entries
            .map { AppSlice(app = it.key, durationMs = it.value) }
            .sortedByDescending { it.durationMs }
    }

    private fun Piece.toSegment(tabs: List<TabPiece>): Segment {
        val url = attachUrl(this, tabs)
        val match = classifier.classify(app, title, url)
        return Segment(
            fromMs = fromMs,
            toMs = toMs,
            app = app,
            title = title,
            url = url,
            categoryId = match.categoryId,
            category = match.path,
            color = match.color,
        )
    }

    /** 读库的中间结果：[pieces] 已是清洗后的最终时间轴，[tabs] 是同一时段内的浏览器标签页。 */
    private class Snapshot(
        val fromMs: Long,
        val toMs: Long,
        val observedMs: Long,
        val afkMs: Long,
        val excludedMs: Long,
        val pieces: List<Piece>,
        val tabs: List<TabPiece>,
    )

    companion object {
        const val WINDOW_TYPE: String = ActivitySettings.WINDOW_TYPE
        const val AFK_TYPE: String = ActivitySettings.AFK_TYPE
        const val AFK_STATUS: String = "afk"

        /** 进程名为空时（浏览器 watcher 的事件没有 `app`）在排行里显示成这个。 */
        const val UNKNOWN_APP: String = "（未知）"

        private const val TOP_APPS: Int = 10
    }
}

/**
 * 清洗后时间轴上的一段：从 [fromMs] 到 [toMs]，是 [app] 的窗口 [title]，归到 [categoryId] 这个分类。
 *
 * [url] 只对浏览器片段有值（来自 `web.tab.current`），[title] 始终是窗口标题 ——
 * 窗口标题通常已经包含当前标签页标题，且比标签页自己的 title 更贴近「用户看到的窗口」。
 *
 * 一天通常有几十到几百段（3700 条原始事件合并之后）。
 */
data class Segment(
    val fromMs: Long,
    val toMs: Long,
    val app: String?,
    val title: String?,
    /** 浏览器标签页 URL；非浏览器片段为 null。 */
    val url: String?,
    /** 分类的稳定 id，例如 `work.coding`。 */
    val categoryId: String,
    /** 分类的完整名字路径，例如 `["工作", "编程"]`。 */
    val category: List<String>,
    /** 分类颜色（`#RRGGBB`），画时间轴/饼图时直接用。 */
    val color: String,
) {
    val durationMs: Long get() = (toMs - fromMs).coerceAtLeast(0)

    /** 分类的展示形式，例如 `工作/编程`。 */
    val categoryPath: String get() = category.joinToString("/")
}

/**
 * 一个分类的时长。
 *
 * [directMs] 是直接落在这个分类规则上的时长；[totalMs] 是它**加上所有子分类**的时长。
 * 看「哪类占大头」用 [totalMs]（不会重复计算子孙），看「这个分类自己的规则命中了多少」用 [directMs]。
 */
data class CategorySlice(
    val categoryId: String,
    /** 完整名字路径，例如 `["工作", "编程"]`。 */
    val name: List<String>,
    val color: String,
    /** 1 = 一级分类，2 = 二级…… */
    val depth: Int,
    val directMs: Long,
    val totalMs: Long,
) {
    val path: String get() = name.joinToString("/")
}

/** 一个进程的时长。 */
data class AppSlice(
    val app: String,
    val durationMs: Long,
)

/**
 * 一天的汇总。
 *
 * 几个时长之间的关系，写在这里免得每次都要重新推：
 * - [observedMs]：当天的窗口事件名义总时长（已排除 [ActivitySettings.excludedApps]）。
 * - [afkMs]：当天的 AFK 总时长。**它可能比 [observedMs] 还大** —— 锁屏/休眠时窗口 watcher 没有事件，
 *   AFK watcher 还在记。
 * - [activeMs]：扣掉 AFK 之后的净时长，也就是真正「在电脑前」的时间。它 = [observedMs] - AFK 重叠部分。
 * - [excludedMs]：被 [ActivitySettings.excludedApps] 排除掉的时长，只在汇总里露一个数字，不进任何分类。
 */
data class DaySummary(
    val dayStartMs: Long,
    val dayEndMs: Long,
    val observedMs: Long,
    val afkMs: Long,
    val excludedMs: Long,
    val activeMs: Long,
    val segmentCount: Int,
    val categories: List<CategorySlice>,
    val topApps: List<AppSlice>,
) {
    /** 当天一条事件都没有。 */
    val isEmpty: Boolean get() = observedMs == 0L

    /** 没有 AFK 记录时（只装了 window watcher）[activeMs] 等于 [observedMs]，这个数字就不可信。 */
    val hasAfkData: Boolean get() = afkMs > 0L

    /** 取某个分类（按 id）的切片；没有该分类的活动时返回 null。 */
    fun category(categoryId: String): CategorySlice? = categories.firstOrNull { it.categoryId == categoryId }
}

/**
 * 清洗过程中的工作副本：`fromMs`/`toMs` 会被就地改写（合并、截断、吸收碎片），
 * 所以它只在 [ActivityAggregator] 内部存在，不对外暴露。
 */
private class Piece(
    var fromMs: Long,
    var toMs: Long,
    val app: String?,
    val title: String?,
) {
    fun durationMs(): Long = (toMs - fromMs).coerceAtLeast(0)
}

/** 浏览器标签页片段（内部用）。 */
private class TabPiece(
    val fromMs: Long,
    val toMs: Long,
    val url: String,
    val title: String?,
)