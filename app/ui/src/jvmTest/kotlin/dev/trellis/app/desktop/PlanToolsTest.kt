package dev.trellis.app.desktop

import dev.trellis.feature.learning.LearningPlanStore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 计划的手：点了「排成学习计划」之后，**计划页**要真的有东西。
 *
 * 真实事故：模型只调了 `set_plan`（这一轮的动作清单），对话里写着「计划已经排好了」，
 * 计划页却还是「还没有计划」。两者不是同一个东西：计划页读的是 learning-process 的库。
 */
class PlanToolsTest {

    /** 今天所在的 ISO 周；weeksFromNow=1 就是下一周。 */
    private fun weekOffset(weeksFromNow: Long = 0): String {
        val date = java.time.LocalDate.now().plusWeeks(weeksFromNow)
        return "%04d-W%02d".format(
            date.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR),
            date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
    }

    /** 假 B 站：finger/spi 给个 cookie，分集接口回 [pagelist]。单元测试不碰真网络。 */
    private fun fakeBilibili(pagelist: String): BilibiliClient = BilibiliClient(
        fetch = { url, _ ->
            when {
                "finger/spi" in url -> """{"code":0,"data":{"b_3":"BUV3TEST","b_4":"BUV4TEST"}}"""
                "player/pagelist" in url -> pagelist
                else -> error("unexpected url: $url")
            }
        },
    )

    /** pagelist 接口的响应：每项是（第几 P、分集标题、时长秒数）。 */
    private fun pageList(parts: List<Triple<Int, String, Int>>): String = buildJsonObject {
        put("code", 0)
        putJsonArray("data") {
            parts.forEach { (page, title, seconds) ->
                add(
                    buildJsonObject {
                        put("page", page)
                        put("part", title)
                        put("duration", seconds)
                    },
                )
            }
        }
    }.toString()

    @Test
    fun createLearningPlanWritesWeeksIntoThePlanDatabase() = runTest {
        val service = LearningPlanStore.inMemory()
        var refreshed = 0
        val tool = PlanTools({ service }, { refreshed++ }).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "操作系统速通")
                putJsonArray("units") {
                    add(buildJsonObject { put("title", "P2 操作系统的概念"); put("minutes", 30) })
                    add(buildJsonObject { put("title", "P3 操作系统的特征") })
                    add(buildJsonObject { put("title", "P4 发展与分类") })
                }
                putJsonArray("weeks") {
                    add(
                        buildJsonObject {
                            put("week", weekOffset())
                            putJsonArray("units") {
                                add("P2 操作系统的概念")
                                add("P3 操作系统的特征")
                            }
                        },
                    )
                    add(
                        buildJsonObject {
                            put("week", weekOffset(1))
                            putJsonArray("units") { add("P4 发展与分类") }
                        },
                    )
                }
            },
        )

        assertTrue(!result.isError, result.content)
        assertEquals(1, refreshed, "写完要通知界面刷新计划页")

        val planId = service.activePlanId()
        assertTrue(planId != null, "计划库里应该有一个活跃计划")
        val map = service.map(planId!!)
        assertEquals(listOf(weekOffset(), weekOffset(1)), map.weeks.map { it.week })
        val first = service.week(planId, weekOffset())
        assertEquals(2, first.commitment.units.size)
        assertEquals("P2 操作系统的概念", first.commitment.units.first().title)
    }

    @Test
    fun aWeekThatNamesAnUnknownUnitIsRejected() = runTest {
        val service = LearningPlanStore.inMemory()
        val tool = PlanTools({ service }, {}).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "打错标题的计划")
                putJsonArray("units") { add("P2 概念") }
                putJsonArray("weeks") {
                    add(
                        buildJsonObject {
                            put("week", weekOffset())
                            putJsonArray("units") { add("P2 概念（错）") }
                        },
                    )
                }
            },
        )

        assertTrue(result.isError, "标题对不上要直接报错：" + result.content)
        assertEquals(null, service.activePlanId(), "报错时不能留下半个计划")
    }

    @Test
    fun aWeekWithoutALabelIsRejectedBeforeWriting() = runTest {
        val service = LearningPlanStore.inMemory()
        val tool = PlanTools({ service }, {}).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "周号写错")
                putJsonArray("units") { add("P2 概念") }
                putJsonArray("weeks") {
                    // 这一周故意漏掉 week 字段：宁可在写库前报错，也不要建出半个计划
                    add(buildJsonObject { putJsonArray("units") { add("P2 概念") } })
                }
            },
        )

        assertTrue(result.isError, result.content)
    }

    /** 用户明确要过：计划第一周就是本周，别从下周开始排。 */
    @Test
    fun aPlanThatStartsNextWeekIsRejected() = runTest {
        val service = LearningPlanStore.inMemory()
        val tool = PlanTools({ service }, {}).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "从下周开始")
                putJsonArray("units") { add("P2 概念") }
                putJsonArray("weeks") {
                    add(
                        buildJsonObject {
                            put("week", weekOffset(1))
                            putJsonArray("units") { add("P2 概念") }
                        },
                    )
                }
            },
        )

        assertTrue(result.isError, "第一周不是本周就要拒绝：" + result.content)
        assertTrue(result.content.contains("本周"), "错误里要说清第一周必须是本周：" + result.content)
        assertEquals(null, service.activePlanId(), "报错时不能留下半个计划")
    }

    // ------------------------------------------------------------------
    // bvid 全量导入：模型只选源，单元和排周都是确定性的
    // ------------------------------------------------------------------

    /** 整门课全量导入：40 集一个都不能少，本周+下周有承诺，剩下的在待安排池。 */
    @Test
    fun aWholeBilibiliCourseIsImportedInFull() = runTest {
        val service = LearningPlanStore.inMemory()
        val parts = (1..40).map { i -> Triple(i, "第${i}章 主题 $i", 20 * 60) }
        val tool = PlanTools({ service }, {}, bilibili = fakeBilibili(pageList(parts)))
            .all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "操作系统速通")
                put("bvid", "BV1YE411D7nH")
                put("dailyMinutes", 60)
            },
        )

        assertTrue(!result.isError, result.content)
        val planId = service.activePlanId()
        assertTrue(planId != null, "计划库里应该有一个活跃计划")
        val map = service.map(planId!!)
        assertEquals(listOf(weekOffset(), weekOffset(1)), map.weeks.map { it.week }, "只排本周和下一周")

        val committed = map.weeks.flatMap { week -> week.units.map { it.title } }
        val pool = service.pool(planId).map { it.title }
        assertEquals(14, committed.size, "本周+下周各 7 个单元")
        assertEquals(26, pool.size, "其余 26 个留在「待安排」池")
        assertEquals(
            (1..40).map { "第${it}章 主题 $it" }.toSet(),
            (committed + pool).toSet(),
            "整门课 40 集一个都不能少",
        )
        assertEquals(60, service.planState(planId).dailyMinutes, "每天投入要记进计划")
    }

    /** 60 分钟的一集：拆成「上/下」两个单元，各 30 分钟（后端只收 10–45）。 */
    @Test
    fun aLongPartBecomesTwoUnits() = runTest {
        val service = LearningPlanStore.inMemory()
        val tool = PlanTools(
            { service }, {},
            bilibili = fakeBilibili(pageList(listOf(Triple(1, "第2章 进程管理", 60 * 60)))),
        ).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "操作系统速通")
                put("bvid", "BV1YE411D7nH")
            },
        )

        assertTrue(!result.isError, result.content)
        val planId = service.activePlanId()!!
        val units = service.week(planId, weekOffset()).commitment.units
        assertEquals(listOf("第2章 进程管理（上）", "第2章 进程管理（下）"), units.map { it.title })
        assertEquals(listOf(30, 30), units.map { it.estimateMinutes })
        assertEquals(listOf("第2章", "第2章"), units.map { it.chapter })
    }

    /** bvid 模式下 weeks 由宿主自动排；模型手写 weeks 要直接拒绝并说清怎么改。 */
    @Test
    fun aBvidImportRejectsHandWrittenWeeks() = runTest {
        val service = LearningPlanStore.inMemory()
        val tool = PlanTools(
            { service }, {},
            bilibili = fakeBilibili(pageList(listOf(Triple(1, "第1章 概述", 20 * 60)))),
        ).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "手写周的计划")
                put("bvid", "BV1YE411D7nH")
                putJsonArray("weeks") {
                    add(
                        buildJsonObject {
                            put("week", weekOffset())
                            putJsonArray("units") { add("第1章 概述") }
                        },
                    )
                }
            },
        )

        assertTrue(result.isError, "用了 bvid 还手写 weeks 要直接拒绝：" + result.content)
        assertTrue(result.content.contains("bvid") && result.content.contains("weeks"), "错误要能纠正：" + result.content)
        assertTrue(result.content.contains("自动"), "要说清宿主会自动排：" + result.content)
        assertEquals(null, service.activePlanId(), "报错时不能留下半个计划")
    }

    /** 分集 → 单元的确定性规则：0 分钟按 25，太短按 10，空标题用 P{page}，章节从标题里找。 */
    @Test
    fun partTitlesAndDurationsBecomeDeterministicUnits() = runTest {
        val service = LearningPlanStore.inMemory()
        val tool = PlanTools(
            { service }, {},
            bilibili = fakeBilibili(
                pageList(
                    listOf(
                        Triple(1, "前言", 0),
                        Triple(2, "第2章 进程", 5 * 60),
                        Triple(3, "", 20 * 60),
                        Triple(4, "3.1 调度", 60 * 60),
                    ),
                ),
            ),
        ).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "操作系统速通")
                put("bvid", "BV1YE411D7nH")
            },
        )

        assertTrue(!result.isError, result.content)
        val planId = service.activePlanId()!!
        val units = service.week(planId, weekOffset()).commitment.units +
            service.week(planId, weekOffset(1)).commitment.units
        assertEquals(listOf("前言", "第2章 进程", "P3", "3.1 调度（上）", "3.1 调度（下）"), units.map { it.title })
        assertEquals(listOf(25, 10, 20, 30, 30), units.map { it.estimateMinutes })
        assertEquals(listOf("未分章", "第2章", "未分章", "第3章", "第3章"), units.map { it.chapter })
    }

    /** fromPart/toPart 只导入指定区间（1-based，两端都含）。 */
    @Test
    fun fromPartAndToPartLimitTheImportedRange() = runTest {
        val service = LearningPlanStore.inMemory()
        val parts = (1..10).map { i -> Triple(i, "第${i}章 主题 $i", 20 * 60) }
        val tool = PlanTools({ service }, {}, bilibili = fakeBilibili(pageList(parts)))
            .all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "只排第 3–5 集")
                put("bvid", "BV1YE411D7nH")
                put("fromPart", 3)
                put("toPart", 5)
            },
        )

        assertTrue(!result.isError, result.content)
        val planId = service.activePlanId()!!
        val titles = service.week(planId, weekOffset()).commitment.units.map { it.title }
        assertEquals(listOf("第3章 主题 3", "第4章 主题 4", "第5章 主题 5"), titles)
        assertTrue(service.pool(planId).isEmpty(), "只导入 3 集，不该有剩余")
    }

    /** 单元上限 200：超过就取前 200 个，并在结果里说明被截断。 */
    @Test
    fun aHugeCourseIsTruncatedAtTwoHundredUnits() = runTest {
        val service = LearningPlanStore.inMemory()
        val parts = (1..201).map { i -> Triple(i, "第${i}章 小节 $i", 20 * 60) }
        val tool = PlanTools({ service }, {}, bilibili = fakeBilibili(pageList(parts)))
            .all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "超大课程")
                put("bvid", "BV1YE411D7nH")
            },
        )

        assertTrue(!result.isError, result.content)
        assertTrue(result.content.contains("截断"), "结果里要说明被截断：" + result.content)
        val planId = service.activePlanId()!!
        val committed = service.map(planId).weeks.sumOf { it.units.size }
        assertEquals(200, committed + service.pool(planId).size, "超过 200 个单元要截到 200 个")
    }

    /**
     * 库打不开时，模型必须拿到**具体原因**，而不是一句「打不开」：
     * 真实事故是打包版漏了 java.sql，用户看到 `java/sql/DriverManager` 完全无从下手，
     * 模型也只能让用户「去计划页看一眼错误提示」。
     */
    @Test
    fun aBrokenPlanDatabaseTellsTheModelTheRealReason() = runTest {
        val tool = PlanTools(
            service = { null },
            onChanged = {},
            planDbError = { "NoClassDefFoundError：java/sql/DriverManager（运行环境缺少 JDK 模块…）" },
        ).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "打不开的计划")
                putJsonArray("units") { add("U1") }
                putJsonArray("weeks") {
                    add(
                        buildJsonObject {
                            put("week", weekOffset())
                            putJsonArray("units") { add("U1") }
                        },
                    )
                }
            },
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("java/sql/DriverManager"), "要带具体原因：" + result.content)
        assertTrue(result.content.contains("get_diagnostics"), "要告诉模型先自查：" + result.content)
    }

    /**
     * 排周必须走注入时钟：开发者模式把「今天」拨到某天后，AI 建的计划要按那天算周号，
     * 否则跨周调试时计划会排到真实时间的周里。
     */
    @Test
    fun theWeekTheModelPlansIntoComesFromTheInjectedClock() = runTest {
        val service = LearningPlanStore.inMemory()
        val clock = DebugTrellisClock().also { it.setSimulatedDate(LocalDate.parse("2026-11-02")) }
        // 8 个 20 分钟的单元：本周按周预算 + 每周 7 个上限先取 7 个，下一个进下一周。
        val parts = (1..8).map { i -> Triple(i, "课时$i", 20 * 60) }
        val tool = PlanTools(
            service = { service },
            onChanged = {},
            bilibili = fakeBilibili(pageList(parts)),
            clock = clock,
        ).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "固定时钟排周")
                put("bvid", "BV1YE411D7nH")
            },
        )

        assertTrue(!result.isError, result.content)
        val planId = service.activePlanId()!!
        // 2026-11-02 是周一，属于 W45；下一批应该排进 W46。
        assertEquals(listOf("2026-W45", "2026-W46"), service.map(planId).weeks.map { it.week })
        assertEquals(7, service.week(planId, "2026-W45").commitment.units.size)
        assertEquals(1, service.week(planId, "2026-W46").commitment.units.size)
    }
}
