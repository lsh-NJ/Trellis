package dev.trellis.app.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 手机端的零件库。
 *
 * 只做几种东西：卡片、栏目名、按钮、输入框、数字块、小标签、分段控件、子页标题栏。
 * 不做通用设计系统 —— 每多一个「可选参数」，页面就会多一种不一致的写法。
 *
 * 先在库里找，找不到再新写；新写的东西如果会被第二页用到，就搬回这里。
 */

/** 卡片：12dp 圆角、1dp 描边、无投影。 */
@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = Gap.l,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LocalCardColor.current)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** 栏目名：等宽小标 +（可选）右侧动作。 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(
                action.first,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = action.second)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun PhoneButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val bg = when {
        !enabled -> Color.Transparent
        filled && pressed -> accent.copy(alpha = 0.8f)
        filled -> accent
        pressed -> accent.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        filled -> MaterialTheme.colorScheme.onPrimary
        else -> accent
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = Touch)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                1.dp,
                when {
                    !enabled -> MaterialTheme.colorScheme.outline
                    filled -> bg
                    else -> accent.copy(alpha = 0.45f)
                },
                RoundedCornerShape(10.dp),
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** 输入框：聚焦时描边转主色。 */
@Composable
fun PhoneField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Touch)
            .clip(RoundedCornerShape(10.dp))
            .background(LocalCardColor.current)
            .border(
                if (focused) 1.5.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyMedium.merge(
            TextStyle(color = MaterialTheme.colorScheme.onSurface),
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
                inner()
            }
        },
    )
}

/** 数字块：一个数字 + 一行小标。四块并排就是「一屏看懂的四个数」。 */
@Composable
fun BigStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    accent: Color? = null,
) {
    val size = when {
        value.length <= 4 -> 26.sp
        value.length <= 8 -> 19.sp
        else -> 15.sp
    }
    Column(modifier) {
        Text(
            text = value,
            fontFamily = if (value.none { it.code > 0x2E80 }) PhoneMono else null,
            fontSize = size,
            lineHeight = size * 1.2f,
            fontWeight = FontWeight.Medium,
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

enum class Tone { Neutral, Good, Warn }

@Composable
fun Pill(text: String, tone: Tone = Tone.Neutral) {
    val (bg, fg) = when (tone) {
        Tone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        Tone.Good -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) to MaterialTheme.colorScheme.primary
        Tone.Warn -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f) to MaterialTheme.colorScheme.error
    }
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/** 分段控件：2–4 个互斥选项，比下拉菜单少一次点击。 */
@Composable
fun Segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else Color.Transparent,
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 小胶囊：横向一排可选值（时间、日期、分类）。
 *
 * 和 [Segmented] 的区别：这里的选项数量不定、可能需要横滑，且不强调「当前是哪一个」，
 * 只强调「选了哪些」。所以不自带底色，选中才填实。
 */
@Composable
fun ChipRow(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Gap.s),
    ) {
        items.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .defaultMinSize(minHeight = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else LocalCardColor.current)
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 二级页的标题栏：返回 + 标题 +（可选）右侧动作。 */
@Composable
fun SubPageBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(Touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(Touch).clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text("←", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(Gap.xs))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
        action?.invoke()
    }
}

@Composable
fun EmptyState(title: String, hint: String? = null, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Gap.l, vertical = Gap.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (hint != null) {
            Spacer(Modifier.height(Gap.xs))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Gap.l))
            PhoneButton(action.first, action.second)
        }
    }
}

/** 勾选圈：22dp，未完成描边、完成填实 + ✓。命中区 48dp。 */
@Composable
fun CheckCircle(checked: Boolean, onChange: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.size(Touch).clickable(onClick = onChange),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) accent else Color.Transparent)
                .border(1.5.dp, if (checked) accent else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** 一条「标签：值」的信息行，设置页与详情页共用。 */
@Composable
fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Gap.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(200.dp),
        )
    }
}

/** 细分隔线：卡片内部成组用。 */
@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    )
}

/** 弹层标题：一句标题 + 一句说明，后面跟动作。 */
@Composable
fun SheetHeading(title: String, hint: String? = null) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    if (hint != null) {
        Spacer(Modifier.height(Gap.xs))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
