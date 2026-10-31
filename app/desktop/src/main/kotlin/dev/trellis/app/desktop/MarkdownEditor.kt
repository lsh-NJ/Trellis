package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 知识库的 Markdown 编辑器（外壳）。
 *
 * 真正的编辑体验在 [MarkdownBlockEditor]：正文按块渲染，光标落在哪一块，
 * 哪一块就显示原始 Markdown，其余块显示渲染结果（行内编辑、行外预览）。
 *
 * 这个文件只留三件页面级的东西：
 *  - [MarkdownEditor] 很薄的入口，页面不必知道块编辑器的细节；
 *  - [EditorChip] / [MarkdownMenuOverlay]：页头的「编辑 / 预览」「目录」「菜单」与菜单浮层；
 *  - [requestFocusWhenReady]：安全的焦点请求（等布局就绪，失败安静放弃，见函数注释）。
 */

/** 页面入口：正文编辑器。参数语义见 [MarkdownBlockEditor]。 */
@Composable
internal fun MarkdownEditor(
    noteId: String,
    body: String,
    onBodyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    conceptNames: List<String> = emptyList(),
    onDeleteAiLine: ((String) -> Unit)? = null,
    reveal: TextRange? = null,
    revealToken: Int = 0,
) {
    MarkdownBlockEditor(
        noteId = noteId,
        body = body,
        onBodyChange = onBodyChange,
        modifier = modifier,
        preview = preview,
        conceptNames = conceptNames,
        onDeleteAiLine = onDeleteAiLine,
        reveal = reveal,
        revealToken = revealToken,
    )
}

/**
 * 安全地请求焦点：焦点节点要等这一帧布局跑完才挂得上，而 LaunchedEffect 在首帧布局之前
 * 就可能执行；这时直接 `requestFocus()` 会抛 `FocusRequester is not initialized`，
 * 异常没人接会把整个组合取消掉——窗口还在，但界面不再响应（表现为卡死，只能重启）。
 *
 * 所以先等一帧，失败再等一帧重试，最多三次；仍然失败就安静放弃：焦点不是功能必需，
 * 不能让它把页面弄崩。调用点：进页面、概念「定位」后把焦点交回正文。
 */
internal suspend fun FocusRequester.requestFocusWhenReady(): Boolean {
    repeat(3) {
        withFrameNanos { }
        val focused = try {
            requestFocus()
            true
        } catch (notReady: IllegalStateException) {
            false
        }
        if (focused) return true
    }
    return false
}

/** 页头的小开关（编辑/预览、目录、菜单）：比 [ChromeButton] 矮一档，一行放得下三个。 */
@Composable
internal fun EditorChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .clip(ControlShape)
            .background(
                when {
                    active -> accent.copy(alpha = 0.14f)
                    hovered -> accent.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = AppFonts.mono,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 快捷键按键帽：行级代码样式（mono + 浅底 + 细边），一眼能看出「这是要按的键」。 */
@Composable
internal fun ShortcutKeyCap(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = AppFonts.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「菜单」：把编辑器的格式命令和页面快捷键列在一张清单里。
 *
 * 一行是「动作 → 按键」：先说人能看懂的动作（粗体/一级标题），按键用代码样式收在右边，
 * 不要求先记符号。macOS 上显示 ⌘ / ⇧，其它平台显示 Ctrl / Shift（见 [markdownShortcutHints]）。
 */
@Composable
internal fun MarkdownMenuSheet(modifier: Modifier = Modifier) {
    val editorHints = remember { markdownShortcutHints.filter { it.owned } }
    val pageHints = remember { markdownShortcutHints.filter { !it.owned } }
    Column(
        modifier = modifier
            .width(380.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "菜单",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                fontFamily = AppFonts.mono,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "点空白处关闭",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column(
            Modifier
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MenuSection("编辑器", editorHints)
            MenuSection("页面", pageHints)
        }
    }
}

@Composable
private fun MenuSection(title: String, hints: List<ShortcutHint>) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 3.dp),
    )
    for (hint in hints) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                hint.action,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            ShortcutKeyCap(hint.keys)
        }
    }
}

/** 菜单浮层：半透明遮罩 + 居中清单；点遮罩（失焦/点别处）就关。 */
@Composable
internal fun MarkdownMenuOverlay(onClose: () -> Unit) {
    val scrim = remember { MutableInteractionSource() }
    val sheet = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.16f))
            .clickable(interactionSource = scrim, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.clickable(interactionSource = sheet, indication = null, onClick = {}),
        ) {
            MarkdownMenuSheet()
        }
    }
}
