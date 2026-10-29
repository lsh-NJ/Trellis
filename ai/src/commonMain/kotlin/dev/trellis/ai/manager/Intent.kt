package dev.trellis.ai.manager

/**
 * 这一句话要 agent 干什么。
 *
 * 这是「分层提示词」的第一层开关：不同意图决定要不要往上下文里塞任务模块、
 * 以及要不要放开工具。它刻意是**确定性的关键词判断**，不是又一次模型调用：
 *
 * - 每轮多一次模型调用，延迟和 token 都会翻倍，而且分类错了整轮就跑偏；
 * - 确定性规则可以被测试钉住，行为可解释（模型分类不能）；
 * - 关键词只是「第一道粗筛」：意图判错时，静态守则里仍然写着
 *   「先看清楚再动手」，工具也能被它自己纠正（比如判成 Chat 但用户其实要改插件，
 *   它也可以说自己要动工具 —— 前提是工具还开着）。
 */
enum class AgentIntent {
    /** 只是聊天/答疑，不动手。 */
    Chat,

    /** 造一个新插件，或者改一个已有的。 */
    CreatePlugin,

    /** 管理已装插件：停用、启用、删除、读源码、看健康。 */
    ManagePlugin,

    /** 调整插件在界面上的摆放/组合。 */
    ArrangeUi,

    /** 找外部学习资源（B 站视频/分集），顺带可能定一份学习计划。 */
    FindResource,
}

interface IntentClassifier {
    fun classify(request: String): AgentIntent

    /**
     * 分类 → 要不要先反问。
     *
     * 默认实现只做单选；关键词分类器会额外判断「这句话是不是能听成两件事」，
     * 能听成两件就先问清楚，别拿一半的把握去动用户的数据。
     */
    fun decide(request: String): IntentDecision = IntentDecision(classify(request))
}

/**
 * 一句话落到哪儿。
 *
 * [ambiguous] 为真时，流程不该往下走（不挑工具、不发模型请求、不调工具），
 * 而是把 [question] 回给用户，并让界面把 [choices] 竖着摆成选项列表 —— 这就是
 * 「选好意图之前先问清楚」的落点。
 */
data class IntentDecision(
    val intent: AgentIntent,
    val ambiguous: Boolean = false,
    val question: String = "",
    val choices: List<String> = emptyList(),
)

/** 意图的中文短名，界面上用来摆选项。 */
internal fun labelOfIntent(intent: AgentIntent): String = when (intent) {
    AgentIntent.Chat -> "只是聊聊"
    AgentIntent.CreatePlugin -> "做个/改个插件"
    AgentIntent.ManagePlugin -> "管一下插件"
    AgentIntent.ArrangeUi -> "调一下界面"
    AgentIntent.FindResource -> "找学习资源"
}

/**
 * 关键词分类器。
 *
 * 顺序有讲究：**先布局、再管理、后创建**。
 * 「把这三个插件排一下」里既有「插件」也有「排」，必须判成布局；
 * 「把心情插件删了」里有「插件」和「删」，必须判成管理而不是创建。
 */
object KeywordIntentClassifier : IntentClassifier {

    private val arrangeWords = listOf(
        "排列", "排一下", "排成", "排布", "横向", "横着", "横排", "竖着", "竖排", "纵向", "并排",
        "布局", "放一起", "摆一起", "合成一个", "合并", "摆", "挪", "顺序", "一行", "一列", "两列",
        "首页", "位置", "换个地方", "调整界面", "整理界面",
        // 样式也算界面调整：用户说「优化一下界面的 UI」「换个主题」，指的是宿主怎么画，
        // 以前这里没有通路口，agent 只能回 cannotDo。工具是 list_ui_options / set_ui。
        "UI", "ui", "样式", "配色", "主题", "字体", "字号", "圆角", "间距", "好看", "美观", "换肤", "颜色",
        // 导航位置也是宿主样式：用户说「把导航放在左侧边」，以前同样只能拒绝。
        "导航", "左侧", "侧边", "顶栏", "标签栏", "左边", "右边",
        // 区域底色：用户说「给导航换深一点的背景」。
        "深色", "浅色", "底色", "背景", "暗色",
        // 逐插件样式：用户说「逐个插件调整」「心情打卡换成填充底」。
        // 「填充/描边/边框」以前会被判成 ManagePlugin（因为有「换」），结果去调插件工具，什么也改不了。
        "逐个", "每个插件", "单个插件", "每个卡片", "单个卡片", "分别",
        "填充", "描边", "边框", "无边框", "扁",
    )

    private val manageWords = listOf(
        "停用", "禁用", "启用", "恢复启用", "删除插件", "删掉", "删了", "卸载", "移除",
        "停掉", "关掉", "关了", "暂停", "关一下",
        "改一下", "改改", "修改插件", "换成", "还原", "回收站", "健康", "诊断",
        "读一下", "看看源码", "源码", "已经装了什么", "装了什么",
    )

    private val createWords = listOf(
        "做一个", "做个", "写一个", "写个", "加一个", "加个", "生成", "新建", "创建",
        "插件", "卡片", "小应用", "打卡", "记录", "统计", "提醒", "追踪", "记一笔",
        "俯卧撑", "读书", "心情", "习惯",
    )

    /**
     * 找外部资源。放在 create 之前：「帮我做个学习计划」里有「做个」，
     * 但它要的是「搜资源 → 摆步骤」，不是「写一个插件」——
     * 判成 CreatePlugin 会被 beforeFinishing 的「必须先 try_plugin + deploy」卡住。
     *
     * 也放在 arrange 之前：真实说法常常是「把分集排成学习计划」，
     * 里面带「排」；只要整句里没有「卡片/布局/挂载点」这类排版对象，
     * 就该按「找资源」处理，否则会去动布局、把用户要的课程当空气。
     */
    private val resourceWords = listOf(
        "B站", "b站", "bilibili", "Bilibili", "视频", "教程", "课程", "网课", "资源", "资料",
        "想学", "学一下", "学习计划", "学习路径", "知识点", "分集", "看什么", "从哪学", "怎么学",
        // 目标型说法也算「要资源」：这类话的下一步通常是「查课程 → 排成待办/计划」，
        // 判成聊天的话手里一只工具都没有，只能回一段「做不到」的解释。
        "速通", "学会", "掌握", "考过", "上岸", "刷题", "刷完", "背单词", "背完", "备考", "复习", "考试",
        // 长期目标类：用户说「我想考研」「6 月前拿到 offer」，下一步是建目标契约 + 排每天的安排。
        // 不加这几个词，这两句话会被判成聊天，而聊天按设计拿不到任何工具 ——
        // 目标插件的三张卡就只能永远停在「还没有目标」。
        "考研", "求职", "找工作", "面试", "简历", "offer", "长期目标",
    )

    /** 出现这些词才算「真的在说排版」，用它压掉资源词里的「排/布局」误判。 */
    private val layoutObjects = listOf("卡片", "布局", "挂载点", "横排", "竖排", "排成一行", "排成两行", "挪到", "放到")

    override fun classify(request: String): AgentIntent {
        val hits = matchedIntents(request)
        return hits.firstOrNull() ?: AgentIntent.Chat
    }

    /**
     * 单选之外再判一次「歧义」。
     *
     * 只认真正的冲突，不拿「一句话踩中两个词」就打断：
     * - 「把视频排成学习计划」：既有资源词又有排版词，说不准是要排计划还是动布局 —— 问；
     * - 「把首页的卡片横着排」：也踩中「卡片」，但那是排版对象，判布局就行，不该反问；
     * - 什么词都没踩中、又短得像半句（「帮我弄一下」）：问一句更便宜。
     */
    override fun decide(request: String): IntentDecision {
        val text = request.trim()
        if (text.isEmpty()) return IntentDecision(AgentIntent.Chat)
        val wantsResource = resourceWords.any { it in text }
        val talksLayout = layoutObjects.any { it in text }
        if (wantsResource && talksLayout) {
            return IntentDecision(
                intent = AgentIntent.FindResource,
                ambiguous = true,
                question = "这句话我能听成两件事：" +
                    labelOfIntent(AgentIntent.FindResource) + " 或 " + labelOfIntent(AgentIntent.ArrangeUi) +
                    "。你是想先找资源，还是先把界面排一下？",
                choices = listOf(labelOfIntent(AgentIntent.FindResource), labelOfIntent(AgentIntent.ArrangeUi)),
            )
        }
        val hits = matchedIntents(text)
        if (hits.isNotEmpty()) return IntentDecision(hits.first())
        // 短句不猜：只说「弄一下 / 优化一下」这种半句话时问一句，且给按钮。
        // 「你好」「你是谁」这类完整寒暄不算歧义 —— 有话直答，不要反过来审问用户。
        if (vagueWords.none { it in text }) return IntentDecision(AgentIntent.Chat)
        return IntentDecision(
            intent = AgentIntent.Chat,
            ambiguous = true,
            question = "你想让我做什么？选一个最接近的，或者直接补充一句。",
            choices = listOf(
                labelOfIntent(AgentIntent.Chat),
                labelOfIntent(AgentIntent.CreatePlugin),
                labelOfIntent(AgentIntent.ArrangeUi),
                labelOfIntent(AgentIntent.FindResource),
                labelOfIntent(AgentIntent.ManagePlugin),
            ),
        )
    }

    /** 按优先级排出这一句话踩中的意图（同一个只留一次）。 */
    private fun matchedIntents(request: String): List<AgentIntent> {
        val text = request.trim()
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<AgentIntent>()
        fun add(intent: AgentIntent) {
            if (intent !in out) out += intent
        }
        val wantsResource = resourceWords.any { it in text }
        val talksLayout = layoutObjects.any { it in text }
        if (wantsResource && !talksLayout) add(AgentIntent.FindResource)
        if (arrangeWords.any { it in text }) add(AgentIntent.ArrangeUi)
        if (manageWords.any { it in text }) add(AgentIntent.ManagePlugin)
        if (wantsResource) add(AgentIntent.FindResource)
        if (createWords.any { it in text }) add(AgentIntent.CreatePlugin)
        return out
    }

    /** 半句话：没踩中任何关键词时才看它，用来决定「反问」还是当聊天。 */
    private val vagueWords = listOf("弄一下", "搞一下", "处理一下", "优化一下", "弄弄", "随便", "看着办", "帮我看看")
}
