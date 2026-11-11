package dev.trellis.app.desktop

import dev.trellis.app.mobile.PhoneTab
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 手机界面的「功能对齐」契约。
 *
 * 手机端和桌面端是**两份排版、同一套运行时**。麻烦的地方在于：给桌面加一个能力时，
 * 手机页很容易漏接 —— 编译照样通过，运行时照样正常，只是手机上那个入口不存在。
 * 这种缺失没有任何机制会提醒你，通常要等用户问「为什么我手机上找不到 X」。
 *
 * 所以这里把「桌面有、手机也必须有」的入口写成清单：任何一条从手机源码里消失，
 * 测试就红，并指名道姓说少了什么。
 *
 * 断言口径（2026-11-09 升过一次级）：
 * - 先去掉 `//` 与 `/* */` 注释，再匹配 —— 注释里提到函数名不算「接线完成」。
 * - 方法入口匹配「名字 + 调用括号」，例如 `runtime.clearData(`、`createCategory(`、
 *   `metaStore.saveDefinition(`（允许 through-import 别名或别的 receiver）；同时排除
 *   `fun 名字(` 这种**定义**，避免把桌面搬过来的纯函数声明当成手机调用。
 * - 属性入口（`pendingApproval`、`planAutoCompletionBlockedCount` 等）只要求名字作为
 *   一个词出现在源码里（同样先剥注释）。
 *
 * 它不检查排版对不对（那靠 `:app:mobile-preview` 的渲染冒烟测试与截图），
 * 只检查**入口还在不在**。
 */
class PhoneScreenContractTest {

    private val mobileDir = File("src/jvmAndAndroidMain/kotlin/dev/trellis/app/mobile")

    /** 契约里的一个入口。 */
    private enum class Kind {
        /** 必须看到「名字(...)」的调用形态（排除 `fun 名字(`）。 */
        CALL,

        /** 属性/类型/枚举值：只要求名字作为独立的词出现。 */
        REFERENCE,
    }

    private data class Entry(val name: String, val what: String, val kind: Kind = Kind.CALL)

    private fun mobileSourceFiles(): List<File> =
        mobileDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 手机源码全文，已去掉注释 —— 注释里的函数名不能算通过。 */
    private fun mobileSource(): String {
        val files = mobileSourceFiles()
        assertTrue(files.size >= 8, "手机界面源文件只有 ${files.size} 个，路径可能不对")
        return stripComments(files.joinToString("\n") { it.readText() })
    }

    /**
     * 去掉行注释与块注释，保留字符串与换行（换行保留只是为了出错时行号还对得上）。
     * 不做完整词法分析，只覆盖 Kotlin 源码里会出现的这几种情况。
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < text.length) {
                        if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '/') {
                            out.append("  ")
                            i += 2
                            break
                        }
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                // Kotlin 原始字符串 """…"""：内部不解释转义，也不能被当成注释。
                c == '"' && i + 2 < text.length && text[i + 1] == '"' && text[i + 2] == '"' -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < text.length) {
                        if (text[i] == '"' && i + 2 < text.length && text[i + 1] == '"' && text[i + 2] == '"') {
                            out.append("\"\"\"")
                            i += 3
                            break
                        }
                        out.append(text[i])
                        i++
                    }
                }
                c == '"' -> {
                    out.append('"')
                    i++
                    while (i < text.length) {
                        val ch = text[i]
                        if (ch == '\\' && i + 1 < text.length) {
                            out.append(ch).append(text[i + 1])
                            i += 2
                            continue
                        }
                        out.append(ch)
                        i++
                        if (ch == '"' || ch == '\n') break
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 源码里是否真的**调用**了这个入口（`fun 名字(` 这种定义不算）。 */
    private fun hasCall(source: String, name: String): Boolean =
        Regex("""(?<!fun\s)(?<!\w)${Regex.escape(name)}\s*\(""").containsMatchIn(source)

    /** 源码里是否出现了这个名字（属性/类型/枚举值用，允许 `runtime.xxx` 这种带 receiver 的写法）。 */
    private fun hasReference(source: String, name: String): Boolean =
        Regex("""(?<!\w)${Regex.escape(name)}(?!\w)""").containsMatchIn(source)

    private fun missingEntries(source: String, entries: List<Entry>): List<Entry> =
        entries.filterNot { entry ->
            when (entry.kind) {
                Kind.CALL -> hasCall(source, entry.name)
                Kind.REFERENCE -> hasReference(source, entry.name)
            }
        }

    private fun assertPresent(source: String, entries: List<Entry>, headline: String) {
        val missing = missingEntries(source, entries)
        assertTrue(
            missing.isEmpty(),
            headline +
                missing.joinToString("\n") { "  - ${it.name}：${it.what}" },
        )
    }

    @Test
    fun `五个页签都有分支且页面文件齐全`() {
        val app = File(mobileDir, "PhoneApp.kt").readText()
        val missing = PhoneTab.entries.filter { "PhoneTab.${it.name}" !in app }
        assertTrue(missing.isEmpty(), "PhoneApp 里没有这些页签的分支：" + missing.joinToString { it.name })

        val pages = listOf(
            "TodayPage.kt",
            "PlanPage.kt",
            "ChatPage.kt",
            "KnowledgePage.kt",
            "KnowledgeGraphPage.kt",
            "MePage.kt",
            "PluginsPage.kt",
        )
        val absent = pages.filterNot { File(mobileDir, it).isFile }
        assertTrue(absent.isEmpty(), "缺少页面文件：" + absent.joinToString())
    }

    @Test
    fun `桌面的关键入口在手机上也有`() {
        val source = mobileSource()

        val required = listOf(
            // 今日
            Entry("openTimer", "任务计时"),
            Entry("logTime", "记投入分钟"),
            Entry("planDoToday", "待办提到今天"),
            // 计划
            Entry("openOccurrenceTimer", "计划节点计时"),
            Entry("planDeferOneDay", "单元挪一天"),
            Entry("planSkipToday", "单元跳过"),
            Entry("completePlanOccurrence", "单元标记完成"),
            Entry("planPlaceUnitToDay", "排到指定的一天"),
            Entry("undoAutoCompletion", "撤销 AI 自动完成"),
            Entry("openAutoCompletionNote", "从自动完成跳到来源笔记"),
            Entry("planAutoCompletionBlockedCount", "AI 自动完成的屏蔽计数", Kind.REFERENCE),
            // 对话
            Entry("dismissStewardProposal", "管家建议卡（先这样）"),
            Entry("roundIsUndone", "撤销这一轮"),
            Entry("stopChat", "停止生成"),
            Entry("copyToClipboard", "复制这一段"),
            // 知识
            Entry("assignConcept", "改概念归属"),
            Entry("renameCategory", "重命名分类"),
            Entry("saveDefinition", "改概念定义"),
            Entry("saveAliases", "改概念别名"),
            Entry("pendingKnowledgeReveal", "从计划跳到笔记里的证据", Kind.REFERENCE),
            Entry("confirmCategorySuggestions", "确认 AI 归类建议"),
            Entry("runGlobalCategorySuggestions", "AI 整理分类"),
            // 我的
            Entry("updateUiPrefs", "外观与字号"),
            Entry("applyTheme", "主题包"),
            Entry("importLlmConfig", "导入模型配置"),
            Entry("grantAndReload", "插件能力授权"),
            Entry("saveActivitySource", "活动数据源"),
            Entry("parseSimulatedDate", "时间调试"),
        )

        assertPresent(source, required, "手机端缺少这些入口（桌面有）：\n")
    }

    @Test
    fun `本轮新增的入口在手机上落地`() {
        val source = mobileSource()

        val required = listOf(
            // 手机壳：审批卡（AskDialogs 等价物）
            Entry("pendingApproval", "审批请求队列", Kind.REFERENCE),
            Entry("answerApproval", "审批卡回答（允许/拒绝）"),
            // 手机壳：运行时驱动的二级页（目标 / 练习 / 计划文档）
            Entry("PhoneSubPage.Goals", "目标二级页", Kind.REFERENCE),
            Entry("PhoneSubPage.Practice", "练习二级页", Kind.REFERENCE),
            Entry("PhoneSubPage.PlanDoc", "计划文档二级页", Kind.REFERENCE),
            Entry("closeGoalScreen", "关闭目标时复位运行时"),
            Entry("closePractice", "关闭练习时复位运行时"),
            Entry("closePlanDocs", "关闭计划文档时复位运行时"),
            // 目标与计划文档的打开入口（计划页头部按钮；手机没有桌面左栏）
            Entry("openGoalScreen", "从计划页打开目标二级页"),
            Entry("openPlanDocs", "从计划页打开计划文档"),
            Entry("planDocBrowseId", "计划文档跳转字段", Kind.REFERENCE),
            // 计划页：计时、撤销完成、屏幕使用归因、周进度、阶段与复盘
            Entry("startTimer", "计划/任务计时真正开始"),
            Entry("uncompletePlanOccurrence", "把已完成的节点改回未完成"),
            Entry("openActivityDay", "从计时/归因跳到活动页那一天"),
            Entry("planWeekAttributedMinutes", "本周实际投入（活动归因）"),
            Entry("planStages", "阶段进度", Kind.REFERENCE),
            Entry("planReview", "周复盘数据", Kind.REFERENCE),
            Entry("planReviewableWeek", "可复盘的周（复盘入口的开关）", Kind.REFERENCE),
            Entry("generatePlanReview", "生成复盘"),
            Entry("generateWeekReview", "生成某一周的复盘"),
            Entry("abandonPlan", "放弃计划（二次确认的终点）"),
            // 对话页：过程行、消息 diff、练习提议、Markdown 渲染
            Entry("ChatToolTrace", "过程行工具轨迹", Kind.REFERENCE),
            Entry("diffForRound", "撤销这一轮时展示的 diff"),
            Entry("acceptPracticeProposal", "接受练习提议"),
            Entry("dismissPracticeProposal", "先不练（关掉练习提议）"),
            Entry("MarkdownText", "对话/知识共用的 Markdown 渲染"),
            // 我的页：清除数据、管家三开关
            Entry("clearData", "清除数据（按范围）"),
            Entry("updateStewardSettings", "写管家设置（自动采纳/每日上限/每周排周）"),
            Entry("autoAdopt", "自动采纳开关字段", Kind.REFERENCE),
            Entry("weeklyPlan", "每周自动排周计划字段", Kind.REFERENCE),
            Entry("stewardAutoAdoptedToday", "今天已自动采纳 N 条", Kind.REFERENCE),
            // 知识网络：分类增删改
            Entry("createCategory", "新建分类"),
            Entry("moveCategory", "移动分类"),
            Entry("deleteCategory", "删除分类"),
        )

        assertPresent(source, required, "手机端缺少这些本轮新增入口：\n")
    }

    @Test
    fun `手机端不把桌面专属能力塞给模型`() {
        // 截屏 / 点击 / 按键 / 打字在安卓沙箱里做不到。工具清单按平台裁剪这件事
        // 写在 AppRuntime 里，这里守住它别被「顺手统一」掉。
        val runtime = File("src/jvmAndAndroidMain/kotlin/dev/trellis/app/desktop/AppRuntime.kt").readText()
        assertTrue(
            "screenTools.all.filter" in runtime,
            "AI 工具清单不再按平台裁剪 —— 手机上会重新出现截屏/点击这些做不到的工具",
        )
    }

    // 维护提示：task-12 列出的入口已全部落地并进了上面的清单，当前没有 TODO 项。
    // 以后发现「桌面有、手机没接」的入口，先加断言再修 —— 别直接往这里写注释绕过。
    // openPractice 不是必须项：练习二级页由 `runtime.currentTab == TAB_PRACTICE` 驱动。
}
