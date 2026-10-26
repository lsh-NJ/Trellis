package dev.trellis.ai

/** 按剧本回话的假模型：第 n 次调用返回第 n 条，用完了就重复最后一条。 */
class ScriptedLlm(private vararg val replies: String) : LlmClient {
    val seenMessages = mutableListOf<List<LlmMessage>>()
    var calls = 0
        private set

    override suspend fun complete(messages: List<LlmMessage>): String {
        seenMessages += messages.toList()
        val reply = replies[minOf(calls, replies.size - 1)]
        calls++
        return reply
    }
}

/** 按剧本失败的假安装器。 */
class ScriptedInstaller(private vararg val failures: String?) : PluginInstaller {
    val installedFiles = mutableListOf<Map<String, String>>()

    override suspend fun install(files: Map<String, String>): String? {
        installedFiles += files
        val index = installedFiles.size - 1
        return if (index < failures.size) failures[index] else failures.lastOrNull()
    }
}

/**
 * 和真实运行时同形的能力清单（2026-10-22：6 个挂载点 / 10 个字段 / 6 个动作）。
 *
 * 这份 fixture 是提示词的「尺子」：它和 `plan-core` 真实注册的贡献点保持一致，
 * 否则 snapshot 测出来的长度和真实发出去的长度对不上。
 */
val TEST_CATALOG = CapabilityCatalog(
    mountPoints = listOf(
        CatalogMountPoint("slot.home.header", "首页头部"),
        CatalogMountPoint("slot.home.section", "首页区块"),
        CatalogMountPoint("slot.empty.state", "空状态"),
        CatalogMountPoint("slot.app.overlay", "全局浮层"),
        CatalogMountPoint("slot.report.chart", "报表图表"),
        CatalogMountPoint("slot.settings.section", "设置页分组"),
    ),
    dataFields = listOf(
        CatalogDataField("summary.total", "全部任务数"),
        CatalogDataField("summary.done", "已完成的数量"),
        CatalogDataField("summary.doing", "正在做的数量"),
        CatalogDataField("summary.todo", "还没完成的数量"),
        CatalogDataField("summary.overdue", "已经逾期的数量"),
        CatalogDataField("summary.dueToday", "今天到期的数量"),
        CatalogDataField("summary.spentMinutes", "一共投入了多少分钟"),
        CatalogDataField("summary.streakDays", "连续完成的天数（断一天就归零）"),
        CatalogDataField("summary.completionRate", "完成率，百分比数字（0~100）"),
        CatalogDataField("summary.oldestOpenTask", "最老的一条未完成任务标题"),
    ),
    actions = listOf(
        CatalogAction(
            "addTask", "新建一个任务", "write_data",
            listOf(CatalogActionParam("title", "任务标题", true)),
        ),
        CatalogAction(
            "logTime", "给任务追加投入时间", "write_data",
            listOf(
                CatalogActionParam("taskId", "给哪个任务记", true),
                CatalogActionParam("minutes", "记多少分钟", true),
            ),
        ),
        CatalogAction(
            "completeTask", "把任务标记完成", "write_data",
            listOf(CatalogActionParam("taskId", "把哪个任务标完成", true)),
        ),
        CatalogAction(
            "renameTask", "改任务标题", "write_data",
            listOf(
                CatalogActionParam("taskId", "任务 id；也可以写 oldest 或 oldestOverdue", true),
                CatalogActionParam("title", "新的标题", true),
            ),
        ),
        CatalogAction(
            "rescheduleTask", "改任务到期时间", "write_data",
            listOf(
                CatalogActionParam("taskId", "任务 id；也可以写 oldest 或 oldestOverdue", true),
                CatalogActionParam("days", "几天后到期（整数，数字也写成字符串）", true),
            ),
        ),
        CatalogAction(
            "deleteTask", "删除任务", "delete_data",
            listOf(CatalogActionParam("taskId", "任务 id；也可以写 oldest 或 oldestOverdue", true)),
        ),
    ),
    events = listOf(
        CatalogEvent(
            "task.completed",
            "完成一个任务（只在第一次完成时发一次）",
            listOf(
                CatalogEventField("taskId", "刚完成的任务 id"),
                CatalogEventField("title", "刚完成的任务标题"),
                CatalogEventField("spentMinutes", "这条任务一共投入了多少分钟"),
            ),
        ),
    ),
)

fun goodJson(id: String = "ai.today"): String =
    """{"id":"$id","name":"今日待办","description":"一眼看到今天还剩多少事","howToUse":"进应用就能看到，在统计数字下面","slots":[{"point":"slot.home.section","title":"今天","body":"还有 {{ summary.dueToday }} 项到期"}]}"""
