package dev.trellis.activity

/**
 * ActivityWatch 的一条原始事件，字段照抄 aw-server 的返回，**不在入口处做任何加工**。
 *
 * 为什么保留 `title`：它是唯一能区分「同一个 app 在做什么」的线索，也是隐私上最敏感的一份。
 * 存储阶段不裁剪（要能回溯），裁剪发生在「给 AI 看」那一步（见 exposure 包）。
 */
data class RawEvent(
    /** aw-server 里那个 bucket，例如 `aw-watcher-window_DESKTOP-9DEEU93`。 */
    val bucket: String,
    /** aw-server 自己的自增 id。用于幂等 upsert —— 同一秒重复拉两次不会变成两条。 */
    val awId: Long,
    /** 事件开始时间，epoch 毫秒（已经按 aw-server 给的时区偏移换算成绝对时刻）。 */
    val tsMs: Long,
    /** 时长，毫秒。aw-server 给的是浮点秒，落库时四舍五入到毫秒。 */
    val durMs: Long,
    /** `currentwindow` bucket 里的进程名，例如 `chrome.exe`。 */
    val app: String? = null,
    /** `currentwindow` bucket 里的窗口标题。 */
    val title: String? = null,
    /**
     * 浏览器标签页 URL。只有 `web.tab.current` bucket 有，`currentwindow` 没有。
     *
     * 为什么值得单独存：窗口标题只能看出「在 B 站看视频」，URL 才能落到具体站点/分区；
     * 分类规则里的 [RuleField.DOMAIN] 就靠它。注意它同时也是隐私上最敏感的一份
     * （可能带搜索词、视频 id），存是本地存，给 AI 之前仍按 [AiExposure] 裁剪。
     */
    val url: String? = null,
    /** `afkstatus` bucket 里的状态：`afk` 或 `not-afk`。 */
    val status: String? = null,
) {
    val endMs: Long get() = tsMs + durMs
}

/** aw-server 里的一个 bucket 的元信息。 */
data class AwBucket(
    val id: String,
    /** `currentwindow` / `afkstatus` / 浏览器 watcher 的 `web.tab.current` 等。 */
    val type: String,
    val hostname: String? = null,
    val createdAt: String? = null,
)