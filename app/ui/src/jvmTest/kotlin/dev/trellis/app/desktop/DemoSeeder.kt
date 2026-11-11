package dev.trellis.app.desktop

import dev.trellis.activity.ActivityStore
import dev.trellis.activity.AwBucket
import dev.trellis.activity.RawEvent
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import java.io.File
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume

/**
 * 演示数据播种器：把 /tmp/trellis-demo/home 准备成「本周有两个极限节点 + 一篇笔记 + 一段拟真的 Chrome 看课事件」。
 *
 * 不是产品测试，不会进 CI 断言；只给录演示视频用，跑法：
 * `./gradlew :app:ui:jvmTest --tests 'dev.trellis.app.desktop.DemoSeeder'`
 */
class DemoSeeder {

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "demo.seed",
    )

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    private val noteBody = """
        # 极限学习笔记

        今天把第 1 节 · 数列极限 重新过了一遍。核心是 ε-N 定义：给定任意小的正数 ε，总能找到一个正整数 N，使得 n > N 之后所有项都落在 a 的 ε 邻域里。老师强调不要背定义，要会用定义证明具体极限——比如证明 1/n 趋于 0 时，取 N 就等于 ⌈1/ε⌉，代入验证 |1/n − 0| < ε 即可。

        接下来做了课后例题，记下三个容易错的点：
        1. 单调有界准则里的「有界」是上下界都要有；先用定义证明有界，再证单调，最后才说极限存在。
        2. 夹逼定理放缩时两端都要收敛到同一个值，不能一端松一端紧。
        3. 数列极限的自变量只能是正整数，和函数极限的取法要分开记。

        我自己把这一节的定理从头推了一遍，课后 12 道题做了 11 道。错的那道递推数列题，是因为没先证明有界就直接写极限存在——顺序反了。证明存在性 → 再求极限值，这个顺序不能颠倒。

        明天再学下一节，先把今天的推导整理成一张图。
    """.trimIndent()

    @Test
    fun seedDemoHome() = runBlocking {
        // 会清空 /tmp/trellis-demo/home：默认跳过，演示前按需重建。
        Assume.assumeTrue(
            "演示数据按需重建：TRELLIS_DEMO_SEED=1 ./gradlew :app:ui:jvmTest --tests '*DemoSeeder*' --rerun-tasks",
            System.getenv("TRELLIS_DEMO_SEED") == "1",
        )
        val home = File("/tmp/trellis-demo/home")
        home.deleteRecursively()
        home.mkdirs()
        File(System.getProperty("user.home"), ".trellis/llm.json")
            .takeIf { it.isFile }
            ?.copyTo(File(home, "llm.json"), overwrite = true)

        val zone = ZoneId.systemDefault()
        val runtime = AppRuntime.create(home)
        try {
            val today = LocalDate.now(zone)
            val week = "%04d-W%02d".format(
                today.get(IsoFields.WEEK_BASED_YEAR),
                today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
            )
            val store = LearningPlanStore.open(
                File(home, "plans/plan.db").toPath(),
                { runtime.clock.nowMs() },
                zone,
            )
            val created = store.createProcess(
                env(),
                "微积分 · 第一章 极限",
                "把数列极限、函数极限各学一节，能独立做例题",
                weeklyCapacityMinutes = 60 * 7,
            ).unwrap()
            val draft = store.saveDraft(
                env(created.planId),
                created.planId,
                null,
                listOf(
                    UnitDraft("第 1 章 · 极限", "第 1 节 · 数列极限", 25),
                    UnitDraft("第 1 章 · 极限", "第 2 节 · 函数极限", 30),
                ),
                listOf(CommitmentDraft(week, listOf(0, 1))),
            ).unwrap()
            store.acceptDraft(env(created.planId), draft.versionId, week).unwrap()

            // 两个节点都摆到今天，演示时一屏能看见两行。
            val occurrences = ArrayList<Pair<String, String>>()
            DriverManager.getConnection("jdbc:sqlite:" + File(home, "plans/plan.db").absolutePath).use { conn ->
                conn.prepareStatement(
                    "SELECT o.id, u.title FROM occurrence o JOIN plan_unit u ON u.id = o.unit_id " +
                        "WHERE o.status = 'planned' ORDER BY o.created_at",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) occurrences += rs.getString(1) to rs.getString(2)
                    }
                }
                // occurrence 的 day 是身份的一部分（DB 触发器禁止 UPDATE）：本周从周一开排，
                // 今天恰好是周一，acceptDraft 已经把两个节点放在今天，不需要再搬。
                conn.prepareStatement(
                    "SELECT o.id, u.title, o.day FROM occurrence o JOIN plan_unit u ON u.id = o.unit_id " +
                        "WHERE o.status = 'planned' ORDER BY o.created_at",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            check(rs.getString(3) == today.toString()) {
                                "节点 " + rs.getString(2) + " 没落在今天（" + rs.getString(3) + "），演示数据不对"
                            }
                        }
                    }
                }
            }

            KnowledgeStore(home, runtime.clock).save(
                KnowledgeNote(
                    id = "n_demo_limits",
                    title = "极限学习笔记",
                    body = noteBody,
                    createdAt = runtime.clock.nowMs(),
                    updatedAt = runtime.clock.nowMs(),
                ),
            )

            // 拟真的 Chrome 看课事件：今天 10:00-10:30 在 B 站看「第 2 节 · 函数极限」。
            val chromeFromMs = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
            val chromeToMs = chromeFromMs + 30 * 60_000L
            ActivityStore.open(File(home, "activity/activity.db").toPath()).use { activity ->
                // 聚合器按 bucket 元信息的 type 找窗口/网页事件，不注册 bucket 就看不到任何片段。
                activity.upsertBucket(AwBucket("aw-watcher-window_demo", "currentwindow"))
                activity.upsertBucket(AwBucket("aw-watcher-web_demo", "web.tab.current"))
                activity.upsertEvents(
                    listOf(
                        RawEvent(
                            bucket = "aw-watcher-window_demo",
                            awId = 1,
                            tsMs = chromeFromMs,
                            durMs = 30 * 60_000L,
                            app = "Google Chrome",
                            title = "【高等数学】第 2 节 函数极限 - 哔哩哔哩",
                        ),
                        RawEvent(
                            bucket = "aw-watcher-web_demo",
                            awId = 1,
                            tsMs = chromeFromMs,
                            durMs = 30 * 60_000L,
                            app = "Google Chrome",
                            title = "【高等数学】第 2 节 函数极限 - 哔哩哔哩",
                            url = "https://www.bilibili.com/video/BV1xx411c7mD",
                        ),
                    ),
                )
            }

            val seed = buildString {
                append("{\n")
                append("  \"planId\": \"").append(created.planId).append("\",\n")
                append("  \"week\": \"").append(week).append("\",\n")
                append("  \"today\": \"").append(today).append("\",\n")
                append("  \"chromeFromMs\": ").append(chromeFromMs).append(",\n")
                append("  \"chromeToMs\": ").append(chromeToMs).append(",\n")
                append("  \"occurrences\": [\n")
                occurrences.forEachIndexed { index, (id, title) ->
                    append("    {\"id\": \"").append(id).append("\", \"title\": \"").append(title).append("\"}")
                    if (index != occurrences.lastIndex) append(",")
                    append("\n")
                }
                append("  ]\n")
                append("}\n")
            }
            File("/tmp/trellis-demo/seed.json").writeText(seed, Charsets.UTF_8)
            assertTrue(occurrences.size >= 2, "播种后应有至少两个本周节点：$occurrences")
        } finally {
            runtime.shutdown()
        }
    }
}
