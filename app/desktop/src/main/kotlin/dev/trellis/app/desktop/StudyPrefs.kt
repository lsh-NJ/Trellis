package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 学习节奏：用户对自己「一天能投多少」的估计。
 *
 * 和 [UiPrefs] 分开：那个决定界面长什么样（配色、密度、导航），这个决定计划怎么排。
 * 混在一份里，改个字号就得重写排期参数，反过来也一样。
 *
 * 只留一个数 —— **每天预计投入的分钟数**。它是用户唯一的输入，其余（每周容量、
 * 排不排得下）都由它派生；让用户填一串数字，最后没人说得清哪个在起作用。
 */
data class StudyPrefs(
    val dailyMinutes: Int = DEFAULT_DAILY_MINUTES,
) {
    /** 收进合法区间。越界的输入不报错，界面按钮和 agent 都靠这一处夹取。 */
    fun normalized(): StudyPrefs =
        copy(dailyMinutes = dailyMinutes.coerceIn(MIN_DAILY_MINUTES, MAX_DAILY_MINUTES))

    /**
     * 写进学习计划 `process.weekly_capacity_minutes` 的值：一天 × 7。
     *
     * 后端那道 CHECK 要求 30–3000，而 [MIN_DAILY_MINUTES] / [MAX_DAILY_MINUTES]
     * 保证乘完仍落在里面 —— 所以这里不必再夹一次。
     */
    val weeklyMinutes: Int get() = dailyMinutes * 7

    /** 设置页那句解释：把分钟数翻成好读的量。 */
    fun describe(): String = "每天 " + dailyMinutes + " 分钟（一周约 " + (weeklyMinutes / 60) + " 小时）"
}

internal const val DEFAULT_DAILY_MINUTES: Int = 60
internal const val MIN_DAILY_MINUTES: Int = 15
internal const val MAX_DAILY_MINUTES: Int = 240

/** 落盘在宿主目录的 `study.json`；坏文件当没改过，不阻断启动。 */
internal class StudyPrefsStore(home: File) {
    private val file = File(home, "study.json")

    fun load(): StudyPrefs {
        // 带 BOM 的文件 JSON 解析器不认它，会让整份偏好悄悄退回默认值。
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?.removePrefix("\uFEFF") ?: return StudyPrefs()
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return StudyPrefs()
        return StudyPrefs(
            dailyMinutes = obj["dailyMinutes"]?.jsonPrimitive?.intOrNull ?: DEFAULT_DAILY_MINUTES,
        ).normalized()
    }

    fun save(prefs: StudyPrefs) {
        val clean = prefs.normalized()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                buildJsonObject { put("dailyMinutes", clean.dailyMinutes) }.toString(),
                Charsets.UTF_8,
            )
        }
    }
}
