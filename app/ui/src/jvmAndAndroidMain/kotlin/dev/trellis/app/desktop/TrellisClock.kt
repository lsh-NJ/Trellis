package dev.trellis.app.desktop

import dev.trellis.kernel.time.KernelClock
import dev.trellis.kernel.time.SystemClock
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 应用统一时间源。
 *
 * 规范见 `docs/time-conventions.md`：业务时间（今天 / 本周 / 事件时间戳）只能从这里取；
 * 计时器与超时走 [monotonicMs]，不受模拟日期影响。
 *
 * 这一层存在的原因：计划周滚动、结算结转、复盘、逾期这些逻辑都是「时间推移才发生」的，
 * 真机验证要么等一周、要么改系统时钟。有了可注入/可跳日期的时钟，测试用固定时钟，
 * 调试用开发者模式把「应用内今天」拨到任意一天。
 */
interface TrellisClock : KernelClock {
    val zone: ZoneId

    /** 业务意义上的「今天」（本地时区）。 */
    fun today(): LocalDate

    /** 今天所在的 ISO 周，比如 "2026-W44"。 */
    fun isoWeek(): String

    /** 非 null = 正在用模拟日期（调试模式）；真实时钟永远返回 null。 */
    fun simulatedDate(): LocalDate?
}

/** 生产时钟：墙钟走系统时钟，单调钟也走系统时钟。 */
class SystemTrellisClock(
    override val zone: ZoneId = ZoneId.systemDefault(),
) : TrellisClock {
    override fun nowMs(): Long = SystemClock.nowMs()
    override fun monotonicMs(): Long = SystemClock.monotonicMs()
    override fun today(): LocalDate =
        java.time.Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()

    override fun isoWeek(): String = isoWeekOf(today())
    override fun simulatedDate(): LocalDate? = null
}

/**
 * 可拨日期的时钟（开发者模式）。
 *
 * 只改「今天是几号」：现在几点、是否跨天判定、事件时间戳的时分秒仍然取真实 wall clock，
 * 所以跳日期之后的记录看起来像「那天此刻发生的」，不会整片都是 00:00。
 *
 * 单调时间始终走真实时钟——计时器、HTTP 超时、看门狗不应该因为跳日期而变化。
 */
class DebugTrellisClock(
    private val real: TrellisClock = SystemTrellisClock(),
) : TrellisClock {

    @Volatile
    private var simulated: LocalDate? = null

    override val zone: ZoneId get() = real.zone

    override fun nowMs(): Long {
        val date = simulated ?: return real.nowMs()
        val realNow = java.time.Instant.ofEpochMilli(real.nowMs()).atZone(zone)
        return date.atTime(realNow.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
    }

    override fun monotonicMs(): Long = real.monotonicMs()
    override fun today(): LocalDate = simulated ?: real.today()
    override fun isoWeek(): String = isoWeekOf(today())
    override fun simulatedDate(): LocalDate? = simulated

    /** null = 回到真实时间。 */
    fun setSimulatedDate(date: LocalDate?) {
        simulated = date
    }
}

/**
 * 一个 ISO 周字符串，比如 "2026-W44"。
 *
 * app 层只保留这一份实现（与后端 `PlanTime.isoWeek` 同规则：ISO-8601、周一起）：
 * 以前 `AppRuntime` / `PlanTools` / `PlanAdminTools` 各抄了一遍，三处一旦不同步，
 * 「本周」在工具和页面里就会差一周。新增代码不要再写第四份。
 */
internal fun isoWeekOf(date: LocalDate): String = "%04d-W%02d".format(
    date.get(IsoFields.WEEK_BASED_YEAR),
    date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
)

/** 解析 `TRELLIS_TODAY` / `dev-clock.json` 里的日期；不合法返回 null（调用方负责报原因）。 */
internal fun parseSimulatedDate(raw: String?): LocalDate? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { LocalDate.parse(text) }.getOrNull()
}

/**
 * 开发者跳日期的持久化：`<home>/dev-clock.json`。
 *
 * 不持久化的话，模拟日期一重启就丢，测到一半还得重新拨。
 * 只在开发者模式（`TRELLIS_DEV=1`）下读写；退出模拟时删文件。
 */
internal class DevClockStore(private val home: File) {

    private val file = File(home, "dev-clock.json")

    fun load(): LocalDate? = runCatching {
        if (!file.isFile) return null
        // 允许被编辑器/脚本写过带 BOM 的文件（仓库里其他配置也做了同样处理）。
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        parseSimulatedDate(Json.parseToJsonElement(text).jsonObject["date"]?.jsonPrimitive?.content)
    }.getOrNull()

    fun save(date: LocalDate?) {
        runCatching {
            if (date == null) {
                file.delete()
            } else {
                file.writeText(
                    buildJsonObject { put("date", date.toString()) }.toString() + "\n",
                    Charsets.UTF_8,
                )
            }
        }
    }
}

/**
 * 启动时决定用哪个时钟：`TRELLIS_TODAY` > `dev-clock.json` > 真实时间。
 *
 * 安全边界（见规范 §7）：模拟日期会真的写 missed / 结算事件，不能拿真实数据目录玩——
 * 默认只允许 `TRELLIS_HOME` 指向非默认目录时启用；确实要在默认目录调试，
 * 必须显式 `TRELLIS_DEV_ALLOW_REAL_HOME=1`。
 */
internal class TrellisClockSetup(
    val clock: DebugTrellisClock,
    /** 这次启动是否真的进入了模拟模式。 */
    val simulated: Boolean,
    /** 请求了模拟但被拒绝时的原因（给日志/设置页显示）。 */
    val rejectedReason: String?,
    /** 当前数据目录是否允许改模拟时间。 */
    val allowed: Boolean,
    /** 是否开启了开发者模式（决定设置页显示不显示调试区）。 */
    val devMode: Boolean,
)

internal fun createTrellisClock(
    home: File,
    env: Map<String, String> = System.getenv(),
    real: TrellisClock = SystemTrellisClock(),
    defaultHome: File = File(System.getProperty("user.home"), ".trellis"),
): TrellisClockSetup {
    val clock = DebugTrellisClock(real)
    val devMode = env["TRELLIS_DEV"]?.trim() == "1"
    val allowed = runCatching { home.canonicalFile != defaultHome.canonicalFile }
        .getOrDefault(home != defaultHome) || env["TRELLIS_DEV_ALLOW_REAL_HOME"]?.trim() == "1"

    val fromEnv = env["TRELLIS_TODAY"]?.trim()?.takeIf { it.isNotEmpty() }
    val fromFile = if (devMode) DevClockStore(home).load() else null
    val raw = fromEnv ?: fromFile?.toString()
        ?: return TrellisClockSetup(clock, simulated = false, rejectedReason = null, allowed = allowed, devMode = devMode)

    if (!allowed) {
        val reason = "模拟时间只在非默认数据目录生效（当前 " + home.absolutePath +
            "）；确实要在这里调试，重启时设 TRELLIS_DEV_ALLOW_REAL_HOME=1"
        return TrellisClockSetup(clock, simulated = false, rejectedReason = reason, allowed = false, devMode = devMode)
    }

    val date = parseSimulatedDate(raw)
        ?: return TrellisClockSetup(
            clock,
            simulated = false,
            rejectedReason = "TRELLIS_TODAY/dev-clock.json 里的日期「$raw」不是 YYYY-MM-DD 格式",
            allowed = allowed,
            devMode = devMode,
        )

    clock.setSimulatedDate(date)
    return TrellisClockSetup(clock, simulated = true, rejectedReason = null, allowed = allowed, devMode = devMode)
}
