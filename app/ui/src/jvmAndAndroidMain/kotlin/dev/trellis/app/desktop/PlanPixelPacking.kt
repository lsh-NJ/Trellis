package dev.trellis.app.desktop

/**
 * 周进度像素拼图的纯布局算法。
 *
 * 输入是按任务顺序排列的权重（1 = 小任务，2 = 中任务，3 = 大任务），输出每个任务
 * 在最多 [maxRows] 行网格里的骨牌位置。算法只决定“形状 + 格子坐标”，不决定 9px
 * 单元、1px 间隔和颜色；这些属于渲染层。
 *
 * 三格任务会尝试六种形状：一字横、一字竖、四个方向的 L。两格任务尝试横/竖。
 * 搜索约束：
 * 1. 任务顺序要被保留为从左到右推进：每个后续任务的最左格 x 不得小于前一个；
 * 2. 除第一块外，每个新块至少与已有块有一条公共边（先形成一整块拼图）；
 * 3. 先在给定行数下找最小宽度；找不到才向右扩宽。
 *
 * 这是个带节点预算的精确搜索。周计划通常只有十几件任务，网格高最多 3 行，
 * 搜索空间很小；预算主要防止异常输入把 UI 卡死。
 */
internal data class PixelCell(val x: Int, val y: Int)

internal enum class PixelShapeKind {
    SINGLE,
    DOMINO_HORIZONTAL,
    DOMINO_VERTICAL,
    TRI_LINE_HORIZONTAL,
    TRI_LINE_VERTICAL,
    TRI_L,
}

internal data class PixelTaskPlacement(
    val taskIndex: Int,
    val shape: PixelShapeKind,
    val cells: List<PixelCell>,
) {
    val minX: Int get() = cells.minOf { it.x }
    val minY: Int get() = cells.minOf { it.y }
    val maxX: Int get() = cells.maxOf { it.x }
    val maxY: Int get() = cells.maxOf { it.y }
}

internal data class PixelPacking(
    val width: Int,
    val height: Int,
    val placements: List<PixelTaskPlacement>,
) {
    /** 外接矩形里没有任务占用的格子数；0 表示完美填满。 */
    val holes: Int get() = width * height - placements.sumOf { it.cells.size }
}

/** 三格任务可选的六种合法形状；检查器也复用这张表。 */
internal fun pixelShapeChoices(units: Int): List<PixelShapeKind> = when (units) {
    1 -> listOf(PixelShapeKind.SINGLE)
    2 -> listOf(PixelShapeKind.DOMINO_HORIZONTAL, PixelShapeKind.DOMINO_VERTICAL)
    3 -> listOf(
        PixelShapeKind.TRI_L,
        PixelShapeKind.TRI_LINE_VERTICAL,
        PixelShapeKind.TRI_LINE_HORIZONTAL,
    )
    else -> emptyList()
}

/**
 * 把任务权重排进固定行数的紧凑网格。
 *
 * @param units 按顺序排列的任务权重，每项只能是 1..3。
 * @param rows 最大行数（1..3）。大任务的一字竖需要 3 行；L 与横线最多需要 2 行。
 * @param maxNodes 单次宽度尝试的搜索节点预算。
 * @param keepSequence true 时强制后续任务不跑到前一个任务左边。
 * @return 找不到合法布局时返回 null（不会抛异常，UI 可以回退到普通条）。
 */
internal fun packPixelTasks(
    units: List<Int>,
    rows: Int,
    maxNodes: Int = 250_000,
    keepSequence: Boolean = true,
): PixelPacking? {
    require(rows in 1..3) { "像素拼图最多三行，收到 rows=$rows" }
    require(units.all { it in 1..3 }) { "任务权重只能是 1..3，收到 $units" }
    if (units.isEmpty()) return PixelPacking(width = 0, height = rows, placements = emptyList())

    val totalUnits = units.sum()
    // 面积下界：即使每行都排满，也不能比这个更窄。
    val minWidth = (totalUnits + rows - 1) / rows
    for (width in minWidth..totalUnits) {
        val search = PackingSearch(
            units = units,
            width = width,
            height = rows,
            maxNodes = maxNodes,
            keepSequence = keepSequence,
        )
        search.solve(0)?.let { return it }
        if (search.abortedByBudget) return null
    }
    return null
}

/**
 * 在“尽量紧凑”和“更像一条长进度”之间做选择时，可直接比较不同行数下的结果。
 * 优先洞更少，其次外接面积更小，最后偏向更矮。
 */
internal fun packPixelTasksCompact(
    units: List<Int>,
    maxRows: Int = 3,
    maxNodes: Int = 250_000,
): PixelPacking? {
    val candidates = (2..maxRows)
        .mapNotNull { packPixelTasks(units, it, maxNodes = maxNodes) }
    return candidates.minWithOrNull(
        compareBy<PixelPacking>({ it.holes }, { it.width * it.height }, { it.height }),
    )
}

private data class LocalPixelShape(
    val kind: PixelShapeKind,
    val cells: List<PixelCell>,
) {
    val width: Int get() = cells.maxOf { it.x } + 1
    val height: Int get() = cells.maxOf { it.y } + 1
}

/**
 * 形状枚举顺序是算法审美的一部分：三格先试 L，再试竖线，最后横线。
 * 当多种铺法同样紧凑时，L 优先能让轮廓更像拼图，而不是永远排成规整横条。
 */
private fun shapesFor(units: Int): List<LocalPixelShape> = when (units) {
    1 -> listOf(
        LocalPixelShape(PixelShapeKind.SINGLE, listOf(PixelCell(0, 0))),
    )
    2 -> listOf(
        LocalPixelShape(
            PixelShapeKind.DOMINO_HORIZONTAL,
            listOf(PixelCell(0, 0), PixelCell(1, 0)),
        ),
        LocalPixelShape(
            PixelShapeKind.DOMINO_VERTICAL,
            listOf(PixelCell(0, 0), PixelCell(0, 1)),
        ),
    )
    3 -> listOf(
        // 四种 L：缺角分别位于右下、左下、右上、左上。
        LocalPixelShape(
            PixelShapeKind.TRI_L,
            listOf(PixelCell(0, 0), PixelCell(1, 0), PixelCell(0, 1)),
        ),
        LocalPixelShape(
            PixelShapeKind.TRI_L,
            listOf(PixelCell(0, 0), PixelCell(1, 0), PixelCell(1, 1)),
        ),
        LocalPixelShape(
            PixelShapeKind.TRI_L,
            listOf(PixelCell(0, 0), PixelCell(0, 1), PixelCell(1, 1)),
        ),
        LocalPixelShape(
            PixelShapeKind.TRI_L,
            listOf(PixelCell(1, 0), PixelCell(0, 1), PixelCell(1, 1)),
        ),
        LocalPixelShape(
            PixelShapeKind.TRI_LINE_VERTICAL,
            listOf(PixelCell(0, 0), PixelCell(0, 1), PixelCell(0, 2)),
        ),
        LocalPixelShape(
            PixelShapeKind.TRI_LINE_HORIZONTAL,
            listOf(PixelCell(0, 0), PixelCell(1, 0), PixelCell(2, 0)),
        ),
    )
    else -> emptyList()
}

private data class PlacementCandidate(
    val placement: PixelTaskPlacement,
    val minX: Int,
    val minY: Int,
    val order: Int,
)

private class PackingSearch(
    private val units: List<Int>,
    private val width: Int,
    private val height: Int,
    private val maxNodes: Int,
    private val keepSequence: Boolean,
) {
    private val occupied = BooleanArray(width * height)
    private val placements = arrayOfNulls<PixelTaskPlacement>(units.size)
    private var visited = 0
    var abortedByBudget = false
        private set

    fun solve(taskIndex: Int): PixelPacking? {
        if (taskIndex == units.size) {
            return PixelPacking(
                width = width,
                height = height,
                placements = placements.filterNotNull(),
            )
        }
        visited += 1
        if (visited > maxNodes) {
            abortedByBudget = true
            return null
        }

        val previousMinX = if (!keepSequence || taskIndex == 0) {
            0
        } else {
            placements[taskIndex - 1]!!.minX
        }
        val candidates = candidatesFor(taskIndex, previousMinX)
        for (candidate in candidates) {
            val cells = candidate.placement.cells
            if (!cells.all { canOccupy(it) }) continue
            cells.forEach { occupy(it) }
            placements[taskIndex] = candidate.placement
            solve(taskIndex + 1)?.let { return it }
            placements[taskIndex] = null
            cells.forEach { release(it) }
        }
        return null
    }

    private fun candidatesFor(taskIndex: Int, previousMinX: Int): List<PlacementCandidate> {
        val candidates = ArrayList<PlacementCandidate>()
        var order = 0
        for (shape in shapesFor(units[taskIndex])) {
            if (shape.width > width || shape.height > height) continue
            for (y in 0..height - shape.height) {
                for (x in 0..width - shape.width) {
                    if (keepSequence && taskIndex > 0 && x < previousMinX) continue
                    val shapeCells = shape.cells.map { PixelCell(x + it.x, y + it.y) }
                    if (!shapeCells.all { canOccupy(it) }) continue
                    // 从第二块起，新块必须和已有拼图共边；否则会留下飞地。
                    if (taskIndex > 0 && !touchesOccupied(shapeCells)) continue
                    candidates += PlacementCandidate(
                        placement = PixelTaskPlacement(
                            taskIndex = taskIndex,
                            shape = shape.kind,
                            cells = shapeCells,
                        ),
                        minX = x,
                        minY = y,
                        order = order,
                    )
                    order += 1
                }
            }
        }
        // 先往左、再往上找；相同位置按 L → 竖 → 横的形状枚举顺序 tie-break。
        return candidates.sortedWith(
            compareBy<PlacementCandidate>({ it.minX }, { it.minY }, { it.order }),
        )
    }

    private fun touchesOccupied(cells: List<PixelCell>): Boolean = cells.any { cell ->
        neighborOffsets.any { (dx, dy) ->
            val x = cell.x + dx
            val y = cell.y + dy
            x in 0 until width && y in 0 until height && occupied[index(x, y)]
        }
    }

    private fun canOccupy(cell: PixelCell): Boolean =
        cell.x in 0 until width &&
            cell.y in 0 until height &&
            !occupied[index(cell.x, cell.y)]

    private fun occupy(cell: PixelCell) {
        occupied[index(cell.x, cell.y)] = true
    }

    private fun release(cell: PixelCell) {
        occupied[index(cell.x, cell.y)] = false
    }

    private fun index(x: Int, y: Int): Int = y * width + x

    private companion object {
        val neighborOffsets = listOf(
            1 to 0,
            -1 to 0,
            0 to 1,
            0 to -1,
        )
    }
}
