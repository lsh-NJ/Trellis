package dev.trellis.app.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.trellis.feature.goal.Clarity
import dev.trellis.feature.goal.FieldKey
import dev.trellis.feature.goal.FieldState
import dev.trellis.feature.goal.GoalEngine
import dev.trellis.feature.goal.GoalKind
import dev.trellis.feature.goal.GoalMirror
import dev.trellis.feature.goal.GoalPhase
import dev.trellis.feature.goal.GoalService
import dev.trellis.feature.goal.GoalSettings
import dev.trellis.feature.goal.GoalStateMachine
import dev.trellis.feature.goal.RequiredFields
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 「目标达成规划」三张卡的离屏渲染。
 *
 * 为什么要有这个测试：这三张卡的状态差异**全在文案和按钮的取舍上**，
 * 纯逻辑测试只能证明「节点树里有这句话」，证明不了「它在 1180 宽的窗口里排得下、
 * 不会被挤成两行、也不会有半张空卡」。这类问题只能看图。
 *
 * 状态不是靠改 kv 造出来的，是走插件自己的状态机走出来的：EMPTY 就是默认，
 * COLLECTING 是答了一半，ACTIVE 是确认后再勾掉两条。造出来的状态和用户真点出来的一致，
 * 截图才有评审价值。
 */
class GoalCardsTest {

    private val today: String = LocalDate.now().toString()

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(runtime: AppRuntime, height: Int = 900): ByteArray {
        val scene = ImageComposeScene(width = 1180, height = height, density = Density(1f)) {
            AppTheme(
                prefs = runtime.uiPrefs,
                themeScheme = runtime.themeScheme(dark = false),
                dark = false,
                chrome = runtime.chromeStyle(),
                extra = runtime.extraColors(dark = false),
            ) {
                AppScreen(runtime)
            }
        }
        // 动态卡片的内容是 LaunchedEffect 里求出来的：第一帧还是「正在准备…」。
        // 多推几帧让 effect 跑完、状态回填，取最后一帧。
        var image = scene.render()
        repeat(4) {
            Thread.sleep(60)
            image = scene.render()
        }
        scene.close()
        return image.encodeToData()?.bytes ?: error("PNG 编码失败")
    }

    /**
     * 截图有两个去处。
     *
     * `build/shots/` 是构建产物，评审时随手看；`_shots/goal-planning/` 会提交进仓库，
     * 是「这一版长什么样」的证据。两份都由测试写 —— 手动另存一份的话，代码改了、
     * 证据还停在三个月前，PR 里那张图就会开始骗人。
     */
    private fun write(name: String, bytes: ByteArray) {
        val built = File("build/shots/" + name)
        built.parentFile?.mkdirs()
        built.writeBytes(bytes)

        val root = File("../..")
        if (File(root, "settings.gradle.kts").exists()) {
            val archived = File(root, "_shots/goal-planning/" + name)
            archived.parentFile?.mkdirs()
            archived.writeBytes(bytes)
        }
        println("  截图: " + built.absolutePath + " （" + bytes.size + " 字节）")
    }

    private fun runtimeFor(): AppRuntime {
        val home = Files.createTempDirectory("trellis-goal-shots").toFile().apply { deleteOnExit() }
        return runBlocking { AppRuntime.create(home) }
    }

    private fun serviceOf(runtime: AppRuntime): GoalService {
        val plugin = assertNotNull(runtime.goalPlugin, "宿主必须留着插件实例，否则没法把状态摆好再画")
        return assertNotNull(plugin.serviceOrNull(), "插件已经启动过，服务不该是空的")
    }

    private fun seed(runtime: AppRuntime, block: (GoalMirror) -> GoalMirror) {
        runBlocking { serviceOf(runtime).update(block) }
    }

    /** 把必备字段一次答完。答案写得像真人说的，截图里才不会全是「已确认-XXX」。 */
    private fun answered(
        statement: String,
        kind: GoalKind,
        clarity: Clarity,
        answers: Map<FieldKey, String>,
    ): GoalMirror {
        var mirror = GoalEngine.start(GoalMirror(), statement, kind, clarity, today, 0L)
        RequiredFields.of(kind).forEachIndexed { index, key ->
            mirror = GoalEngine.answer(mirror, key, answers[key] ?: "（未说）", FieldState.CONFIRMED, index.toLong())
        }
        return mirror
    }

    // ---------------------------------------------------------------- 用户 A：目标模糊

    private val academicAnswers = mapOf(
        FieldKey.DAILY_MINUTES to "90 分钟",
        FieldKey.DEADLINE to "2026-12-20",
        FieldKey.CURRENT_LEVEL to "专业课零基础，英语过了四级",
        FieldKey.HARD_CONSTRAINTS to "在职，只有晚上和周末",
        FieldKey.MAJOR to "计算机",
        FieldKey.TARGET_TIER to "211 及以上",
        FieldKey.WEAK_SUBJECTS to "数学一",
        FieldKey.EXAM_SUBJECTS to "政治、英语一、数学一、408",
    )

    private fun activeAcademic(): GoalMirror {
        val drafted = GoalEngine.toDraft(
            answered("我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, academicAnswers),
            today,
            90,
        )
        // 推送时间设成 0 点：这样不管测试跑在几点，侧条上那条推送都会出现 ——
        // 截图要展示 R2，而不是展示「我跑测试时还没到 8 点」。
        val confirmed = GoalEngine.confirm(drafted.copy(settings = GoalSettings(pushHour = 0)))
        val plan = confirmed.dayPlan(today) ?: return confirmed
        return plan.entries.take(1).fold(confirmed) { acc, entry ->
            GoalEngine.complete(acc, today, entry.id)
        }
    }

    @Test
    fun theThreeStatesRenderOffscreen() {
        val runtime = runtimeFor()

        // ① EMPTY：还没有目标。首页卡上那句引导必须真的出现，而不是一张空卡。
        runtime.currentTab = TAB_TASKS
        val empty = render(runtime)
        write("goal-empty-home.png", empty)

        // ② COLLECTING：答了一半。侧条要说「还差几个信息」，进度条不能是满的。
        seed(runtime) {
            var partial = GoalEngine.start(GoalMirror(), "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L)
            listOf(FieldKey.DAILY_MINUTES, FieldKey.DEADLINE, FieldKey.CURRENT_LEVEL)
                .forEachIndexed { index, key ->
                    partial = GoalEngine.answer(
                        partial,
                        key,
                        academicAnswers.getValue(key),
                        FieldState.CONFIRMED,
                        index.toLong(),
                    )
                }
            partial.copy(collectingRounds = 1)
        }
        runtime.currentTab = TAB_CHAT
        write("goal-collecting-chat.png", render(runtime))

        // ③ ACTIVE：有目标 + 今日条目（勾掉一条）。首页卡和侧条各一张 ——
        //    同一个状态在两处说的不是同一件事，得分别看。
        seed(runtime) { activeAcademic() }
        runtime.currentTab = TAB_TASKS
        val activeHome = render(runtime)
        write("goal-active-home.png", activeHome)
        runtime.currentTab = TAB_CHAT
        write("goal-active-chat.png", render(runtime))

        // ④ REVIEWING：复盘周期到了。撤掉「今天几条」的进度，换成两个出口。
        seed(runtime) { mirror ->
            GoalStateMachine.move(mirror, GoalPhase.REVIEWING).getOrElse { mirror }
        }
        write("goal-reviewing-chat.png", render(runtime))

        // ⑤ 设置页分组：周期 / 推送时间 / 每日投入（只读）/ 删除这份规划。
        //    这一页比一屏长，矮窗口会把整组截掉一半 —— 截图要能一眼看全，就得给够高度。
        runtime.currentTab = TAB_SETTINGS
        write("goal-settings.png", render(runtime, height = 1240))

        assertTrue(empty.size > 5_000, "EMPTY 截图太小，八成是空白画面：" + empty.size)
        assertTrue(activeHome.size > 5_000, "ACTIVE 截图太小，八成是空白画面：" + activeHome.size)
        assertTrue(
            !empty.contentEquals(activeHome),
            "空态和推进态画出来一模一样，说明卡片根本没读到状态",
        )

        runBlocking { runtime.shutdown() }
    }

    // ---------------------------------------------------------------- 用户 B：目标明确

    /**
     * 明确的目标跳过通用层，直接进草稿，条目是求职那一套（简历 / 面试题 / 代码）。
     *
     * 这条同时是 P3 的「用户 B 路径」证据：CONCRETE 时不再推断分叉，
     * 也就不会出现「查 3 个在招岗位的 JD」这种通用动作。
     */
    @Test
    fun aConcreteJobGoalGoesStraightToADraft() {
        val runtime = runtimeFor()

        seed(runtime) {
            GoalEngine.toDraft(
                answered(
                    "我要 6 月前拿到后端岗 offer",
                    GoalKind.JOB,
                    Clarity.CONCRETE,
                    mapOf(
                        FieldKey.DAILY_MINUTES to "120 分钟",
                        FieldKey.DEADLINE to "2027-06-01",
                        FieldKey.CURRENT_LEVEL to "两年 Java 后端",
                        FieldKey.HARD_CONSTRAINTS to "在职，不能脱产",
                        FieldKey.TARGET_ROLE to "后端开发",
                        FieldKey.TARGET_INDUSTRY to "互联网",
                        FieldKey.EXPERIENCE to "两年",
                        FieldKey.PORTFOLIO to "GitHub 一个项目",
                    ),
                ),
                today,
                120,
            )
        }

        val mirror = runBlocking { serviceOf(runtime).mirror() }
        kotlin.test.assertEquals(GoalPhase.DRAFT, mirror.phase, "明确目标应当直接出草稿等确认")
        assertTrue(
            mirror.contract.unknownDecisions.isEmpty(),
            "CONCRETE 不该再有未决策分叉：" + mirror.contract.unknownDecisions,
        )
        val texts = mirror.dayPlan(today)!!.entries.map { it.text }
        assertTrue(
            texts.any { it.contains("简历") || it.contains("面试") || it.contains("代码") },
            "求职目标的日计划要落在简历/面试/代码上：" + texts,
        )

        runtime.currentTab = TAB_CHAT
        write("goal-draft-chat.png", render(runtime))

        runBlocking { runtime.shutdown() }
    }
}
