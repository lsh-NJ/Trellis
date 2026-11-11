package dev.trellis.app.desktop

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assume

/**
 * 笔记 → 计划节点自动完成 的**真实 LLM** 端到端验收。
 *
 * 复刻 [KnowledgeScreen] 的「笔记整理完顺手判一次」链路（KnowledgeScreen.kt:390-410），
 * 唯一不同是那段代码在 Composable 的协程里、这里直接调；中间每个函数都是生产代码：
 *
 * ```
 * runtime.planLinkWeekCandidates()            // 本周还没完成的节点
 *   → shouldAttemptPlanLink(body, candidates) // 正文够长 + 有候选
 *   → buildPlanLinkMessages(...)              // 生产提示词
 *   → runtime.llmClientOrNull()!!.complete()  // 真实 DeepSeek
 *   → parsePlanLinkJudgments(raw)             // 生产解析
 *   → selectPlanLinkCandidates(body, cands)
 *   → validatePlanLinkJudgments(body, cands, judgments, blocked)
 *   → runtime.autoCompletePlanNodes(noteId, noteTitle, completions)
 * ```
 *
 * 两组：
 * 1) **控制组**：一篇 500+ 字、与本周节点毫不相干的笔记 → 必须调用模型（1 次）但**不完成**任何节点、不写账本；
 * 2) **实验组**：一篇把两个节点主题讲完整的笔记 → 节点真的变 done、账本里出现 kind=note 的记录、planMessage 有反馈。
 *
 * 真钱约束：整个测试只花 2 次模型调用（控制 1 次 + 实验 1 次），不做循环重试。
 */
class AutoAdvanceNotesE2ETest {

    private val evidenceDir = File("/tmp/trellis-e2e/t1")

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-auto-advance-e2e").toFile().apply { deleteOnExit() }

    /** 真实 key 只存在于用户家目录；复制进临时 home，`LlmConfigLoader.load(home)` 才拿得到。 */
    private fun copyRealLlmConfig(home: File) {
        val real = File(System.getProperty("user.home"), ".trellis/llm.json")
        assertTrue(real.isFile, "找不到真实 LLM 配置：${real.absolutePath}")
        val text = real.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        File(home, "llm.json").writeText(text, Charsets.UTF_8)
    }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.auto-advance.e2e",
    )

    private fun weekOf(date: LocalDate): String =
        "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    /** runtime.planDays 里所有节点「occurrenceId → status」。 */
    private fun statuses(runtime: AppRuntime): Map<String, String> = runtime.planDays
        .flatMap { day -> day.items }
        .associate { item -> item.occurrenceId to item.status }

    /** 与「第 1 节 / 第 2 节」两篇节点一一对应的正文：每个主题都给定义、步骤、例子、结论。 */
    private val coveringBody = """
        # 线性回归与梯度下降学习笔记

        ## 最小二乘与残差

        本节完整整理线性回归里最基础的那套估计方法。最小二乘法要解决的问题是：给定一组
        (x, y) 观测点，找一条直线 y = w x + b，让所有点到直线的竖直距离的平方和最小。
        这个平方和就是损失函数 L(w, b) = Σ(yᵢ - w xᵢ - b)²，其中每一项 yᵢ - w xᵢ - b
        就是残差，残差衡量的是模型预测值和真实值之间的偏差。

        推导步骤一共三步。第一步，对 w 和 b 分别求偏导，并令偏导数为零，得到两个正规方程。
        第二步，把正规方程联立，解出 b 的闭式解 b = ȳ - w x̄，再代回解出
        w = Σ(xᵢ - x̄)(yᵢ - ȳ) / Σ(xᵢ - x̄)²。第三步，把估出来的 w、b 代回损失函数，
        确认残差平方和比其它候选直线都小。

        举一个具体例子：四个点 (1,2)、(2,4)、(3,5)、(4,8)，先算均值 x̄=2.5、ȳ=4.75，
        再算分子 Σ(xᵢ-x̄)(yᵢ-ȳ)=4.75，分母 Σ(xᵢ-x̄)²=5，于是 w=0.95、b=2.375。
        把这些数代回去，能算出残差分别是 0.575、0.675、-0.725、-0.525，四个残差加起来约等于零，
        这正好验证了正规方程里「残差和为零」这条性质。

        残差除了用来算损失，还能用来做模型检查：把残差对拟合值画散点图，如果图里出现喇叭形，
        说明误差方差不齐，存在异方差，这时要考虑对 y 取对数或者用加权最小二乘；
        如果残差呈现明显的弯曲趋势，说明直线模型不够，应该加二次项或者换非线性模型。
        另外还要看残差是否近似对称、有没有离群点，离群点会把直线整体拉偏。

        本节小结：最小二乘的目标函数、正规方程与闭式解推导、具体数值例子、残差检验和异方差处理，
        这四部分都已经讲完，这一节可以收尾了。

        ## 学习率与收敛

        本节把梯度下降里的学习率讲清楚。参数更新公式是 θ := θ - η ∇L(θ)，η 就是学习率，
        它决定每一步沿着负梯度方向迈多大。学习率太大时，损失会在最优点两侧来回震荡甚至发散；
        学习率太小时，损失下降得很慢，训练时间被白白拉长。合适的做法是先取 0.01 或 0.1 试几步，
        看损失曲线是稳定下降、震荡还是几乎不动，再按十倍量级调整。

        收敛的判定标准通常有三条：损失函数在连续若干轮里变化小于一个很小的阈值（比如 1e-6），
        梯度的范数小于阈值，或者参数更新量的范数小于阈值。工程上一般组合使用：
        每轮记录损失，连续五轮相对变化都小于 1e-4 就认为已经收敛，同时设一个最大轮数兜底，
        防止在平台区一直等下去。

        一个常见例子：在 y = x² 上用梯度下降，从 x=10 出发，学习率取 0.1 时坐标按
        10 → 8 → 6.4 → 5.12 收敛，损失按平方速度掉下去；学习率取 1.1 时坐标绝对值反而越来越大，
        直接发散。这个对比说明学习率只要超过 2/L（这里是 1.0），梯度下降就不再保证收敛。

        实践中还有两个技巧：学习率衰减（每若干轮乘 0.5）和预热（前几百步从很小的学习率线性升到目标值），
        前者帮助后期精细收敛，后者避免一开始步子太大把参数带飞到坏区域。

        本节小结：学习率的含义、过大过小的表现、收敛的三条判定标准、发散阈值的数值例子、
        衰减与预热两个实践技巧都讲完了，这一节到此完整结束。
    """.trimIndent()

    /** 控制组：同样够长，但讲的是阳台种菜，与「最小二乘」「学习率」毫无关系。 */
    private val unrelatedBody = """
        # 周末阳台种菜记录

        这周把阳台上的几个花盆重新整理了一遍。朝南的窗台光照最足，上午能晒到四个小时，
        适合种小番茄和辣椒；朝北的角落只有散射光，就留给生菜和香菜。土壤用的是泥炭土掺珍珠岩，
        比例大概三比一，盆底先铺一层陶粒做排水层，避免浇水后积水烂根。换盆的时候动作要轻，
        把旧土抖掉三分之一，露出白色的须根，再放进新盆，四周填土压实，最后浇一遍透水。

        浇水是最容易出问题的一步。夏天蒸发快，早上浇一次、傍晚看土面干了再补一次；
        冬天要等表土两三厘米都干了再浇，宁可偏干也不要天天浇。判断方法很简单：把手指插进土里，
        指尖觉得潮就再等等，觉得干才浇。喷壶适合给叶面补水，但正午不要喷，水珠会像放大镜一样
        把叶子灼伤。

        施肥分两个阶段。缓苗期不施肥，等新叶长出来再给稀薄的水溶肥，浓度是说明书的一半，
        一周一次；开花结果期换成磷钾偏高的肥，帮助坐果。施肥后第二天要浇一次清水，
        把多余的盐分冲掉，不然盆土表面会结一层白霜。

        虫害方面，蚜虫喜欢嫩芽，发现后先用清水冲掉，再挂黄板诱捕；红蜘蛛怕湿，
        每天给叶背喷一点水就能压住。实在严重才用苦参碱，喷完三天内不要采收。
        这一周小番茄已经开花了，辣椒也结了两个小果，记录一下，下周继续观察。
    """.trimIndent()

    @Test
    fun noteAutoCompletesThisWeeksPlanNodesWithRealLlm(): Unit = runBlocking {
        // 真实网络 + 真实模型：默认跳过，按需跑。
        Assume.assumeTrue(
            "真实 LLM 验收按需跑：TRELLIS_LIVE_LLM=1 ./gradlew :app:ui:jvmTest --tests '*AutoAdvanceNotesE2ETest*'",
            System.getenv("TRELLIS_LIVE_LLM") == "1",
        )
        val startedAt = System.currentTimeMillis()
        val home = tempHome()
        copyRealLlmConfig(home)
        evidenceDir.mkdirs()
        val evidence = StringBuilder()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val week = weekOf(today)

        val runtime = AppRuntime.create(home)
        var store: LearningPlanService? = null
        try {
            runtime.applySimulatedToday(today)
            assertTrue(runtime.devClockAllowed, "临时 home 必须允许模拟时间：${runtime.devClockRejectedReason}")
            assertEquals(today, runtime.clock.today(), "应用内今天应被钉到本周一")

            store = LearningPlanStore.open(
                File(home, "plans/plan.db").toPath(),
                { runtime.clock.nowMs() },
                zone,
            )
            val created = store.createProcess(
                env(),
                "笔记联动 E2E",
                "验证一篇讲完的笔记能自动勾掉本周节点",
                weeklyCapacityMinutes = 60 * 7,
            ).unwrap()
            val draft = store.saveDraft(
                env(created.planId),
                created.planId,
                null,
                listOf(
                    UnitDraft("线性回归", "最小二乘与残差", 20),
                    UnitDraft("梯度下降", "学习率与收敛", 20),
                ),
                listOf(CommitmentDraft(week, listOf(0, 1))),
            ).unwrap()
            store.acceptDraft(env(created.planId), draft.versionId, week).unwrap()

            runtime.refreshPlan()
            assertEquals(created.planId, runtime.planFocusId, "页面焦点应该是刚建的这份计划")

            val candidates = runtime.planLinkWeekCandidates()
            assertEquals(2, candidates.size, "本周两个单元都该成为联动候选：$candidates")
            assertEquals(
                setOf("最小二乘与残差", "学习率与收敛"),
                candidates.map { it.title }.toSet(),
                "候选就是两个未完成节点",
            )
            val before = statuses(runtime)
            assertEquals(2, before.size, "铺天后本周应有两条节点：${runtime.planDays}")
            assertTrue(before.values.all { it == "planned" }, "判定前两个节点都应是 planned：$before")
            evidence.appendLine("[before] week=$week candidates=" + candidates.map { it.title to it.occurrenceId })
            evidence.appendLine("[before] statuses=$before")

            // ---------------- 控制组：不相关正文 → 调 1 次模型但不完成 ----------------
            val client = assertNotNull(runtime.llmClientOrNull(), "llm.json 复制后应能拿到 client")
            assertTrue(
                shouldAttemptPlanLink(unrelatedBody, candidates),
                "控制组正文 ${unrelatedBody.length} 字、候选 2 个，应该真正跑一次判定",
            )
            val controlPrompt = buildPlanLinkMessages(
                noteTitle = "周末阳台种菜记录",
                body = unrelatedBody,
                candidates = selectPlanLinkCandidates(unrelatedBody, candidates),
            )
            val controlRaw = client.complete(controlPrompt)
            val controlJudgments = parsePlanLinkJudgments(controlRaw)
            val controlCompletions = validatePlanLinkJudgments(
                body = unrelatedBody,
                candidates = candidates,
                judgments = controlJudgments,
                blocked = runtime.autoCompletionBlocked(),
            )
            evidence.appendLine()
            evidence.appendLine("[control] prompt.messages=${controlPrompt.size} userChars=${controlPrompt.last().content.length}")
            evidence.appendLine("[control] judgments=${controlJudgments.size} raw=${controlRaw.take(1200)}")
            evidence.appendLine("[control] completions.size=${controlCompletions.size}")
            println("[control] LLM raw: $controlRaw")
            assertTrue(
                controlCompletions.isEmpty(),
                "不相关笔记不能自动完成任何节点，实际：$controlCompletions",
            )
            assertEquals(before, statuses(runtime), "控制组不能改动任何节点状态")
            val ledgerAfterControl = File(home, "plans/auto-completions.json")
            val controlLedgerText = if (ledgerAfterControl.isFile) {
                ledgerAfterControl.readText(Charsets.UTF_8)
            } else {
                "(账本还不存在)"
            }
            assertTrue(
                ledgerEntries(ledgerAfterControl).isEmpty(),
                "控制组不该写账本：$controlLedgerText",
            )
            assertEquals(null, runtime.planMessage, "控制组不该留下「自动完成」反馈：${runtime.planMessage}")

            // ---------------- 实验组：讲完两个节点的正文 → 真自动完成 ----------------
            assertTrue(
                shouldAttemptPlanLink(coveringBody, candidates),
                "实验组正文 ${coveringBody.length} 字，够长",
            )
            val noteId = "note_e2e_auto_advance"
            val noteTitle = "线性回归与梯度下降学习笔记"
            val prompt = buildPlanLinkMessages(
                noteTitle = noteTitle,
                body = coveringBody,
                candidates = selectPlanLinkCandidates(coveringBody, candidates),
            )
            val raw = client.complete(prompt)
            val judgments = parsePlanLinkJudgments(raw)
            val completions = validatePlanLinkJudgments(
                body = coveringBody,
                candidates = candidates,
                judgments = judgments,
                blocked = runtime.autoCompletionBlocked(),
            )
            evidence.appendLine()
            evidence.appendLine("[experiment] prompt.messages=${prompt.size} userChars=${prompt.last().content.length}")
            evidence.appendLine("[experiment] judgments=${judgments.size} raw=${raw.take(2000)}")
            evidence.appendLine("[experiment] acceptedCompletions=${completions.map { it.title to it.confidence }}")
            println("[experiment] LLM raw: $raw")

            assertTrue(
                completions.isNotEmpty(),
                "讲完两个节点的笔记应至少通过一条校验；模型原始返回：$raw",
            )
            completions.forEach { completion ->
                // 2026-11-09 起证据只要求写清依据（摘录或概括），不再要求逐字出现在正文里。
                assertTrue(
                    completion.evidence.length >= 8,
                    "证据要写清判断依据：「${completion.evidence}」",
                )
                assertTrue(completion.confidence >= PLAN_LINK_MIN_CONFIDENCE, "置信度要过门槛")
            }
            assertEquals(
                completions.size,
                completions.map { it.occurrenceId }.toSet().size,
                "同一节点只完成一次",
            )

            runtime.autoCompletePlanNodes(noteId, noteTitle, completions)

            val after = statuses(runtime)
            evidence.appendLine()
            evidence.appendLine("[after] statuses=$after")
            evidence.appendLine("[after] planMessage=${runtime.planMessage}")
            completions.forEach { completion ->
                assertEquals(
                    "done",
                    after[completion.occurrenceId],
                    "已接受的节点必须变 done（${completion.title}）：$after",
                )
            }
            // 没被接受的那条如果存在，应该还是 planned（没被误改）。
            val untouched = candidates.filter { candidate -> completions.none { it.occurrenceId == candidate.occurrenceId } }
                .map { it.occurrenceId }
            untouched.forEach { id -> assertEquals("planned", after[id], "模型没判过的节点不能被改：$after") }

            val ledgerFile = File(home, "plans/auto-completions.json")
            assertTrue(ledgerFile.isFile, "自动完成后应写出账本：${ledgerFile.absolutePath}")
            val entries = ledgerEntries(ledgerFile)
            evidence.appendLine("[ledger] ${ledgerFile.absolutePath}")
            evidence.appendLine(ledgerFile.readText(Charsets.UTF_8))
            val noteEntries = entries.filter { entry ->
                (entry["kind"] as? JsonPrimitive)?.content == "note" &&
                    (entry["noteId"] as? JsonPrimitive)?.content == noteId &&
                    (entry["status"] as? JsonPrimitive)?.content == "auto"
            }
            assertEquals(completions.size, noteEntries.size, "每个完成的节点都要有一条 kind=note 的账本记录")
            noteEntries.forEach { entry ->
                assertTrue(
                    ((entry["evidence"] as? JsonPrimitive)?.content ?: "").isNotBlank(),
                    "账本要留正文证据：$entry",
                )
                assertTrue(
                    ("done" == after[(entry["occurrenceId"] as? JsonPrimitive)?.content]),
                    "账本里的节点在页面上也必须是 done：$entry",
                )
            }
            assertTrue(
                runtime.planMessage?.contains("自动完成") == true,
                "页面要有反馈，实际：${runtime.planMessage}",
            )

            evidence.appendLine()
            evidence.appendLine("[result] PASSED")
        } catch (failure: Throwable) {
            evidence.appendLine()
            evidence.appendLine("[result] FAILED: ${failure::class.simpleName}: ${failure.message}")
            throw failure
        } finally {
            // 证据无条件落盘：断言失败时也要能拿到中间状态和模型原始返回。
            evidence.appendLine("[timing] totalMs=" + (System.currentTimeMillis() - startedAt))
            evidence.appendLine("[home] ${home.absolutePath}")
            runCatching {
                evidenceDir.mkdirs()
                File(evidenceDir, "run-evidence.txt").writeText(evidence.toString(), Charsets.UTF_8)
            }
            runCatching { runtime.shutdown() }
            runCatching { store?.close() }
        }
    }

    private fun ledgerEntries(file: File): List<JsonObject> {
        if (!file.isFile) return emptyList()
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        if (text.isBlank()) return emptyList()
        val root = Json.parseToJsonElement(text).jsonObject
        return (root["entries"] ?: return emptyList()).jsonArray.map { it.jsonObject }
    }
}
