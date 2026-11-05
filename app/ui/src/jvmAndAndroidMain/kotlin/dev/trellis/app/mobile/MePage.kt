package dev.trellis.app.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.trellis.activity.ActivitySettings
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.desktop.HostPlatform
import dev.trellis.app.desktop.StewardRuntime
import dev.trellis.app.desktop.TAB_CHAT
import dev.trellis.app.desktop.parseSimulatedDate
import java.io.File
import kotlinx.coroutines.launch

/**
 * 我的：外观、投入、模型、管家、入口。
 *
 * 桌面的设置页有「导航位置」这类选项；手机上导航固定在底部，摆出来只会让人困惑，
 * 所以这里**不出现**。其余真正影响结果的开关一个不少：外观、主题包、每日投入、
 * 模型 API、管家时间、笔记联动、活动数据源、插件、活动、诊断、时间调试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MePage(runtime: AppRuntime, onOpen: (PhoneSubPage) -> Unit) {
    var importOpen by remember { mutableStateOf(false) }
    var activityOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = Gap.l, end = Gap.l, top = Gap.s, bottom = Gap.xxl),
        verticalArrangement = Arrangement.spacedBy(Gap.m),
    ) {
        item { PageHead("我的", "外观、投入、模型与管家") }

        item {
            CardBox {
                SectionLabel("外观")
                Spacer(Modifier.height(Gap.m))
                val modes = listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色")
                Segmented(
                    items = modes.map { it.second },
                    selected = modes.indexOfFirst { it.first == runtime.uiPrefs.appearance }.coerceAtLeast(0),
                    onSelect = {
                        runtime.updateUiPrefs(runtime.uiPrefs.copy(appearance = modes[it].first))
                    },
                )
            }
        }

        item {
            CardBox {
                SectionLabel("主题包")
                Spacer(Modifier.height(Gap.m))
                val themes = remember(runtime.tick) { runtime.themeOptions() }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Gap.s),
                ) {
                    themes.forEach { (key, label) ->
                        val active = runtime.uiPrefs.theme == key ||
                            (key == "builtin" && runtime.uiPrefs.theme.isBlank())
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else LocalCardColor.current,
                                )
                                .clickable { runtime.applyTheme(key) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                label.substringBefore("(").substringBefore("（").trim(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            CardBox {
                SectionLabel("每天预计投入")
                Spacer(Modifier.height(Gap.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            runtime.studyPrefs.dailyMinutes.toString() + " 分钟",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "×7 就是学习计划每周的容量",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PhoneButton("−15", { runtime.bumpDailyMinutes(-15) }, filled = false)
                    Spacer(Modifier.width(Gap.s))
                    PhoneButton("+15", { runtime.bumpDailyMinutes(15) })
                }
            }
        }

        item {
            CardBox {
                SectionLabel("模型 API")
                Spacer(Modifier.height(Gap.m))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (runtime.llmConfigured()) "已配置" else "未配置",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "来源：" + runtime.llmSource(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PhoneButton("导入配置", { importOpen = true }, filled = !runtime.llmConfigured())
                }
            }
        }

        item { StewardCard(runtime) }

        item {
            CardBox {
                SectionLabel("笔记联动")
                Spacer(Modifier.height(Gap.s))
                val on = runtime.uiPrefs.notifyAutoCompletion
                Text(
                    "笔记整理完若自动完成了计划节点，要不要弹系统通知。记录始终会出现在计划页「AI 自动完成」里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Gap.m))
                Segmented(
                    items = listOf("通知已关", "通知已开"),
                    selected = if (on) 1 else 0,
                    onSelect = { runtime.updateUiPrefs(runtime.uiPrefs.copy(notifyAutoCompletion = it == 1)) },
                )
            }
        }

        item {
            CardBox {
                SectionLabel("活动数据源")
                Spacer(Modifier.height(Gap.s))
                val settingsFile = remember(runtime) { File(runtime.home, "activity/settings.json") }
                val current = remember(runtime, activityOpen) {
                    runCatching { ActivitySettings.load(settingsFile.toPath()) }.getOrNull()
                }
                Text(
                    current?.baseUrl ?: "还没有活动库",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(Gap.xs))
                Text(
                    "手机上的 localhost 是手机自己。填局域网里那台跑 ActivityWatch 的地址，例如 http://192.168.1.10:5600/api/0。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Gap.s))
                PhoneButton("改数据源", { activityOpen = true }, filled = false)
            }
        }

        item { DevTimeCard(runtime) }

        item {
            CardBox(padding = 0.dp) {
                EntryRow("插件", runtime.pluginInfos.size.toString() + " 个") { onOpen(PhoneSubPage.Plugins) }
                DividerLine()
                EntryRow("活动", "采集与分类") { onOpen(PhoneSubPage.Activity) }
                DividerLine()
                EntryRow("诊断", "错误与日志") { onOpen(PhoneSubPage.Diagnostics) }
            }
        }

        item {
            CardBox {
                SectionLabel("关于")
                Spacer(Modifier.height(Gap.s))
                InfoRow("版本", "1.0.0")
                DividerLine()
                InfoRow("数据目录", runtime.home.absolutePath)
                DividerLine()
                InfoRow("内核", "sdk 1.0.0 · " + (if (HostPlatform.isDesktop) "JVM" else "ANDROID"))
            }
        }
    }

    if (importOpen) {
        ModalBottomSheet(
            onDismissRequest = { importOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            ImportConfigSheet(runtime) { importOpen = false }
        }
    }

    if (activityOpen) {
        ModalBottomSheet(
            onDismissRequest = { activityOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            ActivitySourceSheet(runtime) { activityOpen = false }
        }
    }
}

/** 管家：早晚两次主动问候的时间，以及要不要弹系统通知。 */
@Composable
private fun StewardCard(runtime: AppRuntime) {
    val settings = runtime.stewardSettings
    val status = buildString {
        append(if (settings.morningEnabled) "晨 " + settings.morningTime else "晨 关")
        append(" · ")
        append(if (settings.eveningEnabled) "晚 " + settings.eveningTime else "晚 关")
    }
    CardBox {
        SectionLabel("管家", action = "打开会话" to {
            runtime.ensureStewardSession()
            runtime.openChat(StewardRuntime.STEWARD_SESSION_ID)
            runtime.currentTab = TAB_CHAT
        })
        Spacer(Modifier.height(Gap.xs))
        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Gap.m))

        Text("晨间问候", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(Gap.xs))
        ChipRow(
            items = listOf("07:30", "08:00", "08:30", "09:00", "10:00", "关"),
            selected = stewardChipIndex(settings.morningEnabled, settings.morningTime, listOf("07:30", "08:00", "08:30", "09:00", "10:00")),
            onSelect = { index ->
                if (index == 5) {
                    runtime.updateStewardSettings(settings.copy(morningEnabled = false))
                } else {
                    val time = listOf("07:30", "08:00", "08:30", "09:00", "10:00")[index]
                    runtime.updateStewardSettings(settings.copy(morningEnabled = true, morningTime = time))
                }
            },
        )

        Spacer(Modifier.height(Gap.m))
        Text("晚间收尾", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(Gap.xs))
        val eveningTimes = listOf("21:30", "22:00", "22:30", "23:00")
        ChipRow(
            items = eveningTimes + "关",
            selected = stewardChipIndex(settings.eveningEnabled, settings.eveningTime, eveningTimes),
            onSelect = { index ->
                if (index == eveningTimes.size) {
                    runtime.updateStewardSettings(settings.copy(eveningEnabled = false))
                } else {
                    runtime.updateStewardSettings(settings.copy(eveningEnabled = true, eveningTime = eveningTimes[index]))
                }
            },
        )

        Spacer(Modifier.height(Gap.m))
        Text("系统通知", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(Gap.xs))
        Segmented(
            items = listOf("关", "开"),
            selected = if (settings.notifyDesktop) 1 else 0,
            onSelect = { runtime.updateStewardSettings(settings.copy(notifyDesktop = it == 1)) },
        )
        Spacer(Modifier.height(Gap.s))
        Text(
            "应用在后台时才弹通知；前台只留对话页签上的小圆点。手机上系统通知受「通知权限」控制。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun stewardChipIndex(enabled: Boolean, time: String, options: List<String>): Int =
    if (!enabled) options.size else options.indexOf(time).let { if (it < 0) options.size else it }

/**
 * 时间调试：把「应用内今天」往后拨，用来验证跨天、周结算、逾期与复盘。
 *
 * 门槛在 AppRuntime 里：默认数据目录不允许拨（会真的写 missed / 结算事件）。
 * 界面只负责拨日期和说明后果。
 */
@Composable
private fun DevTimeCard(runtime: AppRuntime) {
    var draft by remember(runtime.simulatedToday) {
        mutableStateOf(runtime.simulatedToday?.toString() ?: runtime.clock.today().toString())
    }
    var note by remember { mutableStateOf<String?>(null) }

    CardBox {
        SectionLabel(
            "时间调试",
            action = "说明" to { note = "模拟日期会真的写 missed / 结算事件；请用演示数据目录" },
        )
        Spacer(Modifier.height(Gap.xs))
        Text(
            runtime.simulatedToday?.let { "模拟 " + it + "（真实 " + java.time.LocalDate.now() + "）" }
                ?: ("真实时间（" + runtime.clock.today() + "）"),
            style = MaterialTheme.typography.labelSmall,
            color = if (runtime.simulatedToday != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Gap.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PhoneField(value = draft, onValueChange = { draft = it }, placeholder = "YYYY-MM-DD")
            }
            Spacer(Modifier.width(Gap.s))
            PhoneButton("应用", {
                val date = parseSimulatedDate(draft)
                if (date == null) {
                    note = "日期要写成 2026-11-02 这种格式"
                } else {
                    note = null
                    runtime.applySimulatedToday(date)
                }
            }, filled = false)
        }
        Spacer(Modifier.height(Gap.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
            PhoneButton("+1 天", { runtime.jumpSimulatedDays(1) }, filled = false)
            PhoneButton("+1 周", { runtime.jumpSimulatedWeeks(1) }, filled = false)
            if (runtime.simulatedToday != null) {
                PhoneButton("退出模拟", { runtime.applySimulatedToday(null) }, filled = false)
            }
        }
        Spacer(Modifier.height(Gap.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
            PhoneButton("晨间总结", { runtime.debugStewardRitual(morning = true) }, filled = false)
            PhoneButton("晚间总结", { runtime.debugStewardRitual(morning = false) }, filled = false)
        }
        if (!runtime.devClockAllowed) {
            Spacer(Modifier.height(Gap.xs))
            Text(
                runtime.devClockRejectedReason ?: "当前数据目录不允许改模拟时间",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        note?.let {
            Spacer(Modifier.height(Gap.xs))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EntryRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Gap.l, vertical = Gap.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Gap.s))
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 粘贴一段配置就导入：这是手机上唯一可行的配置方式（没有环境变量、没有家目录）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportConfigSheet(runtime: AppRuntime, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val current = runtime.llmConfig()
    var raw by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(current?.endpoint ?: "") }
    var model by remember { mutableStateOf(current?.model ?: "") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
        SheetHeading(
            "导入模型配置",
            "把服务商文档里的那段 JSON / 命令行粘进来，或者直接填下面两项。",
        )
        Spacer(Modifier.height(Gap.l))
        PhoneField(raw, { raw = it }, placeholder = "粘贴配置（含 apiKey）", singleLine = false, minLines = 3)
        Spacer(Modifier.height(Gap.s))
        PhoneField(endpoint, { endpoint = it }, placeholder = "endpoint，例如 https://api.deepseek.com/v1/chat/completions")
        Spacer(Modifier.height(Gap.s))
        PhoneField(model, { model = it }, placeholder = "model，例如 deepseek-chat")
        Spacer(Modifier.height(Gap.l))
        val result = message
        if (result != null) {
            Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Gap.s))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
            PhoneButton("导入", {
                val outcome = runtime.importLlmConfig(raw, endpoint, model)
                message = outcome.message
                scope.launch { runtime.refresh() }
            }, modifier = Modifier.weight(1f), enabled = raw.isNotBlank() || endpoint.isNotBlank())
            PhoneButton("完成", { onDone() }, modifier = Modifier.weight(1f), filled = false)
        }
    }
}

/** 活动数据源：手机上唯一能改的就是「那台机器在哪」。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivitySourceSheet(runtime: AppRuntime, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settingsFile = remember(runtime) { File(runtime.home, "activity/settings.json") }
    val current = remember(runtime) { runCatching { ActivitySettings.load(settingsFile.toPath()) }.getOrNull() }
    var baseUrl by remember { mutableStateOf(current?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(current?.apiKey ?: "") }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
        SheetHeading(
            "活动数据源",
            "填局域网里 ActivityWatch 的地址；留空会回落 localhost —— 手机上的 localhost 是手机自己。",
        )
        Spacer(Modifier.height(Gap.l))
        PhoneField(baseUrl, { baseUrl = it }, placeholder = "http://192.168.1.10:5600/api/0")
        Spacer(Modifier.height(Gap.s))
        PhoneField(apiKey, { apiKey = it }, placeholder = "apiKey（AW 没开鉴权就留空）")
        Spacer(Modifier.height(Gap.l))
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Gap.s))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
            PhoneButton("保存", {
                scope.launch {
                    message = runtime.saveActivitySource(baseUrl.trim(), apiKey.trim())
                }
            }, modifier = Modifier.weight(1f))
            PhoneButton("完成", { onDone() }, modifier = Modifier.weight(1f), filled = false)
        }
    }
}
