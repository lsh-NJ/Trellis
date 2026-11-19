package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanPixelPackingTest {

    private val units = listOf(1, 2, 3, 2, 1, 3, 2, 1)

    @Test
    fun threeRowsPackTheWeekIntoFiveColumnsWithoutHoles() {
        val packing = assertNotNull(packPixelTasks(units, rows = 3))

        assertEquals(5, packing.width)
        assertEquals(3, packing.height)
        assertEquals(0, packing.holes)
        assertEquals(units.size, packing.placements.size)
        assertLayoutIsValid(packing)
        assertTrue(
            packing.placements.any { it.shape == PixelShapeKind.TRI_L },
            "同样紧凑时应优先用 L 形，让拼图轮廓有变化",
        )
    }

    @Test
    fun twoRowsStretchToEightColumnsAndKeepTheSequenceLeftToRight() {
        val packing = assertNotNull(packPixelTasks(units, rows = 2))

        assertEquals(8, packing.width)
        assertEquals(2, packing.height)
        assertEquals(1, packing.holes, "15 单位放进 2×8 网格，必然剩一格")
        assertEquals(units.size, packing.placements.size)
        assertLayoutIsValid(packing)

        val byTask = packing.placements.sortedBy { it.taskIndex }
        byTask.zipWithNext().forEach { (a, b) ->
            assertTrue(b.minX >= a.minX, "后续任务不应跑到前一个任务左边：$a -> $b")
        }
    }

    @Test
    fun largeTasksExposeAllThreeShapeFamilies() {
        val choices = pixelShapeChoices(3).toSet()

        assertEquals(
            setOf(
                PixelShapeKind.TRI_L,
                PixelShapeKind.TRI_LINE_VERTICAL,
                PixelShapeKind.TRI_LINE_HORIZONTAL,
            ),
            choices,
        )
        assertEquals(
            setOf(PixelShapeKind.DOMINO_HORIZONTAL, PixelShapeKind.DOMINO_VERTICAL),
            pixelShapeChoices(2).toSet(),
        )
        assertEquals(setOf(PixelShapeKind.SINGLE), pixelShapeChoices(1).toSet())
    }

    @Test
    fun compactModeChoosesTheSmallerEnvelopeWhenItDoesNotAddHoles() {
        val packing = assertNotNull(packPixelTasksCompact(units, maxRows = 3))

        // 3×5 面积 15；2×8 面积 16，所以“最紧凑”选三行方形。
        assertEquals(5, packing.width)
        assertEquals(3, packing.height)
        assertEquals(0, packing.holes)
    }

    private fun assertLayoutIsValid(packing: PixelPacking) {
        val allCells = packing.placements.flatMap { it.cells }
        assertEquals(allCells.size, allCells.toSet().size, "任务之间不能重叠")
        assertTrue(
            allCells.all { it.x in 0 until packing.width && it.y in 0 until packing.height },
            "所有格子都必须落在画板内",
        )

        packing.placements.forEachIndexed { index, placement ->
            assertEquals(index, placement.taskIndex, "placement 顺序必须与任务顺序一致")
            assertEquals(units[index], placement.cells.size, "每个任务的格子数应等于权重")
            assertTrue(isConnected(placement.cells), "单个任务的格子必须连成一块骨牌")
        }
    }

    private fun isConnected(cells: List<PixelCell>): Boolean {
        if (cells.isEmpty()) return false
        val remaining = cells.toMutableSet()
        val queue = ArrayDeque<PixelCell>()
        queue += remaining.first()
        remaining.remove(remaining.first())
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            val neighbors = listOf(
                PixelCell(cell.x + 1, cell.y),
                PixelCell(cell.x - 1, cell.y),
                PixelCell(cell.x, cell.y + 1),
                PixelCell(cell.x, cell.y - 1),
            )
            neighbors.filter { it in remaining }.forEach {
                remaining.remove(it)
                queue += it
            }
        }
        return remaining.isEmpty()
    }

    private operator fun <T> MutableCollection<T>.plusAssign(value: T) {
        add(value)
    }
}
