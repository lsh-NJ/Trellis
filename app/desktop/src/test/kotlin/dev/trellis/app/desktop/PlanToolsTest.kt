package dev.trellis.app.desktop

import dev.trellis.feature.learning.LearningPlanStore
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
}
