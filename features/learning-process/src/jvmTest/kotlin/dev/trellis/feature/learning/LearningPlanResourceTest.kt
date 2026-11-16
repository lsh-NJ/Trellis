package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.PlanResourceDraft
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 资源池：课程/分集两级、按 url 去重、dropped 默认不返回。 */
class LearningPlanResourceTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = OffsetDateTime.parse("2026-10-26T08:00+08:00").toInstant().toEpochMilli()
    private val svc = SqliteLearningPlanService(PlanDatabase.inMemory(), clock = { now }, zone = zone)

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID(), actor = Actor.AGENT, planId = planId, origin = "test",
    )

    private fun seed(): String {
        val created = svc.createProcess(env(), "资源计划", "把资源管起来")
        assertTrue(created.ok, created.error?.message)
        return created.value!!.planId
    }

    @Test
    fun aCourseAndItsPartsAreStoredAsATwoLevelPool() {
        val planId = seed()
        val course = svc.upsertResources(
            env(planId), planId,
            listOf(PlanResourceDraft(kind = "course", title = "计算机网络 微课堂", url = "https://b23.tv/BV1", source = "bilibili")),
        )
        assertTrue(course.ok, course.error?.message)
        val courseId = course.value!!.single()
        val parts = svc.upsertResources(
            env(planId), planId,
            listOf(
                PlanResourceDraft("part", "1.1 什么是网络", "https://b23.tv/BV1?p=1", "第 1 集", 12, parentId = courseId, source = "bilibili"),
                PlanResourceDraft("part", "课程介绍", "https://b23.tv/BV1?p=0", "第 0 集", 2, parentId = courseId, status = "dropped"),
            ),
        )
        assertTrue(parts.ok, parts.error?.message)

        val visible = svc.resources(planId)
        assertEquals(listOf("计算机网络 微课堂", "1.1 什么是网络"), visible.map { it.title })
        assertEquals(courseId, visible[1].parentId)
        val all = svc.resources(planId, includeDropped = true)
        assertEquals(3, all.size)
        assertEquals("dropped", all.first { it.title == "课程介绍" }.status)
    }

    @Test
    fun theSameUrlIsUpdatedInsteadOfDuplicated() {
        val planId = seed()
        val first = svc.upsertResources(env(planId), planId, listOf(PlanResourceDraft("article", "旧标题", "https://example.com/a")))
        assertTrue(first.ok)
        val again = svc.upsertResources(env(planId), planId, listOf(PlanResourceDraft("article", "新标题", "https://example.com/a", note = "读第 1 节")))
        assertTrue(again.ok)
        assertEquals(first.value, again.value)
        val resources = svc.resources(planId)
        assertEquals(1, resources.size)
        assertEquals("新标题", resources.single().title)
        assertEquals("读第 1 节", resources.single().note)
    }

    @Test
    fun droppingAResourceHidesItFromTheDefaultList() {
        val planId = seed()
        val id = svc.upsertResources(env(planId), planId, listOf(PlanResourceDraft("article", "试听 9.9", "https://example.com/ad"))).value!!.single()
        assertTrue(svc.setResourceStatus(env(planId), id, "dropped").ok)
        assertTrue(svc.resources(planId).isEmpty())
        assertEquals(1, svc.resources(planId, includeDropped = true).size)
        assertFalse(svc.setResourceStatus(env(planId), id, "bad").ok)
    }
}
