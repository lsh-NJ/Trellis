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
 * 它不检查排版对不对（那靠 `:app:mobile-preview` 的渲染冒烟测试与截图），
 * 只检查**入口还在不在**。
 */
class PhoneScreenContractTest {

    private val mobileDir = File("src/jvmAndAndroidMain/kotlin/dev/trellis/app/mobile")

    private fun mobileSource(): String {
        val files = mobileDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 8, "手机界面源文件只有 ${files.size} 个，路径可能不对")
        return files.joinToString("\n") { it.readText() }
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

        // 键 = 桌面运行时/图谱里的入口名，值 = 这个入口对应的功能（报错时给人看）。
        val required = linkedMapOf(
            // 今日
            "openTimer" to "任务计时",
            "logTime" to "记投入分钟",
            "planDoToday" to "待办提到今天",
            // 计划
            "openOccurrenceTimer" to "计划节点计时",
            "planDeferOneDay" to "单元挪一天",
            "planSkipToday" to "单元跳过",
            "completePlanOccurrence" to "单元标记完成",
            "planPlaceUnitToDay" to "排到指定的一天",
            "undoAutoCompletion" to "撤销 AI 自动完成",
            "openAutoCompletionNote" to "从自动完成跳到来源笔记",
            "planAutoCompletionBlockedCount" to "AI 自动完成的屏蔽计数",
            // 对话
            "dismissStewardProposal" to "管家建议卡（先这样）",
            "roundIsUndone" to "撤销这一轮",
            "stopChat" to "停止生成",
            "copyToClipboard" to "复制这一段",
            // 知识
            "assignConcept" to "改概念归属",
            "renameCategory" to "重命名分类",
            "saveDefinition" to "改概念定义",
            "saveAliases" to "改概念别名",
            "pendingKnowledgeReveal" to "从计划跳到笔记里的证据",
            "confirmCategorySuggestions" to "确认 AI 归类建议",
            "runGlobalCategorySuggestions" to "AI 整理分类",
            // 我的
            "updateUiPrefs" to "外观与字号",
            "applyTheme" to "主题包",
            "importLlmConfig" to "导入模型配置",
            "grantAndReload" to "插件能力授权",
            "saveActivitySource" to "活动数据源",
            "parseSimulatedDate" to "时间调试",
        )

        val missing = required.filterKeys { it !in source }
        assertTrue(
            missing.isEmpty(),
            "手机端缺少这些入口（桌面有）：\n" +
                missing.entries.joinToString("\n") { "  - ${it.key}：${it.value}" },
        )
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
}
