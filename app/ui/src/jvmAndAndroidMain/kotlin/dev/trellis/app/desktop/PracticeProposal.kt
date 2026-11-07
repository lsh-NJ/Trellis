package dev.trellis.app.desktop

/**
 * 学完一个单元后，AI 提议「练一道题」。
 *
 * 这是**模板生成**的提议（`ExerciseBank.suggestFor` 按学科/课程/标签匹配），
 * 不经过模型：提议要可复现、要便宜。卡片只带展示和落盘需要的字段，
 * 题干/选项/答案解析仍在 `:features:exams` 的题库里，按 [exerciseId] 取 ——
 * 会话文件不该塞整份题库。
 *
 * 接受之后打开练习二级页，只显示这一道题（不做题库浏览）。
 */
data class PracticeProposal(
    val id: String,
    /** 哪个计划的学习单元触发的；去重和「先不练」都按它记。 */
    val planId: String,
    val planTitle: String,
    /** 练习学科（用于卡片标题/正文，例如「数据结构」）。 */
    val subject: String,
    val exerciseId: String,
    val title: String,
    val detail: String,
)

/** 练习提议被接受/被「先不练」之后记下来，重启后卡片不再显示按钮。 */
enum class PracticeProposalState { Open, Accepted, Dismissed }
