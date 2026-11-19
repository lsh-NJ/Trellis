package dev.trellis.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trellis.domain.plan.CommitmentUnitView
import dev.trellis.domain.plan.WeekDay
import dev.trellis.feature.learning.PlanUnitBand
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 计划页的周像素进度。
 *
 * 布局由 [packPixelTasks] 生成，渲染层再按“固定 9dp 单元 + 3dp 任务缝”重新铺一遍：
 * 同一个任务的多格之间用同色桥接，所以任务内部连续；任务与任务之间才有缝。
 * 路径不是“每格一个圆角矩形”拼出来的，而是先求整个任务的联合外轮廓，再对轮廓上的
 * 每一个转角（包括 L 形的内侧凹角）统一做 2dp 圆角。
 */
internal enum class PlanWeeklyPixelState { DONE, NEXT, TODO }

internal data class PlanWeeklyPixelTask(
    val id: String,
    val title: String,
    val weight: Int,
    val state: PlanWeeklyPixelState,
)

internal fun planWeeklyPixelWeight(minutes: Int): Int = when (PlanUnitBand.labelOf(minutes)) {
    "小" -> 1
    "中" -> 2
    else -> 3
}

/**
 * 把当前选中周的任务摊平成像素任务。
 *
 * day 模式用七天里的 occurrence；queue 模式没有铺天，用本周承诺节点。
 * “下一件”是第一个未完成项；后面的未完成项都算普通待做。
 */
internal fun planWeeklyPixelTasks(
    days: List<WeekDay>,
    queueUnits: List<CommitmentUnitView>,
    queueMode: Boolean,
): List<PlanWeeklyPixelTask> {
    data class RawTask(val id: String, val title: String, val minutes: Int, val done: Boolean)

    val raw = if (queueMode && queueUnits.isNotEmpty()) {
        queueUnits.map { unit ->
            RawTask(
                id = unit.id,
                title = unit.title,
                minutes = unit.estimateMinutes,
                done = unit.state == "done",
            )
        }
    } else {
        days.flatMap { day ->
            day.items.map { item ->
                RawTask(
                    id = item.occurrenceId,
                    title = item.title,
                    minutes = item.minutes,
                    done = item.status == "done",
                )
            }
        }
    }
    if (raw.isEmpty()) return emptyList()
    val nextIndex = raw.indexOfFirst { !it.done }
    return raw.mapIndexed { index, item ->
        PlanWeeklyPixelTask(
            id = item.id,
            title = item.title,
            weight = planWeeklyPixelWeight(item.minutes),
            state = when {
                item.done -> PlanWeeklyPixelState.DONE
                index == nextIndex -> PlanWeeklyPixelState.NEXT
                else -> PlanWeeklyPixelState.TODO
            },
        )
    }
}

/**
 * 周条优先用两行：它更像进度条，宽度也可以自然拉长。
 * 两行无解时退回紧凑模式（最多三行）。
 */
internal fun packWeeklyPixelTasks(tasks: List<PlanWeeklyPixelTask>): PixelPacking? {
    if (tasks.isEmpty()) return null
    val weights = tasks.map { it.weight }
    return packPixelTasks(weights, rows = 2)
        ?: packPixelTasksCompact(weights, maxRows = 3)
        // 极少数顺序约束把可行解堵死时，宁可牺牲“严格从左到右”，也别让整块进度消失。
        ?: packPixelTasks(weights, rows = 3, keepSequence = false)
        ?: packPixelTasks(weights, rows = 2, keepSequence = false)
}

@Composable
internal fun PlanWeeklyPixelProgress(
    tasks: List<PlanWeeklyPixelTask>,
    modifier: Modifier = Modifier,
) {
    if (tasks.isEmpty()) return
    val packing = remember(tasks) { packWeeklyPixelTasks(tasks) } ?: return
    val tokens = LocalTrellisTokens.current
    val doneUnits = tasks.filter { it.state == PlanWeeklyPixelState.DONE }.sumOf { it.weight }
    val totalUnits = tasks.sumOf { it.weight }
    val doneTasks = tasks.count { it.state == PlanWeeklyPixelState.DONE }
    val percent = if (totalUnits > 0) (doneUnits * 100f / totalUnits).roundToInt() else 0
    val doneColor = tokens.colors.accent
    val nextColor = tokens.colors.warn.copy(alpha = 0.58f)
    val todoColor = tokens.colors.ruleStrong.copy(alpha = 0.30f)
    val shape = RoundedCornerShape(tokens.radius.control)
    // 任务内部连接、外轮廓和凹角都在一个 Path 里完成；这里不画单元边框。
    val unit = 9.dp
    val gap = 3.dp
    val radius = 2.dp
    val boardWidth = unit * packing.width + gap * (packing.width - 1).coerceAtLeast(0)
    val boardHeight = unit * packing.height + gap * (packing.height - 1).coerceAtLeast(0)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.space.m),
    ) {
        Box(
            modifier = Modifier
                .background(tokens.colors.sunken.copy(alpha = 0.36f), shape)
                .border(1.dp, tokens.colors.rule, shape)
                .padding(7.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .size(boardWidth, boardHeight)
                    .semantics {
                        contentDescription = "本周像素进度：完成 $doneUnits / $totalUnits 像素，" +
                            "$doneTasks / ${tasks.size} 件任务"
                    },
            ) {
                val unitPx = unit.toPx()
                val gapPx = gap.toPx()
                val radiusPx = radius.toPx()
                packing.placements.forEach { placement ->
                    val color = when (tasks[placement.taskIndex].state) {
                        PlanWeeklyPixelState.DONE -> doneColor
                        PlanWeeklyPixelState.NEXT -> nextColor
                        PlanWeeklyPixelState.TODO -> todoColor
                    }
                    drawPath(
                        path = roundedTaskPath(placement.cells, unitPx, gapPx, radiusPx),
                        color = color,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本周像素",
                    style = tokens.type.control,
                    color = tokens.colors.ink2,
                )
                Spacer(Modifier.width(tokens.space.s))
                Text(
                    "$doneUnits / $totalUnits px",
                    style = tokens.type.meta,
                    fontFamily = AppFonts.mono,
                    color = tokens.colors.ink,
                )
                Text(
                    "  ·  $doneTasks / ${tasks.size} 件",
                    style = tokens.type.meta,
                    color = tokens.colors.ink2,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.space.s),
            ) {
                WeekPixelLegendDot(doneColor)
                Text("完成", style = tokens.type.caption, color = tokens.colors.ink3)
                WeekPixelLegendDot(nextColor)
                Text("下一件", style = tokens.type.caption, color = tokens.colors.ink3)
                WeekPixelLegendDot(todoColor)
                Text("待做", style = tokens.type.caption, color = tokens.colors.ink3)
                Text(
                    "小1 · 中2 · 大3",
                    style = tokens.type.caption,
                    color = tokens.colors.ink3,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        Text(
            "$percent%",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = AppFonts.mono,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.ink,
        )
    }
}

@Composable
private fun WeekPixelLegendDot(color: Color) {
    Box(
        Modifier
            .size(7.dp)
            .background(color, RoundedCornerShape(1.dp)),
    )
}

// ---------------------------------------------------------------- 联合轮廓 + 圆角

private data class MosaicRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private data class GridEdge(
    val fromX: Int,
    val fromY: Int,
    val toX: Int,
    val toY: Int,
)

/**
 * 先求“任务格子 + 内部连接块”的并集，再输出一条带圆角的联合外轮廓。
 *
 * 用压缩网格而不是逐格画矩形，是因为圆角必须落在整个任务的转角上：
 * - L 的外角要圆；
 * - L 的内侧凹角也要圆；
 * - 3 格直条、2 格直条和单格共用同一条路径算法。
 */
internal fun roundedTaskPath(
    cells: List<PixelCell>,
    unit: Float,
    gap: Float,
    radius: Float,
): Path {
    val path = Path().apply { fillType = PathFillType.NonZero }
    if (cells.isEmpty()) return path
    val occupiedCells = cells.toHashSet()
    val pitch = unit + gap
    val rects = ArrayList<MosaicRect>(cells.size * 2)

    for ((x, y) in cells) {
        val left = x * pitch
        val top = y * pitch
        rects += MosaicRect(left, top, left + unit, top + unit)
        // 同一任务内部没有视觉间隙：相邻格之间补一条同色连接块。
        if (PixelCell(x + 1, y) in occupiedCells) {
            rects += MosaicRect(left + unit, top, (x + 1) * pitch, top + unit)
        }
        if (PixelCell(x, y + 1) in occupiedCells) {
            rects += MosaicRect(left, top + unit, left + unit, (y + 1) * pitch)
        }
    }

    val xs = rects.flatMap { listOf(it.left, it.right) }.distinct().sorted()
    val ys = rects.flatMap { listOf(it.top, it.bottom) }.distinct().sorted()
    if (xs.size < 2 || ys.size < 2) return path
    val cellCountX = xs.size - 1
    val cellCountY = ys.size - 1
    val occupied = Array(cellCountY) { BooleanArray(cellCountX) }
    for (yi in 0 until cellCountY) {
        val cy = (ys[yi] + ys[yi + 1]) / 2f
        for (xi in 0 until cellCountX) {
            val cx = (xs[xi] + xs[xi + 1]) / 2f
            occupied[yi][xi] = rects.any { rect ->
                cx > rect.left && cx < rect.right && cy > rect.top && cy < rect.bottom
            }
        }
    }

    val edges = ArrayList<GridEdge>()
    for (yi in 0 until cellCountY) {
        for (xi in 0 until cellCountX) {
            if (!occupied[yi][xi]) continue
            if (yi == 0 || !occupied[yi - 1][xi]) edges += GridEdge(xi, yi, xi + 1, yi)
            if (xi == cellCountX - 1 || !occupied[yi][xi + 1]) {
                edges += GridEdge(xi + 1, yi, xi + 1, yi + 1)
            }
            if (yi == cellCountY - 1 || !occupied[yi + 1][xi]) {
                edges += GridEdge(xi + 1, yi + 1, xi, yi + 1)
            }
            if (xi == 0 || !occupied[yi][xi - 1]) edges += GridEdge(xi, yi + 1, xi, yi)
        }
    }

    val byStart = edges.associateBy { it.fromX to it.fromY }
    val usedStarts = HashSet<Pair<Int, Int>>()
    for (first in edges) {
        if (first.fromX to first.fromY in usedStarts) continue
        val vertices = ArrayList<Pair<Int, Int>>()
        var edge = first
        while (true) {
            val start = edge.fromX to edge.fromY
            if (!usedStarts.add(start)) break
            vertices += start
            if (edge.toX == first.fromX && edge.toY == first.fromY) break
            edge = byStart[edge.toX to edge.toY] ?: break
        }
        if (vertices.size < 3) continue
        val points = vertices.map { (x, y) -> Offset(xs[x], ys[y]) }
        val simplified = removeCollinear(points)
        if (simplified.size >= 3) appendRoundedLoop(path, simplified, radius)
    }
    return path
}

private fun removeCollinear(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val result = ArrayList<Offset>(points.size)
    for (i in points.indices) {
        val previous = points[(i - 1 + points.size) % points.size]
        val current = points[i]
        val next = points[(i + 1) % points.size]
        val sameX = kotlin.math.abs(previous.x - current.x) < 0.01f &&
            kotlin.math.abs(current.x - next.x) < 0.01f
        val sameY = kotlin.math.abs(previous.y - current.y) < 0.01f &&
            kotlin.math.abs(current.y - next.y) < 0.01f
        if (!sameX && !sameY) result += current
    }
    return result
}

private fun appendRoundedLoop(path: Path, points: List<Offset>, radius: Float) {
    val n = points.size
    for (i in 0 until n) {
        val previous = points[(i - 1 + n) % n]
        val current = points[i]
        val next = points[(i + 1) % n]
        val inLength = (current - previous).getDistance()
        val outLength = (next - current).getDistance()
        if (inLength < 0.01f || outLength < 0.01f) continue
        val r = min(radius, min(inLength / 2f, outLength / 2f))
        val inUnit = (current - previous) / inLength
        val outUnit = (next - current) / outLength
        val inPoint = current - inUnit * r
        val outPoint = current + outUnit * r
        if (i == 0) path.moveTo(inPoint.x, inPoint.y) else path.lineTo(inPoint.x, inPoint.y)
        if (r > 0.01f) {
            path.quadraticTo(current.x, current.y, outPoint.x, outPoint.y)
        } else {
            path.lineTo(outPoint.x, outPoint.y)
        }
    }
    path.close()
}
