package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * 版式与知识库/网络页同一套：区段标题（[GraphSectionLabel]）＋ 发丝分隔线 ＋ 扁平行，
 * 去掉了每块标题下的解释性长文案；只保留「当前值 / 安全提示」这类功能信息。
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

        // 时间调试：只有开发者模式（TRELLIS_DEV=1）才出现。
        // 拨「应用内今天」而不是改系统时间——跨周/结算/复盘这些逻辑才需要它。
        if (runtime.devModeEnabled) item { DevTimeSection(runtime) }

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

/** 外观里的一组：弱标签 + 若干选项行。 */
@Composable
private fun AppearanceGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    val tokens = LocalTrellisTokens.current
    Text(
        label,
        style = tokens.type.meta,
        color = tokens.colors.ink3,
        modifier = Modifier.padding(
            start = tokens.space.s,
            top = tokens.space.m,
            bottom = tokens.space.xs,
        ),
    )
    Column(Modifier.fillMaxWidth()) { content() }
}

/**
 * 外观选项行：直接走 [ChromeListRow]。
 *
 * 行高、选中竖条、紧跟标签的 ✓、键盘焦点环都由组件负责；设置页只声明「哪个选了」。
 * 旧的 [SettingsChoiceRow]（7dp 灰方块 + 行尾「当前」）也换成同一个组件——方块对纸底
 * 只有 1.23:1 等于没画，「当前」贴在 1000pt 外要横跨整页去找。
 */
@Composable
private fun AppearanceChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ChromeListRow(title = label, selected = selected, onClick = onClick)
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
                runtime.devClockRejectedReason ?: "当前数据目录不允许改模拟时间：换一个 TRELLIS_HOME 再试",
                error = true,
            )
        }
        SettingsStatusLine(
            "提醒：模拟日期下活动页、计时器仍是真实数据；拨动后计划会立刻按新日期结算/补周，数据目录请用演示副本",
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
