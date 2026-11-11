package dev.trellis.app.mobile.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.mobile.PhoneApp
import dev.trellis.app.mobile.PhoneSubPage
import dev.trellis.domain.TaskStatus
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.ResourceDraft
import dev.trellis.domain.plan.StageDraft
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * 手机界面预览器。
 *
 * 渲染的就是**手机上跑的那一份** `PhoneApp` —— 不是另画一版示意图。
 * 这样「手机布局对不对」在电脑上就能看，不用每次装 APK。
 *
 *   ./gradlew :app:mobile-preview:run              开 390×844 的窗口，可以真的点
 *   TRELLIS_SHOT_DIR=build/preview ...             不开窗，逐页渲染 PNG
 *   TRELLIS_SEED=1 ...                             先灌一份演示数据
 *   TRELLIS_APPEARANCE=dark ...                    看暗色
 *
 * 数据目录默认 `build/preview-home`，**不碰 `~/.trellis`**：预览器是开发工具。
 */
fun main() {
    val home = File(System.getenv("TRELLIS_PREVIEW_HOME") ?: "build/preview-home").apply { mkdirs() }

    // 计划库必须在运行时之前造：`AppRuntime.create` 会立刻打开同一个 SQLite 文件，
    // 那时候再灌数据就是两个连接抢锁。
    if (System.getenv("TRELLIS_SEED") == "1") {
        seedLearningPlan(home)
    }

    val runtime = runBlocking { AppRuntime.create(home) }

    if (System.getenv("TRELLIS_SEED") == "1") {
        runBlocking { seedIfEmpty(runtime) }
    }
    // 外观走同一个入口（它会顺便落盘并 bump tick，界面才会刷新）。
    val appearance = System.getenv("TRELLIS_APPEARANCE")?.takeIf { it.isNotBlank() }
    if (appearance != null) {
        runtime.updateUiPrefs(runtime.uiPrefs.copy(appearance = appearance))
    }

    val shotDir = System.getenv("TRELLIS_SHOT_DIR")?.takeIf { it.isNotBlank() }
    if (shotDir != null) {
        renderAll(runtime, File(shotDir))
        return
    }

    Runtime.getRuntime().addShutdownHook(
        Thread { runBlocking { runCatching { runtime.shutdown() } } },
    )

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Trellis · 手机预览 390×844",
            state = rememberWindowState(size = DpSize(390.dp, 844.dp)),
        ) {
            PhoneApp(runtime)
        }
    }
}

/** 手机竖屏 + 平板，各来一套 —— 自适应只验一种尺寸等于没验。 */
internal val formFactors = listOf(
    Triple("phone", 390, 844),
    Triple("tablet", 834, 1112),
)

/** 顺序与 `PhoneTab` 一致：今日 / 计划 / 对话 / 知识 / 我的；知识再看「笔记」与「网络」。 */
internal val pages = listOf(
    Shot("01-today", 0),
    Shot("02-plan", 1),
    Shot("03-chat", 2),
    Shot("04-knowledge-notes", 3),
    Shot("05-knowledge-graph", 3, knowledgeTab = 1),
    Shot("06-knowledge-overview", 3, knowledgeTab = 1, graphMode = 1),
    Shot("07-knowledge-note", 3, noteId = "n_preview_transport"),
    Shot("08-me", 4),
    // 插件页：安装 / 卸载 / 能力授权三块都在这一页，折叠在「我的」下面，
    // 不给它单独一张图就等于没验过。
    Shot("09-plugins", 4, subPage = PhoneSubPage.Plugins),
    // 第二轮新增的桌面页复用：目标页要看到「里程碑 N/M」，练习页至少验证空态与页头。
    Shot("10-goals", 1, subPage = PhoneSubPage.Goals),
    Shot("11-practice", 2, subPage = PhoneSubPage.Practice),
)

internal data class Shot(
    val label: String,
    val tab: Int,
    val knowledgeTab: Int = 0,
    val graphMode: Int = 0,
    val noteId: String? = null,
    val subPage: PhoneSubPage? = null,
)

private fun renderAll(runtime: AppRuntime, dir: File) {
    dir.mkdirs()
    for ((name, w, h) in formFactors) {
        for (shot in pages) {
            val out = File(dir, "$name-${shot.label}.png")
            render(w, h, out) {
                PhoneApp(
                    runtime,
                    startTab = shot.tab,
                    startKnowledgeTab = shot.knowledgeTab,
                    startGraphMode = shot.graphMode,
                    startNoteId = shot.noteId,
                    startSubPage = shot.subPage,
                    // 预览器没有 SAF：给一个空的选文件实现，好让「从文件安装」按钮
                    // 在截图里真的出现（点了不会弹窗，这是预览，不是真机）。
                    onPickPluginArchive = { _ -> },
                )
            }
            println("已渲染：${out.absolutePath}")
        }
    }
}

private fun render(widthDp: Int, heightDp: Int, out: File, content: @Composable () -> Unit) {
    val bytes = renderToPng(widthDp, heightDp, content)
    out.parentFile?.mkdirs()
    out.writeBytes(bytes)
}

/**
 * 离屏渲染成 PNG 字节。
 *
 * 单独抽出来是为了让测试也走**同一条**渲染路径（见 `PhoneRenderSmokeTest`）：
 * 截图和冒烟测试用两套代码，测出来的「没问题」就不作数了。
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun renderToPng(
    widthDp: Int,
    heightDp: Int,
    content: @Composable () -> Unit,
): ByteArray {
    val scale = 2
    val scene = ImageComposeScene(
        width = widthDp * scale,
        height = heightDp * scale,
        density = Density(scale.toFloat()),
        content = content,
    )
    try {
        val data = scene.render().encodeToData() ?: error("PNG 编码失败")
        return data.bytes
    } finally {
        scene.close()
    }
}

/**
 * 学习计划：直接开库造一份。
 *
 * 为什么不用 `AppRuntime` 的入口：那个方法是 `internal`，预览器在另一个模块。
 * `LearningPlanStore.open` 才是这一层的公开契约 —— 同一个文件路径（`<home>/plans/plan.db`）、
 * 同一套命令信封，造出来的数据和真机点出来的没有区别。
 *
 * 灌完立刻关连接：运行时马上就要用这个文件。
 */
private fun seedLearningPlan(home: File) {
    val file = File(home, "plans/plan.db")
    file.parentFile?.mkdirs()
    val service = runCatching { LearningPlanStore.open(file.toPath()) }.getOrNull() ?: return
    try {
        // 已经有计划就不动：预览目录是复用的，每次覆盖会把上次的手感冲掉。
        if (service.activePlanId() != null) return

        fun env(planId: String? = null) = CommandEnvelope(
            commandId = "preview-" + UUID.randomUUID().toString().replace("-", ""),
            actor = Actor.USER,
            planId = planId,
            origin = "preview",
        )

        val created = service.createProcess(
            env(),
            "网络原理 · 六周过一遍",
            "把传输层和网络层讲清楚，能自己画一遍握手与拥塞控制",
            weeklyCapacityMinutes = 60 * 7,
        )
        val planId = created.value?.planId ?: return

        val units = listOf(
            UnitDraft("第 1 章", "分层与协议栈", 25, 10),
            UnitDraft("第 2 章", "物理层与数据链路", 30, 12),
            UnitDraft("第 3 章", "以太网与交换", 30, 12),
            UnitDraft("第 4 章", "网络层：IP 与路由", 40, 15),
            UnitDraft("第 5 章", "传输层：TCP 与 UDP", 40, 15),
            UnitDraft("第 5 章", "拥塞控制四段", 35, 12),
            UnitDraft("第 6 章", "应用层与 DNS", 25, 10),
            UnitDraft("第 6 章", "HTTP 与 HTTPS 的区别", 20, 8),
            UnitDraft("第 7 章", "Wireshark 抓一次握手", 45, 15),
            UnitDraft("第 7 章", "写一份拥塞控制小结", 40, 15),
        )
        val week = isoWeekOf(LocalDate.now())
        val draft = service.saveDraft(
            env(planId),
            planId,
            null,
            units,
            // 本周只承诺前三个：剩下的留在池子里，截图上才看得到「待办 N」。
            listOf(CommitmentDraft(week, listOf(0, 1, 2))),
        )
        val versionId = draft.value?.versionId ?: return
        service.acceptDraft(env(planId), versionId, week)
        // 铺到天。不铺的话「今天」是空的，计划页只剩一条周条 ——
        // 日历条、单元卡片、单位动作这些排版全都看不到。
        service.scheduleWeek(env(planId), week)

        // 阶段：手机计划页的「阶段进度」要看到真实的两段，不然截图里永远不出现。
        // 阶段周区间必须落在计划已承诺的周内（本演示只承诺了本周），所以两段都放本周。
        service.setStages(
            env(planId),
            planId,
            listOf(
                StageDraft("打基础", week, week, focus = "分层、物理层与以太网", sortOrder = 0),
                StageDraft("传输层攻坚", week, week, focus = "TCP 与拥塞控制", sortOrder = 1),
            ),
        )
        // 目标 + 里程碑：目标页和计划页「目标」入口的演示数据。
        val goal = service.createGoal(
            env(),
            "期末把网络原理补起来",
            intent = "六周过一遍，考试不慌",
            success = "能自己讲清三次握手与拥塞控制",
        ).value
        if (goal != null) {
            service.linkPlanToGoal(env(planId), planId, goal.goalId)
            service.addMilestone(env(), goal.goalId, "手画三次握手与四次挥手")
        }

        // 资料：挂到今天第一条排期上，这样「资料 N」在截图里就能展开看到。
        // 只用真实存在的链接，不编造。
        val todayUnitId = service.today(planId, LocalDate.now().toString()).occurrences
            .firstOrNull { it.unitId != null }?.unitId
        if (todayUnitId != null) {
            service.setUnitResources(
                env(planId),
                planId,
                todayUnitId,
                listOf(
                    ResourceDraft("web", "TCP 协议规范（RFC 793）", url = "https://www.rfc-editor.org/rfc/rfc793"),
                    ResourceDraft("doc", "课程讲义 第 4 节", locator = "讲义 第 4 节"),
                ),
            )
        }

        // 今天第二个勾掉：截图里才有「已完成 1」，也让人看到「剩下的还能处理」。
        val today = LocalDate.now().toString()
        service.today(planId, today).occurrences.drop(1).take(1).forEach { occurrence ->
            service.completeOccurrence(env(planId), occurrence.id, "先看例题再动手，快了一倍。")
        }
    } finally {
        (service as? AutoCloseable)?.close()
    }
}

/** ISO 周（`2026-W45`），和后端 `PlanTime` 的格式一致。 */
private fun isoWeekOf(date: LocalDate): String {
    val fields = java.time.temporal.WeekFields.ISO
    return "%04d-W%02d".format(
        date.get(fields.weekBasedYear()),
        date.get(fields.weekOfWeekBasedYear()),
    )
}

/**
 * 演示数据：只在预览目录、只在 `TRELLIS_SEED=1` 时灌一次。
 *
 * 空界面看不出排版对不对 —— 分组高度、逾期颜色、长标题会不会溢出，
 * 都要有真实数据才暴露得出来。
 */
private fun homeOf(runtime: AppRuntime): File = runtime.home

private suspend fun seedIfEmpty(runtime: AppRuntime) {
    val plan = runtime.planService
    if (plan.tasks().isNotEmpty()) return
    val now = runtime.clock.nowMs()
    val hour = 3_600_000L
    val day = 86_400_000L

    plan.addTask("整理错题本：传输层三道题", dueAt = now + 2 * hour)
    plan.addTask("看第 5 章：传输层与拥塞控制", dueAt = now + 6 * hour)
    plan.addTask(
        "把「为什么 TCP 要三次握手而不是两次」用自己的话写明白，并补上两次握手会遇到的经典问题",
        dueAt = now + 8 * hour,
    )
    plan.addTask("补交上机实验报告（第 4 章）", dueAt = now - 3 * day)
    plan.addTask("复习第一章：分层与协议栈", dueAt = now + day)
    plan.addTask("背 50 个网络术语", dueAt = null)
    plan.addTask("读完第 3 章", dueAt = null)

    repeat(6) { i ->
        val task = plan.addTask("第 ${i + 1} 组练习", dueAt = now - (i % 3) * day)
        plan.setStatus(task.id, TaskStatus.DONE)
        if (i == 2) plan.summarize(task.id, "先看例题再动手，速度快了一倍。")
    }

    // 知识库：直接按落盘格式写两篇。不碰 internal 的 KnowledgeStore ——
    // 预览器是另一个模块，本来就不该看见它；文件格式才是真契约（见 KnowledgeNote.formatNoteMarkdown）。
    val notesDir = File(homeOf(runtime), "knowledge/notes").apply { mkdirs() }
    if (notesDir.listFiles().isNullOrEmpty()) {
        File(notesDir, "n_preview_transport.md").writeText(
            """
            ---
            title: 传输层笔记
            concepts: TCP, UDP, 拥塞控制, 流量控制
            ---

            # 传输层
            传输层负责端到端的可靠交付，两个主角是 [[TCP]] 与 [[UDP]]。

            ## 拥塞控制
            [[拥塞控制]] 是发送方根据网络反馈调整速率的机制，常见四段：慢启动、拥塞避免、快重传、快恢复。

            ## 与 [[流量控制]] 的区别
            流量控制看接收方，拥塞控制看网络。
            """.trimIndent(),
            Charsets.UTF_8,
        )
        File(notesDir, "n_preview_handshake.md").writeText(
            """
            ---
            title: 三次握手
            concepts: TCP, 拥塞控制
            ---

            # 三次握手
            [[TCP]] 建立连接需要三次握手：SYN、SYN-ACK、ACK。
            两次不够，因为网络里可能残留旧连接请求。

            与 [[拥塞控制]] 无关，别混在一起。
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }
    runtime.refresh()
}
