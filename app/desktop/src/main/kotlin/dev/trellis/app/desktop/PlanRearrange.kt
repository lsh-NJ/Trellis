package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 拖拽重排：侧栏里的节点可以拖到某一天的日期格上，日视图里的节点也可以直接拖到别的日期。
 *
 * 坐标全部用 root 坐标系：拖拽源在 [Modifier.planDragSource] 里记住自己的 `positionInRoot`，
 * 落点由 [PlanDragController.targetAt] 拿当前指针去比各个日期格的 `boundsInRoot`。
 * 这样不用 Compose 的 dragAndDrop（那套只传字符串，画不了 ghost、也不好做同一窗口内的命中判定）。
 */

/** 一次拖拽携带的节点。侧栏里有两种来源：本周逾期（occurrence）和更早欠下的单元（unit）。 */
internal sealed interface PlanDragPayload {
    val title: String
    val minutes: Int

    data class Occurrence(
        val occurrenceId: String,
        override val title: String,
        override val minutes: Int,
    ) : PlanDragPayload

    /** 不在本周承诺里的单元：更早欠下的（backlog）和还没排的（池）。 */
    data class PendingUnit(
        val unitId: String,
        override val title: String,
        override val minutes: Int,
    ) : PlanDragPayload
}

/**
 * 拖拽状态：谁在被拖、指针在哪、每个日期格占哪块矩形。
 *
 * 日期格每帧 `onGloballyPositioned` 时重新登记；`targetAt` 取包含指针且面积最小的那个，
 * 这样日期格（小）永远优先于整块日列表（大）。
 */
internal class PlanDragController {
    var payload: PlanDragPayload? by mutableStateOf(null)
        private set

    var pointer: Offset by mutableStateOf(Offset.Zero)
        private set

    private val dayBounds = mutableStateMapOf<String, Rect>()

    /** 「当天安排」整块的落点：拖到列表里 = 放到选中的那天。和日期格分开存，避免互相覆盖。 */
    private var listDay: String? = null
    private var listBounds: Rect? = null

    fun registerDay(day: String, bounds: Rect) {
        dayBounds[day] = bounds
    }

    fun unregisterDay(day: String) {
        dayBounds.remove(day)
    }

    fun targetAt(point: Offset): String? {
        listBounds?.let { bounds ->
            if (bounds.contains(point)) return listDay
        }
        return dayBounds.entries
            .filter { it.value.contains(point) }
            .minByOrNull { it.value.width * it.value.height }
            ?.key
    }

    fun registerList(day: String, bounds: Rect) {
        listDay = day
        listBounds = bounds
    }

    fun unregisterList() {
        listDay = null
        listBounds = null
    }

    fun start(payload: PlanDragPayload, at: Offset) {
        this.payload = payload
        pointer = at
    }

    fun move(at: Offset) {
        pointer = at
    }

    fun cancel() {
        payload = null
    }
}

/**
 * 把一个 Composable 变成拖拽源。
 *
 * 注意 origin 用 `remember { mutableStateOf }` 存而不是普通变量：`pointerInput` 的 lambda 只在 key
 * 变化时重建，闭包里必须读 State 才能拿到最新的布局位置。滚动之后位置会变，这个设计才跟得上。
 */
@Composable
internal fun Modifier.planDragSource(
    payload: PlanDragPayload,
    controller: PlanDragController,
    onDrop: (PlanDragPayload, String) -> Unit,
    onDragStart: () -> Unit = {},
): Modifier {
    val origin = remember(payload) { mutableStateOf(Offset.Zero) }
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    return this
        .onGloballyPositioned { coordinates -> origin.value = coordinates.positionInRoot() }
        .pointerInput(payload) {
            detectDragGestures(
                onDragStart = { local ->
                    controller.start(payload, origin.value + local)
                    currentOnDragStart()
                },
                onDrag = { change, _ ->
                    controller.move(origin.value + change.position)
                    change.consume()
                },
                onDragEnd = {
                    val target = controller.targetAt(controller.pointer)
                    controller.cancel()
                    if (target != null) currentOnDrop(payload, target)
                },
                onDragCancel = { controller.cancel() },
            )
        }
}

/**
 * 右侧「重排」抽屉：列出本周到期没做的项和更早欠下的单元，拖到日期格上就算重排。
 *
 * 这里只负责拖和放下，真正写库走 [AppRuntime.planDragOccurrenceToDay] / [AppRuntime.planPlaceUnitToDay]，
 * 能不能放（过去日期、跨周）也由运行时报错，抽屉里不做第二套规则。
 */
@Composable
internal fun PlanRearrangeDrawer(
    runtime: AppRuntime,
    controller: PlanDragController,
    modifier: Modifier = Modifier,
) {
    val overdue = runtime.planOverdue
    val thisWeek = overdue?.thisWeek.orEmpty()
    val backlog = overdue?.backlog.orEmpty()
    val pool = runtime.planPool
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .width(296.dp)
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .graphicsLayer { shadowElevation = 18f; this.shape = shape; clip = true }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "重排",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "拖到下面某一天的日期格，或者拖进「今天」的列表",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { runtime.closePlanRearrange() }) {
                Text("收起", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (thisWeek.isEmpty() && backlog.isEmpty() && pool.isEmpty()) {
            Text(
                "现在没有需要重排的节点：本周的都在计划里，欠账池和待安排也都是空的。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PlanRearrangeHintText(runtime)
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (thisWeek.isNotEmpty()) {
                item { SectionLabel("本周到期没做 · " + thisWeek.size) }
                items(thisWeek, key = { "occ:" + it.occurrenceId }) { item ->
                    DraggablePlanRow(
                        payload = PlanDragPayload.Occurrence(item.occurrenceId, item.title, item.minutes),
                        controller = controller,
                        hint = item.chapter.ifBlank { "第 " + (item.sortOrder + 1) + " 节" } +
                            " · " + item.minutes + " 分钟 · 原定 " + item.day.takeLast(5),
                        onDrop = { payload, day ->
                            if (payload is PlanDragPayload.Occurrence) {
                                runtime.planDragOccurrenceToDay(payload.occurrenceId, day)
                                runtime.selectPlanDay(day)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (backlog.isNotEmpty()) {
                item { SectionLabel("更早欠下 · " + backlog.size) }
                items(backlog, key = { "unit:" + it.unitId }) { item ->
                    DraggablePlanRow(
                        payload = PlanDragPayload.PendingUnit(item.unitId, item.title, item.estimateMinutes),
                        controller = controller,
                        hint = item.missedInWeek + " 欠下 · " + item.estimateMinutes + " 分钟",
                        onDrop = { payload, day ->
                            if (payload is PlanDragPayload.PendingUnit) {
                                runtime.planPlaceUnitToDay(payload.unitId, day)
                                runtime.selectPlanDay(day)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (pool.isNotEmpty()) {
                item { SectionLabel("待安排 · " + pool.size + "（拖到某天就排上）") }
                items(pool, key = { "pool:" + it.id }) { item ->
                    DraggablePlanRow(
                        payload = PlanDragPayload.PendingUnit(item.id, item.title, item.estimateMinutes),
                        controller = controller,
                        hint = item.chapter.ifBlank { "待安排" } + " · " + item.estimateMinutes + " 分钟",
                        onDrop = { payload, day ->
                            if (payload is PlanDragPayload.PendingUnit) {
                                runtime.planPlaceUnitToDay(payload.unitId, day)
                                runtime.selectPlanDay(day)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        PlanRearrangeHintText(runtime)
    }
}

/** 侧栏底部的操作结果提示：成功绿、失败红。 */
@Composable
private fun PlanRearrangeHintText(runtime: AppRuntime) {
    runtime.planRearrangeHint?.let { hint ->
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = if (runtime.planRearrangeHintIsError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** 抽屉里的一行：拖柄 + 标题 + 一行提示。放大 / 阴影交给 [PlanDragGhost]。 */
@Composable
private fun DraggablePlanRow(
    payload: PlanDragPayload,
    controller: PlanDragController,
    hint: String,
    onDrop: (PlanDragPayload, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dragging = controller.payload == payload
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .planDragSource(payload, controller, onDrop = onDrop)
            .graphicsLayer { alpha = if (dragging) 0.4f else 1f }
            .clip(shape)
            .background(
                if (dragging) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                },
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .width(10.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.outline),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                payload.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            payload.minutes.toString() + "′",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 跟着指针走的 ghost：让「正在拖的是哪一条」一眼可见。
 *
 * [containerOrigin] 是宿主 Box 在 root 里的位置：controller 里的指针是 root 坐标，
 * 而 `offset` 用的是这个 Box 的局部坐标，所以要减掉一次，否则 ghost 会整体偏出去。
 */
@Composable
internal fun PlanDragGhost(controller: PlanDragController, containerOrigin: Offset) {
    val payload = controller.payload ?: return
    val density = LocalDensity.current
    val width = 190.dp
    val height = 44.dp
    val widthPx = with(density) { width.toPx() }
    val heightPx = with(density) { height.toPx() }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .offset {
                val local = controller.pointer - containerOrigin
                IntOffset(
                    (local.x - widthPx / 2f).roundToInt(),
                    (local.y - heightPx / 2f).roundToInt(),
                )
            }
            .size(width, height)
            .graphicsLayer {
                shadowElevation = 14f
                scaleX = 1.03f
                scaleY = 1.03f
                this.shape = shape
                clip = true
                alpha = 0.97f
            }
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.primary, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "↕",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                payload.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                payload.minutes.toString() + "′",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
