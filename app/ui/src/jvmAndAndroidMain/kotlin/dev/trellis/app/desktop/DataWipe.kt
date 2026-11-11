package dev.trellis.app.desktop

import java.io.File

/**
 * 调试用「清除数据」：只给开发者模式用，把演示/测试弄脏的数据一次擦掉。
 *
 * 规则很简单：
 * - **只动 `TRELLIS_HOME` 里的数据文件**，不认识的东西（别人的笔记、外部题库、用户自己放的插件）都在 [WipeScope] 里写清楚去留；
 * - **配置永远保留**：模型 key（`llm.json`）、界面偏好（`ui.json`）、学习偏好（`study.json`）、管家设置（`steward/settings.json`）、
 *   活动分类与同步设置（`activity/taxonomy.json`、`activity/settings.json`）、调试模式开关（`dev.json`）——
 *   清数据不该把「怎么用」也清掉，也不该顺手把正在用的调试模式关掉；
 * - 删除失败要**报出来**，不能悄悄当成删好了；调用方（设置页）负责把 failures 显示给用户。
 */
internal enum class WipeScope(val title: String) {
    Plans("清空学习计划"),
    Chats("清空会话"),
    Knowledge("清空知识库"),
    Activity("清空活动记录"),
    Practice("清空练习记录"),
    All("全部清空"),
}

internal data class WipeResult(
    val deleted: List<String>,
    val failed: List<String>,
) {
    val ok: Boolean get() = failed.isEmpty()
}

internal object DataWipe {

    /** 全部清空时无论如何都保留的配置文件（相对 home）。 */
    private val ALWAYS_PRESERVED = listOf(
        "llm.json",
        "ui.json",
        "study.json",
        "steward/settings.json",
        "activity/taxonomy.json",
        "activity/settings.json",
        "dev.json",
    )

    /**
     * 每个范围要删的顶层目标（相对 home）。
     *
     * 说明几个刻意的取舍：
     * - 活动只删 `activity.db*`，保留分类规则与同步设置：分类是用户手调过的，删了每次都要重配；
     * - 计划要连 `plans/docs/`、`plans/sessions.json`、`plans/auto-completions.json` 一起删：
     *   只删库会留下对不上的文档/会话绑定/账本，反而更脏；
     * - 「全部」会连 `plugins/` 一起删：演示环境本来就该从零开始。插件当前已加载到内核里，删完要重启才彻底干净。
     */
    fun targets(home: File, scope: WipeScope): List<File> = when (scope) {
        WipeScope.Plans -> plansTargets(home)
        WipeScope.Chats -> chatsTargets(home)
        WipeScope.Knowledge -> knowledgeTargets(home)
        WipeScope.Activity -> activityTargets(home)
        WipeScope.Practice -> practiceTargets(home)
        WipeScope.All -> allTargets(home)
    }

    /** 全部清空时哪些会被留下（给确认框展示，避免用户担心把 key 弄丢）。 */
    fun preservedPaths(home: File, scope: WipeScope): List<String> =
        if (scope == WipeScope.All) ALWAYS_PRESERVED else emptyList()

    fun wipe(home: File, scope: WipeScope): WipeResult {
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        targets(home, scope).forEach { file ->
            if (!file.exists()) return@forEach
            val ok = deleteRecursively(file)
            val path = file.relativeTo(home).path.ifBlank { file.name }
            if (ok) deleted += path else failed += path
        }
        return WipeResult(deleted = deleted, failed = failed)
    }

    private fun plansTargets(home: File): List<File> {
        val plans = File(home, "plans")
        val dbBackups = plans.listFiles()
            ?.filter { it.name.startsWith("plan.db") }
            .orEmpty()
        return dbBackups + listOf(
            File(plans, "docs"),
            File(plans, "categories.json"),
            File(plans, "sessions.json"),
            File(plans, "auto-completions.json"),
        )
    }

    private fun chatsTargets(home: File): List<File> = listOf(
        File(home, "chat"),
        File(home, "steward/digests"),
        File(home, "steward/memory.jsonl"),
        File(home, "steward/checkpoints.json"),
    )

    private fun knowledgeTargets(home: File): List<File> = listOf(
        File(home, "knowledge/notes"),
        File(home, "knowledge/concepts.json"),
        File(home, "knowledge/graph.user.json"),
        File(home, "knowledge/graph.cache.json"),
    )

    private fun activityTargets(home: File): List<File> {
        val activity = File(home, "activity")
        return activity.listFiles()
            ?.filter { it.name.startsWith("activity.db") }
            .orEmpty()
    }

    private fun practiceTargets(home: File): List<File> = listOf(
        File(home, "exams/attempts.jsonl"),
    )

    private fun allTargets(home: File): List<File> {
        val preserved = ALWAYS_PRESERVED.toSet()
        val result = mutableListOf<File>()
        home.listFiles().orEmpty().forEach { child ->
            when {
                child.name in preserved -> Unit
                child.name == "steward" -> result += childrenExcept(child, setOf("settings.json"))
                child.name == "activity" -> result += childrenExcept(child, setOf("taxonomy.json", "settings.json"))
                else -> result += child
            }
        }
        return result
    }

    /** 子目录里只留 [keep] 这些文件，其它（digests/memory/checkpoints…）全删。 */
    private fun childrenExcept(dir: File, keep: Set<String>): List<File> =
        dir.listFiles().orEmpty().filter { it.name !in keep }

    /** 递归删除：目录要自底向上删，失败收集起来而不是中途放弃。 */
    private fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            var ok = true
            file.listFiles().orEmpty().forEach { child ->
                if (!deleteRecursively(child)) ok = false
            }
            return file.delete() && ok
        }
        return file.delete()
    }
}
