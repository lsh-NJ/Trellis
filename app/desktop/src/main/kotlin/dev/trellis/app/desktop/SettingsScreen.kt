package dev.trellis.app.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.trellis.kernel.slot.Slots
import dev.trellis.ui.SlotArea

/**
 * 设置：程序怎么陪你排计划，以及插件自己的开关。
 *
 * 宿主自己只认一项（[StudyPrefs.dailyMinutes]），其余都交给插件 ——
 * 「设置页分组」这个挂载点（`slot.settings.section`）就是给它们的。
 * 以前这块挂在插件页底下，把「应用设置」和「插件管理」混成了一页。
 */
@Composable
fun SettingsScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Text("改了就存，重启还在", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { DailyBudgetCard(runtime) }

            // 插件设置：没有插件贡献时 SlotArea 自己什么都不画，不占位。
            item {
                Spacer(Modifier.height(10.dp))
                SlotArea(
                    runtime.kernel.extensions,
                    Slots.SettingsSection,
                    refreshKey = runtime.tick,
                    onAction = { owner, action -> runtime.performSlotAction(owner, action) },
                    layout = runtime.layoutFor(Slots.SettingsSection.id),
                )
            }

            // 诊断原本是导航里的一页，现在从这块进去：它只在出事时才打开，
            // 常年占一个导航位不值当。
            item {
                Spacer(Modifier.height(10.dp))
                DiagnosticsCard(runtime)
            }
        }
    }
}

/**
 * 诊断的入口。
 *
 * 诊断原先在导航里占一格，可它只在「出事了」的时候才打开 —— 平时白占地方。
 * 挪进设置当一块：整块可点（这一页没有第二个动作，不用再加个按钮）。
 */
@Composable
private fun DiagnosticsCard(runtime: AppRuntime) {
    val errors = runtime.kernelErrors.size
    Card(
        modifier = Modifier.fillMaxWidth().clickable { runtime.currentTab = TAB_DIAG },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("诊断", style = MaterialTheme.typography.titleSmall)
            Text(
                "装插件、停用、删除都留一行记录，内核出过的错也都在这里。" +
                    "平时不用看，出了问题先来这一页。",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                (if (errors == 0) "没有未处理的问题" else errors.toString() + " 条问题记录") + " · 点这一块进去 ›",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「每天预计投入」。
 *
 * 只有一个数：多问一句「工作日和周末一样吗」就得再给四个开关，而用户此刻只需要
 * 一个能加减的数字。这个数唯一的去处是建计划时的每周容量，界面上也就只解释这件事。
 */
@Composable
private fun DailyBudgetCard(runtime: AppRuntime) {
    val prefs = runtime.studyPrefs
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("每日预计投入", style = MaterialTheme.typography.titleSmall)
            Text(
                "每天大概能投多少时间。建学习计划时，这个数乘 7 写进计划的每周容量。",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton("−") { runtime.bumpDailyMinutes(-STEP_MINUTES) }
                Spacer(Modifier.width(8.dp))
                Text(
                    prefs.dailyMinutes.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.widthIn(min = 52.dp),
                    textAlign = TextAlign.Center,
                )
                Text("分钟 / 天", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                StepButton("+") { runtime.bumpDailyMinutes(STEP_MINUTES) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                prefs.describe() + " · 可调范围 " + MIN_DAILY_MINUTES + "–" + MAX_DAILY_MINUTES + " 分钟",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 加减一格。一次 15 分钟：一小时是四格，手感比 5 分钟一档省事。 */
private const val STEP_MINUTES: Int = 15

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.bodyMedium)
    }
}
