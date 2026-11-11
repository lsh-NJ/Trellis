@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.kernel.slot.Slots
import dev.trellis.ui.LocalExtraColors
import dev.trellis.ui.SlotArea
import kotlin.math.roundToInt

/**
 * 设置：程序怎么陪你排计划，以及插件自己的开关。
 *
 * 宿主自己只认一项（[StudyPrefs.dailyMinutes]），其余都交给插件 ——
 * 「设置页分组」这个挂载点（`slot.settings.section`）就是给它们的。
 *
 * 版式与知识库/网络页同一套：区段标题（[GraphSectionLabel]）＋ 发丝分隔线，
 * 去掉了每块标题下的解释性长文案；只保留「当前值 / 安全提示」这类功能信息。
 * 外观七组各用一个「浅凹陷底 + 发丝边框」的组卡圈起来，组名加粗、用次级墨色；
 * 选项是横向胶囊（窄窗自动换行），一眼能看出「这是哪一组的取值」。
 * 插件管理和诊断也收在这里：两者都不常用，各占一个导航位不值当，
 * 所以导航是 任务 / 计划 / 知识库 / 对话 / 活动 / 设置 六条。
 */
@Composable
fun SettingsScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    var llmOpen by remember { mutableStateOf(false) }
    var llmNote by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { ModelSection(runtime, llmNote) { llmOpen = true } }

        item { AppearanceSection(runtime) }

        item { DailyBudgetSection(runtime) }
        item { KnowledgeLinkSection(runtime) }

        item { StewardSection(runtime) }

        item { PluginsSection(runtime) }

        // 插件设置：没有插件贡献时 SlotArea 自己什么都不画，不占位。
        item {
            SlotArea(
                runtime.kernel.extensions,
                Slots.SettingsSection,
                refreshKey = runtime.tick,
                onAction = { owner, action -> runtime.performSlotAction(owner, action) },
                layout = runtime.layoutFor(Slots.SettingsSection.id),
            )
        }

        // 诊断原先在导航里占一格，现在从这一块进去：它只在出事时才打开。
        item { DiagnosticsSection(runtime) }

        // 调试模式：以前只能靠启动前设 TRELLIS_DEV=1，现在开关落盘在 <home>/dev.json。
        // 打开后下面两张卡才出现——时间调试拨「应用内今天」，清除数据会真删文件。
        item { DebugSection(runtime) }
        if (runtime.devModeEnabled) item { DevTimeSection(runtime) }
        if (runtime.devModeEnabled) item { DevDataSection(runtime) }
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
private fun ModelSection(runtime: AppRuntime, note: String?, onImport: () -> Unit) {
    val config = runtime.llmConfig()
    val source = runtime.llmSource()
    val status = if (config == null) {
        "未配置"
    } else {
        val host = config.endpoint.substringAfter("://").substringBefore('/')
        config.model + " @ " + host + if (source == "环境变量") " · 环境变量" else ""
    }
    SettingsSection(
        title = "模型 API",
        status = status,
        action = {
            EditorChip(
                label = if (config == null) "导入" else "重新导入",
                active = false,
                onClick = onImport,
            )
        },
    ) {
        if (!note.isNullOrBlank()) {
            SettingsStatusLine(note)
        }
    }
}

/**
 * 外观：主题 / 深浅色 / 字号 / 密度 / 卡片 / 圆角 / 导航。
 *
 * 评审 P0 之一：这一页原来只有「主题」和「导航」，深浅色、字号、密度、卡片、圆角
 * 只能靠对话里的 agent 调 `set_ui` —— 用户看不见、也找不到入口。
 * 现在七组都在这里，每次改动立刻走 [AppRuntime.updateUiPrefs] 落盘并重建界面。
 *
 * 卡片样式会给到「硬边（bento）」这种具体选项，但圆角默认是**跟随主题**：
 * 圆角是主题的壳基因（Bento 3dp、书桌 8dp），用户手动改过才覆盖。
 */
@Composable
private fun AppearanceSection(runtime: AppRuntime) {
    val prefs = runtime.uiPrefs
    var message by remember { mutableStateOf("") }

    // builtin 永远可选；插件贡献的主题跟着插件的装载/停用来去。
    val themes = buildList {
        add("builtin" to "跟随配色方案")
        addAll(runtime.themeOptions())
    }
    val currentKey = prefs.theme.ifBlank { "builtin" }
    val currentKnown = themes.any { it.first == currentKey }

    fun commit(next: UiPrefs, done: String) {
        val result = runtime.updateUiPrefs(next)
        message = if (result.startsWith("界面偏好已更新")) done else result
    }

    SettingsSection(
        title = "外观",
        status = appearanceLabel(prefs.appearance) + " · " + densityLabel(prefs.density) + " · " +
            Math.round(prefs.fontScale * 100) + "%",
    ) {
        AppearanceGroup("主题") {
            for ((key, label) in themes) {
                AppearanceChoiceRow(label = label, selected = key == currentKey) {
                    val result = runtime.applyTheme(key)
                    message = if (result.startsWith("界面偏好已更新")) {
                        "主题已切到「" + label + "」"
                    } else {
                        result
                    }
                }
            }
        }
        if (!currentKnown) {
            SettingsStatusLine(
                "当前存的主题「" + currentKey + "」已经不可用（插件停了或删了），界面回落到配色方案",
                error = true,
            )
        }

        AppearanceGroup("深浅色") {
            for (key in APPEARANCES) {
                AppearanceChoiceRow(label = appearanceLabel(key), selected = prefs.appearance == key) {
                    commit(prefs.copy(appearance = key), "深浅色已改成「" + appearanceLabel(key) + "」")
                }
            }
        }

        AppearanceGroup("字号") {
            for (scale in FONT_SCALE_CHOICES) {
                AppearanceChoiceRow(label = fontScaleLabel(scale), selected = prefs.fontScale == scale) {
                    commit(prefs.copy(fontScale = scale), "字号已改成「" + fontScaleLabel(scale) + "」")
                }
            }
        }

        AppearanceGroup("密度") {
            for (key in DENSITIES) {
                AppearanceChoiceRow(label = densityLabel(key), selected = prefs.density == key) {
                    commit(prefs.copy(density = key), "密度已改成「" + densityLabel(key) + "」，行高跟着变")
                }
            }
        }

        AppearanceGroup("卡片") {
            for (key in CARDS) {
                AppearanceChoiceRow(label = cardLabel(key), selected = prefs.card == key) {
                    commit(prefs.copy(card = key), "卡片已改成「" + cardLabel(key) + "」")
                }
            }
        }

        AppearanceGroup("圆角") {
            for (radius in RADIUS_CHOICES) {
                // 「跟随主题」要显示主题实际给的 dp，得先读 token；点回调里不能再读，
                // 所以标签先算好交给行和回调。
                val label = radiusLabel(radius)
                AppearanceChoiceRow(label = label, selected = prefs.radius == radius) {
                    commit(prefs.copy(radius = radius), "圆角已改成「" + label + "」")
                }
            }
        }

        AppearanceGroup("导航") {
            for ((key, label) in listOf("top" to "顶部标签栏", "left" to "左侧边栏（默认）")) {
                AppearanceChoiceRow(label = label, selected = prefs.nav == key) {
                    commit(prefs.copy(nav = key), "导航已改成「" + label + "」")
                }
            }
        }

        if (message.isNotBlank()) {
            SettingsStatusLine(message)
        }
    }
}

/**
 * 外观里的一组：小标题 + 横向选项。
 *
 * 每组用「凹陷底 + 发丝边框」圈成一块，和上面区段标题下的发丝线是同一套语言；
 * 组名加粗、用次级墨色，选项则是带边框的胶囊——名字是标签，选项是控件，
 * 一眼分得开。选项用 [FlowRow] 横排，窄窗自动换行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceGroup(
    label: String,
    content: @Composable FlowRowScope.() -> Unit,
) {
    val tokens = LocalTrellisTokens.current
    val shape = RoundedCornerShape(tokens.radius.card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = tokens.space.s)
            .clip(shape)
            .background(tokens.colors.sunken.copy(alpha = 0.35f))
            .border(1.dp, tokens.colors.rule.copy(alpha = 0.55f), shape)
            .padding(horizontal = tokens.space.m, vertical = tokens.space.s),
    ) {
        Text(
            label,
            style = tokens.type.control.copy(fontWeight = FontWeight.SemiBold),
            color = tokens.colors.ink2,
        )
        Spacer(Modifier.height(tokens.space.s))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.space.s),
            verticalArrangement = Arrangement.spacedBy(tokens.space.s),
        ) {
            content()
        }
    }
}

/**
 * 外观选项：横向胶囊。
 *
 * 选中态是「强调色边框 + 浅强调底 + ✓」，未选中是发丝线边框；hover 时边框收紧一档。
 * 不用 [ChromeListRow]：外观一组最多 6 个短选项，竖排会让 7 组占满整页、也更难看出
 * 「这些是同一组里的取值」。其他设置页（管家/每日投入）仍然是竖排行。
 */
@Composable
private fun AppearanceChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalTrellisTokens.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(tokens.radius.pill)
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 32.dp)
            .clip(shape)
            .background(
                when {
                    selected -> tokens.colors.accentSoft.copy(alpha = 0.55f)
                    hovered -> tokens.colors.sunken.copy(alpha = 0.85f)
                    else -> Color.Transparent
                },
            )
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused || selected -> tokens.colors.accent.copy(alpha = 0.85f)
                    hovered -> tokens.colors.ruleStrong
                    else -> tokens.colors.rule
                },
                shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = tokens.space.m, vertical = tokens.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Text("✓", style = tokens.type.control, color = tokens.colors.accent)
            Spacer(Modifier.width(tokens.space.xs))
        }
        Text(
            label,
            style = tokens.type.control,
            color = tokens.colors.ink,
            maxLines = 1,
        )
    }
}

/** 「跟随主题」在界面上要把数字写出来，否则用户不知道现在到底是几 dp。 */
@Composable
private fun radiusLabel(radius: Int): String {
    val tokens = LocalTrellisTokens.current
    return if (radius == FOLLOW_THEME_RADIUS) {
        "跟随主题（" + tokens.radius.card.value.roundToInt() + "dp）"
    } else {
        radius.toString() + "dp"
    }
}

/** 字号档位：写百分比，不写「大/小」这种没有刻度的词。 */
private fun fontScaleLabel(scale: Float): String = when (scale) {
    0.85f -> "小 85%"
    1f -> "标准 100%"
    else -> "大 " + Math.round(scale * 100) + "%"
}

/**
 * 「每天预计投入」。
 *
 * 只有一个数：这个数唯一的去处是建计划时的每周容量。
 */
@Composable
private fun DailyBudgetSection(runtime: AppRuntime) {
    val prefs = runtime.studyPrefs
    SettingsSection(
        title = "每日预计投入",
        status = prefs.describe(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepChip("−") { runtime.bumpDailyMinutes(-STEP_MINUTES) }
            Spacer(Modifier.width(10.dp))
            Text(
                prefs.dailyMinutes.toString(),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.widthIn(min = 52.dp),
                textAlign = TextAlign.Center,
            )
            Text(" 分钟 / 天", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(10.dp))
            StepChip("+") { runtime.bumpDailyMinutes(STEP_MINUTES) }
        }
        SettingsStatusLine("可调范围 " + MIN_DAILY_MINUTES + "–" + MAX_DAILY_MINUTES + " 分钟")
    }
}

/**
 * 「笔记联动」：笔记整理完自动勾掉计划节点时的桌面通知开关。
 *
 * 默认关：自动完成本身已经写在计划页的「AI 自动完成」列表里，通知只是可选的提醒。
 */
@Composable
private fun KnowledgeLinkSection(runtime: AppRuntime) {
    val prefs = runtime.uiPrefs
    var message by remember { mutableStateOf("") }
    SettingsSection(
        title = "笔记联动",
        status = if (prefs.notifyAutoCompletion) "通知已开" else "通知已关",
    ) {
        SettingsChoiceRow(
            label = "弹桌面通知",
            selected = prefs.notifyAutoCompletion,
            onClick = {
                val result = runtime.updateUiPrefs(runtime.uiPrefs.copy(notifyAutoCompletion = true))
                message = if (result.startsWith("界面偏好已更新")) "自动完成时会弹桌面通知" else result
            },
        )
        SettingsChoiceRow(
            label = "不弹（默认）",
            selected = !prefs.notifyAutoCompletion,
            onClick = {
                val result = runtime.updateUiPrefs(runtime.uiPrefs.copy(notifyAutoCompletion = false))
                message = if (result.startsWith("界面偏好已更新")) "自动完成不再弹通知" else result
            },
        )
        SettingsStatusLine("笔记整理完若自动完成了计划节点，这里决定要不要弹系统通知；记录始终会出现在计划页「AI 自动完成」里")
        if (message.isNotBlank()) {
            SettingsStatusLine(message)
        }
    }
}

/**
 * 开发者 · 调试模式开关。
 *
 * 这一组以前只有启动前设 `TRELLIS_DEV=1` 才能看到：验证周滚动/结算、演示前清数据
 * 都得改环境变量再重启。现在开关写进 `<home>/dev.json`，打开后立刻出现下面的
 * 「时间调试」「清除数据」；环境变量仍然是启动级覆盖（脚本/截图/CI 用），
 * 由它强制开启时这里只显示状态、不给关掉的选项。
 */
@Composable
private fun DebugSection(runtime: AppRuntime) {
    val forced = runtime.devModeForcedByEnv
    val on = runtime.devModeEnabled
    var message by remember { mutableStateOf<String?>(null) }
    SettingsSection(
        title = "调试模式",
        status = when {
            forced -> "已开（环境变量）"
            on -> "已开"
            else -> "已关"
        },
    ) {
        if (forced) {
            SettingsStatusLine("环境变量 TRELLIS_DEV=1 正在强制开启；这里只能决定下次不带它启动时开不开。")
        } else {
            SettingsChoiceRow("打开", selected = on) { message = runtime.setDebugMode(true) }
            SettingsChoiceRow("关闭（默认）", selected = !on) { message = runtime.setDebugMode(false) }
        }
        SettingsStatusLine("开启后出现「时间调试」和「清除数据」。两者都会改真实数据：时间调试会按模拟日期真的结算/补周（主数据目录也会生效，卡片里会提示先备份），清除数据前会二次确认。")
        message?.let { SettingsStatusLine(it) }
    }
}

/**
 * 开发者 · 时间调试。
 *
 * 程序里很多逻辑是「时间推移才发生」的（周滚动、结算、结转、复盘、逾期、跨天）。
 * 靠改系统时钟既危险又影响别的应用，所以这里只拨**应用内今天**：
 * 计划后端、AI 工具、计划页都从同一个时钟取日期；计时器/超时仍走真实单调时间。
 *
 * 安全：拨日期会真的写 missed / 结算事件。2026-11-09 起调试模式本身就是明确授权，
 * 主数据目录也能拨（不再要求换 `TRELLIS_HOME`），此时卡片里红字提示先备份；
 * 演示目录（非默认 home）一直允许。界面只负责拨日期，写入仍走 AppRuntime.applySimulatedToday。
 */
@Composable
private fun DevTimeSection(runtime: AppRuntime) {
    var draft by remember(runtime.simulatedToday) {
        mutableStateOf(runtime.simulatedToday?.toString() ?: runtime.clock.today().toString())
    }
    var note by remember { mutableStateOf<String?>(null) }
    val current = runtime.simulatedToday?.let {
        "模拟 " + it + "（真实 " + java.time.LocalDate.now() + "）"
    } ?: ("真实时间（" + runtime.clock.today() + "）")

    SettingsSection(title = "时间调试（开发者）", status = current) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChromeTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "YYYY-MM-DD",
                modifier = Modifier.weight(1f),
            )
            EditorChip(
                label = "应用",
                active = false,
                onClick = {
                    val date = parseSimulatedDate(draft)
                    if (date == null) {
                        note = "日期要写成 2026-11-02 这种格式"
                    } else {
                        note = null
                        runtime.applySimulatedToday(date)
                    }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EditorChip("+1 天", active = false, onClick = { runtime.jumpSimulatedDays(1) })
            EditorChip("+1 周", active = false, onClick = { runtime.jumpSimulatedWeeks(1) })
            if (runtime.simulatedToday != null) {
                EditorChip("退出模拟", active = false, onClick = { runtime.applySimulatedToday(null) })
            }
        }
        // 除了拨日期，还能直接触发一次晨/晚总结：验证文案、建议卡和系统通知，不用等真实时间。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EditorChip("触发晨间总结", active = false, onClick = { runtime.debugStewardRitual(morning = true) })
            EditorChip("触发晚间总结", active = false, onClick = { runtime.debugStewardRitual(morning = false) })
        }
        SettingsStatusLine("触发后会跳到管家会话；不消耗当天的仪式机会，可以反复点。")
        note?.let { SettingsStatusLine(it, error = true) }
        if (!runtime.devClockAllowed) {
            SettingsStatusLine(
                runtime.devClockRejectedReason ?: "模拟时间没启用：先关掉再打开调试模式试试",
                error = true,
            )
        }
        if (runtime.devClockAllowed && runtime.devClockOnRealHome) {
            SettingsStatusLine(
                "当前是主数据目录（" + runtime.home.absolutePath +
                    "）：拨日期会真的写 missed / 结算事件，建议先备份",
                error = true,
            )
        }
        SettingsStatusLine(
            "提醒：模拟日期下活动页、计时器仍是真实数据；拨动后计划会立刻按新日期结算/补周",
        )
    }
}

/**
 * 管家：早晚两次主动问候的时间，以及要不要弹桌面通知。
 *
 * 不摆「开关 AI」这种大词 —— 关掉某个仪式就是关掉那一条消息；通知关了
 * 会话里的未读红点仍在。打开管家会话走普通对话页，人格和工具与管理员共用一套。
 */
@Composable
private fun StewardSection(runtime: AppRuntime) {
    val settings = runtime.stewardSettings
    val status = buildString {
        append(if (settings.morningEnabled) "晨 " + settings.morningTime else "晨 关")
        append(" · ")
        append(if (settings.eveningEnabled) "晚 " + settings.eveningTime else "晚 关")
    }
    SettingsSection(title = "管家", status = status) {
        SettingsSubLabel("晨间问候")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (time in listOf("07:30", "08:00", "08:30", "09:00", "10:00")) {
                EditorChip(
                    label = time,
                    active = settings.morningEnabled && settings.morningTime == time,
                    onClick = {
                        runtime.updateStewardSettings(
                            settings.copy(morningEnabled = true, morningTime = time),
                        )
                    },
                )
            }
            EditorChip(
                label = "关",
                active = !settings.morningEnabled,
                onClick = { runtime.updateStewardSettings(settings.copy(morningEnabled = false)) },
            )
        }

        Spacer(Modifier.height(6.dp))
        SettingsSubLabel("晚间收尾")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (time in listOf("21:30", "22:00", "22:30", "23:00")) {
                EditorChip(
                    label = time,
                    active = settings.eveningEnabled && settings.eveningTime == time,
                    onClick = {
                        runtime.updateStewardSettings(
                            settings.copy(eveningEnabled = true, eveningTime = time),
                        )
                    },
                )
            }
            EditorChip(
                label = "关",
                active = !settings.eveningEnabled,
                onClick = { runtime.updateStewardSettings(settings.copy(eveningEnabled = false)) },
            )
        }

        Spacer(Modifier.height(6.dp))
        SettingsSubLabel("桌面通知")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EditorChip(
                label = "开",
                active = settings.notifyDesktop,
                onClick = { runtime.updateStewardSettings(settings.copy(notifyDesktop = true)) },
            )
            EditorChip(
                label = "关",
                active = !settings.notifyDesktop,
                onClick = { runtime.updateStewardSettings(settings.copy(notifyDesktop = false)) },
            )
        }
        SettingsStatusLine("应用在后台时才弹系统通知；前台只留会话里的未读红点。")

        Spacer(Modifier.height(6.dp))
        SettingsSubLabel("自动采纳")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EditorChip(
                label = "开",
                active = settings.autoAdopt,
                onClick = { runtime.updateStewardSettings(settings.copy(autoAdopt = true)) },
            )
            EditorChip(
                label = "关",
                active = !settings.autoAdopt,
                onClick = { runtime.updateStewardSettings(settings.copy(autoAdopt = false)) },
            )
        }
        SettingsStatusLine(
            if (!settings.autoAdopt) {
                "关闭：所有建议都要你点「一键采纳」。"
            } else {
                if (settings.autoAdoptDailyLimit <= 0) {
                    "只在应用不在前台时执行；不限条数，挪动逾期和补排欠账都会自动做。" +
                        "今天已自动采纳 " + runtime.stewardAutoAdoptedToday + " 条。"
                } else {
                    "只在应用不在前台时执行；每天最多 " + settings.autoAdoptDailyLimit +
                        " 条，今天已自动采纳 " + runtime.stewardAutoAdoptedToday + " 条。"
                }
            },
        )

        Spacer(Modifier.height(6.dp))
        SettingsSubLabel("每周自动排周计划")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EditorChip(
                label = "开",
                active = settings.weeklyPlan,
                onClick = { runtime.updateStewardSettings(settings.copy(weeklyPlan = true)) },
            )
            EditorChip(
                label = "关",
                active = !settings.weeklyPlan,
                onClick = { runtime.updateStewardSettings(settings.copy(weeklyPlan = false)) },
            )
        }
        SettingsStatusLine(
            if (settings.weeklyPlan) {
                "周日 18:00 后、周一早上，在每条计划自己的会话里跑一次「排本周」；" +
                    "没有绑定会话（或会话被删了）就由管家代排，结果直接出现在对应会话里。"
            } else {
                "关闭：不会自动排周计划；要用时在会话里说「排本周」，或点计划页的每周计划。"
            },
        )

        Spacer(Modifier.height(6.dp))
        SettingsLinkRow(label = "打开管家会话", status = "每天早晚问候") {
            runtime.ensureStewardSession()
            runtime.openChat(StewardRuntime.STEWARD_SESSION_ID)
            runtime.currentTab = TAB_CHAT
        }
    }
}

/** 插件页的入口：装插件、看贡献、授权能力这些事基本交给对话里的 agent。 */
@Composable
private fun PluginsSection(runtime: AppRuntime) {
    val plugins = runtime.pluginInfos
    val broken = plugins.count { it.errors.isNotEmpty() }
    val status = when {
        plugins.isEmpty() -> "还没有装插件"
        broken > 0 -> plugins.size.toString() + " 个 · " + broken + " 个有错误"
        else -> plugins.size.toString() + " 个 · 都在跑"
    }
    SettingsSection(title = "插件") {
        SettingsLinkRow(label = "打开插件页", status = status) {
            runtime.currentTab = TAB_PLUGINS
        }
    }
}

/** 诊断的入口：装插件、停用、删除都留一行记录，内核出过的错也在这里。 */
@Composable
private fun DiagnosticsSection(runtime: AppRuntime) {
    val errors = runtime.kernelErrors.size
    val status = if (errors == 0) "没有未处理的问题" else errors.toString() + " 条问题记录"
    SettingsSection(title = "诊断") {
        SettingsLinkRow(label = "打开诊断页", status = status) {
            runtime.currentTab = TAB_DIAG
        }
    }
}

// ---------------------------------------------------------------------------
// 小部件：区段标题 / 发丝线 / 选项行 / 状态行
// ---------------------------------------------------------------------------

/** 区段：标题行（右侧可带当前值/动作）＋ 发丝分隔线 ＋ 内容。 */
@Composable
private fun SettingsSection(
    title: String,
    status: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val extra = LocalExtraColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GraphSectionLabel(title)
            Spacer(Modifier.weight(1f))
            if (status != null) {
                Text(
                    status,
                    style = LocalTrellisTokens.current.type.meta,
                    color = extra.dataMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
            if (action != null) {
                Spacer(Modifier.width(4.dp))
                action()
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.height(2.dp))
        content()
    }
}

/** 选项行：和外观选项同一个组件，历史上分在两段代码里。 */
@Composable
private fun SettingsChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ChromeListRow(title = label, selected = selected, onClick = onClick)
}

/** 链接行：名称在左、状态在右、末尾一个 ›，整行可点进二级页。 */
@Composable
private fun SettingsLinkRow(label: String, status: String, onClick: () -> Unit) {
    val extra = LocalExtraColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            status,
            style = LocalTrellisTokens.current.type.meta,
            color = extra.dataMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text("›", style = MaterialTheme.typography.labelMedium, color = extra.dataMuted)
    }
}

/** 行内状态/提示：默认弱色，出错时用错误色。 */
@Composable
private fun SettingsStatusLine(text: String, error: Boolean = false) {
    val extra = LocalExtraColors.current
    Text(
        text,
        style = LocalTrellisTokens.current.type.meta,
        color = if (error) MaterialTheme.colorScheme.error else extra.dataMuted,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** 小节内的弱标签（主题 / 导航）。 */
@Composable
private fun SettingsSubLabel(text: String) {
    Text(
        text,
        style = LocalTrellisTokens.current.type.meta,
        color = LocalExtraColors.current.dataMuted,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** 加减一格。一次 15 分钟：一小时是四格，手感比 5 分钟一档省事。 */
private const val STEP_MINUTES: Int = 15

@Composable
private fun StepChip(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.bodyMedium)
    }
}


/**
 * 开发者 · 清除数据。
 *
 * 只有开发者模式出现；每个按钮都要二次确认，「全部清空」还要手输「清除」。
 * 删什么、留什么全在 [DataWipe] 里定；这里只负责把「将删除/将保留」摊给用户看，以及把结果说清楚。
 */
@Composable
private fun DevDataSection(runtime: AppRuntime) {
    var pending by remember { mutableStateOf<WipeScope?>(null) }
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    SettingsSection(
        title = "清除数据（开发者）",
        status = note ?: "只影响 " + runtime.home.relativeOrName(),
    ) {
        SettingsStatusLine("不可恢复。清完之后计划页会变成一个空库；建议先备份整个数据目录。")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WipeChip("学习计划", WipeScope.Plans) { pending = it }
            WipeChip("会话", WipeScope.Chats) { pending = it }
            WipeChip("知识库", WipeScope.Knowledge) { pending = it }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            WipeChip("活动记录", WipeScope.Activity) { pending = it }
            WipeChip("练习记录", WipeScope.Practice) { pending = it }
            WipeChip("全部清空", WipeScope.All) { pending = it }
        }
        SettingsStatusLine("保留：llm.json、ui.json、study.json、dev.json、steward/settings.json、activity 下的分类与同步设置。全部清空后建议重启应用。")
    }

    val scope = pending
    if (scope != null) {
        AlertDialog(
            onDismissRequest = { pending = null; typed = "" },
            title = { Text(scope.title + "？") },
            text = {
                Column {
                    Text(if (scope == WipeScope.All) "将删除数据目录下除配置外的全部内容：计划、会话、知识库、活动记录、练习记录、插件。" else scope.title + "会删除：")
                    Spacer(Modifier.height(6.dp))
                    Text(DataWipe.targets(runtime.home, scope).joinToString("\n") { it.relativeOrName() })
                    if (scope == WipeScope.All) {
                        Spacer(Modifier.height(8.dp))
                        Text("保留 " + DataWipe.preservedPaths(runtime.home, scope).joinToString("、"))
                        Spacer(Modifier.height(8.dp))
                        Text("输入「清除」确认：")
                        ChromeTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            placeholder = "清除",
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = scope != WipeScope.All || typed.trim() == "清除",
                    onClick = {
                        val result = runtime.clearData(scope)
                        note = if (result.ok) {
                            "已删除 " + result.deleted.size + " 项"
                        } else {
                            "删了 " + result.deleted.size + " 项，失败 " + result.failed.size + " 项：" + result.failed.joinToString("、")
                        }
                        pending = null
                        typed = ""
                    },
                ) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null; typed = "" }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun WipeChip(label: String, scope: WipeScope, onClick: (WipeScope) -> Unit) {
    EditorChip(label, active = false, onClick = { onClick(scope) })
}

/** 数据目录在界面上只显示最后两级，避免一长串 `/Users/...` 把标题挤掉。 */
private fun java.io.File.relativeOrName(): String {
    val parts = absolutePath.trimEnd('/').split('/').filter { it.isNotBlank() }
    return parts.takeLast(2).joinToString("/")
}
