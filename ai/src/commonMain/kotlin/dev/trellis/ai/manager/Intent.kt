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
}

interface IntentClassifier {
    fun classify(request: String): AgentIntent
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

    override fun classify(request: String): AgentIntent {
        val text = request.trim()
        if (text.isEmpty()) return AgentIntent.Chat
        if (arrangeWords.any { it in text }) return AgentIntent.ArrangeUi
        if (manageWords.any { it in text }) return AgentIntent.ManagePlugin
        if (createWords.any { it in text }) return AgentIntent.CreatePlugin
        return AgentIntent.Chat
    }
}
