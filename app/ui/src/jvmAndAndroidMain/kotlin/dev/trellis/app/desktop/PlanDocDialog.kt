package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

/**
 * 计划文档对话框：「想法」层的一篇文章，改完保存；「同步本周」把本周条目确认落进计划（不可撤销）。
 *
 * 文档格式与解析在 `:features:learning-process` 的 `PlanDocStore`；AI 侧同一份文件用
 * `PlanDocTools` 的 `read_plan_doc` / `write_plan_doc` / `sync_plan_week` 操作。
 */
@Composable
internal fun PlanDocDialog(runtime: AppRuntime) {
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = { runtime.closePlanDoc() },
        title = {
            Text(
                "计划文档" + (runtime.planDocPlanTitle?.let { " · " + it } ?: ""),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "先想清楚再排：目标是这段学习的验收；「## 2026-W45」这类小节是一周的条目；" +
                        "没想好哪天做的放「## 待安排」。同步只把本周条目追加进计划库（进待安排池，不可撤销），" +
                        "具体哪天做仍由拖拽/计划页决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp, max = 430.dp)
                        .clip(shape)
                        .border(1.dp, colors.outlineVariant, shape)
                        .background(colors.surfaceVariant.copy(alpha = 0.30f))
                        .padding(10.dp),
                ) {
                    BasicTextField(
                        value = runtime.planDocDraft,
                        onValueChange = runtime::updatePlanDocDraft,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
                    )
                }
                runtime.planDocNote?.let { note ->
                    Text(note, style = MaterialTheme.typography.labelSmall, color = colors.primary)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChromeTextLink("同步本周到计划", onClick = { runtime.syncPlanDocWeek() })
                Spacer(Modifier.width(4.dp))
                ChromeButton(label = "保存", onClick = { runtime.savePlanDocDraft() })
            }
        },
        dismissButton = {
            ChromeButton(label = "关闭", onClick = { runtime.closePlanDoc() })
        },
    )
}
