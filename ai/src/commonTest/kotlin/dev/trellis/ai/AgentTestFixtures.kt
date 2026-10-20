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

val TEST_CATALOG = CapabilityCatalog(
    mountPoints = listOf(
        CatalogMountPoint("slot.home.header", "首页头部"),
        CatalogMountPoint("slot.home.section", "首页区块"),
    ),
    dataFields = listOf(
        CatalogDataField("summary.todo", "还没完成的数量"),
        CatalogDataField("summary.dueToday", "今天到期的数量"),
    ),
    actions = listOf(
        CatalogAction("addTask", "新建一个任务", "write_data"),
        CatalogAction("logTime", "给任务追加投入时间", "write_data"),
        CatalogAction("completeTask", "把任务标记完成", "write_data"),
    ),
    events = listOf(
        CatalogEvent(
            "task.completed",
            "完成一个任务（只在第一次完成时发一次）",
            listOf(
                CatalogEventField("taskId", "刚完成的任务 id"),
                CatalogEventField("title", "刚完成的任务标题"),
            ),
        ),
    ),
)

fun goodJson(id: String = "ai.today"): String =
    """{"id":"$id","name":"今日待办","description":"一眼看到今天还剩多少事","howToUse":"进应用就能看到，在统计数字下面","slots":[{"point":"slot.home.section","title":"今天","body":"还有 {{ summary.dueToday }} 项到期"}]}"""
