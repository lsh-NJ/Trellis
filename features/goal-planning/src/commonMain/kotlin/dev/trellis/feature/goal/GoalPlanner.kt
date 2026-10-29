package dev.trellis.feature.goal

import kotlinx.serialization.Serializable

/** 阶段目标。允许在数据层存在（它是「往哪儿走」），但不落成具体日期条目。 */
@Serializable
data class Milestone(
    val id: String,
    val title: String,
    val targetDate: String? = null,
)

/**
 * 院校 / 公司名固定词表。
 *
 * 只用固定词表、不接外部 NER：这条规则的意义是**可被测试钉住**。
 * 接一个会随版本变化的实体识别，今天绿明天红，谁也不知道是代码坏了还是模型变了。
 */
object EntityLexicon {

    val INSTITUTIONS = listOf(
        "清华", "北大", "北京大学", "清华北大", "复旦", "上交", "上海交通", "浙大", "浙江大学",
        "南大", "南京大学", "中科大", "中国科学技术", "哈工大", "哈尔滨工业", "西安交大",
        "华中科技", "北航", "北京航空", "同济", "东南大学", "中山大学", "武汉大学", "川大",
    )

    val COMPANIES = listOf(
        "阿里", "阿里巴巴", "腾讯", "字节", "字节跳动", "美团", "京东", "百度", "华为",
        "小米", "网易", "拼多多", "滴滴", "快手", "小红书", "微软", "谷歌", "亚马逊",
    )

    /** 文本里命中了哪些实体（去重、保持出现顺序）。 */
    fun hits(text: String): List<String> {
        val all = INSTITUTIONS + COMPANIES
        return all.filter { text.contains(it) }.distinct()
    }

    fun hasEntity(text: String): Boolean = hits(text).isNotEmpty()
}

/**
 * 生成某一天的条目。
 *
 * ### R4 是这里最重要的规则
 *
 * VAGUE / DIRECTED 时**只能用通用层**：任何路径下都必须做的事（词汇、数学基础、
 * 教材第一轮）。具体院校、公司一律不出现 —— 用户只说「想考研」，模型替他挑一个学校，
 * 后面所有安排都会围绕一个他没做过的决定展开，而他还以为这是自己选的。
 *
 * 尚未决策的分叉点不等着，转成**决策任务**排进计划：用规划去消解不确定性，
 * 而不是等不确定性消失才开始规划。
 */
object GoalPlanner {

    /** 生成一天。返回的条目**尚未校验**，调用方要过 [DayPlanValidator]。 */
    fun planFor(
        contract: GoalContract,
        date: String,
        dailyMinutes: Int,
        milestone: Milestone,
        dayIndex: Int = 0,
    ): DayPlan {
        val budget = if (dailyMinutes <= 0) 60 else dailyMinutes
        val templates = templatesFor(contract)
        // 每条拿到「预算 ÷ 目标条数」的份额：先分配再生成，比先生成再砍更稳 ——
        // 先生成再砍会把最后一条砍成 3 分钟，而 3 分钟的条目等于没排。
        val target = ENTRY_TARGET
        val per = (budget / target).coerceAtLeast(DayPlanValidator.MIN_MINUTES)
        val entries = mutableListOf<DayEntry>()

        // ① 决策任务优先：它决定后面所有安排的方向，越早做越省事。
        val decisions = contract.unknownDecisions
        if (decisions.isNotEmpty() && dayIndex % 2 == 0) {
            val decision = decisions[(dayIndex / 2) % decisions.size]
            entries += decisionEntry(decision, milestone, date)
                .copy(minutes = per.coerceIn(DayPlanValidator.MIN_MINUTES, 40))
        }

        // ② 通用层：每种目标都有「不管走哪条路都得做」的那几件事。
        var cursor = dayIndex
        while (entries.size < target && templates.isNotEmpty()) {
            val template = templates[cursor % templates.size]
            cursor += 1
            entries += template.copy(minutes = minOf(template.minutes, per))
        }

        // ③ 兜底：模板被预算吃光时，至少留一条能做的，不留空白天。
        while (entries.size < DayPlanValidator.MIN_ENTRIES) {
            entries += DayEntry(
                id = "filler-" + date + "-" + entries.size,
                text = "整理今天的资料清单",
                output = "一页清单",
                minutes = DayPlanValidator.MIN_MINUTES,
                goalRef = milestone.id,
                doneCriteria = "写下来并能照着做",
            )
        }

        return DayPlan(date = date, entries = entries)
    }

    /** 一天的目标条数。三条是「有推进又不至于做不完」的常见落点。 */
    private const val ENTRY_TARGET = 3

    /** 决策条目：产出是一个「决定」，不是一页笔记。 */
    fun decisionEntry(decision: String, milestone: Milestone, date: String): DayEntry {
        val text = if (decision.startsWith("查")) decision else "查" + decision
        return DayEntry(
            id = "decision-" + date + "-" + decision.hashCode(),
            text = GoalCompressor.clamp(text, 24),
            output = "一张对比表",
            minutes = 40,
            goalRef = milestone.id,
            doneCriteria = "表里至少 3 行，每行有可核对的数字",
            kind = EntryKind.DECISION,
        )
    }

    /**
     * 模板库。
     *
     * VAGUE / DIRECTED 的模板**一个实体都不许有**；CONCRETE 才追加针对性条目。
     * 这条不是靠调用方自觉，是靠 [templatesFor] 自己把关。
     */
    private fun templatesFor(contract: GoalContract): List<DayEntry> {
        val base = when (contract.kind) {
            GoalKind.ACADEMIC -> academicBase()
            GoalKind.JOB -> jobBase()
            GoalKind.GENERAL -> generalBase()
        }
        if (contract.clarity != Clarity.CONCRETE) return base

        // CONCRETE：用户已经把具体对象写进了目标，这时针对性内容才是他要的。
        val targets = EntityLexicon.hits(contract.statement)
        if (targets.isEmpty()) return base
        val focus = targets.first()
        return listOf(
            DayEntry(
                id = "target-1",
                text = GoalCompressor.clamp("查 " + focus + " 的最新要求", 24),
                output = "一页要求摘录",
                minutes = 30,
                goalRef = "milestone-1",
                doneCriteria = "条目、截止时间、科目三项都抄下来",
                kind = EntryKind.DECISION,
            ),
        ) + base
    }

    private fun academicBase(): List<DayEntry> = listOf(
        entry("acad-1", "背 40 个考研词汇", "一页默写纸", 30, "看中文能写出英文"),
        entry("acad-2", "读 2 篇长难句并拆结构", "2 句结构标注", 25, "能说出主谓宾和从句边界"),
        entry("acad-3", "做 10 道数学基础题", "10 道题的订正", 40, "错题能说出错在哪一步"),
        entry("acad-4", "读专业课教材 8 页并批注", "8 页批注", 35, "合上书能复述 3 个概念"),
    )

    private fun jobBase(): List<DayEntry> = listOf(
        entry("job-1", "改一版简历的项目段落", "简历 PDF 一版", 40, "每段都有数字结果"),
        entry("job-2", "刷 5 道岗位面试题", "5 道题的答案提纲", 35, "能不看稿讲 2 分钟"),
        entry("job-3", "写 60 行代码并提交", "一个可运行的提交", 45, "本地跑通并推上去"),
    )

    private fun generalBase(): List<DayEntry> = listOf(
        entry("gen-1", "整理今天要读的材料", "一页清单", 15, "清单上的每项都能点开"),
        entry("gen-2", "读 20 页并写 3 条要点", "3 条要点", 30, "不看原文能讲清这 3 条"),
        entry("gen-3", "写 200 字小结", "200 字小结", 20, "能说清今天学到了什么"),
    )

    private fun entry(
        id: String,
        text: String,
        output: String,
        minutes: Int,
        doneCriteria: String,
    ): DayEntry = DayEntry(
        id = id,
        text = text,
        output = output,
        minutes = minutes,
        goalRef = "milestone-1",
        doneCriteria = doneCriteria,
    )

    /** 默认阶段目标：一份计划至少要有一个「往哪儿走」，否则条目全是散的。 */
    fun defaultMilestone(contract: GoalContract): Milestone {
        val title = when (contract.kind) {
            GoalKind.ACADEMIC -> "第一轮：打基础"
            GoalKind.JOB -> "第一轮：补齐硬性要求"
            GoalKind.GENERAL -> "第一轮：先跑起来"
        }
        return Milestone("milestone-1", title, contract.targetDate)
    }
}
