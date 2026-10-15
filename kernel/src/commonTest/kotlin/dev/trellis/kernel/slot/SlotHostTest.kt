package dev.trellis.kernel.slot

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.extension.Contribution
import dev.trellis.kernel.extension.ExtensionRegistry
import dev.trellis.kernel.extension.MutableExtensionRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ExplodingSlot(override val id: String, override val order: Int = 100) : SlotContribution

private fun registryWith(vararg items: Pair<String, SlotContribution>): ExtensionRegistry {
    val r = MutableExtensionRegistry()
    r.recordCardinality(Slots.HomeHeader)
    for ((owner, item) in items) {
        r.add(Contribution(Slots.HomeHeader.id, PluginId(owner), item.id, item.order, item))
    }
    return ExtensionRegistry(r)
}

class SlotHostTest {

    @Test
    fun rendersContributionsInOrder() {
        val host = SlotHost(
            registryWith(
                "dev.a" to TextSlot("a", order = 200, title = "后", body = "x"),
                "dev.b" to TextSlot("b", order = 10, title = "先", body = "y"),
            ),
            ConsoleSlotRenderer(),
        )
        val lines = host.render(Slots.HomeHeader)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("[b]"), lines[0])
        assertTrue(lines[1].contains("[a]"), lines[1])
    }

    // ErrorBoundary：一个贡献崩了，只降级它自己
    @Test
    fun aThrowingContributionIsDegradedWithoutAffectingItsNeighbours() {
        val failures = mutableListOf<String>()
        val host = SlotHost(
            registryWith(
                "dev.a" to TextSlot("good-1", order = 10, title = "A", body = "x"),
                "dev.b" to ExplodingSlot("bad", order = 20),
                "dev.c" to TextSlot("good-2", order = 30, title = "C", body = "z"),
            ),
            SlotRenderer { _, c ->
                if (c is ExplodingSlot) error("这张卡片炸了")
                ConsoleSlotRenderer().renderOne(Slots.HomeHeader.id, c)
            },
        ) { _, contribution, error ->
            failures += contribution.id + ":" + error.message
        }

        val lines = host.render(Slots.HomeHeader)
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("[good-1]"))
        assertTrue(lines[1].contains("此卡片暂时不可用"), lines[1])
        assertTrue(lines[2].contains("[good-2]"))
        assertEquals(listOf("bad:这张卡片炸了"), failures)
    }

    @Test
    fun aQuarantinedContributionIsNotRenderedAgainThisSession() {
        var attempts = 0
        val host = SlotHost(
            registryWith("dev.b" to ExplodingSlot("bad")),
            SlotRenderer { _, _ ->
                attempts++
                error("boom")
            },
        )

        host.render(Slots.HomeHeader)
        host.render(Slots.HomeHeader)
        host.render(Slots.HomeHeader)

        assertEquals(1, attempts, "被隔离的贡献不该每次重组都再炸一遍")
    }

    @Test
    fun unknownContributionTypesDegradeInsteadOfCrashing() {
        val host = SlotHost(registryWith("dev.a" to ExplodingSlot("weird")), ConsoleSlotRenderer())
        val lines = host.render(Slots.HomeHeader)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("不认识这种贡献"), lines[0])
    }

    @Test
    fun emptySlotRendersNothing() {
        val host = SlotHost(registryWith(), ConsoleSlotRenderer())
        assertEquals(emptyList(), host.render(Slots.HomeHeader))
    }
}