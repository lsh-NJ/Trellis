package dev.trellis.ai.manager

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 提示词的顺序就是缓存策略本身：静态前缀（含工作流）→ 动态状态 → 历史 → 这一句。
 * 顺序一挪，前缀缓存就全废了 —— 而这件事从功能上看不出来。
 */
class ManagerPromptTest {

    private val catalog = CapabilityCatalog(emptyList(), emptyList())
    private val prompt = ManagerPrompt(ManagerPrompt.buildBase(catalog, emptyList(), "守则"))

    @Test
    fun theRoleSaysWhatTheHostDraws() {
        assertTrue(prompt.base.contains("<what_the_host_draws>"))
        assertTrue(prompt.base.contains("圆角"))
        assertTrue(prompt.base.contains("宿主"))
    }

    @Test
    fun theThinkingLanguageIsPinnedToChinese() {
        // reasoning 跟着系统提示词的语言走：不写这一句，模型会拿英文思考。
        assertTrue(prompt.base.contains("<thinking_language>"))
        assertTrue(prompt.base.contains("思考过程（reasoning）也必须用中文"))
        assertFalse(prompt.base.contains("think in English", ignoreCase = true))
    }

    @Test
    fun theStaticPrefixIsIdenticalEveryTime() {
        val again = ManagerPrompt.buildBase(catalog, emptyList(), "守则")
        assertEquals(prompt.base, again)
        assertFalse(prompt.base.contains("已装插件："))
    }

    @Test
    fun stateGoesAfterTheStaticPrefixAndBeforeHistory() {
        val history = listOf(
            LlmMessage(LlmRole.User, "之前说的话"),
            LlmMessage(LlmRole.Assistant, "之前的回答"),
        )
        val messages = prompt.messages("已装插件：1 个", "把话说清楚", history)

        assertEquals(LlmRole.System, messages[0].role)
        assertEquals(prompt.base, messages[0].content)
        assertEquals(LlmRole.System, messages[1].role)
        assertTrue(messages[1].content.contains("<current_state>"))
        assertEquals("之前说的话", messages[2].content)
        assertEquals("之前的回答", messages[3].content)
        assertEquals(LlmRole.User, messages.last().role)
        assertEquals("我想要：把话说清楚", messages.last().content)
    }

    @Test
    fun theIdentityComesBeforeThePluginManual() {
        // 以前第一句是「你是一个为 Trellis 写声明式插件的作者」，把管理员框成了插件作者；
        // 现在身份必须在最前，插件说明书只作为「要造插件时才看」的分支。
        val base = prompt.base
        val role = base.indexOf("你是这个应用的管理员")
        val pluginManual = base.indexOf("<when_you_make_a_plugin>")
        check(role >= 0) { "系统提示词开头要有管理员身份" }
        check(pluginManual > role) { "身份要排在插件说明书前面" }
        assertFalse(
            base.trimStart().startsWith("你是一个为 Trellis 写声明式插件的作者"),
            "第一句不能再是插件作者",
        )
    }

    @Test
    fun theWorkflowIsInTheStaticPrefixAndCoversTheWholeSurface() {
        // 工具全量之后，工作流也必须覆盖所有本事 —— 少写一条，那一类活就会被判成「不该做」。
        val base = prompt.base
        assertTrue("<task:work>" in base)
        for (key in listOf("学习计划", "B 站", "deadline", "confirmed", "插件", "deploy", "get_diagnostics")) {
            assertTrue(key in base, "工作流里要提到：$key")
        }
    }

    @Test
    fun aChatTurnCarriesNoExtraTaskModule() {
        // 删掉意图分类器之后没有「任务模块」了：每轮都是同一份静态前缀 + 这一句。
        val chat = prompt.messages("已装插件：1 个", "你好")
        val text = chat.joinToString("\n") { it.content }
        assertEquals(1, Regex("<task:work>").findAll(text).count())
        assertEquals(LlmRole.User, chat.last().role)
    }

    private fun pluginDecisionBlock(): String =
        prompt.base.substringAfter("<when_to_create_a_plugin>")
            .substringBefore("</when_to_create_a_plugin>")

    @Test
    fun creatingAPluginStartsWithARequirementChecklist() {
        // 真实事故：用户要「一键创建并跳转读书笔记」，模型只做了书名/页码，
        // 把「跳笔记」写进 limitation 就交付了。根因之一是提示词没要求先拆需求、先对能力。
        val block = pluginDecisionBlock()
        for (key in listOf("需求清单", "list_plugins", "list_actions", "describe_screen", "ask_choices")) {
            assertTrue(key in block, "造插件前的规则里要提到：$key")
        }
        // 顺序：先拆需求 → 再对能力 → 再谈做不到。顺序反了，模型会先想「这能不能做」而漏掉需求。
        assertTrue(block.indexOf("需求清单") < block.indexOf("list_actions"), "先列需求清单，再 list_actions 对能力")
        assertTrue(block.indexOf("list_actions") < block.indexOf("做不到不许静默降级"), "先对能力，再判做不到")
    }

    @Test
    fun theAbilityStepPrefersComposingGenericHostActionsOverAskingForNewOnes() {
        // 语义转向：不要每做一个插件就要求宿主加一个动作。
        // 先看 list_actions，能用通用动作（跳页/写笔记/通知/计时/任务）拼出来的就自己拼；
        // 只有拼不出来的一整类缺口，才明确告知用户「需要宿主新增 X 类能力」——模型改不了宿主代码。
        val block = pluginDecisionBlock()
        for (key in listOf(
            "list_actions", "openPage", "writeKnowledgeNote", "openKnowledgeNote",
            "notify", "startFocusTimer", "任务类动作", "通用动作",
            "宿主新增", "只能告知", "不要每做一个插件就要求宿主加一个动作",
        )) {
            assertTrue(key in block, "对能力的第 2/3 步要提到：$key")
        }
        assertTrue(block.indexOf("list_actions") < block.indexOf("通用动作"), "先 list_actions，再谈用通用动作组合")
        assertTrue(
            block.indexOf("通用动作") < block.indexOf("宿主新增"),
            "先谈组合，组合不出来才谈宿主缺口",
        )
        assertTrue(block.contains("limitation"), "缺口要写进 limitation 当真实缺口")
    }

    @Test
    fun thePluginStepOfTheWorkflowPrefersGenericActions() {
        val workflow = prompt.base.substringAfter("<task:work>").substringBefore("</task:work>")
        val step = workflow.substringAfter("5. 插件").substringBefore("6. 写操作纪律")
        for (key in listOf("openPage", "writeKnowledgeNote", "startFocusTimer", "通用动作", "宿主新增")) {
            assertTrue(key in step, "插件工作流要提到：$key")
        }
        assertTrue(step.indexOf("通用动作") < step.indexOf("宿主新增"), "先组合通用动作，再谈宿主缺口")
    }

    @Test
    fun creatingAPluginShowsADesignAndWaitsForTheUserBeforeWritingFiles() {
        // 用户这轮最核心的流程改造（m00788 第 4 条）：先设计、后实装。
        // 设计里要说清放哪、每行是什么、点哪里发生什么、用哪些宿主动作、哪条做不到；
        // 再 ask_choices 等用户确认，不能闷头写文件。
        val block = pluginDecisionBlock()
        for (key in listOf(
            "先设计、后实装", "短设计", "卡片放在哪", "从上到下有什么", "点哪里发生什么",
            "宿主动作", "哪条需求做不到", "ask_choices", "按这个做 / 我要改",
            "等用户确认才写文件", "直接做",
        )) {
            assertTrue(key in block, "先设计那一步要提到：$key")
        }
        assertTrue(block.indexOf("先设计、后实装") < block.indexOf("列需求清单"), "设计在拆需求之前")
        assertTrue(block.indexOf("短设计") < block.indexOf("等用户确认才写文件"), "先给设计再等确认")
        // 新插件默认落在首页最下方；改已有插件保留原 order。
        assertTrue(block.contains("slot.home.section"), "默认挂载点")
        assertTrue(block.contains("order 写 900"), "默认 order")
        assertTrue(block.contains("改已有插件保留原来的 order"), "改已有插件不挪位置")
    }

    @Test
    fun thePluginDeliverySaysHowToUseThenDesignThenLimitations() {
        val block = pluginDecisionBlock()
        // 详情页会展示这两句，必填。
        assertTrue(block.contains("description"), "manifest description 必填")
        assertTrue(block.contains("usage"), "manifest usage 必填")
        assertTrue(block.contains("插件详情页会展示"), "要说明为什么必填")
        assertTrue(block.contains("limitation 只写真实取舍"), "limitation 不是免责声明")
        // 交付第一句/结尾顺序固定：怎么用 → 设计 → 缺口/限制。
        assertTrue(block.contains("先「怎么用」"), "先讲怎么用")
        assertTrue(block.contains("再「设计」"), "再讲设计")
        assertTrue(block.contains("最后「缺口/限制」"), "最后讲缺口")
        assertTrue(block.indexOf("先「怎么用」") < block.indexOf("再「设计」"), "怎么用在设计前")
        assertTrue(block.indexOf("再「设计」") < block.indexOf("最后「缺口/限制」"), "缺口在最后")
        assertTrue(block.contains("不能只回一句「装好了」"), "不能只回装好了")
    }

    @Test
    fun cardCopyMustSayOneThingOnce() {
        val block = pluginDecisionBlock()
        // 用户抱怨卡片信息重复两遍（书名/进度）与装饰性废话。
        assertTrue(block.contains("必要的说明"), "卡片要有一句必要说明")
        assertTrue(block.contains("同一信息只出现"), "同一信息只写一次")
        assertTrue(block.contains("书名只写一次"), "书名不能重复")
        assertTrue(block.contains("12 / 300 页"), "进度不能重复")
        assertTrue(block.contains("装饰性废话"), "不要装饰性废话")
        assertTrue(block.contains("空标题"), "不要空标题占位")
    }

    @Test
    fun theDesignStepNamesThePopupInputAndBansExplanatoryCardCopy() {
        val block = pluginDecisionBlock()
        // 用户看过模型自造的读书卡片后说说明行「多余」，并要求输入/修改/添加都走浮窗。
        // 设计稿阶段就要把这些定死，不能等卡片画出来再改。
        for (key in listOf(
            "标题 + 数据 + 动作", "操作说明句", "同一信息只出现一次",
            "浮窗", "点哪里弹浮窗", "浮窗里填什么",
        )) {
            assertTrue(key in block, "设计那一步要提到：$key")
        }
        assertTrue(
            block.indexOf("卡片正文只放必要内容") < block.indexOf("点哪里弹浮窗"),
            "先定「卡面只留必要内容」，再定编辑操作走浮窗",
        )
    }

    @Test
    fun aMissingRequirementMayNotBeDowngradedSilently() {
        val block = pluginDecisionBlock()
        assertTrue(block.contains("做不到不许静默降级"), "必须明写不许静默降级")
        assertTrue(block.contains("不能拿来掩盖没做的需求"), "limitation 不能用来掩盖没做的需求")
        assertTrue(block.contains("交付第一句就说明缺哪条"), "降级交付要第一句说明缺哪条")
        assertTrue(block.contains("ask_choices"), "降级前要问用户，不能自己替他决定")
    }

    @Test
    fun thePluginStepOfTheWorkflowVerifiesBeforeClaimingSuccess() {
        val workflow = prompt.base.substringAfter("<task:work>").substringBefore("</task:work>")
        val step = workflow.substringAfter("5. 插件").substringBefore("6. 写操作纪律")
        for (key in listOf(
            "需求清单", "list_actions", "try_plugin", "deploy",
            "describe_screen", "take_screenshot", "逐条回报", "没核对过不能说「装好了」",
            "短设计", "ask_choices", "slot.home.section", "order 900",
            "description", "usage", "怎么用（1-3 句操作路径）",
        )) {
            assertTrue(key in step, "插件工作流里要提到：$key")
        }
        assertTrue(step.indexOf("短设计") < step.indexOf("list_plugins"), "先给设计再对能力")
        // 验收顺序：先试跑 → 再部署 → 部署后核对 → 最后逐条回报。
        assertTrue(step.indexOf("try_plugin") < step.indexOf("deploy"), "先 try_plugin 再 deploy")
        assertTrue(step.indexOf("deploy") < step.indexOf("describe_screen"), "deploy 后要 describe_screen 核对")
        assertTrue(step.indexOf("describe_screen") < step.indexOf("逐条回报"), "核对完再按需求清单逐条回报")
        assertTrue(step.indexOf("需求清单") < step.indexOf("list_actions"), "先拆需求再对能力")
    }
}
