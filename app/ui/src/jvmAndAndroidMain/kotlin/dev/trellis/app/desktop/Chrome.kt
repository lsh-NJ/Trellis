package dev.trellis.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 分段控件：一组互斥视图/筛选的切换（活动页「日视图 / 范围总览 / …」用）。
 *
 * 和 [ChromeChip] 的区别是语义：chip 是「可多选的标签」，分段是「单选、且当前选中项
 * 必须一眼看出来」——所以选中段不是浅色药丸，而是实心底 + 强调文字，整组共用一条描边。
 */
@Composable
internal fun ChromeSegmented(
    segments: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalTrellisTokens.current
    val shape = RoundedCornerShape(tokens.radius.control)
    Row(
        modifier = modifier
            .clip(shape)
            .border(1.dp, tokens.colors.rule, shape)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val interaction = remember(label) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(
                        when {
                            selected -> tokens.colors.accent
                            hovered -> tokens.colors.sunken
                            else -> Color.Transparent
                        },
                    )
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(index) }
                    .defaultMinSize(minHeight = (tokens.rows.active - 8.dp).coerceAtLeast(28.dp))
                    .padding(horizontal = tokens.space.l, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = tokens.type.control,
                    color = if (selected) tokens.colors.onAccent else tokens.colors.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 宿主自己的外壳组件。
 *
 * 为什么不用 M3 的 Button / TextField：它们把高度、圆角、内边距、悬停态都写死了，
 * 主题只能换颜色；而「Bento」这类风格要的是方角、细边、等宽小标、以及和卡片同一套圆角。
 * 这些组件只取 MaterialTheme 的语义色（由主题包控制），几何量用下面这组固定规则：
 *
 * - 控件高度 44dp（桌面端可点区底线），圆角 6dp，描边 1dp；
 * - 描边色：浅色主题 #8E897C（对纸底 3.4:1，刚好过非文本对比度），暗色主题取 onSurfaceVariant；
 * - 页面内元素零投影，阴影只留给浮层（日历、菜单）。
 *
 * 迁移策略：新代码一律用 Chrome*；老代码遇到一个换一个，不再往 M3 默认外观上加补丁。
 */

/** 控件统一高度：低于 44 的点击目标在触摸屏上会漏点。 */
internal val ControlHeight: Dp = 44.dp

/** 控件统一圆角：和卡片区分开一档，又不至于变成胶囊。 */
internal val ControlShape =
    androidx.compose.foundation.shape.RoundedCornerShape(CONTROL_RADIUS_DP.dp)

/**
 * 控件圆角的默认值（4dp），和 [ChromeStyle.controlRadius] 的默认值一致。
 *
 * 为什么这里仍是常量而不是从 [LocalTrellisTokens] 取：`ControlShape` 被 12 个文件
 * 34 处引用，改成 `@Composable fun controlShape()` 会让这一轮铺到整个界面。
 * 批 2 统一组件时一起迁；在这之前以「默认主题的圆角」为准，主题包自己的
 * `controlRadius` 只在读取 tokens 的地方生效（如 [AppTheme] 的 M3 Shapes）。
 */
private const val CONTROL_RADIUS_DP = 4

/** 控件边界色：比卡片边线深，但比正文浅——「能点」和「只是分隔」要能看出来。 */
@Composable
private fun controlBorder(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    return if (dark) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    } else {
        Color(0xFF8E897C)
    }
}

@Composable
internal fun ChromeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    mono: Boolean = false,
    /** 破坏性操作（彻底删除、清空）：底色用 error，别再和「取消」长得一模一样。 */
    destructive: Boolean = false,
) {
    // 自己处理交互态：`clickable` 的涟漪在方角细边的语言里太软，
    // 而且鼠标产品更需要「悬停就变色、按下再压一档」这种即时反馈。
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val background = when {
        filled && pressed -> accent.copy(alpha = 0.82f)
        filled && hovered -> accent.copy(alpha = 0.92f)
        !filled && pressed -> accent.copy(alpha = 0.12f)
        !filled && hovered -> accent.copy(alpha = 0.06f)
        filled -> accent
        else -> Color.Transparent
    }
    val foreground = when {
        destructive && filled -> MaterialTheme.colorScheme.onError
        destructive -> accent
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> accent
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = ControlHeight)
            .clip(ControlShape)
            .background(background)
            .border(1.dp, if (filled) background else controlBorder(), ControlShape)
            .chromeFocusRing(focused)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) foreground else foreground.copy(alpha = 0.4f),
            fontFamily = if (mono) AppFonts.mono else null,
        )
    }
}

/**
 * 「堆出来的对勾」：完成状态的艺术化提示。
 *
 * 评审 §4.3 说完成态只有「面变浅 + 删除线」，读起来像禁用而不是做完。这里沿「短臂下探 →
 * 长臂上甩」两段线按固定间距叠方块，颜色取主题的荧光笔色（mark）——它是唯一「奖赏」语义的颜色。
 * 不引字体/贴图，16/24/32dp 都是同一枚勾。
 */
@Composable
internal fun BlockCheck(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = LocalTrellisTokens.current.colors.mark,
) {
    Canvas(modifier.size(size)) {
        val cell = this.size.minDimension / 5.2f
        val left = Offset(this.size.width * 0.08f, this.size.height * 0.44f)
        val knee = Offset(this.size.width * 0.36f, this.size.height * 0.82f)
        val top = Offset(this.size.width * 0.94f, this.size.height * 0.16f)
        fun stamps(from: Offset, to: Offset) {
            val length = kotlin.math.hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble()).toFloat()
            val count = (length / (cell * 0.72f)).toInt().coerceAtLeast(1)
            for (i in 0..count) {
                val t = i / count.toFloat()
                val x = from.x + (to.x - from.x) * t
                val y = from.y + (to.y - from.y) * t
                drawRect(
                    color = tint.copy(alpha = if (i % 2 == 0) 1f else 0.78f),
                    topLeft = Offset(x - cell / 2f, y - cell / 2f),
                    size = Size(cell, cell),
                )
            }
        }
        stamps(left, knee)
        stamps(knee, top)
    }
}

/**
 * 勾选框：任务行和计划节点行共用同一个。
 *
 * 视觉框 20dp、命中区 44dp —— 勾选是行里最常点的动作，不能只有 20px。
 * 换主题只换语义色（accentSoft/accent/onAccentSoft），形状和命中区不变。
 */
@Composable
internal fun ChromeCheckbox(
    checked: Boolean,
    onCheckedChange: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = LocalTrellisTokens.current
    val shape = RoundedCornerShape(tokens.radius.control)
    val hit = tokens.rows.active.coerceAtLeast(40.dp)
    Box(
        modifier = modifier.size(hit).clickable(enabled = enabled, onClick = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(shape)
                .background(if (checked) tokens.colors.accentSoft else tokens.colors.surface)
                .border(1.dp, if (checked) tokens.colors.accent else tokens.colors.ink3, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", style = tokens.type.meta, color = tokens.colors.onAccentSoft)
            }
        }
    }
}
/**
 * 单行输入框。
 *
 * 用 [BasicTextField] 而不是 M3 OutlinedTextField：后者最小高度写死 56dp，
 * 44dp 的控件根本压不下去，而这里的高度是全局规则的一部分。
 */
@Composable
internal fun ChromeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
) {
    // 聚焦时描边换成主色：BasicTextField 默认没有焦点提示，
    // 多开几个窗口之后，用户会不知道键盘现在打在哪个框里。
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = if (focused) MaterialTheme.colorScheme.primary else controlBorder()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .defaultMinSize(minHeight = ControlHeight)
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(if (focused) 1.5.dp else 1.dp, borderColor, ControlShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        enabled = enabled,
        singleLine = singleLine,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyMedium.merge(
            TextStyle(color = MaterialTheme.colorScheme.onSurface),
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                }
                inner()
            }
        },
    )
}

/**
 * 二级页的头部：一条返回箭头贴在标题左边，副标题在标题下面。
 *
 * 之前是「标题 → 副标题 → 一行小小的『← 返回设置』」，返回按钮在正文流里、字号和说明文字一样大，
 * 看着像脚注而不是出口；现在统一成标题左侧的方形按钮（44dp 命中区，箭头跟标题同档字级）。
 * 右侧留 [trailing]，给「打开插件目录」这类页级动作。
 */
@Composable
internal fun ChromeSecondaryHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(ControlShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "←",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = LocalTrellisTokens.current.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/**
 * 键盘焦点环：2dp 主色描边，画在控件内侧，不改变布局。
 *
 * 评审 §4.4 第 7 条：桌面端此前只有鼠标悬停态，Tab 走到哪个按钮上看不出来。
 * 描边选内侧是因为外扩会把相邻元素挤动一像素级；2dp 在 44dp 控件上已经足够辨认。
 */
@Composable
private fun Modifier.chromeFocusRing(visible: Boolean, corner: Dp = CONTROL_RADIUS_DP.dp): Modifier {
    val color = MaterialTheme.colorScheme.primary
    if (!visible) return this
    return this.drawWithContent {
        drawContent()
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius((corner.toPx() - stroke / 2f).coerceAtLeast(0f)),
            style = Stroke(width = stroke),
        )
    }
}

/**
 * 文字按钮：没有底框、没有涟漪，悬停才加下划线。
 *
 * 用来替 M3 [androidx.compose.material3.TextButton]：后者带 48dp 最小高度和大写字母间距，
 * 在一行元操作里比正文还高。这里承 40dp 命中区、焦点环和禁用态。
 */
@Composable
internal fun ChromeTextLink(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 破坏性动作（删除、弃用）：文字用 error，和普通链接区分开。 */
    danger: Boolean = false,
) {
    val tokens = LocalTrellisTokens.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = tokens.rows.active)
            .clip(RoundedCornerShape(tokens.radius.control))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .chromeFocusRing(focused, tokens.radius.control)
            .padding(horizontal = tokens.space.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = tokens.type.control,
            color = when {
                !enabled -> tokens.colors.ink3.copy(alpha = 0.5f)
                danger -> tokens.colors.danger
                else -> tokens.colors.accent
            },
            textDecoration = if (hovered && enabled) TextDecoration.Underline else null,
        )
    }
}

/**
 * 选择片：单选/多选项、过滤器。选中用强调色浅底，未选中只留描边。
 *
 * 之前全应用有 10 种手写 chip（Pill / Tag / FilterChip / 自绘方框…），高度 22–32dp 不等。
 * 这一个是它们的目标形态：胶囊形、28dp 高、文字用 control 字级。
 */
@Composable
internal fun ChromeChip(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = LocalTrellisTokens.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(percent = 50)
    val background = when {
        selected -> tokens.colors.accentSoft
        hovered && enabled -> tokens.colors.sunken
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, if (selected) tokens.colors.accentSoft else tokens.colors.rule, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .chromeFocusRing(focused, tokens.radius.pill)
            .padding(horizontal = tokens.space.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = tokens.type.control,
            color = when {
                !enabled -> tokens.colors.ink3.copy(alpha = 0.5f)
                selected -> tokens.colors.onAccentSoft
                else -> tokens.colors.ink2
            },
            maxLines = 1,
        )
    }
}

/**
 * 列表行：设置项、选项组的统一行。
 *
 * 行高走 `tokens.rows.active`（密度三档 32/40/48），选中态是「浅强调底 + 行首竖条 + ✓」。
 * 评审里「当前贴行尾」「7dp 方块单选」两个问题都由这一行收敛：勾选标记紧跟在标签后面，
 * 不再横跨半个窗口。
 */
@Composable
internal fun ChromeListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val tokens = LocalTrellisTokens.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(tokens.radius.control)
    val background = when {
        // 选中只是「这一行有底」，不是一整条色带：桌面上这一行可能 1000pt 宽，
        // 满强度 accentSoft 会把设置页切成一段段绿条。
        selected -> tokens.colors.accentSoft.copy(alpha = 0.3f)
        hovered && enabled && onClick != null -> tokens.colors.sunken.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = tokens.rows.active)
            .clip(shape)
            .background(background)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .chromeFocusRing(focused, tokens.radius.control)
            .padding(horizontal = tokens.space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.space.s),
    ) {
        if (selected) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(tokens.rows.active / 2)
                    .background(tokens.colors.accent, RoundedCornerShape(2.dp)),
            )
        }
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = tokens.type.control,
                    color = when {
                        !enabled -> tokens.colors.ink3.copy(alpha = 0.5f)
                        selected -> tokens.colors.onAccentSoft
                        else -> tokens.colors.ink
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill=false：✓ 紧跟文字，而不是被放到行尾 1000pt 外。
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Spacer(Modifier.width(tokens.space.s))
                    Text("✓", style = tokens.type.control, color = tokens.colors.accent)
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = tokens.type.meta,
                    color = tokens.colors.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/** 区段标题：设置页/详情页里「主题、深浅色、宽度」这类分组名，右侧可挂状态。 */
@Composable
internal fun ChromeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val tokens = LocalTrellisTokens.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = tokens.space.l, bottom = tokens.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = tokens.type.meta,
            color = tokens.colors.ink3,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** 页头：页面标题 + 可选副标题 + 右侧动作。计划页此前没有页标题，就是缺这一个。 */
@Composable
internal fun ChromePageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = LocalTrellisTokens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = tokens.type.page, color = tokens.colors.ink)
            if (subtitle != null) {
                Text(subtitle, style = tokens.type.meta, color = tokens.colors.ink3)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(tokens.space.s),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}
