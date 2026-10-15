package dev.trellis.kernel.extension

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Item(
    override val id: String,
    override val order: Int = 100,
    val label: String = "",
) : OrderedContribution

private val ManyPoint = ExtensionPoint<Item>(ExtensionPointId("test.many"), Cardinality.MANY)
private val OnePoint = ExtensionPoint<Item>(ExtensionPointId("test.one"), Cardinality.ONE)

private fun entry(point: ExtensionPoint<*>, owner: String, id: String, order: Int) =
    Contribution(point.id, PluginId(owner), id, order, Item(id, order, owner))

class ExtensionRegistryTest {

    private fun freshRegistry(): MutableExtensionRegistry = MutableExtensionRegistry().apply {
        recordCardinality(ManyPoint)
        recordCardinality(OnePoint)
    }

    @Test
    fun manyPointSortsByOrderThenOwnerThenId() {
        val r = freshRegistry()
        r.add(entry(ManyPoint, "dev.b", "x", 100))
        r.add(entry(ManyPoint, "dev.a", "y", 200))
        r.add(entry(ManyPoint, "dev.a", "z", 10))

        assertEquals(listOf("z", "x", "y"), ExtensionRegistry(r).all(ManyPoint).map { it.id })
    }

    @Test
    fun sameOrderFallsBackToOwnerThenIdSoResultIsDeterministic() {
        val r = freshRegistry()
        r.add(entry(ManyPoint, "dev.c", "same", 100))
        r.add(entry(ManyPoint, "dev.a", "same", 100))
        r.add(entry(ManyPoint, "dev.b", "same", 100))

        val owners = r.effective(ManyPoint.id).map { it.owner.value }
        assertEquals(listOf("dev.a", "dev.b", "dev.c"), owners)
    }

    @Test
    fun onePointKeepsOnlyTheWinnerButRemembersTheLoser() {
        val r = freshRegistry()
        r.add(entry(OnePoint, "dev.a", "slow", 500))
        r.add(entry(OnePoint, "dev.b", "fast", 10))

        val view = ExtensionRegistry(r)
        assertEquals(listOf("fast"), view.all(OnePoint).map { it.id })
        assertEquals(2, view.rawCount(OnePoint.id))

        val rejected = r.rejected(OnePoint.id)
        assertEquals(1, rejected.size)
        assertEquals("slow", rejected.first().first.id)
        assertEquals("fast", rejected.first().second.id)
    }

    // 这条是「裁决放在读取时计算」这个设计决策的直接验证
    @Test
    fun removingTheWinnerPromotesTheRunnerUpWithoutAnyPatching() {
        val r = freshRegistry()
        r.add(entry(OnePoint, "dev.a", "slow", 500))
        r.add(entry(OnePoint, "dev.b", "fast", 10))
        val view = ExtensionRegistry(r)

        assertEquals(listOf("fast"), view.all(OnePoint).map { it.id })

        r.removeOwner(PluginId("dev.b"))
        assertEquals(listOf("slow"), view.all(OnePoint).map { it.id })
        assertTrue(r.rejected(OnePoint.id).isEmpty())
    }

    @Test
    fun removeOwnerIsScopedToOneOwnerAndReportsCount() {
        val r = freshRegistry()
        r.add(entry(ManyPoint, "dev.a", "1", 10))
        r.add(entry(ManyPoint, "dev.a", "2", 20))
        r.add(entry(ManyPoint, "dev.b", "3", 30))

        assertEquals(2, r.removeOwner(PluginId("dev.a")))
        assertEquals(listOf("3"), ExtensionRegistry(r).all(ManyPoint).map { it.id })
        assertEquals(0, r.removeOwner(PluginId("dev.a")))
    }

    @Test
    fun declaringTheSamePointWithTwoDifferentCardinalitiesIsAProgrammingError() {
        val r = MutableExtensionRegistry()
        val many = ExtensionPoint<Item>(ExtensionPointId("test.clash"), Cardinality.MANY)
        val one = ExtensionPoint<Item>(ExtensionPointId("test.clash"), Cardinality.ONE)
        r.recordCardinality(many)
        val failure = runCatching { r.recordCardinality(one) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "同 id 不同基数必须立刻失败，实际: $failure")
    }
}