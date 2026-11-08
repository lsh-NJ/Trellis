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
        var focusedPlanId: String? = null
        val tool = PlanTools({ service }, { id -> refreshed++; focusedPlanId = id })
            .all.single { it.name == "create_learning_plan" }

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
        assertEquals(planId, focusedPlanId, "新建计划后要把计划页焦点切到新计划")
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

    /** 整门课全量导入：40 集一个都不能少，而且要一次排成**全局周表**（不再只排前两周）。 */
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
        // 40 个 20 分钟单元、每天 60 分钟（周参考量 60×7=420 分钟）→ 每周 21 个：21+19，共 2 周。
        assertEquals((0L..1L).map { weekOffset(it) }, map.weeks.map { it.week }, "全局周表要从本周起排满")

        val committed = map.weeks.flatMap { week -> week.units.map { it.title } }
        val pool = service.pool(planId).map { it.title }
        assertEquals(40, committed.size, "整门课都要排进全局周表")
        assertTrue(pool.isEmpty(), "全局排完就不该再有待安排：" + pool)
        assertEquals(
            (1..40).map { "第${it}章 · 主题 $it" }.toSet(),
            committed.toSet(),
            "整门课 40 集一个都不能少，且标题按章节清洗而不是逐字抄分集标题",
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
        assertEquals(listOf("第2章 · 进程管理（上）", "第2章 · 进程管理（下）"), units.map { it.title })
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
        assertEquals(
            listOf("前言", "第2章 · 进程", "P3", "第3章 · 3.1 调度（上）", "第3章 · 3.1 调度（下）"),
            units.map { it.title },
            "标题来自章节 + 清洗后的分集名，不逐字抄分集标题",
        )
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
        assertEquals(
            listOf("第3章 · 主题 3", "第4章 · 主题 4", "第5章 · 主题 5"),
            titles,
        )
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
     * 手写周表不设周数上限（用户口径：先整体、再每周局部，不能只排前两周）：
     * 3 周的计划要能一次写进去，周与周连续。
     */
    @Test
    fun aHandWrittenPlanCanCoverMoreThanTwoWeeks() = runTest {
        val service = LearningPlanStore.inMemory()
        val tool = PlanTools({ service }, {}).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "四周速通")
                putJsonArray("units") {
                    add("第 1 单元")
                    add("第 2 单元")
                    add("第 3 单元")
                    add("第 4 单元")
                    add("第 5 单元")
                    add("第 6 单元")
                }
                putJsonArray("weeks") {
                    add(buildJsonObject { put("week", weekOffset(0)); putJsonArray("units") { add("第 1 单元"); add("第 2 单元") } })
                    add(buildJsonObject { put("week", weekOffset(1)); putJsonArray("units") { add("第 3 单元"); add("第 4 单元") } })
                    add(buildJsonObject { put("week", weekOffset(2)); putJsonArray("units") { add("第 5 单元"); add("第 6 单元") } })
                }
            },
        )

        assertTrue(!result.isError, result.content)
        val planId = service.activePlanId()!!
        assertEquals(
            (0L..2L).map { weekOffset(it) },
            service.map(planId).weeks.map { it.week },
            "三周（或更多）都要能一次排进全局周表",
        )
        assertEquals(2, service.week(planId, weekOffset(2)).commitment.units.size)
    }

    /** 全局周表必须一周接一周：跳周（或从下周起）时要说清应该怎么改。 */
    @Test
    fun aWeekTableWithAGapIsRejected() = runTest {
        val service = LearningPlanStore.inMemory()
        val tool = PlanTools({ service }, {}).all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "跳周的计划")
                putJsonArray("units") { add("U1"); add("U2") }
                putJsonArray("weeks") {
                    add(buildJsonObject { put("week", weekOffset()); putJsonArray("units") { add("U1") } })
                    add(buildJsonObject { put("week", weekOffset(2)); putJsonArray("units") { add("U2") } })
                }
            },
        )

        assertTrue(result.isError, "跳周要拒绝：" + result.content)
        assertTrue(result.content.contains("一周接一周"), result.content)
        assertEquals(null, service.activePlanId(), "报错时不能留下半个计划")
    }

    /**
     * 排周必须走注入时钟：开发者模式把「今天」拨到某天后，AI 建的计划要按那天算周号，
     * 否则跨周调试时计划会排到真实时间的周里。
     */
    @Test
    fun theWeekTheModelPlansIntoComesFromTheInjectedClock() = runTest {
        val service = LearningPlanStore.inMemory()
        val clock = DebugTrellisClock().also { it.setSimulatedDate(LocalDate.parse("2026-11-02")) }
        // 8 个 20 分钟的单元：周参考量 60×7=420 分钟，一个周就装得下。
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
        // 2026-11-02 是周一，属于 W45；8 个单元按分钟参考量都排得进这一周。
        assertEquals(listOf("2026-W45"), service.map(planId).weeks.map { it.week })
        assertEquals(8, service.week(planId, "2026-W45").commitment.units.size)
    }

    /** 有截止日期：只排到那一周，排不下的留在待安排（时间只做参考，但截止日仍然算数）。 */
    @Test
    fun aDeadlineStopsTheGlobalWeekTableAndLeavesTheRestInThePool() = runTest {
        val service = LearningPlanStore.inMemory()
        val parts = (1..40).map { i -> Triple(i, "课时$i", 20 * 60) }
        val tool = PlanTools({ service }, {}, bilibili = fakeBilibili(pageList(parts)))
            .all.single { it.name == "create_learning_plan" }
        val deadline = java.time.LocalDate.now().plusWeeks(1).toString()

        val result = tool.call(
            buildJsonObject {
                put("title", "两周冲刺")
                put("bvid", "BV1YE411D7nH")
                put("dailyMinutes", 30)
                put("deadline", deadline)
            },
        )

        assertTrue(!result.isError, result.content)
        val planId = service.activePlanId()!!
        val map = service.map(planId)
        assertEquals((0L..1L).map { weekOffset(it) }, map.weeks.map { it.week }, "有截止日期就只排到那一周")
        val committed = map.weeks.sumOf { it.units.size }
        assertEquals(22, committed, "每天 30 分钟参考、两周排 22 个（每周 11 个）")
        assertEquals(18, service.pool(planId).size, "排不下的要留在待安排池")
        assertTrue(result.content.contains("待安排"), "回复要说明剩下的去哪了：" + result.content)
        assertTrue(result.content.contains(deadline), "回复要提到截止日：" + result.content)
    }

    // ------------------------------------------------------------------
    // 目录清洗：片尾/预告不是课，单元名也不是分集标题的复印件
    // ------------------------------------------------------------------

    private fun part(page: Int, title: String): BilibiliPart =
        BilibiliPart(page = page, title = title, durationText = "")

    /** 清洗规则：去「第 N 集/P1/【…】/(…)」标签、过滤黑名单、纯英文缩写别误杀。 */
    @Test
    fun partTitlesAreCleanedIntoUnitNames() {
        assertEquals("操作系统概述", cleanPartTitle("P1 第1集 操作系统概述"))
        assertEquals("进程管理", cleanPartTitle("第3话 (补录) 进程管理"))
        assertEquals("内存管理", cleanPartTitle("Ep5 内存管理"))
        assertEquals("内存管理", cleanPartTitle("【第4章】内存管理"))
        assertEquals("调度算法", cleanPartTitle("第12集 （上）调度算法"))

        assertEquals(null, cleanPartTitle("片尾"))
        assertEquals(null, cleanPartTitle("花絮【预告】"))
        assertEquals(null, cleanPartTitle("下一集预告"))
        assertEquals(null, cleanPartTitle("Trailer (Coming Soon)"))
        assertEquals(null, cleanPartTitle("Behind the Scenes: 拍摄花絮"))
        assertEquals(null, cleanPartTitle("   "))
        assertEquals(null, cleanPartTitle("第1集"), "只剩编号的分集没有信息量")
        assertEquals("Introduction", cleanPartTitle("Introduction"), "别把 Introduction/AD 转换误杀")
    }

    /** 目录清洗：过滤片尾/预告；清洗后不足 3 集就退回原始目录，但警告计数保留。 */
    @Test
    fun pickCoursePartsFiltersJunkAndFallsBackWhenTooFewRemain() {
        val course = listOf(
            part(1, "P1 概述"),
            part(2, "第2章 进程管理"),
            part(3, "3.1 调度"),
            part(4, "片尾"),
            part(5, "花絮【预告】"),
        )
        val (kept, filtered) = pickCourseParts(course)
        assertEquals(listOf(1, 2, 3), kept.map { it.page })
        assertEquals(2, filtered)

        // 只有一两集的短视频课：清洗后不足 3 集不能清空，退回原始目录。
        val shortCourse = listOf(part(1, "第1章 概述"), part(2, "片尾"), part(3, "花絮"))
        val (fallback, filteredShort) = pickCourseParts(shortCourse)
        assertEquals(listOf(1, 2, 3), fallback.map { it.page }, "不足 3 集要退回原始分集")
        assertEquals(2, filteredShort, "退回时仍然保留过滤计数当警告")

        // 空标题不是垃圾：它有用 page 兜底成 P{n} 的价值。
        val withBlank = listOf(part(1, "第1章 概述"), part(2, ""), part(3, "第3章 内存"), part(4, "预告"))
        assertEquals(listOf(1, 2, 3), pickCourseParts(withBlank).first.map { it.page })
    }

    /** bvid 全量导入：片尾/花絮被过滤，单元名按章节清洗，结果里要说清过滤了几条。 */
    @Test
    fun aBilibiliImportFiltersTrailersAndCleansTitles() = runTest {
        val service = LearningPlanStore.inMemory()
        val parts = listOf(
            Triple(1, "P1 第1集 操作系统概述", 20 * 60),
            Triple(2, "第2章 进程管理", 20 * 60),
            Triple(3, "3.1 调度算法", 60 * 60),
            Triple(4, "【第4章】内存管理", 20 * 60),
            Triple(5, "片尾", 5 * 60),
            Triple(6, "花絮【预告】", 3 * 60),
        )
        val tool = PlanTools({ service }, {}, bilibili = fakeBilibili(pageList(parts)))
            .all.single { it.name == "create_learning_plan" }

        val result = tool.call(
            buildJsonObject {
                put("title", "操作系统速通")
                put("bvid", "BV1YE411D7nH")
                put("dailyMinutes", 120)
            },
        )

        assertTrue(!result.isError, result.content)
        assertTrue(result.content.contains("清洗"), "结果里要说明清洗过：" + result.content)
        assertTrue(result.content.contains("过滤 2 条"), "要报出过滤条数：" + result.content)

        val planId = service.activePlanId()!!
        val units = service.week(planId, weekOffset()).commitment.units
        assertEquals(
            listOf(
                "操作系统概述",
                "第2章 · 进程管理",
                "第3章 · 3.1 调度算法（上）",
                "第3章 · 3.1 调度算法（下）",
                "第4章 · 内存管理",
            ),
            units.map { it.title },
            "片尾/花絮不该变成节点，标题也不该逐字抄分集标题",
        )
        assertEquals(listOf("未分章", "第2章", "第3章", "第3章", "第4章"), units.map { it.chapter })
        assertTrue(
            units.none { it.title.contains("片尾") || it.title.contains("花絮") || it.title.contains("预告") },
            units.map { it.title }.toString(),
        )

        // 分集资源也要落库：单元是清洗后的学习点，资源是能点回原分集的那一条。
        assertTrue(units.all { it.resources.size == 1 }, units.map { it.title to it.resources.size }.toString())
        val first = units.first().resources.single()
        assertEquals("bilibili", first.kind)
        assertEquals("P1 第1集 操作系统概述", first.title, "资源标题要保留原分集标题")
        assertEquals("https://www.bilibili.com/video/BV1YE411D7nH?p=1", first.url)
        assertTrue(first.locator.startsWith("第 1 集"), first.locator)
        // 长分集拆成（上）（下）两个单元，但都指向同一集。
        assertEquals("https://www.bilibili.com/video/BV1YE411D7nH?p=3", units[3].resources.single().url)
    }
}
