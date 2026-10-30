package dev.trellis.app.desktop

import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 *
 * 插件管理和诊断也收在这里：两者都不常用（插件交给对话里的 agent 装、停、删），
 * 各占一个导航位不值当，所以导航只剩 任务 / 计划 / 对话 / 设置 四条。
 */
@Composable
fun SettingsScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    var llmOpen by remember { mutableStateOf(false) }
    var llmNote by remember { mutableStateOf<String?>(null) }

    Column(modifier) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Text("改了就存，重启还在", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { ModelCard(runtime, llmNote) { llmOpen = true } }

            item { AppearanceCard(runtime) }

            item { DailyBudgetCard(runtime) }

            // 时间调试：只有开发者模式（TRELLIS_DEV=1）才出现。
            // 拨「应用内今天」而不是改系统时间——跨周/结算/复盘这些逻辑才需要它。
            if (runtime.devModeEnabled) item { DevTimeCard(runtime) }

            item {
                Spacer(Modifier.height(10.dp))
                PluginsCard(runtime)
            }

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

    if (llmOpen) {
        LlmImportDialog(
            runtime = runtime,
            onDismiss = { llmOpen = false },
            onDone = { message ->
                llmNote = message
                llmOpen = false
            },
        )
    }
}

/**
 * 模型 API：一键导入。
 *
 * 以前只能设环境变量或手写 `llm.json`（还要记住字段名、路径、不能带 BOM），
 * 这是给作者用的流程；用户手里通常只有服务商给的一串 key。
 * 导入后写的是同一个 `llm.json`，客户端每次发消息按配置新建，所以立刻生效。
 */
@Composable
private fun ModelCard(runtime: AppRuntime, note: String?, onImport: () -> Unit) {
    val config = runtime.llmConfig()
    val source = runtime.llmSource()
    val description = if (config == null) {
        "还没有配置 —— 粘贴一个 key 就能开始对话。"
    } else {
        val host = config.endpoint.substringAfter("://").substringBefore('/')
        val suffix = if (source == "环境变量") "（来自环境变量，改文件不生效）" else ""
        config.model + " @ " + host + suffix
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("模型 API", style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(10.dp))
            ChromeButton(
                label = if (config == null) "导入" else "重新导入",
                onClick = onImport,
                filled = config == null,
            )
            if (!note.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 主题与导航。
 *
 * 这两样以前只能靠对话里的 agent 改（`set_theme` / `set_ui`），或者手改 `ui.json`：
 * 用户装完主题插件，插件页明明写着 ACTIVE，界面却没变——因为它只是贡献了主题，
 * 没有被「选中」。设置页现在给两个最常被问到的开关：主题（内置 Bento + 插件贡献的）
 * 和导航位置；点一下走的是和 agent 同一套入口，改完立即落盘。
 */
@Composable
private fun AppearanceCard(runtime: AppRuntime) {
    val prefs = runtime.uiPrefs
    var message by remember { mutableStateOf("") }

    // builtin 永远可选；插件贡献的主题跟着插件的装载/停用来去。
    val themes = buildList {
        add("builtin" to "跟随配色方案")
        addAll(runtime.themeOptions())
    }
    val currentKey = prefs.theme.ifBlank { "builtin" }
    val currentKnown = themes.any { it.first == currentKey }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("主题与导航", style = MaterialTheme.typography.titleSmall)
            Text(
                "主题由插件贡献（内置 Bento 默认启用）；导航换个位置，整页的顶栏就变成左栏。改了就存，重启还在。",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(10.dp))

            Text(
                "主题",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            for ((key, label) in themes) {
                ChoiceRow(
                    label = label,
                    detail = if (key == "builtin") "builtin · 跟随配色方案" else key,
                    selected = key == currentKey,
                    onClick = {
                        val result = runtime.applyTheme(key)
                        message = if (result.startsWith("界面偏好已更新")) {
                            "主题已切到「" + label + "」"
                        } else {
                            result
                        }
                    },
                )
            }
            if (!currentKnown) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前存的主题「" + currentKey + "」已经不可用（插件停了或删了），界面回落到配色方案。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "导航",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            for ((key, label) in listOf("top" to "顶部标签栏", "left" to "左侧边栏（默认）")) {
                ChoiceRow(
                    label = label,
                    detail = null,
                    selected = prefs.nav == key,
                    onClick = {
                        val result = runtime.updateUiPrefs(runtime.uiPrefs.copy(nav = key))
                        message = if (result.startsWith("界面偏好已更新")) {
                            "导航已改成「" + label + "」"
                        } else {
                            result
                        }
                    },
                )
            }

            if (message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 单选行：一个小方块当标记，整行可点；选中的那行右侧写「当前」。 */
@Composable
private fun ChoiceRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Text(
                "当前",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 插件页的入口。
 *
 * 装插件、看贡献、授权能力这些事现在基本交给对话里的 agent，人只在「怎么没反应了」
 * 的时候来这一页看一眼，所以它不该常年占一个导航位。
 */
@Composable
private fun PluginsCard(runtime: AppRuntime) {
    val plugins = runtime.pluginInfos
    val broken = plugins.count { it.errors.isNotEmpty() }
    val status = when {
        plugins.isEmpty() -> "还没有装插件"
        broken > 0 -> plugins.size.toString() + " 个插件 · " + broken + " 个有错误"
        else -> plugins.size.toString() + " 个插件 · 都在跑"
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { runtime.currentTab = TAB_PLUGINS },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("插件", style = MaterialTheme.typography.titleSmall)
            Text(
                "装了什么、贡献了哪些卡片、拿了哪些能力，都在这一页。",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                status + " · 点这一块进去 ›",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
 * 开发者 · 时间调试。
 *
 * 程序里很多逻辑是「时间推移才发生」的（周滚动、结算、结转、复盘、逾期、跨天）。
 * 靠改系统时钟既危险又影响别的应用，所以这里只拨**应用内今天**：
 * 计划后端、AI 工具、计划页都从同一个时钟取日期；计时器/超时仍走真实单调时间。
 *
 * 安全门槛：默认数据目录不允许（模拟日期会真的写 missed / 结算事件），
 * 要么用 `TRELLIS_HOME` 指到演示目录，要么显式 `TRELLIS_DEV_ALLOW_REAL_HOME=1`。
 * 界面只负责拨日期；「真的会写数据」这件事在 AppRuntime.applySimulatedToday 里再拦一次。
 */
@Composable
private fun DevTimeCard(runtime: AppRuntime) {
    var draft by remember(runtime.simulatedToday) {
        mutableStateOf(runtime.simulatedToday?.toString() ?: runtime.clock.today().toString())
    }
    var note by remember { mutableStateOf<String?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text("时间调试（开发者）", style = MaterialTheme.typography.titleSmall)
            Text(
                "把应用内的「今天」拨到任意日期，用来看跨天 / 跨周 / 结算 / 结转 / 复盘。" +
                    "只影响本应用，不改系统时间；计时器和超时仍按真实时间走。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("YYYY-MM-DD") },
                    modifier = Modifier.weight(1f),
                )
                ChromeButton(
                    label = "应用",
                    onClick = {
                        val date = parseSimulatedDate(draft)
                        if (date == null) {
                            note = "日期要写成 2026-11-02 这种格式"
                        } else {
                            note = null
                            runtime.applySimulatedToday(date)
                        }
                    },
                    filled = false,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChromeButton(label = "+1 天", onClick = { runtime.jumpSimulatedDays(1) }, filled = false)
                ChromeButton(label = "+1 周", onClick = { runtime.jumpSimulatedWeeks(1) }, filled = false)
                if (runtime.simulatedToday != null) {
                    TextButton(onClick = { runtime.applySimulatedToday(null) }) {
                        Text("退出模拟", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "当前：" + (
                    runtime.simulatedToday?.let {
                        "模拟 " + it + "（真实 " + java.time.LocalDate.now() + "）"
                    } ?: ("真实时间（" + runtime.clock.today() + "）")
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (!runtime.devClockAllowed) {
                Text(
                    runtime.devClockRejectedReason ?: "当前数据目录不允许改模拟时间：换一个 TRELLIS_HOME 再试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "提醒：模拟日期下活动页、计时器仍是真实数据；拨动后计划会立刻按新日期结算/补周，数据目录请用演示副本。",
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
