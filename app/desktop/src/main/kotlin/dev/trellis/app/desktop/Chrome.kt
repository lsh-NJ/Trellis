package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
internal val ControlShape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)

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
) {
    // 自己处理交互态：`clickable` 的涟漪在方角细边的语言里太软，
    // 而且鼠标产品更需要「悬停就变色、按下再压一档」这种即时反馈。
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    val background = when {
        filled && pressed -> accent.copy(alpha = 0.82f)
        filled && hovered -> accent.copy(alpha = 0.92f)
        !filled && pressed -> accent.copy(alpha = 0.12f)
        !filled && hovered -> accent.copy(alpha = 0.06f)
        filled -> accent
        else -> Color.Transparent
    }
    val foreground = if (filled) MaterialTheme.colorScheme.onPrimary else accent
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = ControlHeight)
            .clip(ControlShape)
            .background(background)
            .border(1.dp, if (filled) background else controlBorder(), ControlShape)
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
